package com.hikari.app.iptv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Remind me" on a show in the guide. Each reminder gets an alarm a little
 * before the show (settings → lead minutes); the alarm posts a notification
 * whose tap opens the app. Past reminders are pruned on every pass.
 */
object LiveTvReminders {

    private lateinit var app: HikariApp
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        app = context.applicationContext as HikariApp
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prune()
            armAll()
        }
    }

    fun idOf(r: LiveReminder): String = "livetv-rem|" + r.id

    fun add(channel: LiveChannel, program: EpgProgram) {
        val r = LiveReminder(
            id = channel.key + "|" + program.startMs,
            channelKey = channel.key,
            channelName = channel.name,
            title = program.title,
            startMs = program.startMs,
        )
        LiveTvEngine.store.updateUserState { st ->
            if (st.reminders.any { it.id == r.id }) st
            else st.copy(reminders = st.reminders + r)
        }
        arm(r)
    }

    fun remove(id: String) {
        LiveTvEngine.store.updateUserState { st -> st.copy(reminders = st.reminders.filterNot { it.id == id }) }
        cancel(id)
    }

    fun all(): List<LiveReminder> = LiveTvEngine.store.userState().reminders.sortedBy { it.startMs }

    private fun prune() {
        val now = System.currentTimeMillis()
        LiveTvEngine.store.updateUserState { st ->
            st.copy(reminders = st.reminders.filter { it.startMs + 10 * 60_000L > now })
        }
    }

    private fun armAll() {
        all().forEach { arm(it) }
    }

    private fun arm(r: LiveReminder) {
        if (!started) return
        val leadMs = LiveTvEngine.store.settings().reminderLeadMinutes * 60_000L
        val at = maxOf(r.startMs - leadMs, System.currentTimeMillis() + 1_000L)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(r.id, at, r)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun cancel(id: String) {
        if (!started) return
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            am.cancel(
                PendingIntent.getBroadcast(
                    app, id.hashCode(), Intent(app, ReminderReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
    }

    private fun pending(id: String, at: Long, r: LiveReminder): PendingIntent {
        val intent = Intent(app, ReminderReceiver::class.java).apply {
            putExtra("rid", id)
            putExtra("title", r.title)
            putExtra("channel", r.channelName)
            putExtra("ckey", r.channelKey)
        }
        return PendingIntent.getBroadcast(
            app, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra("title").orEmpty()
            val channel = intent.getStringExtra("channel").orEmpty()
            val rid = intent.getStringExtra("rid").orEmpty()
            val open = runCatching {
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    PendingIntent.getActivity(
                        context, rid.hashCode(), launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            }.getOrNull()
            val note = NotificationCompat.Builder(context, LiveTvEngine.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText("Starting on $channel")
                .apply { if (open != null) setContentIntent(open) }
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(rid.hashCode(), note)
            }.onFailure { Logs.log("LiveTv", "reminder notify failed: ${it.message}") }
        }
    }
}
