package com.hikari.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * On-device rolling logs, so a bug report is a share button instead of a photo
 * of the screen.
 *
 * Three files live in `filesDir/logs/` (shared out through the app's
 * FileProvider — see `res/xml/file_paths.xml`):
 *
 *  - `app.log`          the current session log; rolls over at [FILE_MAX_BYTES]
 *  - `app.previous.log` the session before it (exactly two app logs are kept)
 *  - `crash.log`        the full, untruncated report for the last uncaught
 *                       exception, including the last [RING_MAX] log lines
 *                       ("breadcrumbs") so the cause is visible, not guessed
 *
 * Everything is best-effort: a log write must never be the thing that crashes
 * the app, so every file operation is wrapped and the in-memory ring keeps
 * working even if the directory can't be created.
 *
 * FILE I/O IS OFF THE CALLING THREAD. [log] is called for every interesting UI
 * event — i.e. on the MAIN thread — and it used to append to `app.log` and
 * stat the file inline to check the roll-over size. On a slow/cheap device that
 * is a disk write plus an `fstat` in the middle of a frame, on every event, and
 * it is invisible in a profile until the list starts to jank. Now [log] only
 * touches the in-memory ring and hands the line to a single daemon writer
 * thread ([queue]), which owns the files: ordering is preserved, rotation
 * happens there, and a caller that needs to READ the files (Settings → share
 * logs, the crash banner) calls [flush] first.
 */
object Logs {

    /** Roll `app.log` over once it passes this size. */
    private const val FILE_MAX_BYTES = 512L * 1024L

    /** How many recent lines the crash report carries as breadcrumbs. */
    private const val RING_MAX = 300

