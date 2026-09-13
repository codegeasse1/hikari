package com.hikari.app.download

import android.content.Context
import com.hikari.app.HikariApp
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single source of truth for the download queue. In-memory state is a
 * [StateFlow] the Downloads screen collects; it is mirrored into DataStore
 * ([DownloadStore]) on every status change and throttled during progress.
 *
 * Concurrency model: [pump] claims the next QUEUED task ATOMICALLY (under
 * [claimLock]) and flips it to RUNNING, so two pumps — the service's, plus one
 * started by a fresh enqueue — can never run the same task twice.
 */
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
                if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.PAUSED) else it
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
            // Load the persisted queue FIRST — otherwise a download started
            // before the Downloads tab was ever opened would replace the stored
            // task list with just this one task.
            ensureLoaded(ctx)
            _tasks.update { list -> list.filterNot { it.id == task.id } + task }
            flush(ctx, true)
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
                it.copy(status = DownloadStatus.QUEUED, error = null, resumePartial = true)
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
                runCatching { File(path).parentFile?.deleteRecursively() }
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
            val running = list.count { it.status == DownloadStatus.RUNNING }
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
        try {
            val result = DownloadEngine.run(
                ctx = ctx,
                task = task,
                workDir = workDir,
                onProgress = { p ->
                    update(ctx, task.id, force = false) {
                        it.copy(
                            bytesDone = p.doneBytes,
                            bytesTotal = if (p.totalBytes > 0) p.totalBytes else it.bytesTotal,
                            durationMs = if (p.durationMs > 0) p.durationMs else it.durationMs,
                            doneDurationMs = if (p.doneDurationMs > 0) p.doneDurationMs else it.doneDurationMs,
                        )
                    }
                },
                isCancelled = { flag.get() },
            )
            update(ctx, task.id, force = true) {
                it.copy(
                    status = DownloadStatus.DONE,
                    localPath = result.localPath,
                    savedUri = result.savedUri,
                    error = null,
                    resumePartial = false,
                    bytesDone = if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone,
                    doneDurationMs = if (it.durationMs > 0) it.durationMs else it.doneDurationMs,
                )
            }
        } catch (c: DownloadCancelledException) {
            update(ctx, task.id, force = true) { it.copy(status = DownloadStatus.PAUSED, error = null) }
        } catch (t: Throwable) {
            if (flag.get()) {
                update(ctx, task.id, force = true) { it.copy(status = DownloadStatus.PAUSED, error = null) }
            } else {
                update(ctx, task.id, force = true) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }
}
