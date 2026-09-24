package com.hikari.app.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plays a Telegram video by streaming it out of TDLib.
 *
 * A Telegram file has no URL to hand ExoPlayer: it lives in TDLib's own store,
 * fetched chunk by chunk over MTProto, with no HTTP server anywhere in the path.
 * So the player is given a URI of our own — `hikari-td://file?id=<fileId>` —
 * and this DataSource answers it: on every read it makes sure the bytes at the
 * current offset are on disk (asking TDLib for them if they are not) and then
 * reads them out of the file TDLib wrote. Seeking costs nothing extra: TDLib is
 * asked for the range the seek landed on, so jumping into the middle of a film
 * starts downloading at the middle.
 *
 * Waiting is a poll rather than a callback, deliberately: reads already happen
 * on ExoPlayer's own loading thread, which is allowed to block, and TDLib's
 * synchronous `getFile` answers from any thread. That keeps this class free of
 * the update-plumbing a callback version would need — and it is the reason a
 * fresh TDLib request has to be issued for every new offset (TDLib cancels the
 * previous range request for a file when a new one arrives).
 */
class TdFileDataSource : BaseDataSource(false) {

    private var uri: Uri? = null
    private var fileId: Int = 0
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var length: Long = C.LENGTH_UNSET.toLong()
    private var reader: RandomAccessFile? = null

    /** How much of the file is known to be on disk, counted from its start. */
    private var ready: Long = 0
    private var complete: Boolean = false
    private var transferring: Boolean = false

