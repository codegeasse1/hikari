package com.hikari.app.iptv

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hikari.app.data.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records Live TV to app-private files: a live stream copied from its start
 * time to its stop time, or — when the channel has catch-up — the whole replay
 * downloaded after the show ends (the phone doesn't have to be on at air time).
 */
class LiveTvRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    companion object {
        private const val ACTION_KICK = "com.hikari.app.iptv.RECORD"

        fun kick(context: Context, scheduleId: String) {
            val intent = Intent(context, LiveTvRecordService::class.java).apply {
                action = ACTION_KICK
                putExtra("sid", scheduleId)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { runCatching { context.startService(intent) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(41, note("Live TV recording", "Preparing…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sid = intent?.getStringExtra("sid").orEmpty()
        if (intent?.action == ACTION_KICK && sid.isNotBlank()) {
            job?.cancel()
            job = scope.launch { runSchedule(sid) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSchedule(sid: String) {
        runCatching { LiveTvEngine.init(this) }
        val sched = LiveTvRecordings.schedules().firstOrNull { it.id == sid }
        if (sched == null) {
            stopSelf()
            return
        }
        val channel = LiveTvEngine.channelOf(sched.channelKey)
        if (channel == null) {
            finishSchedule(sched, "That channel is no longer in this playlist.")
            stopSelf()
            return
        }
        updateNote("Recording ${sched.title}", channel.name)
        val dir = LiveTvEngine.store.recordingDir(sid)
        val file = File(dir, "recording.ts")
        val rec = LiveTvRecording(
            id = sid,
            channelKey = channel.key,
            channelName = channel.name,
            channelLogo = channel.logo,
            title = sched.title,
            programStartMs = 0L,
            programStopMs = 0L,
            startedAtMs = System.currentTimeMillis(),
            stopAtMs = sched.stopMs,
            dir = dir.absolutePath,
            seriesId = sched.seriesId,
        )
        writeInfo(dir, rec)
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hikari:livetv-record")
        wake.acquire(4 * 60 * 60 * 1000L)
        try {
            // Catch-up path: wait for the show to end, then download the replay.
            val now = System.currentTimeMillis()
            val guideStop = sched.stopMs - LiveTvEngine.store.settings().recordPostMinutes * 60_000L
            if (channel.catchup != null && guideStop > now) {
                val wait = guideStop - now
                Logs.log("LiveTv", "recording ${sched.title}: waiting ${wait / 1000}s for catch-up")
                var left = wait
                while (left > 0 && scope.isActive) {
                    delay(minOf(left, 30_000L))
                    left = guideStop - System.currentTimeMillis()
                }
                val prog = LiveTvEngine.guideWindow(channel, sched.startMs, guideStop).firstOrNull()
                val url = prog?.let { runCatching { LiveTvEngine.catchupUrl(channel, it, System.currentTimeMillis()) }.getOrNull() }
                if (url != null) {
                    val ok = downloadUrl(client, url, channel.headers, file, Long.MAX_VALUE)
                    finishDownload(dir, rec, sched, ok, file, fromCatchup = true)
                    stopSelf()
                    return
                }
                Logs.log("LiveTv", "recording ${sched.title}: no replay yet, falling back to live")
            }
            // Live path: copy the stream until the stop time.
            val stopAt = sched.stopMs
            val ok = downloadUrl(client, LiveTvEngine.playUrl(channel), channel.headers, file, stopAt)
            finishDownload(dir, rec, sched, ok, file, fromCatchup = false)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            finishDownload(dir, rec, sched, false, file, fromCatchup = false, problem = t.message)
        } finally {
            runCatching { wake.release() }
            stopSelf()
        }
    }

    /** Copies [url] to [file] until [stopAtMs] (or forever for a replay). */
    private fun downloadUrl(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        file: File,
        stopAtMs: Long,
    ): Boolean {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
            if (headers.keys.none { it.equals("User-Agent", true) }) {
                header("User-Agent", XtreamApi.DEFAULT_UA)
            }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) {
                Logs.log("LiveTv", "record: HTTP ${resp.code}")
                return false
            }
            val body = resp.body ?: return false
            body.byteStream().use { ins ->
                file.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (System.currentTimeMillis() >= stopAtMs) break
                        if (!scope.isActive) break
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (file.length() > 8L * 1024 * 1024 * 1024) break
                    }
                }
            }
            return true
        }
    }

    private fun finishDownload(
        dir: File,
        rec: LiveTvRecording,
        sched: RecordingSchedule,
        ok: Boolean,
        file: File,
        fromCatchup: Boolean,
        problem: String? = null,
    ) {
        val size = file.length()
        val done = rec.copy(
            endedAtMs = System.currentTimeMillis(),
            status = if (ok && size > 0) RecordingStatus.DONE else RecordingStatus.FAILED,
            sizeBytes = size,
            problem = problem ?: if (!ok || size == 0L) "the stream could not be read" else null,
            fromCatchup = fromCatchup,
        )
        writeInfo(dir, done)
        if (!done.isRecording) {
            LiveTvRecordings.saveSchedules(LiveTvRecordings.schedules().filterNot { it.id == sched.id })
        }
        enforceSpaceRule()
        updateNote(
            if (done.status == RecordingStatus.DONE) "Recorded ${sched.title}" else "Recording failed",
            done.problem ?: channelNameOf(sched),
        )
        Logs.log("LiveTv", "recorded ${sched.title}: ${done.status} ${size / 1024}KB")
    }

    private fun finishSchedule(sched: RecordingSchedule, problem: String) {
        LiveTvRecordings.saveSchedules(LiveTvRecordings.schedules().filterNot { it.id == sched.id })
        Logs.log("LiveTv", "recording dropped (${sched.title}): $problem")
    }

    private fun writeInfo(dir: File, rec: LiveTvRecording) {
        runCatching {
            File(dir, "info.json").writeText(
                rec.toJson().put("dir", "").toString()
            )
        }
    }

    private fun channelNameOf(sched: RecordingSchedule): String = sched.channelName

    /** The storage rule: stop future recordings, or delete the oldest first. */
    private fun enforceSpaceRule() {
        if (LiveTvEngine.store.settings().recordSpaceRule != "DELETE_OLDEST") return
        val free = LiveTvEngine.store.recordingsRoot.usableSpace
        if (free > 1L * 1024 * 1024 * 1024) return
        val done = LiveTvRecordings.recordings().filter { !it.isRecording }.sortedBy { it.startedAtMs }
        for (r in done) {
            runCatching { File(r.dir).deleteRecursively() }
            if (LiveTvEngine.store.recordingsRoot.usableSpace > 2L * 1024 * 1024 * 1024) break
        }
    }

    private fun note(title: String, text: String): Notification =
        NotificationCompat.Builder(this, LiveTvEngine.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNote(title: String, text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(41, note(title, text))
        }
    }
}
