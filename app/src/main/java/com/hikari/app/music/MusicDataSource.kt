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
 * The music player's data source on a television.
 *
 * It does two jobs the plain "resolve once, then stream" setup could not:
 *
 * 1. **Ranged reading of YouTube audio.** googlevideo.com serves a song fine for the first few
 *    hundred kilobytes and then throttles or drops a single long, open-ended connection, so the song
 *    is read as ranged requests, each on a fresh request, and the player still sees one continuous
 *    stream.
 *
 *    Every song stopped at about 1:04 with "Couldn't play": 1 MB of 128 kbit/s AAC is 1:04, so the
 *    SECOND 1 MB request was the one that failed. Two things changed here:
 *    - A request now covers up to 10 MB (what yt-dlp uses), so a normal song is ONE request and
 *      there is no second request to fail. Only a very long track needs a second one.
 *    - If a later request is refused anyway it is NOT the end of the song any more. The ladder is:
 *      range as a `range=` parameter, then as a `Range` header, each first with the big size and then
 *      with 1 MB; then a brand-new address (made by a DIFFERENT YouTube client than the one that just
 *      failed, see [MusicApi.invalidate]); then one plain open-ended read from the same byte.
 *      Whichever way works is kept for the rest of the song.
 *    A chunk that comes back shorter than asked is also no longer taken for "the song ended": when the
 *    file size is known the next chunk simply continues from the byte where the data stopped.
 * 2. **A dead address is replaced on the spot.** If YouTube answers 403 / 404 / 410 (the address
 *    expired or was refused) the song is asked for again, once, and the read carries on from the
 *    same byte, instead of retrying the same dead address until the player gives up.
 *
 * Every ranged request can be sent four ways: GET or POST (the two-byte `x` + zero body the YouTube
 * apps and NewPipe send), each with the range as a `range=` parameter or as a `Range` header. The way
 * the address was proven to work in ([MusicApi.formFor]) is tried first; if a request is refused, the
 * other ways are tried before the address is given up. Whichever works is kept for the rest of the song.
 *
 * Addresses that are not YouTube's (JioSaavn) are passed straight through.
 *
 * A song that cannot be resolved at all fails with a "file not found" error, which the player does
 * not retry by itself: [MusicPlayer] decides what happens next (retry one step further down the
 * chain, never silently another song).
 */
@UnstableApi
class MusicDataSource(private val upstream: DataSource) : DataSource {

    /** The server answered a `range=` request with more bytes than asked: it did not honour the range. */
    private class RangeIgnored(message: String = "range parameter ignored") : IOException(message)

    private var direct = false
    private var opened = false
    private var songId = ""
    private var spec: DataSpec? = null
    private var baseUri: Uri? = null
    private var headers: Map<String, String> = emptyMap()

    /** Next byte to request / the byte the next read continues from. */
    private var position = 0L

    /** End (exclusive) of the data the player wants, once known; [UNSET] while it is not. */
    private var endExclusive = UNSET

    private var chunkOpen = false
    private var chunkWant = 0L
    private var chunkRead = 0L

    /** True while the open request is the last-resort plain read (no end of range). */
    private var streaming = false
    private var eof = false
    private var freshTried = false

    /** True: the byte range goes in the address (`range=`); false: in the `Range` header. */
    private var viaParam = true

    /** True: requests are POSTs with the two-byte body the YouTube apps send; false: plain GETs. */
    private var viaPost = false

    /** How many bytes one request asks for. Drops to 1 MB if the big size is refused. */
    private var chunkSize = CHUNK_BIG

    /** Requests in a row that ended without a single byte. */
    private var emptyChunks = 0

    /** Bytes handed to the player since this open: above 0 the player already has music buffered. */
    private var delivered = 0L