    /** The download window TDLib was last asked for, and how many bytes were on
     *  disk when it was asked — see [prime]. Kept across reads rather than per
     *  call, because that is what makes a window's own coverage knowable. */
    private var windowRequested: Long = Long.MIN_VALUE
    private var windowBaseline: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.getQueryParameter("id")?.toIntOrNull()
            ?: throw IOException("Not a Telegram file: ${dataSpec.uri}")
        fileId = id
        position = dataSpec.position
        // stateOf (not fileState): a video the user has never played has never
        // been downloaded, so TDLib has sent no `UpdateFile` for it and
        // `fileState` alone knows nothing — it has to be ASKED for. That lookup
        // is a round trip to TDLib and is therefore suspend; this runs on
        // ExoPlayer's own loading thread, which is allowed to block.
        val first = runBlocking { Td.stateOf(fileId) }
            ?: throw IOException("Telegram did not return this video (file $fileId)")
        length = if (first.size > 0) first.size else C.LENGTH_UNSET.toLong()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length != C.LENGTH_UNSET.toLong() -> length - position
            else -> C.LENGTH_UNSET.toLong()
        }
        if (bytesRemaining == 0L) {
            transferring = true
            transferStarted(dataSpec)
            return 0
        }
        prime(position, FIRST_BYTES, START_WAIT_MS)
        // The path can only be known once TDLib has started writing the file,
        // which is exactly what prime() waits for.
        val path = Td.fileState(fileId)?.path.orEmpty()
        if (path.isBlank()) throw IOException("Telegram has no local copy of this video yet")
        reader = RandomAccessFile(path, "r").apply { seek(position) }
        transferring = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /**
     * Make sure [need] bytes starting at [offset] are on disk, asking TDLib for
     * them when they are not. A read that arrives before the bytes do waits here
     * — up to [waitMs] — so a slow connection shows ExoPlayer's own buffering
     * indicator rather than an error.
     *
     * [need] is what THIS read is about to consume, not the whole chunk the
     * download is fetched in. Waiting for all 2 MiB before handing ExoPlayer its
     * first byte is what made every Telegram video report "Server is not
     * responding (still buffering after 20s)": 2 MiB is half a minute at
     * 76 KB/s, and on a slower connection it never arrives at all. The request
     * TDLib is given stays chunked and aligned to the read (see
     * [Td.CHUNK_BYTES]), so the download still runs ahead of playback while the
     * player gets its first frames as soon as they exist.
     */
    private fun prime(offset: Long, need: Long, waitMs: Long) {
        if (complete && ready >= offset + need) return
        // Never wait for bytes the file does not have — the last chunk of a
        // video is short, and asking for a full window past the end would sit
        // here until the timeout on every seek into the tail.
        val want = if (length != C.LENGTH_UNSET.toLong()) {
            minOf(need, (length - offset).coerceAtLeast(1L))
        } else need
        val window = offset / Td.CHUNK_BYTES * Td.CHUNK_BYTES
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val state = Td.fileState(fileId)
            if (state != null) {
                if (state.complete) {
                    ready = maxOf(state.size, offset + want)
                    complete = true
                    return
                }
                if (state.downloaded >= offset + want) {
                    // The ordinary case: the file's own prefix has reached the
                    // bytes this read wants, so they are on disk.
                    ready = maxOf(ready, state.downloaded)
                    return
                }
                if (window == windowRequested) {
                    // The window's own coverage. Everything TDLib has written
                    // since it was asked for this window is the window's — a new
                    // range request cancels whatever else it was doing for this
                    // file — so this is what tells a SEEK its bytes have landed:
                    // a range download does not advance the file's prefix, so
                    // `downloaded` on its own never reaches into the middle of a
                    // film.
                    ready = maxOf(ready, window + (state.downloaded - windowBaseline))
                    if (ready >= offset + want) return
                }
            }
            if (System.currentTimeMillis() >= deadline) return
            if (window != windowRequested) {
                Td.request(fileId, window, Td.CHUNK_BYTES)
                windowRequested = window
                windowBaseline = Td.fileState(fileId)?.downloaded ?: 0L
            }
            // Sleeping on ExoPlayer's own loading thread, which is allowed to
            // block: this is what shows the player's buffering indicator instead
            // of an error while Telegram delivers the bytes.
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = reader ?: throw IOException("Not open")
        if (!complete && position + length > ready) {
            // Only the bytes this read is about to consume — see [prime].
            prime(position, (position + length) - minOf(ready, position), READ_WAIT_MS)
        }
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            raf.read(buffer, offset, want)
        } catch (e: IOException) {
            // TDLib may still be appending to the end of the file: a read there
            // can come back empty. That is a "not yet", not the end of the
            // media — unless the download is finished, in which case it is.
            if (complete) throw e else 0
        }
        if (n <= 0) {
            if (complete) return C.RESULT_END_OF_INPUT
            // Ask for more and let ExoPlayer retry this same read rather than
            // declaring the stream over.
            prime(position, length.toLong(), READ_WAIT_MS)
            return 0
        }
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { reader?.close() }
        reader = null
        uri = null
        ready = 0
        complete = false
        windowRequested = Long.MIN_VALUE
        windowBaseline = 0
        if (transferring) {
            transferring = false
            // BaseDataSource.transferEnded() requires a started transfer.
            runCatching { transferEnded() }
        }
    }

    companion object {
        /** The scheme the player sees; the file id rides in a query parameter. */
        const val SCHEME = "hikari-td"

        /**
         * How many bytes must be on disk before [open] returns, i.e. before the
         * player is handed the video at all.
         *
         * Enough for an MP4's `moov` atom and its first frames — Telegram videos
         * are faststart, so their header sits at the front — and small enough to
         * arrive in a second or two on a mobile connection. This is the whole
         * point of not waiting for the full 2 MiB download window: the player
         * starts, and keeps filling in from disk as it reads.
         */
        private const val FIRST_BYTES = 128L * 1024

        /**
         * How long [open] waits for those first bytes before handing the player a
         * source that will keep filling in as it reads. Public because the
         * player's "server is not responding" watchdog has to wait longer than
         * this — see [PLAYER_START_BUDGET_MS].
         */
        const val START_WAIT_MS = 20_000L

        /** How long one read may wait for the bytes it is about to consume. */
        private const val READ_WAIT_MS = 25_000L

        /**
         * How long the player waits for a Telegram video to start before showing
         * its "server is not responding" prompt.
         *
         * A TDLib fetch has a cold-start cost an HTTP request does not (the file
         * reference is resolved over MTProto and the first chunk is pulled from
         * Telegram's servers, with no CDN in front of it), so the ordinary 20s
         * budget declared every Telegram video a dead server before
         * [START_WAIT_MS] had even elapsed. Used by PlayerActivity's
         * scheduleBufferingWatchdog.
         */
        const val PLAYER_START_BUDGET_MS = 60_000L

        fun uriFor(fileId: Int): String = "$SCHEME://file?id=$fileId"

        fun isTd(uri: Uri?): Boolean = uri?.scheme?.equals(SCHEME, ignoreCase = true) == true
    }
}

/**
 * The player's data sources, with Telegram folded in: a `hikari-td:` URI is
 * answered by [TdFileDataSource] and everything else goes to [base] exactly as
 * it did before. The player therefore gains Telegram playback with one line —
 * wrap its existing factory in this (see PlayerActivity).
 */
class TdDataSourceFactory(private val base: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = SwitchingDataSource(base.createDataSource())
}

/** Picks one of the two sources per [DataSpec]. */
private class SwitchingDataSource(private val base: DataSource) : DataSource {

    private val td = TdFileDataSource()

    @Volatile
    private var current: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (TdFileDataSource.isTd(dataSpec.uri)) td else base
        current = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = current ?: throw IOException("Not open")
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = current?.uri

    override fun close() {
        runCatching { td.close() }
        runCatching { base.close() }
        current = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        base.addTransferListener(transferListener)
    }
}