    /** How long a reader waits for the writer to drain before giving up (a
     *  log file must never be able to hang the Settings screen). */
    private const val FLUSH_TIMEOUT_MS = 2_000L

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_MAX)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampShort = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** SimpleDateFormat is not thread-safe: every format call happens under
     *  [lock] (logging runs on the main thread AND on IO threads). */
    private fun stampOf(fmt: SimpleDateFormat): String = synchronized(lock) { fmt.format(Date()) }

    /** One queued file operation. The writer thread is the ONLY thing that ever
     *  touches the log files — that is what keeps `log()` free of disk I/O. */
    private sealed class Op {
        /** Append [text] (already newline-terminated) to app.log. */
        class Append(val text: String) : Op()
        /** Overwrite a whole file (the crash report). */
        class Replace(val name: String, val text: String) : Op()
        /** Delete everything (or just the crash report) on the writer thread. */
        class Delete(val name: String?) : Op()
        /** Signal that every earlier op has been applied. */
        class Flush(val done: CountDownLatch) : Op()
    }

    private val queue = LinkedBlockingQueue<Op>()

    /**
     * The single log writer. Daemon + owned by this object, so the app shutting
     * down is never delayed by it, and its own failures can never propagate
     * into user code (every op is wrapped).
     */
    private val writer: Thread by lazy {
        Thread({
            while (true) {
                val op = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                try {
                    applyOp(op)
                } catch (t: Throwable) {
                    // Best effort by design: logging must never be the thing
                    // that crashes the app.
                }
            }
        }, "hikari-log").apply { isDaemon = true; start() }
    }

    private fun applyOp(op: Op) {
        val d = dir
        when (op) {
            is Op.Append -> {
                if (d == null) return
                runCatching {
                    if (fileBytes(d, "app.log") > FILE_MAX_BYTES) rotate(d)
                    File(d, "app.log").appendText(op.text)
                }
            }
            is Op.Replace -> {
                if (d == null) return
                runCatching { File(d, op.name).writeText(op.text) }
            }
            is Op.Delete -> {
                if (d == null) return
                runCatching {
                    if (op.name == null) {
                        File(d, "app.log").delete()
                        File(d, "app.previous.log").delete()
                        File(d, "crash.log").delete()
                    } else {
                        File(d, op.name).delete()
                    }
                }
            }
            is Op.Flush -> op.done.countDown()
        }
    }

    /** Blocks until the writer has applied everything queued before this call
     *  (bounded by [FLUSH_TIMEOUT_MS]), so a reader sees a complete file. */
    fun flush() {
        if (!enabled) return
        val done = CountDownLatch(1)
        queue.offer(Op.Flush(done))
        writer // ensure the thread exists even if every earlier op was dropped
        runCatching { done.await(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    @Volatile
    private var dir: File? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var versionLine = "Hikari"

    /** One shareable log file plus the wording the Settings screen shows. */
    data class LogFile(
        val key: String,
        val title: String,
        val description: String,
        val file: File,
        val isCrash: Boolean,
    )

    /**
     * Point the logger at the app's private `logs/` folder. Safe to call more
     * than once; the first successful call wins.
     */
    fun init(context: Context) {
        if (enabled) return
        synchronized(lock) {
            if (enabled) return
            runCatching {
                versionLine = versionString(context)
                val d = File(context.filesDir, "logs")
                if (!d.exists()) d.mkdirs()
                if (!d.isDirectory) return
                dir = d
                enabled = true
                writer // start the writer before anything is queued
                append("=".repeat(64) + "\n")
                append("session start · $versionLine · ${deviceLine()}\n")
            }.onFailure { Log.w("HikariLogs", "init failed", it) }
        }
    }

    fun isReady(): Boolean = enabled

    /** Append one line to the in-memory ring and (when ready) to `app.log`. */
    fun log(tag: String, message: String) {
        val line = "${stampOf(stamp)} [${Thread.currentThread().name}] $tag: $message"
        synchronized(lock) { pushRing(line) }
        if (enabled) append(line + "\n")
        runCatching { Log.d("Hikari/$tag", message) }
    }

    /** Same as [log], but also records the throwable's class + message. */
    fun logError(tag: String, message: String, t: Throwable? = null) {
        val suffix = if (t == null) "" else " · ${t.javaClass.simpleName}: ${t.message}"
        log("$tag!", message + suffix)
        if (t != null && enabled) append(stackTrace(t))
    }

    /**
     * Write the full crash report for an uncaught exception. Returns the report
     * text so the caller can surface it in the in-app crash banner.
     *
     * crash.log is written SYNCHRONOUSLY (after draining the queue, so its
     * breadcrumbs include everything that happened): the process may be killed
     * the instant this returns, and a queued write could be lost.
     */
    fun recordCrash(threadName: String, t: Throwable): String {
        val report = buildString {
            appendLine("Hikari crash report")
            appendLine("time: ${stampOf(stampShort)}")
            appendLine("thread: $threadName")
            appendLine("version: $versionLine")
            appendLine("device: ${deviceLine()}")
            appendLine("abis: ${runCatching { Build.SUPPORTED_ABIS.joinToString() }.getOrDefault("?")}")
            appendLine()
            append(stackTrace(t))
            appendLine("--- last log lines (breadcrumbs) ---")
            snapshotLocked(RING_MAX).forEach { appendLine(it) }
        }
        val crashLine = "${stampOf(stamp)} [crash] $threadName: ${t.javaClass.name}: ${t.message}"
        synchronized(lock) { pushRing(crashLine) }
        if (enabled) {
            // Drain first: the report's breadcrumbs are already snapshotted on
            // the main thread, but app.log should reach the crash line before
            // the report is offered for sharing.
            flush()
            // Exactly one crash file: the newest crash replaces the last.
            runCatching { dir?.let { File(it, "crash.log").writeText(report) } }
            append("$crashLine\n")
        }
        return report
    }

    /** The three shareable files, newest-app-log first. */
    fun logFiles(context: Context): List<LogFile> {
        val d = dir ?: File(context.filesDir, "logs")
        return listOf(
            LogFile(
                key = "app.log",
                title = "App log (latest)",
                description = "Everything the app did this session and the previous ones.",
                file = File(d, "app.log"),
                isCrash = false,
            ),
            LogFile(
                key = "app.previous.log",
                title = "App log (previous)",
                description = "The session before the latest log rolled over.",
                file = File(d, "app.previous.log"),
                isCrash = false,
            ),
            LogFile(
                key = "crash.log",
                title = "Crash log",
                description = "Full stack trace plus the last log lines before the crash.",
                file = File(d, "crash.log"),
                isCrash = true,
            ),
        )
    }

    /** Files that actually exist — what the share button hands to Android. */
    fun existingFiles(context: Context): List<LogFile> {
        flush()
        return logFiles(context).filter { it.file.exists() && it.file.length() > 0L }
    }

    /** True when a crash has been recorded (the Home banner uses this too). */
    fun hasCrash(context: Context): Boolean {
        flush()
        return runCatching {
            File(dir ?: File(context.filesDir, "logs"), "crash.log").let { it.exists() && it.length() > 0L }
        }.getOrDefault(false)
    }

    /** The last recorded crash report, or null. */
    fun crashText(context: Context): String? {
        flush()
        return runCatching {
            val f = File(dir ?: File(context.filesDir, "logs"), "crash.log")
            if (f.exists() && f.length() > 0L) f.readText() else null
        }.getOrNull()
    }

    /** Delete all three files (and the in-memory breadcrumbs). */
    fun clear(context: Context) {
        // Drain first, then delete ON THE WRITER THREAD — a delete racing a
        // queued append would otherwise resurrect the file it just removed.
        flush()
        synchronized(lock) { ring.clear() }
        if (enabled) queue.offer(Op.Delete(null))
        else logFiles(context).forEach { runCatching { it.file.delete() } }
    }

    /** Delete only the crash report (the Home banner's dismiss button). */
    fun clearCrash(context: Context) {
        if (enabled) queue.offer(Op.Delete("crash.log"))
        else runCatching { File(dir ?: File(context.filesDir, "logs"), "crash.log").delete() }
    }

    /** The in-memory breadcrumb snapshot, newest last. */
    fun snapshot(): String = synchronized(lock) { snapshotLocked(RING_MAX).joinToString("\n") }

    private fun snapshotLocked(limit: Int): List<String> =
        ring.toList().takeLast(limit)

    private fun pushRing(line: String) {
        if (ring.size >= RING_MAX) ring.removeFirst()
        ring.addLast(line)
    }

    /** Hands [text] to the writer thread. Returns immediately — this is the
     *  whole point of the queue (it is called on the main thread). */
    private fun append(text: String) {
        queue.offer(Op.Append(text))
        writer
    }

    private fun rotate(d: File) {
        runCatching {
            val previous = File(d, "app.previous.log")
            if (previous.exists()) previous.delete()
            File(d, "app.log").renameTo(previous)
        }
    }

    private fun fileBytes(d: File, name: String): Long =
        runCatching { File(d, name).let { if (it.exists()) it.length() else 0L } }.getOrDefault(0L)

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun versionString(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Hikari ${info.versionName} (build ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
    }.getOrDefault("Hikari")
}
