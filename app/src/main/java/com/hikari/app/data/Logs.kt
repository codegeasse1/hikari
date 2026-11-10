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

object Logs {

    /** Roll `app.log` over once it passes this size. 2MB: image/poster
     *  failure traces are sampled into the file, so the older 512KB rolled
     *  away the evidence before a bug report was ever shared. */
    private const val FILE_MAX_BYTES = 2L * 1024L * 1024L

    /** How many recent lines the crash report carries as breadcrumbs. 2000:
     *  per-poster/per-image failures go to the ring (see [trace]) without
     *  touching the file, so the ring has to be deep enough to still hold
     *  them when a crash report is written. */
    private const val RING_MAX = 2000

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

    /** The app's private `logs/` folder (heap dumps live next to the logs). */
    fun logDir(context: Context): File {
        val d = dir ?: File(context.filesDir, "logs")
        runCatching { if (!d.isDirectory) d.mkdirs() }
        return d
    }

    /** A fresh heap-dump destination: `hikari-heap-<timestamp>.hprof`. The
     *  logs FileProvider path already covers it, so it shares like a log. */
    fun heapDumpFile(context: Context): File {
        val name = "hikari-heap-" + heapStamp() + ".hprof"
        return File(logDir(context), name)
    }

    /** Heap dumps already on disk, newest first (for the share/save list). */
    fun heapFiles(context: Context): List<File> =
        logDir(context).listFiles { f ->
            f.isFile && f.name.startsWith("hikari-heap-") && f.name.endsWith(".hprof")
        }?.sortedByDescending { it.lastModified() }.orEmpty()

    private val heapStampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private fun heapStamp(): String = synchronized(lock) { heapStampFmt.format(Date()) }

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

    /** Hot-path logging: ring + logcat, but NEVER the file. Per-poster and
     *  per-image failures fire hundreds of times per screen — they would
     *  churn the log file (and its rotation) without bound — so they live
     *  here: still visible in logcat, still in the crash-report breadcrumbs
     *  (the ring is deep on purpose), and the Logs screen's live tail reads
     *  the ring. Callers add a sampled file line (see [traceSampledCount])
     *  so the persisted log keeps a census without the flood. */
    fun trace(tag: String, message: String) {
        val line = "${stampOf(stamp)} [${Thread.currentThread().name}] $tag: $message"
        synchronized(lock) { pushRing(line) }
        runCatching { Log.d("Hikari/$tag", message) }
    }

    /** Per-key occurrence counter for [traceSampledEvery]: how many times
     *  each failure key has fired this process. Bounded — keys are
     *  caller-chosen constants/small hashes, never full payloads. */
    private val traceCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private const val TRACE_COUNTS_MAX = 256

    /** Fires [trace] every call but also writes ONE file line per [every]
     *  occurrences of [key] ("still failing: N so far"). The persisted log
     *  keeps a census of chronic failures; the ring keeps every instance. */
    fun traceSampledEvery(tag: String, key: String, every: Long, message: () -> String) {
        if (traceCounts.size >= TRACE_COUNTS_MAX) traceCounts.clear()
        val n = (traceCounts.getOrPut(key) { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet())
        trace(tag, message())
        if (n % every == 0L) log(tag, "$key still failing: $n so far · ${message()}")
    }

    /** Redacts a URL for logging: masks credential/token query values, drops
     *  cookies, truncates to 300 chars. Host + path shape stay, secrets go. */
    fun safeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        var s = url.trim()
        if (s.startsWith("data:")) {
            val comma = s.indexOf(',')
            val head = if (comma > 0) s.substring(0, comma.coerceAtMost(64)) else s.take(64)
            return "$head…<data len=${s.length}>"
        }
        s = s.replace(Regex("(?i)((?:password|passwd|pwd|token|auth|api[_-]?key|session|sid)=)[^&\\s]*")) { m -> m.groupValues[1] + "… " }
        return if (s.length > 300) s.take(300) + "…<len=${url.length}>" else s
    }

    /** Host + scheme only — for per-host failure lines (no path, no query). */
    fun safeHost(url: String?): String = runCatching {
        val u = url?.trim().orEmpty()
        if (u.startsWith("data:")) return "data:uri len=${u.length}"
        val uri = java.net.URI(u)
        "${uri.scheme}://${uri.host ?: "?"}"
    }.getOrDefault("<bad-url>")

    suspend fun <T> timed(tag: String, label: String, block: suspend () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        log(tag, "$label start")
        try {
            val v = block()
            log(tag, "$label end in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            return v
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                log(tag, "$label CANCELLED after ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            } else {
                logError(tag, "$label FAILED after ${android.os.SystemClock.elapsedRealtime() - t0}ms", t)
            }
            throw t
        }
    }

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

    /** Delete all heap dumps from this device (the Logs-screen clear button).
     *  Heap files are written directly by `dumpHprofData`, never by the log
     *  writer thread, so a plain delete can't race a queued append. */
    fun clearHeap(context: Context) {
        heapFiles(context).forEach { runCatching { it.delete() } }
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
