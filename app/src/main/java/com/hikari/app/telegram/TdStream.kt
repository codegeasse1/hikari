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

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.getQueryParameter("id")?.toIntOrNull()
            ?: throw IOException("Not a Telegram file: ${dataSpec.uri}")
        fileId = id
        position = dataSpec.position
        val first = Td.fileState(fileId) ?: throw IOException("Telegram is not available")
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
        prime(position)
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
     * Make sure [offset] is on disk, asking TDLib for it when it is not. A read
     * that arrives before the bytes do waits here — up to two minutes — so a
     * slow connection shows ExoPlayer's own buffering indicator rather than an
     * error.
     */
    private fun prime(offset: Long) {
        if (complete && ready > offset) return
        var state = runBlocking { Td.await(fileId, offset, CHUNK, 120_000) }
        // TDLib answers the first request with whatever it already had while the
        // download it just started is still running, so one more round is the
        // difference between "the first frame appears" and a stall.
        if (state != null && state.downloaded < offset + CHUNK && !state.complete) {
            state = runBlocking { Td.await(fileId, offset, CHUNK, 120_000) }
        }
        if (state == null) throw IOException("Telegram did not answer")
        ready = maxOf(state.downloaded, if (state.complete) state.size else 0L)
        complete = state.complete
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = reader ?: throw IOException("Not open")
        if (!complete && position + length > ready) prime(position)
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
            prime(position)
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
        if (transferring) {
            transferring = false
            // BaseDataSource.transferEnded() requires a started transfer.
            runCatching { transferEnded() }
        }
    }

    companion object {
        /** The scheme the player sees; the file id rides in a query parameter. */
        const val SCHEME = "hikari-td"

        /** How much to ask TDLib for at a time: big enough that 1080p plays
         *  through it smoothly, small enough that a seek starts playing without
         *  waiting for a whole film. */
        private const val CHUNK = 2L * 1024 * 1024

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
