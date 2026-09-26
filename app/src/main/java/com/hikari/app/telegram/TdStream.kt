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
 * So the player is given a URI of our own —
 * `hikari-td://file?id=<fileId>&chat=<chatId>&msg=<messageId>` — and this
 * DataSource answers it: on every read it makes sure the bytes at the current
 * offset are on disk (asking TDLib for them if they are not) and then reads them
 * out of the file TDLib wrote. Seeking costs nothing extra: TDLib is asked for
 * the range the seek landed on, so jumping into the middle of a film starts
 * downloading at the middle.
 *
 * Waiting is a poll rather than a callback, deliberately: reads already happen
 * on ExoPlayer's own loading thread, which is allowed to block, and TDLib's
 * `getFileDownloadedPrefixSize` answers "how much of this file is on disk from
 * offset X" from any thread. That keeps this class free of the update-plumbing
 * a callback version would need.
 *
 * Three things here are the difference between "plays" and "buffers forever",
 * and all three were learned from TDLib's own source rather than guessed:
 *
 *  1. **Readiness is asked of TDLib, per offset.** `GetFileDownloadedPrefixSize`
 *     (`FileNode::downloaded_prefix`: it walks the file's downloaded-parts
 *     bitmask) is the only query that answers for an arbitrary offset — seek
 *     into the middle of a film and it says how much of the middle is really
 *     there. The local file's `downloadedPrefixSize` is measured from
 *     `downloadOffset`, and `downloadedSize` is documented as progress-only
 *     ("some parts of it may contain garbage"); trusting either one is how the
 *     old code declared bytes ready that were not.
 *  2. **A refusal is reported, not waited out.** `DownloadFile`'s result is the
 *     error when TDLib will not do the download at all (an expired file
 *     reference, an id from a dead session). [Td.downloadError] carries it here
 *     so the player is told what is wrong in a second instead of being left to
 *     buffer until its watchdog blames the server.
 *  3. **An expired file reference is repaired.** Telegram's file references are
 *     short-lived; re-reading the post ([Td.touchMessage]) is how TDLib is
 *     handed a fresh one, so a video opened from a long-lived list is refreshed
 *     and retried once before it is declared unplayable.
 *
 * And one rule that is easy to get wrong and fatal to ExoPlayer: **`read` never
 * returns 0.** media3 reads a zero as "no data" and simply keeps buffering —
 * the exact reported symptom — so a read that has nothing yet re-asks and
 * blocks, and only a real failure throws.
 */
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

        // Ask TDLib for the video where the player wants it, and wait only for
        // the first frames' worth of bytes: the header of a faststart MP4 plus
        // enough to sniff the container. Everything after that is read as it
        // arrives, so playback starts in a second or two instead of on a
        // finished download. prime() throws with the real reason (a refusal, an
        // expired link, a download that never started) rather than leaving the
        // player to buffer.
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
     * Make sure [need] bytes starting at [offset] are READABLE, asking TDLib for
     * them when they are not — and throwing with the real reason when they will
     * never be.
     *
     * [need] is what this read is about to consume, not the whole window the
     * download is fetched in: waiting for a full download before handing
     * ExoPlayer its first byte is what made every Telegram video report a dead
     * server, and holding up each read for the whole window would do the same
     * thing mid-playback.
     *
     * Readiness is TDLib's own answer for the exact offset ([Td.availableFrom]),
     * so a seek into the middle of a film is answered truthfully — the bytes are
     * either on disk or they are not, whatever TDLib happens to be downloading.
     */
    private fun prime(offset: Long, need: Long, waitMs: Long) {
        if (complete && ready >= offset + need) return
        // Never wait for bytes the file does not have — the last chunk of a
        // video is short, and asking for a full window past the end would sit
        // here until the timeout on every seek into the tail.
        val want = if (length != C.LENGTH_UNSET.toLong()) {
            minOf(need, (length - offset).coerceAtLeast(1L))
        } else need

        val deadline = System.currentTimeMillis() + waitMs
        // A download that is genuinely moving gets longer than the ordinary wait
        // before it is called dead: the budget exists to catch "nothing is
        // happening", not to punish a slow connection.
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
            // No usable remote location and nothing running: that is a refusal
            // that arrived as state rather than as an error (a file reference
            // that expired while TDLib was deciding), so repair and retry.
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
                // Progress at this exact offset means the download is alive, just
                // slower than this read's budget — wait longer rather than
                // declaring a working video dead.
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

    /**
     * Re-read the post this video came from, which is what hands TDLib a fresh
     * file reference — see [Td.touchMessage]. Returns false when there is
     * nothing to repair (no post in the URI, or this source already tried), in
     * which case the caller reports the failure instead of retrying.
     */
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
                // TDLib may still be appending to the end of the file: a read
                // there can come back empty. That is a "not yet", not the end of
                // the media — unless the download is finished, in which case it
                // is.
                if (complete) throw e else 0
            }
            if (n > 0) {
                position += n
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
                bytesTransferred(n)
                return n
            }
            if (complete) return C.RESULT_END_OF_INPUT
            // prime() said the bytes were there and the file gave us nothing:
            // TDLib is writing the file as it goes, so drop the belief and ask
            // again. Returning 0 here is what left ExoPlayer buffering forever —
            // media3 reads a zero as "no data at all", so this loop must either
            // produce bytes or throw.
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

        /**
         * How many bytes must be on disk before [open] returns, i.e. before the
         * player is handed the video at all.
         *
         * Enough for an MP4's `moov` atom and its first frames — Telegram videos
         * are faststart, so their header sits at the front — and small enough to
         * arrive in a second or two on a mobile connection. This is the whole
         * point of not waiting for the full download window: the player starts,
         * and keeps filling in from disk as it reads.
         */
        private const val FIRST_BYTES = 128L * 1024

        /**
         * How long [open] waits for those first bytes before giving up with a
         * reason. Public because the player's "server is not responding"
         * watchdog has to wait longer than this — see [PLAYER_START_BUDGET_MS].
         */
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

        /**
         * How long the player waits for a Telegram video to start before showing
         * its "server is not responding" prompt.
         *
         * A TDLib fetch has a cold-start cost an HTTP request does not (the file
         * reference is resolved over MTProto and the first chunk is pulled from
         * Telegram's servers, with no CDN in front of it). It is still a BACKSTOP
         * now, not the thing that reports a failure: a download TDLib refuses,
         * or one that never moves, is thrown out of [TdFileDataSource] with its
         * reason in a few seconds, so this budget only ever catches a video that
         * is genuinely still arriving. Used by PlayerActivity's
         * scheduleBufferingWatchdog.
         */
        const val PLAYER_START_BUDGET_MS = 60_000L

        /**
         * The URI for one video. [chatId]/[messageId] are the post it came from
         * and are what lets the data source refresh an expired file reference
         * (see [Td.touchMessage]); a caller that does not know them can pass
         * nothing and playback still works.
         */
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
