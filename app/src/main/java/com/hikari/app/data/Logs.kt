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
 */
object Logs {

    /** Roll `app.log` over once it passes this size. */
    private const val FILE_MAX_BYTES = 512L * 1024L

    /** How many recent lines the crash report carries as breadcrumbs. */
    private const val RING_MAX = 300

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_MAX)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampShort = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** SimpleDateFormat is not thread-safe: every format call happens under
     *  [lock] (logging runs on the main thread AND on IO threads). */
    private fun stampOf(fmt: SimpleDateFormat): String = synchronized(lock) { fmt.format(Date()) }

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
                writeLineUnlocked("app.log", "=".repeat(64))
                writeLineUnlocked("app.log", "session start · $versionLine · ${deviceLine()}")
            }.onFailure { Log.w("HikariLogs", "init failed", it) }
        }
    }

    fun isReady(): Boolean = enabled

    /** Append one line to the in-memory ring and (when ready) to `app.log`. */
    fun log(tag: String, message: String) {
        val line = "${stampOf(stamp)} [${Thread.currentThread().name}] $tag: $message"
        synchronized(lock) {
            pushRing(line)
            if (enabled) writeLineUnlocked("app.log", line)
        }
        runCatching { Log.d("Hikari/$tag", message) }
    }

    /** Same as [log], but also records the throwable's class + message. */
    fun logError(tag: String, message: String, t: Throwable? = null) {
        val suffix = if (t == null) "" else " · ${t.javaClass.simpleName}: ${t.message}"
        log("$tag!", message + suffix)
        if (t != null) {
            synchronized(lock) {
                if (enabled) {
                    runCatching { File(dir, "app.log").appendText(stackTrace(t)) }
                }
            }
        }
    }

    /**
     * Write the full crash report for an uncaught exception. Returns the report
     * text so the caller can surface it in the in-app crash banner.
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
        synchronized(lock) {
            pushRing("${stampOf(stamp)} [crash] $threadName: ${t.javaClass.name}: ${t.message}")
            if (enabled) {
                // Exactly one crash file: the newest crash replaces the last.
                runCatching { File(dir, "crash.log").writeText(report) }
                writeLineUnlocked("app.log", "${stampOf(stamp)} [crash] ${t.javaClass.simpleName}: ${t.message}")
            }
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
    fun existingFiles(context: Context): List<LogFile> =
        logFiles(context).filter { it.file.exists() && it.file.length() > 0L }

    /** True when a crash has been recorded (the Home banner uses this too). */
    fun hasCrash(context: Context): Boolean =
        runCatching { File(dir ?: File(context.filesDir, "logs"), "crash.log").let { it.exists() && it.length() > 0L } }
            .getOrDefault(false)

    /** The last recorded crash report, or null. */
    fun crashText(context: Context): String? =
        runCatching {
            val f = File(dir ?: File(context.filesDir, "logs"), "crash.log")
            if (f.exists() && f.length() > 0L) f.readText() else null
        }.getOrNull()

    /** Delete all three files (and the in-memory breadcrumbs). */
    fun clear(context: Context) {
        synchronized(lock) {
            ring.clear()
            logFiles(context).forEach { runCatching { it.file.delete() } }
        }
    }

    /** Delete only the crash report (the Home banner's dismiss button). */
    fun clearCrash(context: Context) {
        synchronized(lock) {
            runCatching { File(dir ?: File(context.filesDir, "logs"), "crash.log").delete() }
        }
    }

    /** The in-memory breadcrumb snapshot, newest last. */
    fun snapshot(): String = synchronized(lock) { snapshotLocked(RING_MAX).joinToString("\n") }

    private fun snapshotLocked(limit: Int): List<String> =
        ring.toList().takeLast(limit)

    private fun pushRing(line: String) {
        if (ring.size >= RING_MAX) ring.removeFirst()
        ring.addLast(line)
    }

    private fun writeLineUnlocked(name: String, line: String) {
        val d = dir ?: return
        runCatching {
            if (name == "app.log" && fileBytes(d, name) > FILE_MAX_BYTES) rotateUnlocked(d)
            File(d, name).appendText(line + "\n")
        }
    }

    private fun rotateUnlocked(d: File) {
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
