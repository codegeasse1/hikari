package com.hikari.app.download

import android.content.Context
import androidx.media3.common.MimeTypes
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import com.hikari.app.net.StreamProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object DownloadsRepository {

    private const val DEFAULT_CONCURRENCY = 3
    private const val MIN_CONCURRENCY = 1
    private const val MAX_CONCURRENCY = 10

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()

    /** How many downloads run at once — the user setting, clamped to 1–10. */
    @Volatile
    private var maxConcurrent: Int = DEFAULT_CONCURRENCY

    fun setMaxConcurrent(n: Int) {
        maxConcurrent = n.coerceIn(1, 10)
    }

    @Volatile
    private var loaded = false

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    suspend fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val stored = runCatching { DownloadStore.load(ctx) }.getOrDefault(emptyList())
            _tasks.value = stored.map {
                // A task that was mid-download when the process died is not
                // running any more — surface it as paused so the user can resume.
                if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.CONVERTING) {
                    it.copy(status = DownloadStatus.PAUSED, bytesPerSec = 0L)
                } else {
                    it
                }
            }
            val app = ctx.applicationContext as? HikariApp
            if (app != null) {
                maxConcurrent = runCatching { app.store.downloadConcurrency() }
                    .getOrDefault(DEFAULT_CONCURRENCY)
                    .coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
            }
            loaded = true
        }
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    /** The flag the engine polls; set true to stop the in-flight task. */
    fun cancelFlag(id: String): AtomicBoolean =
        cancelFlags.getOrPut(id) { AtomicBoolean(false) }

    private var lastFlush = 0L

    private suspend fun flush(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < 2000L) return
        lastFlush = now
        runCatching { DownloadStore.save(ctx, _tasks.value) }
    }

    private suspend fun update(
        ctx: Context,
        id: String,
        force: Boolean,
        transform: (DownloadTask) -> DownloadTask,
    ) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        flush(ctx, force)
    }

    fun enqueue(ctx: Context, task: DownloadTask) {
        cancelFlag(task.id).set(false)
        lastFlush = 0L
        scope.launch {
            ensureLoaded(ctx)
            _tasks.update { list -> list.filterNot { it.id == task.id } + task }
            flush(ctx, true)
            Logs.log(
                "Download",
                "enqueue ${task.id} kind=${task.kind} src=${task.sourceName.take(40)} " +
                    "host=${runCatching { java.net.URI(task.url).host }.getOrDefault("?")}",
            )
            DownloadService.start(ctx)
        }
    }

    fun pause(ctx: Context, id: String) {
        // Flag immediately so a running engine stops now; the status write
        // follows.
        cancelFlag(id).set(true)
        scope.launch {
            ensureLoaded(ctx)
            update(ctx, id, force = true) { it.copy(status = DownloadStatus.PAUSED) }
        }
    }

    fun resume(ctx: Context, id: String) {
        cancelFlag(id).set(false)
        scope.launch {
            ensureLoaded(ctx)
            update(ctx, id, force = true) {
                it.copy(
                    status = DownloadStatus.QUEUED,
                    error = null,
                    resumePartial = true,
                    bytesPerSec = 0L,
                )
            }
            DownloadService.start(ctx)
        }
    }

    /** Removes the task and its on-disk copy. An EXPORTED file already in the
     *  phone's Downloads folder is left alone — it belongs to the user now. */
    fun remove(ctx: Context, id: String) {
        cancelFlag(id).set(true)
        cancelFlags.remove(id)
        scope.launch {
            ensureLoaded(ctx)
            val t = _tasks.value.firstOrNull { it.id == id }
            val path = t?.localPath
            if (path != null && !path.startsWith("content:")) {
                runCatching {
                    val f = File(path)
                    if (f.name == "index.m3u8") f.parentFile?.deleteRecursively()
                    else f.delete()
                }
            }
            runCatching { workDirFor(ctx, id).deleteRecursively() }
            _tasks.value = _tasks.value.filterNot { it.id == id }
            flush(ctx, true)
        }
    }

    /** Atomically takes the next QUEUED task, marking it RUNNING so no other
     *  pump can claim it too — but only while fewer than [maxConcurrent] tasks
     *  are already running. Null when the queue is drained or at capacity. */
    private fun claimNextQueued(): DownloadTask? {
        var claimed: DownloadTask? = null
        _tasks.update { list ->
            val limit = maxConcurrent.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
            // A task that is converting is still occupying its slot, so it
            // counts against the concurrency limit.
            val running = list.count {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.CONVERTING
            }
            val idx = if (running < limit) list.indexOfFirst { it.status == DownloadStatus.QUEUED } else -1
            if (idx < 0) {
                claimed = null
                list
            } else {
                val taken = list[idx].copy(status = DownloadStatus.RUNNING, error = null)
                claimed = taken
                list.toMutableList().also { it[idx] = taken }
            }
        }
        return claimed
    }

    fun workDirFor(ctx: Context, id: String): File =
        File(File(ctx.filesDir, "downloads"), id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    private fun relocateOffline(ctx: Context, task: DownloadTask, path: String?): String? {
        if (path.isNullOrBlank()) return path
        return runCatching {
            val root = File(File(ctx.filesDir, "downloads"), sanitizeFile(task.title).ifBlank { "hikari-video" })
            val src = File(path)
            if (!src.exists()) return path
            if (src.name == "index.m3u8") {
                // HLS bundle: the whole work dir becomes <Title>/<ep>/.
                val parent = src.parentFile ?: return path
                val epDir = File(root, task.episodeStem())
                if (parent.canonicalPath != epDir.canonicalPath) {
                    if (epDir.exists()) runCatching { epDir.deleteRecursively() }
                    root.mkdirs()
                    if (!parent.renameTo(epDir)) {
                        epDir.mkdirs()
                        parent.copyRecursively(epDir, overwrite = true)
                        parent.deleteRecursively()
                    }
                }
                File(epDir, "index.m3u8").takeIf { it.exists() }?.absolutePath ?: path
            } else {
                // Single file: <Title>/<ep>.ext, then drop the empty work dir.
                root.mkdirs()
                val ext = src.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                val dest = File(root, task.episodeStem() + ext)
                if (src.canonicalPath != dest.canonicalPath) {
                    if (dest.exists()) dest.delete()
                    if (!src.renameTo(dest)) {
                        src.copyTo(dest, overwrite = true)
                        src.delete()
                    }
                    runCatching { workDirFor(ctx, task.id).deleteRecursively() }
                }
                dest.takeIf { it.exists() }?.absolutePath ?: path
            }
        }.getOrDefault(path)
    }

    /** Runs the queue until it is drained, keeping up to [maxConcurrent] tasks
     *  in flight. Re-reads the limit each pass, so changing it in Settings takes
     *  effect on the next queued task without restarting the service. */
    suspend fun pump(ctx: Context) {
        ensureLoaded(ctx)
        coroutineScope {
            val jobs = ArrayList<Job>()
            while (true) {
                jobs.removeAll { it.isCompleted }
                val task = claimNextQueued()
                if (task != null) {
                    jobs += launch { runTask(ctx, task) }
                    continue
                }
                if (jobs.isEmpty()) break
                // Queue drained or concurrency limit reached — wait for a slot.
                delay(200)
            }
            jobs.forEach { it.join() }
        }
    }

    private suspend fun runTask(ctx: Context, task: DownloadTask) {
        flush(ctx, true)
        val flag = cancelFlag(task.id)
        val workDir = workDirFor(ctx, task.id)
        Logs.log("Download", "start ${task.id} kind=${task.kind} urlLen=${task.url.length}")
        // Resolve-then-download: server rows often carry wrapper/landing URLs
        // (text/html), not media — playback resolves these via StreamProbe,
        // and the downloader now does the same BEFORE fetching a byte, so a
        // wrapper page can never become a fake instant DONE ("Available
        // offline" with nothing on disk). Falls back to the raw URL when
        // nothing resolves; the engine's content guard still rejects pages
        // loudly instead of saving them.
        val effTask = try {
            resolveForDownload(task, flag)
        } catch (t: Throwable) {
            if (t is DownloadCancelledException) throw t
            Logs.log("Download", "not starting ${task.id} — ${t.message}")
            update(ctx, task.id, force = true) {
                it.copy(status = DownloadStatus.FAILED, error = t.message, bytesPerSec = 0L)
            }
            return
        }
        var lastBytes = 0L
        var lastSample = System.currentTimeMillis()
        var speedEma = 0.0
        try {
            val result = DownloadEngine.run(
                ctx = ctx,
                task = effTask,
                workDir = workDir,
                onProgress = { p ->
                    // Smoothed (EMA) transfer rate from the byte deltas between
                    // progress callbacks, so the row can show a live speed.
                    val now = System.currentTimeMillis()
                    val dt = now - lastSample
                    if (dt >= 200L) {
                        val delta = p.doneBytes - lastBytes
                        if (delta > 0L) {
                            val instant = delta.toDouble() * 1000.0 / dt.toDouble()
                            speedEma = if (speedEma <= 0.0) instant else speedEma * 0.6 + instant * 0.4
                        }
                        lastBytes = p.doneBytes
                        lastSample = now
                    }
                    update(ctx, task.id, force = false) {
                        it.copy(
                            bytesDone = p.doneBytes,
                            bytesTotal = if (p.totalBytes > 0) p.totalBytes else it.bytesTotal,
                            durationMs = if (p.durationMs > 0) p.durationMs else it.durationMs,
                            doneDurationMs = if (p.doneDurationMs > 0) p.doneDurationMs else it.doneDurationMs,
                            bytesPerSec = speedEma.toLong(),
                        )
                    }
                },
                onConverting = {
                    Logs.log("Download", "converting ${task.id}")
                    update(ctx, task.id, force = true) {
                        it.copy(status = DownloadStatus.CONVERTING, bytesPerSec = 0L)
                    }
                },
                isCancelled = { flag.get() },
            )
            Logs.log(
                "Download",
                "done ${task.id} kind=${task.kind} " +
                    "local=${result.localPath ?: "-"} saved=${result.savedUri ?: "-"}",
            )
            update(ctx, task.id, force = true) {
                it.copy(
                    status = DownloadStatus.DONE,
                    // OFFLINE copies live in their title folder (see
                    // relocateOffline); exports keep the engine's own path.
                    localPath = if (task.kind == DownloadKind.OFFLINE) relocateOffline(ctx, task, result.localPath)
                    else result.localPath,
                    savedUri = result.savedUri,
                    error = null,
                    resumePartial = false,
                    bytesPerSec = 0L,
                    bytesDone = if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone,
                    doneDurationMs = if (it.durationMs > 0) it.durationMs else it.doneDurationMs,
                )
            }
        } catch (c: DownloadCancelledException) {
            Logs.log("Download", "cancelled ${task.id}")
            update(ctx, task.id, force = true) {
                it.copy(status = DownloadStatus.PAUSED, error = null, bytesPerSec = 0L)
            }
        } catch (t: Throwable) {
            if (flag.get()) {
                Logs.log("Download", "cancelled ${task.id}")
                update(ctx, task.id, force = true) {
                    it.copy(status = DownloadStatus.PAUSED, error = null, bytesPerSec = 0L)
                }
            } else {
                Logs.log("Download", "failed ${task.id} — ${t.message ?: t.javaClass.simpleName}")
                update(ctx, task.id, force = true) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                        bytesPerSec = 0L,
                    )
                }
            }
        }
    }

    /** Resolve-then-download: the [task]'s URL rewritten to its playable form
     *  (or the task unchanged when there is nothing to resolve or nothing
     *  resolved — the engine's content guard still rejects pages loudly
     *  instead of saving them). Throws only for DASH manifests (which the
     *  engine cannot fetch) and cancellation; everything else falls back to
     *  the raw URL. */
    private suspend fun resolveForDownload(task: DownloadTask, flag: AtomicBoolean): DownloadTask {
        val url = task.url
        if (url.isBlank()) return task
        // Already a media URL (extension, HLS/DASH flag, loopback proxy):
        // probing it would only burn a single-use token or waste time.
        if (!StreamProbe.needsResolve(url, false, task.isM3u8, false)) return task
        if (flag.get()) throw DownloadCancelledException()
        val ua = task.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
        val cached = StreamProbe.cached(url)
        if (cached != null) {
            Logs.log("Download", "resolve mem-hit ${task.id}")
            return applyResolved(task, cached)
        }
        val resolved = runCatching {
            withTimeoutOrNull(30_000L) { StreamProbe.resolve(url, task.headers + ("User-Agent" to ua)) }
        }.getOrNull()
        if (resolved == null || resolved.url.isBlank()) {
            Logs.log("Download", "resolve miss ${task.id} — trying raw URL")
            return task
        }
        Logs.log("Download", "resolve hit ${task.id} mime=${resolved.mime ?: "-"}")
        return applyResolved(task, resolved)
    }

    private fun applyResolved(task: DownloadTask, resolved: StreamProbe.Resolved): DownloadTask {
        val m = resolved.mime
        val low = resolved.url.substringBefore('?').lowercase()
        // The engine has no DASH path: a manifest fetched as a file would be
        // another fake DONE, so refuse it here with a clear error instead.
        if (m == MimeTypes.APPLICATION_MPD || (m == null && low.contains(".mpd"))) {
            throw IOException("Server gave a DASH stream — DASH downloads are not supported yet")
        }
        val m3u8 = m == MimeTypes.APPLICATION_M3U8 || (m == null && low.contains(".m3u8"))
        if (resolved.url == task.url && m3u8 == task.isM3u8) return task
        return task.copy(url = resolved.url, isM3u8 = m3u8)
    }
}
