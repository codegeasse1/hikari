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

class TdFileDataSource : BaseDataSource(false) {

    private var uri: Uri? = null
    private var fileId: Int = 0

    /** The post the video came from, when the caller knew it — see [repair]. */
    private var chatId: Long = 0
    private var messageId: Long = 0

    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var length: Long = C.LENGTH_UNSET.toLong()
    private var reader: RandomAccessFile? = null

    /** The end of the contiguous prefix this reader has verified as readable. */
    private var ready: Long = 0
    private var complete: Boolean = false
    private var transferring: Boolean = false

    /** The window TDLib was last asked for: a request with a DIFFERENT offset
     *  cancels the one in flight, so it is only issued when the read has left
     *  the window (see [Td.AHEAD_BYTES]). */
    private var askedOffset: Long = Long.MIN_VALUE
    private var askedLimit: Long = 0

    /** True once this source has re-read its post to refresh the file reference. */
    private var repaired: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.getQueryParameter("id")?.toIntOrNull()
            ?: throw IOException("Not a Telegram file: ${dataSpec.uri}")
        fileId = id
        // Optional, and only used to repair a stale file reference (see below).
        // An older `hikari-td://file?id=…` URI simply has no post to re-read.
        chatId = dataSpec.uri.getQueryParameter("chat")?.toLongOrNull() ?: 0L
        messageId = dataSpec.uri.getQueryParameter("msg")?.toLongOrNull() ?: 0L
        position = dataSpec.position
        Td.clearDownloadError(fileId)

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

    private fun prime(offset: Long, need: Long, waitMs: Long) {
        if (complete && ready >= offset + need) return
        val want = if (length != C.LENGTH_UNSET.toLong()) {
            minOf(need, (length - offset).coerceAtLeast(1L))
        } else need

        val deadline = System.currentTimeMillis() + waitMs
        val hardDeadline = deadline + GRACE_MS

        while (true) {
            val state = Td.fileState(fileId)
            if (state != null && state.complete) {
                ready = if (state.size > 0) state.size else Long.MAX_VALUE
                complete = true
                return
            }
            val available = runBlocking { Td.availableFrom(fileId, offset) }
            if (available >= want) {
                ready = offset + available
                return
            }

            // Anything past the end of the window we asked for needs a fresh
            // request; staying inside it leaves TDLib's download alone so it can
            // run ahead of the playhead.
            if (offset < askedOffset || offset + want > askedOffset + askedLimit) {
                Td.request(fileId, offset, Td.AHEAD_BYTES)
                askedOffset = offset
                askedLimit = Td.AHEAD_BYTES
            }

            val refused = Td.downloadError(fileId)
            if (refused != null) {
                if (!repaired && repair()) continue
                throw IOException(
                    "Telegram will not download this video ($refused) — " +
                        "open the chat again and retry, or play another server."
                )
            }
            if (state != null && !state.canBeDownloaded && !state.active && available <= 0 &&
                System.currentTimeMillis() > deadline
            ) {
                if (!repaired && repair()) continue
                throw IOException(
                    "Telegram has no usable copy of this video for your account — " +
                        "open the chat again and retry, or play another server."
                )
            }

            if (System.currentTimeMillis() >= deadline) {
                if (available > 0 && System.currentTimeMillis() < hardDeadline) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                if (!repaired && available <= 0 && repair()) continue
                throw IOException(
                    "Telegram is not sending this video" +
                        (if (available > 0) " fast enough" else "") +
                        " (file $fileId, nothing readable at $offset" +
                        (if (available > 0) ", $available bytes available" else "") + ")."
                )
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Telegram", e)
            }
        }
    }

    private fun repair(): Boolean {
        if (repaired) return false
        repaired = true
        if (chatId == 0L || messageId == 0L) return false
        val fresh = runBlocking { Td.touchMessage(chatId, messageId) }
        com.hikari.app.data.Logs.log(
            "Telegram",
            "re-read post $chatId/$messageId for file $fileId" +
                if (fresh != 0 && fresh != fileId) " (now file $fresh)" else "",
        )
        if (fresh != 0 && fresh != fileId) {
            // TDLib re-registered the file under a new id: follow it.
            fileId = fresh
        }
        Td.clearDownloadError(fileId)
        askedOffset = Long.MIN_VALUE
        askedLimit = 0
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = reader ?: throw IOException("Not open")
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        if (want <= 0) return C.RESULT_END_OF_INPUT

        var stalls = 0
        while (true) {
            if (!complete && ready < position + want) {
                prime(position, want.toLong(), READ_WAIT_MS)
            }
            val n = try {
                raf.read(buffer, offset, want)
            } catch (e: IOException) {
                if (complete) throw e else 0
            }
            if (n > 0) {
                position += n
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
                bytesTransferred(n)
                return n
            }
            if (complete) return C.RESULT_END_OF_INPUT
            ready = minOf(ready, position)
            if (++stalls > MAX_EMPTY_READS) {
                throw IOException(
                    "Telegram stopped delivering this video before its end " +
                        "(file $fileId, at $position) — try again or play another server."
                )
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while reading from Telegram", e)
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { reader?.close() }
        reader = null
        uri = null
        ready = 0
        complete = false
        askedOffset = Long.MIN_VALUE
        askedLimit = 0
        repaired = false
        if (transferring) {
            transferring = false
            // BaseDataSource.transferEnded() requires a started transfer.
            runCatching { transferEnded() }
        }
    }

    companion object {
        /** The scheme the player sees; the file id rides in a query parameter. */
        const val SCHEME = "hikari-td"

        private const val FIRST_BYTES = 128L * 1024

        const val START_WAIT_MS = 20_000L

        /** How long one read may wait for the bytes it is about to consume. */
        private const val READ_WAIT_MS = 25_000L

        /**
         * How much longer a download that is demonstrably moving (there are
         * bytes at the offset we are waiting on) gets before it is called dead.
         */
        private const val GRACE_MS = 30_000L

        /** How often TDLib is polled for the bytes a read is waiting on. */
        private const val POLL_MS = 120L

        /** How many empty reads in a row are tolerated before the stream is
         *  declared broken — see [read], which may never return 0. */
        private const val MAX_EMPTY_READS = 8

        const val PLAYER_START_BUDGET_MS = 60_000L

        fun uriFor(fileId: Int, chatId: Long = 0L, messageId: Long = 0L): String {
            val base = "$SCHEME://file?id=$fileId"
            return if (chatId != 0L && messageId != 0L) "$base&chat=$chatId&msg=$messageId" else base
        }

        /** The file id inside a [SCHEME] URI, or 0. Used by the player's watchdog. */
        fun fileIdOf(uri: String?): Int =
            uri?.let { Uri.parse(it).getQueryParameter("id")?.toIntOrNull() } ?: 0

        fun isTd(uri: Uri?): Boolean = uri?.scheme?.equals(SCHEME, ignoreCase = true) == true
    }
}

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
