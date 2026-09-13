package com.hikari.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hikari.app.MainActivity
import com.hikari.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the download queue running while the user is elsewhere (or the screen is
 * off) — a foreground `dataSync` service, so Android won't freeze the process
 * mid-file. The service pumps [DownloadsRepository] until nothing is QUEUED,
 * shows progress in a quiet notification, then stops itself. A task paused or
 * interrupted by a process death is picked up again on the next resume.
 */
class DownloadService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private var pumpJob: Job? = null
    private var notifJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (pumpJob?.isActive != true) {
            pumpJob = scope.launch {
                DownloadsRepository.ensureLoaded(this@DownloadService)
                while (true) {
                    DownloadsRepository.pump(this@DownloadService)
                    if (DownloadsRepository.snapshot().none { it.status == DownloadStatus.QUEUED }) {
                        // Grace window: an enqueue racing this final check would
                        // otherwise leave a task stranded in QUEUED forever.
                        delay(1200)
                        if (DownloadsRepository.snapshot().none { it.status == DownloadStatus.QUEUED }) break
                    }
                }
                withContext(Dispatchers.Main) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
        if (notifJob?.isActive != true) {
            notifJob = scope.launch {
                DownloadsRepository.tasks.collectLatest { list ->
                    runCatching { NotificationManagerCompat.from(this@DownloadService).notify(NOTIF_ID, buildNotification(list)) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        supervisor.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notif = buildNotification(DownloadsRepository.snapshot())
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val active = tasks.firstOrNull { it.status == DownloadStatus.RUNNING }
            ?: tasks.firstOrNull { it.status == DownloadStatus.QUEUED }
        val pending = tasks.count {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
        }
        val title = if (active != null) "Downloading" else "Hikari downloads"
        val text = if (active != null) {
            buildString {
                append(active.title)
                if (active.episodeLabel.isNotBlank()) append(" · ").append(active.episodeLabel)
                if (pending > 1) append("  (+").append(pending - 1).append(" queued)")
            }
        } else {
            "Finishing up…"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (active != null) {
            val indeterminate = active.durationMs <= 0L && active.bytesTotal <= 0L
            builder.setProgress(100, (active.progress * 100).toInt().coerceIn(0, 100), indeterminate)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Video downloads in progress"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hikari_downloads"
        private const val NOTIF_ID = 0x484b01

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            }
        }
    }
}
