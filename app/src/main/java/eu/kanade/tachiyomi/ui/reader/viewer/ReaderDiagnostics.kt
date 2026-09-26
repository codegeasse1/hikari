package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue

/**
 * Test-build diagnostic tracker for the reader. Every page-render decision (path chosen, image
 * dimensions, decode timings, chunk counts) is logged here so a comix lag report can be diagnosed
 * from the phone instead of logcat. Lines accumulate in memory (ring buffer), are mirrored to a log
 * file in the app's files dir (`nekoread-diagnostic.log` — reachable with root), and the latest
 * buffer is pushed to the on-screen overlay in the reader via [onUpdate].
 *
 * The tracker must not itself cost frames: [log] used to open/write/close the log file on the
 * CALLING thread — often the main thread inside a page-bind callback — and to rebuild a 120-line
 * snapshot + recompose the overlay on EVERY line. During a fling that is dozens of synchronous disk
 * writes and string builds per second on the UI thread, which is scroll jank in its own right and
 * inflates the measured `decoded …ms` times (the bind's success callback is delayed by however long
 * the UI thread is blocked). So [log] now only touches the in-memory ring buffer and hands the line
 * to a single background writer thread, and overlay notifications are coalesced to a few per second.
 *
 * This whole file is TEMPORARY: it exists to nail the comix long-strip scroll lag. It is removed
 * once the fix is confirmed.
 */
object ReaderDiagnostics {

    /** Set to false to compile it out of a normal build. */
    const val ENABLED = false

    private const val MAX_LINES = 120

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var logFile: File? = null
    private val startMs = SystemClock.elapsedRealtime()

    /** Callback for the on-screen overlay (single subscriber: the reader screen). */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    /** Page label for the next [log] lines (set by the page holder before rendering a page). */
    @Volatile
    var currentLabel: String = "-"

    // Lines waiting to be appended to the log file. Drained by the writer thread below; bounded so a
    // pathological burst can never grow it without limit (dropping the oldest is fine — the ring
    // buffer shown in the overlay is authoritative for the recent past).
    private val pending = ArrayBlockingQueue<String>(4096)

    @Volatile
    private var writer: Thread? = null

    // Overlay notifications are coalesced: the overlay is recomposed at most once per NOTIFY_MS
    // instead of on every single line.
    private const val NOTIFY_MS = 400L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var notifyScheduled = false

    /** One-time init from any view that has a Context (idempotent). */
    fun init(context: Context) {
        if (!ENABLED) return
        if (logFile != null) return
        logFile = File(context.applicationContext.filesDir, "nekoread-diagnostic.log")
        runCatching { logFile?.writeText("") }
        startWriter()
    }

    private fun startWriter() {
        if (writer != null) return
        synchronized(lock) {
            if (writer != null) return
            val t = Thread({
                val file = logFile
                if (file != null) {
                    var out: BufferedWriter? = null
                    try {
                        val w = BufferedWriter(FileWriter(file, true), 64 * 1024)
                        out = w
                        while (true) {
                            val first = pending.take()
                            w.write(first)
                            w.write("\n")
                            // Drain the rest of the burst before flushing, so a busy moment costs one
                            // write() syscall rather than one per line.
                            var drained = 0
                            while (drained < 512) {
                                val more = pending.poll() ?: break
                                w.write(more)
                                w.write("\n")
                                drained++
                            }
                            w.flush()
                        }
                    } catch (e: Throwable) {
                        // Interrupted / file gone — stop quietly; the in-memory buffer still works.
                    } finally {
                        runCatching { out?.close() }
                    }
                }
            }, "nekoread-diag-writer")
            t.isDaemon = true
            t.start()
            writer = t
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        pending.clear()
        logFile?.let { f -> runCatching { f.writeText("") } }
        notifyNow()
    }

    fun log(msg: String) = logFor(currentLabel, msg)

    /**
     * Like [log], but tags the line with an explicit [label] instead of the mutable [currentLabel].
     *
     * Async lines (a page's download finishing, its decode landing) are written well after the
     * bind that started them, and [currentLabel] is whatever page was bound MOST RECENTLY by then —
     * so a `file ready`/`decoded` line used to be tagged with the wrong page, which made the log
     * look like the same page was bound twice (the dx14 reader log had two `file ready` lines with
     * different sizes under one URL). Callers that log from a coroutine/Coil callback must pass the
     * label captured at bind time.
     */
    fun logFor(label: String, msg: String) {
        if (!ENABLED) return
        val since = SystemClock.elapsedRealtime() - startMs
        val line = "+${since}ms [$label] $msg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        if (writer != null && !pending.offer(line)) {
            // Queue full — drop the oldest to make room for the newest.
            pending.poll()
            pending.offer(line)
        }
        scheduleNotify()
    }

    private fun scheduleNotify() {
        if (onUpdate == null || notifyScheduled) return
        notifyScheduled = true
        mainHandler.postDelayed({
            notifyScheduled = false
            runCatching { onUpdate?.invoke() }
        }, NOTIFY_MS)
    }

    private fun notifyNow() {
        notifyScheduled = false
        runCatching { onUpdate?.invoke() }
    }

    /** Full diagnostic text for the copy button / log file. */
    fun fullText(): String {
        val header = "Nekoread reader diagnostics\n" +
            "log: ${logFile?.absolutePath ?: "not-yet-initialized"}\n"
        val body = synchronized(lock) { lines.joinToString("\n") }
        return header + body
    }

    /** Absolute path of the log file on disk (for the overlay / root pulls). */
    fun path(): String? = logFile?.absolutePath

    /** Latest lines (for the overlay), most recent last. */
    fun text(): String = synchronized(lock) { lines.joinToString("\n") }

    /** Copies the full diagnostic text to the clipboard. */
    fun copy(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("nekoread-diagnostic", fullText()))
    }
}
