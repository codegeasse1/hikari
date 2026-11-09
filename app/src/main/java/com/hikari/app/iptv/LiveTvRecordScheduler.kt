package com.hikari.app.iptv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Recording alarms: each schedule gets one alarm at its (pre-padded) start.
 * At fire time the engine is asked for the current channel + catch-up state and
 * the record service starts the right job (live now, or catch-up download).
 * Series rules are re-applied after every guide refresh by the caller.
 */
object LiveTvRecordScheduler {

    fun rescan() {
        runCatching {
            val app = HikariApp.instance
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            LiveTvRecordings.schedules().forEach { s ->
                if (s.startMs <= now + 1_000L) {
                    // Already due: fire now through the service.
                    LiveTvRecordService.kick(app, s.id)
                } else {
                    val pi = pending(app, s.id)
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.startMs, pi)
                        } else {
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.startMs, pi)
                        }
                    }.onFailure {
                        runCatching { am.set(AlarmManager.RTC_WAKEUP, s.startMs, pi) }
                    }
                }
            }
        }.onFailure { Logs.log("LiveTv", "record rescan failed: ${it.message}") }
    }

    fun cancel(scheduleId: String) {
        runCatching {
            val app = HikariApp.instance
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(app, scheduleId))
        }
    }

    private fun pending(app: HikariApp, scheduleId: String): PendingIntent {
        val intent = Intent(app, RecordAlarmReceiver::class.java).apply {
            putExtra("sid", scheduleId)
        }
        return PendingIntent.getBroadcast(
            app, scheduleId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    class RecordAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sid = intent.getStringExtra("sid").orEmpty()
            if (sid.isBlank()) return
            LiveTvRecordService.kick(context, sid)
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    LiveTvEngine.init(context)
                    LiveTvEngine.ensureFresh()
                    rescan()
                    LiveTvRecordings.applySeriesRules()
                }.onFailure { Logs.log("LiveTv", "boot reschedule failed: ${it.message}") }
            }
        }
    }
}