    /** Counts the requests of this song, sent as `rn` like NewPipe does. */
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
            // YouTube puts the file size in the address (clen=...). A ranged answer made with the
            // `range=` parameter has no Content-Range header, so this is where the size comes from.
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
                // This request is finished.
                val got = chunkRead
                val asked = chunkWant
                val wasStreaming = streaming
                closeChunk()
                emptyChunks = if (got == 0L) emptyChunks + 1 else 0
                if (endExclusive == UNSET) {
                    // The file size is not known: a request shorter than asked (or a plain read that
                    // ends) is the end of the file.
                    if (wasStreaming || got < asked) eof = true
                } else if (position < endExclusive && emptyChunks >= 2) {
                    // The size is known and the server keeps answering with nothing: this address is
                    // no good. Throw it away so the player's retry gets a different one.
                    Logs.log(TAG, "no more data for $songId at byte $position of $endExclusive")
                    MusicApi.invalidate(songId)
                    throw IOException("no more data at byte $position of $endExclusive")
                }
                // With the size known a short request is NOT the end: the loop asks again from
                // `position`, and stops by itself once `position` reaches the end of the file.
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
            // Always closed: after a failed open the wrapped source may still hold a half-open
            // connection, and the player's retry opens this same object again.
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
        // Step 1: this address, both range forms, big size then 1 MB.
        var failure: IOException = tryOpen(uri, want) ?: return
        // Step 2: a brand-new address. In the middle of a song the player still has a minute or so of
        // music buffered, so this keeps asking (every few seconds, for up to RECOVER_MS) instead of
        // giving up after one try: YouTube's answers come and go (a "not a bot" check that passes the
        // next time, a connection that fails once), and the music keeps playing meanwhile.
        val patient = delivered > 0L
        if (mayNeedNewAddress(failure) && (patient || !freshTried)) {
            freshTried = true
            val giveUpAt = System.currentTimeMillis() + RECOVER_MS
            var round = 0
            while (true) {
                round += 1
                Logs.log(TAG, "address refused (" + describe(failure) + ") for $songId at byte $position, asking for a new one (round $round)")
                val again = tryFreshAddress() ?: return
                failure = again
                val keepTrying = patient && System.currentTimeMillis() < giveUpAt &&
                    (mayNeedNewAddress(failure) || failure is FileNotFoundException)
                if (!keepTrying) break
                pause(RECOVER_PAUSE_MS)
            }
            // No address at all (or none for this very file): nothing is left to read from.
            if (failure is FileNotFoundException) throw failure
        }
        // Step 3: one plain read from this byte to the end, the way a phone plays it.
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
        // Nothing worked on this address: forget it, so the player's own retry is handed a different one
        // instead of this same dead address again (which is what made a song hang on "Loading").
        Logs.log(TAG, "giving up this address for $songId at byte $position (" + describe(failure) + ")")
        MusicApi.invalidate(songId)
        throw failure
    }

    /**
     * Asks for a brand-new address (made by another YouTube client than the one that just failed when
     * there is one) and opens the chunk on it: null when that worked, otherwise why it did not.
     * A new address is only used when it is the SAME file: bytes of another file (another source,
     * another encoding) in the middle of the stream would play as noise.
     */
    private fun tryFreshAddress(): IOException? {
        closeChunk()
        MusicApi.invalidate(songId)
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
        chunkSize = CHUNK_BIG
        if (size > 0L) endExclusive = size
        return tryOpen(fresh, nextChunkSize().coerceAtLeast(1L))
    }

    /** Waits [ms] on the loading thread; a song thrown away by the player stops the wait at once. */
    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled while waiting for a new address")
        }
    }

    /** Opens one request through the ladder; null when it worked, otherwise why it did not. */
    private fun tryOpen(uri: Uri, want: Long): IOException? =
        try {
            openWithEitherForm(uri, want)
            null
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            e
        } catch (e: RangeIgnored) {
            e
        }

    /**
     * Opens one chunk the preferred way and, if the server refuses that, every other way (GET / POST,
     * range parameter / Range header), first with the wanted size and then (when that was big) with
     * 1 MB. Whichever combination works becomes the preferred one for the rest of the song.
     */
    private fun openWithEitherForm(uri: Uri, want: Long) {
        val sizes = if (want > CHUNK_SMALL) longArrayOf(want, CHUNK_SMALL) else longArrayOf(want)
        var refused: IOException? = null
        for (size in sizes) {
            for (form in formsToTry()) {
                try {
                    openChunkAt(uri, size, form.param, form.post)
                    if (form.post != viaPost || form.param != viaParam) {
                        Logs.log(TAG, "request form " + formLabel(form.post, form.param) + " accepted for $songId at byte $position")
                    }
                    viaPost = form.post
                    viaParam = form.param
                    if (size < want) chunkSize = size
                    return
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    closeChunk()
                    if (e.responseCode == 416) {
                        // Asked for bytes past the end of the file: that is the end of the song.
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

    /** The current request form first, then the other three. */
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
            // Plain bytes, so the answer's length is exact.
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
            // A POST with a `Range` header carries the request number as well, like NewPipe's.
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
        // A page, an error text or YouTube's own "UMP" wrapper instead of audio bytes: not usable.
        val type = headerOf(answer, "Content-Type").lowercase()
        if (type.contains("ump") || type.startsWith("text/") || type.contains("json") || type.contains("html")) {
            throw RangeIgnored("answer is not audio: " + type)
        }
        if (asParam && position > 0L) {
            // A server that ignores `range=` sends the whole file from byte 0. Playing that after
            // byte `position` would put the wrong data into the stream, so it counts as a refusal.
            val size = lengthOf(answer)
            if (size != UNSET && size > want) throw RangeIgnored()
        }
        if (endExclusive == UNSET) {
            val total = totalSize(answer)
            if (total > 0L) endExclusive = total
        }
    }

    /** Last resort: one open-ended read from [position], no end of range (GET, or POST when [post]). */
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
            // the chunk is finished anyway
        }
        chunkOpen = false
        streaming = false
        chunkRead = 0L
        chunkWant = 0L
    }

    /** "Content-Range: bytes 0-1048575/3451023" -> 3451023, or [UNSET]. */
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

    /** The first value of header [name] (any letter case), or "" when the answer has none. */
    private fun headerOf(h: Map<String, List<String>>, name: String): String {
        for ((k, v) in h) {
            if (k.equals(name, ignoreCase = true)) return v.firstOrNull().orEmpty()
        }
        return ""
    }

    /** "Content-Length: 1048576" -> 1048576, or [UNSET]. */
    private fun lengthOf(h: Map<String, List<String>>): Long {
        for ((k, v) in h) {
            if (!k.equals("Content-Length", ignoreCase = true)) continue
            val n = v.firstOrNull().orEmpty().trim().toLongOrNull()
            if (n != null && n >= 0L) return n
        }
        return UNSET
    }

    /** The file size YouTube writes into its stream addresses ("clen=3451023"), or [UNSET]. */
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
            // FileNotFoundException is one of the errors the player never retries by itself, so a
            // song that cannot be resolved fails at once and the music player picks the next step.
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
        freshTried = false
        viaParam = true
        viaPost = false
        chunkSize = CHUNK_BIG
        emptyChunks = 0
        delivered = 0L
        requestNo = 0
    }

    private companion object {
        const val TAG = "HikariMusic"
        const val SCHEME = "hikarimusic"
        const val UNSET = -1L

        /** The body of a POST stream request: the two bytes the YouTube apps (and NewPipe) send. */
        val POST_BODY = byteArrayOf(0x78, 0)

        /** A POST made by a form-less client is sent as a form, like NewPipe's connection does. */
        val POST_HEADERS = mapOf("Content-Type" to "application/x-www-form-urlencoded")

        /**
         * One range request. 10 MB is what yt-dlp asks YouTube for and it is never throttled at that
         * size; it is also bigger than almost every song (3:41 of AAC is about 3.4 MB), so a song is
         * ONE request and there is no second request to be refused at 1:04.
         */
        const val CHUNK_BIG = 10L * 1024L * 1024L

        /** The smaller size tried when the big one is refused. */
        const val CHUNK_SMALL = 1024L * 1024L

        /**
         * How long a song that is already playing keeps asking for a new address when its next piece
         * is refused. The player has about a minute of music buffered, so the song does not stop
         * while this runs; it has to end before that buffer does.
         */
        const val RECOVER_MS = 20_000L

        /** Pause between two of those asks. */
        const val RECOVER_PAUSE_MS = 1_500L
    }
}
