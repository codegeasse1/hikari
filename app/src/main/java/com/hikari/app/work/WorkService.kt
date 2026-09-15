package com.hikari.app.work

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hikari.app.MainActivity
import com.hikari.app.R

/**
 * Keeps the process alive (and awake) while [BackgroundWork] has registered
 * tasks — a foreground `dataSync` service.
 *
 * Without it, pressing Home puts the app into the cached state, where the
 * platform freezes the process: catalog loads, searches and provider scans all
 * stop dead and only resume when the app is reopened. A foreground service (see
 * [BackgroundWork] for the full explanation) is what makes them continue while
 * the user is in another app.
 *
 * The service owns one quiet, ongoing notification saying what is running, and
 * a partial wakelock so work also survives the screen turning off. It stops
 * itself when the last task ends.
 */
class WorkService : Service() {

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !BackgroundWork.isActive()) {
            // Nothing registered: either a delayed stop raced a new start, or
            // the process was restarted by the system with an empty registry.
            stopSelfWork()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notif = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    private fun stopSelfWork() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        releaseWakeLock()
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val label = BackgroundWork.label() ?: "Finishing up…"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_work)
            .setContentTitle(label)
            .setContentText("Hikari keeps running while you use other apps")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    /**
     * Takes (or refreshes) the partial wakelock. Called on every command and
     * again each minute by [BackgroundWork]'s sweep while work is running; the
     * timeout means a token that is somehow never ended cannot hold the CPU
     * forever.
     */
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            // Re-acquire from a known-released state so the timeout window is
            // reset rather than accumulated.
            if (lock.isHeld) lock.release()
            lock.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Background work",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Searches, catalog loads and provider scans that keep running in the background"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hikari_work"
        private const val NOTIF_ID = 0x484b02
        private const val ACTION_STOP = "com.hikari.app.work.STOP"
        private const val WAKELOCK_TAG = "hikari:background-work"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000

        @Volatile
        private var instance: WorkService? = null

        /** Ensures the foreground service is up. Safe to call repeatedly. */
        fun start() {
            val ctx = BackgroundWork.context() ?: return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java))
            }
        }

        /** Repaints the notification (the label changed). */
        fun refresh() {
            instance?.updateNotification()
        }

        /** Re-takes the wakelock so a long-running task does not lose the CPU. */
        fun renewWakeLock() {
            instance?.acquireWakeLock()
        }

        /** Convenience overload used when a token is dropped directly. */
        fun stop() {
            instance?.stopSelfWork()
        }
    }
}
