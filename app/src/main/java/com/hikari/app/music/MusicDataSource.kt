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
 * 1. **Chunked reading of YouTube audio.** googlevideo.com serves a song fine for the first few
 *    hundred kilobytes and then throttles or drops a single long connection, so a song played for
 *    about half a minute and went back to "Loading" for good. Here the song is read as a chain of
 *    1 MB range requests (the way NewPipe and yt-dlp read YouTube), each on a fresh request, and
 *    the player still sees one continuous stream.
 * 2. **A dead address is replaced on the spot.** If YouTube answers 403 / 404 / 410 (the address
 *    expired or was refused) the song is asked for again, once, and the read carries on from the
 *    same byte, instead of retrying the same dead address until the player gives up.
 *
 * Addresses that are not YouTube's (JioSaavn) are passed straight through.
 *
 * A song that cannot be resolved at all fails with a "file not found" error, which the player does
 * not retry by itself: [MusicPlayer] decides what happens next (retry one step further down the
 * chain, never silently another song).
 */
@UnstableApi
class MusicDataSource(private val upstream: DataSource) : DataSource {

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
    private var eof = false
    private var freshTried = false

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
        val hdr = mapOf("User-Agent" to MusicApi.userAgentFor(url))
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
        position = dataSpec.position
        endExclusive = if (dataSpec.length != UNSET) dataSpec.position + dataSpec.length else UNSET
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
                // This chunk is finished. A chunk shorter than asked means the file itself ended.
                val shortChunk = chunkRead < chunkWant
                closeChunk()
                if (shortChunk) eof = true
                continue
            }
            position += n
            chunkRead += n
            return n
        }
    }

    override fun getUri(): Uri? = if (direct) upstream.uri else baseUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            if (opened || chunkOpen) upstream.close()
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
        try {
            openChunkAt(uri, want)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val code = e.responseCode
            if (code == 416) {
                // Asked for bytes past the end of the file: that is the end of the song.
                eof = true
                chunkOpen = false
                return
            }
            if (!deadAddress(code) || freshTried) throw e
            // The address expired or was refused: ask for a new one once and go on from the same byte.
            freshTried = true
            Logs.log(TAG, "address refused ($code) for $songId at byte $position, asking for a new one")
            closeChunk()
            MusicApi.invalidate(songId)
            val url = resolve(songId)
            val fresh = Uri.parse(url)
            baseUri = fresh
            headers = mapOf("User-Agent" to MusicApi.userAgentFor(url))
            openChunkAt(fresh, nextChunkSize().coerceAtLeast(1L))
        }
    }

    private fun openChunkAt(uri: Uri, want: Long) {
        val original = spec ?: throw IOException("no request")
        val chunk = original.buildUpon()
            .setUri(uri)
            .setPosition(position)
            .setLength(want)
            .setHttpRequestHeaders(headers)
            .build()
        chunkWant = want
        chunkRead = 0L
        upstream.open(chunk)
        chunkOpen = true
        if (endExclusive == UNSET) {
            val total = totalSize(upstream.responseHeaders)
            if (total > 0L) endExclusive = total
        }
    }

    private fun nextChunkSize(): Long =
        if (endExclusive != UNSET) minOf(CHUNK, endExclusive - position) else CHUNK

    private fun closeChunk() {
        try {
            upstream.close()
        } catch (e: IOException) {
            // the chunk is finished anyway
        }
        chunkOpen = false
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

    private fun deadAddress(code: Int): Boolean = code == 403 || code == 404 || code == 410 || code == 401

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
        eof = false
        freshTried = false
    }

    private companion object {
        const val TAG = "HikariMusic"
        const val SCHEME = "hikarimusic"
        const val UNSET = -1L

        /** One range request. Small enough that YouTube never throttles it, big enough to need few requests. */
        const val CHUNK = 1024L * 1024L
    }
}
