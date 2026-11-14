package com.hikari.app.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.hikari.app.data.Logs
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Television streaming data source.
 * Reads YouTube audio in safe 900 KB ranged requests (strictly under YouTube's 1 MB
 * per-request / throttle threshold) and transparently refreshes the stream URL if a
 * later chunk is refused.
 */
@UnstableApi
class MusicDataSource(private val upstream: DataSource) : DataSource {

    private class RangeIgnored(message: String = "range parameter ignored") : IOException(message)

    private var direct = false
    private var opened = false
    private var songId = ""
    private var spec: DataSpec? = null
    private var baseUri: Uri? = null
    private var headers: Map<String, String> = emptyMap()

    private var position = 0L
    private var endExclusive = UNSET

    private var chunkOpen = false
    private var chunkWant = 0L
    private var chunkRead = 0L

    private var streaming = false
    private var eof = false
    private var refreshRoundsUsed = 0

    private var viaParam = true
    private var viaPost = false
    private var chunkSize = CHUNK_SAFE
    private var emptyChunks = 0
    private var delivered = 0L
    private var requestNo = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        reset()
        if (dataSpec.uri.scheme != SCHEME) {
            direct = true
            val n = upstream.open(dataSpec)
            opened = true
            return n
        }
        spec = dataSpec
        songId = dataSpec.uri.lastPathSegment.orEmpty()
        val url = resolve(songId)
        val uri = Uri.parse(url)
        val hdr = MusicApi.streamHeaders(url)
        val host = uri.host.orEmpty().lowercase()
        if (!(host.endsWith("googlevideo.com") || host.endsWith("youtube.com"))) {
            direct = true
            val n = upstream.open(dataSpec.buildUpon().setUri(uri).setHttpRequestHeaders(hdr).build())
            opened = true
            return n
        }
        direct = false
        baseUri = uri
        headers = hdr
        val hint = MusicApi.formFor(url)
        viaPost = hint.post
        viaParam = hint.param
        position = dataSpec.position
        endExclusive = if (dataSpec.length != UNSET) dataSpec.position + dataSpec.length else UNSET
        if (endExclusive == UNSET) {
            val size = lengthInUrl(uri)
            if (size > 0L) endExclusive = size
        }
        openChunk()
        opened = true
        return if (endExclusive != UNSET) endExclusive - dataSpec.position else UNSET
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (direct) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (eof) return C.RESULT_END_OF_INPUT
            if (endExclusive != UNSET && position >= endExclusive) return C.RESULT_END_OF_INPUT
            if (!chunkOpen) {
                openChunk()
                if (eof) return C.RESULT_END_OF_INPUT
            }
            val n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) {
                val got = chunkRead
                val asked = chunkWant
                val wasStreaming = streaming
                closeChunk()
                emptyChunks = if (got == 0L) emptyChunks + 1 else 0
                if (endExclusive == UNSET) {
                    if (wasStreaming || got < asked) eof = true
                } else if (position < endExclusive && emptyChunks >= 2) {
                    Logs.log(TAG, "no more data for $songId at byte $position of $endExclusive")
                    MusicApi.invalidate(songId, countAttempt = false)
                    throw IOException("no more data at byte $position of $endExclusive")
                }
                continue
            }
            position += n
            chunkRead += n
            delivered += n
            return n
        }
    }

    override fun getUri(): Uri? = if (direct) upstream.uri else baseUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } catch (e: IOException) {
            // nothing left to release
        } finally {
            reset()
        }
    }

    // ------------------------------------------------------------------ chunks

    private fun openChunk() {
        val uri = baseUri ?: throw IOException("no stream address")
        val want = nextChunkSize()
        if (want <= 0L) {
            eof = true
            return
        }
        // Step 1: current address with safe <= 900 KB range chunks (never exceeds 1 MB cap).
        var failure: IOException = tryOpen(uri, want) ?: return

        // Step 2: bounded fast refresh (max 2 rounds) with a different client if this URL was refused.
        if (mayNeedNewAddress(failure) && refreshRoundsUsed < MAX_REFRESH_ROUNDS) {
            while (refreshRoundsUsed < MAX_REFRESH_ROUNDS) {
                refreshRoundsUsed += 1
                Logs.log(TAG, "address refused (" + describe(failure) + ") for $songId at byte $position, refreshing (round $refreshRoundsUsed)")
                val again = tryFreshAddress() ?: return
                failure = again
                if (failure is FileNotFoundException) throw failure
                if (!mayNeedNewAddress(failure)) break
                pause(RECOVER_PAUSE_MS)
            }
        }

        // Step 3: plain open-ended read from current byte.
        val current = baseUri ?: uri
        for (post in booleanArrayOf(viaPost, !viaPost)) {
            try {
                openStreaming(current, post)
                return
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                Logs.log(TAG, "plain " + (if (post) "POST" else "GET") + " read at byte $position refused (" + e.responseCode + ") for $songId")
                closeChunk()
            }
        }

        Logs.log(TAG, "giving up this address for $songId at byte $position (" + describe(failure) + ")")
        MusicApi.invalidate(songId, countAttempt = true)
        throw failure
    }

    private fun tryFreshAddress(): IOException? {
        closeChunk()
        // Do not inflate MusicPlayer retry count during internal chunk recovery.
        MusicApi.invalidate(songId, countAttempt = false)
        val url = try {
            resolve(songId)
        } catch (e: FileNotFoundException) {
            return e
        }
        val fresh = Uri.parse(url)
        val host = fresh.host.orEmpty().lowercase()
        val size = lengthInUrl(fresh)
        val sameFile = (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")) &&
            (size <= 0L || endExclusive == UNSET || size == endExclusive)
        if (!sameFile) {
            Logs.log(TAG, "new address for $songId is not the same file, not used")
            return FileNotFoundException("No matching stream for $songId")
        }
        baseUri = fresh
        headers = MusicApi.streamHeaders(url)
        val hint = MusicApi.formFor(url)
        viaPost = hint.post
        viaParam = hint.param
        chunkSize = CHUNK_SAFE
        if (size > 0L) endExclusive = size
        return tryOpen(fresh, nextChunkSize().coerceAtLeast(1L))
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled while waiting for a new address")
        }
    }

    private fun tryOpen(uri: Uri, want: Long): IOException? =
        try {
            openWithEitherForm(uri, want)
            null
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            e
        } catch (e: RangeIgnored) {
            e
        }

    private fun openWithEitherForm(uri: Uri, want: Long) {
        val sizes = if (want > CHUNK_SMALL) longArrayOf(want, CHUNK_SMALL) else longArrayOf(want)
        var refused: IOException? = null
        for (size in sizes) {
            for (form in formsToTry()) {
                try {
                    openChunkAt(uri, size, form.param, form.post)
                    viaPost = form.post
                    viaParam = form.param
                    if (size < want) chunkSize = size
                    return
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    closeChunk()
                    if (e.responseCode == 416) {
                        eof = true
                        return
                    }
                    Logs.log(TAG, "chunk at byte $position refused (" + e.responseCode + ", " + formLabel(form.post, form.param) + ", " + size + " bytes)")
                    refused = e
                } catch (e: RangeIgnored) {
                    closeChunk()
                    Logs.log(TAG, "chunk at byte $position not usable for $songId (" + (e.message ?: "range ignored") + ", " + formLabel(form.post, form.param) + ")")
                    refused = e
                }
            }
        }
        throw refused ?: IOException("chunk refused")
    }

    private fun formsToTry(): List<MusicApi.StreamForm> {
        val all = listOf(
            MusicApi.StreamForm(false, true),
            MusicApi.StreamForm(false, false),
            MusicApi.StreamForm(true, false),
            MusicApi.StreamForm(true, true),
        )
        val out = ArrayList<MusicApi.StreamForm>()
        out.add(MusicApi.StreamForm(viaPost, viaParam))
        for (f in all) {
            if (f.post != viaPost || f.param != viaParam) out.add(f)
        }
        return out
    }

    private fun formLabel(post: Boolean, param: Boolean): String =
        (if (post) "POST" else "GET") + "/" + (if (param) "range parameter" else "Range header")

    private fun openChunkAt(uri: Uri, want: Long, asParam: Boolean, post: Boolean) {
        val original = spec ?: throw IOException("no request")
        val builder = original.buildUpon()
            .setHttpRequestHeaders(if (post) headers + POST_HEADERS else headers)
            .setFlags(original.flags and DataSpec.FLAG_ALLOW_GZIP.inv())
        if (post) {
            builder.setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(POST_BODY)
        } else {
            builder.setHttpMethod(DataSpec.HTTP_METHOD_GET).setHttpBody(null)
        }
        if (asParam) {
            requestNo += 1
            val target = uri.buildUpon()
                .appendQueryParameter("range", position.toString() + "-" + (position + want - 1L))
                .appendQueryParameter("rn", requestNo.toString())
                .build()
            builder.setUri(target).setPosition(0L).setLength(UNSET)
        } else if (post && uri.getQueryParameter("rn") == null) {
            requestNo += 1
            val target = uri.buildUpon()
                .appendQueryParameter("rn", requestNo.toString())
                .build()
            builder.setUri(target).setPosition(position).setLength(want)
        } else {
            builder.setUri(uri).setPosition(position).setLength(want)
        }
        chunkWant = want
        chunkRead = 0L
        streaming = false
        upstream.open(builder.build())
        chunkOpen = true
        val answer = upstream.responseHeaders
        val type = headerOf(answer, "Content-Type").lowercase()
        if (type.contains("ump") || type.startsWith("text/") || type.contains("json") || type.contains("html")) {
            throw RangeIgnored("answer is not audio: " + type)
        }
        if (asParam && position > 0L) {
            val size = lengthOf(answer)
            if (size != UNSET && size > want) throw RangeIgnored()
        }
        if (endExclusive == UNSET) {
            val total = totalSize(answer)
            if (total > 0L) endExclusive = total
        }
    }

    private fun openStreaming(uri: Uri, post: Boolean) {
        val original = spec ?: throw IOException("no request")
        val builder = original.buildUpon()
            .setUri(uri)
            .setHttpRequestHeaders(if (post) headers + POST_HEADERS else headers)
            .setFlags(original.flags and DataSpec.FLAG_ALLOW_GZIP.inv())
            .setPosition(position)
            .setLength(UNSET)
        if (post) {
            builder.setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(POST_BODY)
        } else {
            builder.setHttpMethod(DataSpec.HTTP_METHOD_GET).setHttpBody(null)
        }
        chunkWant = UNSET
        chunkRead = 0L
        streaming = true
        upstream.open(builder.build())
        chunkOpen = true
        Logs.log(TAG, "plain " + (if (post) "POST" else "GET") + " read opened for $songId at byte $position")
    }

    private fun nextChunkSize(): Long =
        if (endExclusive != UNSET) minOf(chunkSize, endExclusive - position) else chunkSize

    private fun closeChunk() {
        try {
            upstream.close()
        } catch (e: IOException) {
            // already closed
        }
        chunkOpen = false
        streaming = false
        chunkRead = 0L
        chunkWant = 0L
    }

    private fun totalSize(h: Map<String, List<String>>): Long {
        for ((k, v) in h) {
            if (!k.equals("Content-Range", ignoreCase = true)) continue
            val text = v.firstOrNull().orEmpty()
            val slash = text.lastIndexOf('/')
            if (slash < 0) continue
            val n = text.substring(slash + 1).trim().toLongOrNull()
            if (n != null && n > 0L) return n
        }
        return UNSET
    }

    private fun headerOf(h: Map<String, List<String>>, name: String): String {
        for ((k, v) in h) {
            if (k.equals(name, ignoreCase = true)) return v.firstOrNull().orEmpty()
        }
        return ""
    }

    private fun lengthOf(h: Map<String, List<String>>): Long {
        for ((k, v) in h) {
            if (!k.equals("Content-Length", ignoreCase = true)) continue
            val n = v.firstOrNull().orEmpty().trim().toLongOrNull()
            if (n != null && n >= 0L) return n
        }
        return UNSET
    }

    private fun lengthInUrl(uri: Uri): Long =
        uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L } ?: UNSET

    private fun deadAddress(code: Int): Boolean =
        code == 400 || code == 401 || code == 403 || code == 404 || code == 410

    private fun mayNeedNewAddress(e: IOException): Boolean =
        e is RangeIgnored ||
            (e is HttpDataSource.InvalidResponseCodeException && deadAddress(e.responseCode))

    private fun describe(e: IOException): String = when (e) {
        is HttpDataSource.InvalidResponseCodeException -> e.responseCode.toString()
        is RangeIgnored -> "range ignored"
        else -> e.message ?: "no address"
    }

    private fun resolve(id: String): String =
        try {
            MusicApi.resolveStreamBlocking(id, MusicPlayer.songFor(id))
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException("No stream for $id: " + e.message)
        }

    private fun reset() {
        direct = false
        opened = false
        spec = null
        baseUri = null
        headers = emptyMap()
        position = 0L
        endExclusive = UNSET
        chunkOpen = false
        chunkWant = 0L
        chunkRead = 0L
        streaming = false
        eof = false
        refreshRoundsUsed = 0
        viaParam = true
        viaPost = false
        chunkSize = CHUNK_SAFE
        emptyChunks = 0
        delivered = 0L
        requestNo = 0
    }

    private companion object {
        const val TAG = "HikariMusic"
        const val SCHEME = "hikarimusic"
        const val UNSET = -1L

        val POST_BODY = byteArrayOf(0x78, 0)
        val POST_HEADERS = mapOf("Content-Type" to "application/x-www-form-urlencoded")

        /** Strictly under YouTube's 1,048,576-byte (1 MB) per-chunk refusal threshold. */
        const val CHUNK_SAFE = 900L * 1024L
        const val CHUNK_SMALL = 512L * 1024L

        const val MAX_REFRESH_ROUNDS = 2
        const val RECOVER_PAUSE_MS = 600L
    }
}
