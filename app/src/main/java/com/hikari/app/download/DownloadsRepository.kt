package com.hikari.app.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val claimLock = Any()

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
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
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
            _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
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
     *  pump can claim it too. Null when the queue is drained. */
    private fun claimNextQueued(): DownloadTask? = synchronized(claimLock) {
        val list = _tasks.value
        val idx = list.indexOfFirst { it.status == DownloadStatus.QUEUED }
        if (idx < 0) return null
        val claimed = list[idx].copy(status = DownloadStatus.RUNNING, error = null)
        _tasks.value = list.toMutableList().also { it[idx] = claimed }
        claimed
    }

    fun workDirFor(ctx: Context, id: String): File =
        File(File(ctx.filesDir, "downloads"), id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** Downloads every QUEUED task, in order, until the queue is drained. */
    suspend fun pump(ctx: Context) {
        ensureLoaded(ctx)
        while (true) {
            val task = claimNextQueued() ?: break
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
}
