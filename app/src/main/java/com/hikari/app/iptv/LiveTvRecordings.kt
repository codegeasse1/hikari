package com.hikari.app.iptv

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recordings storage: schedules + series rules in SharedPreferences, finished
 * and in-progress recordings as folders under filesDir/livetv/recordings (each
 * with its own info.json, so a folder is complete on its own).
 */
object LiveTvRecordings {

    private lateinit var app: HikariApp
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        app = context.applicationContext as HikariApp
        started = true
        // A crash or a kill mid-recording leaves a RECORDING row whose service
        // is gone — mark it failed on the next start so the list is honest.
        runCatching {
            val store = LiveTvEngine.store
            store.recordingsRoot.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val info = File(dir, "info.json")
                if (!info.isFile) return@forEach
                runCatching {
                    val o = JSONObject(info.readText())
                    if (o.optString("status") == RecordingStatus.RECORDING.name) {
                        o.put("status", RecordingStatus.FAILED.name)
                        o.put("problem", "stopped when the app closed")
                        o.put("endedAtMs", System.currentTimeMillis())
                        info.writeText(o.toString())
                    }
                }
            }
        }
    }

    private fun prefs() = app.getSharedPreferences("hikari_livetv_rec", Context.MODE_PRIVATE)

    fun schedules(): List<RecordingSchedule> =
        prefs().getString("schedules", null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                List(a.length()) { i -> a.optJSONObject(i)?.let { RecordingSchedule.fromJson(it) } }.filterNotNull()
            }.getOrDefault(emptyList())
        }.orEmpty()

    fun seriesRules(): List<SeriesRule> =
        prefs().getString("series", null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                List(a.length()) { i -> a.optJSONObject(i)?.let { SeriesRule.fromJson(it) } }.filterNotNull()
            }.getOrDefault(emptyList())
        }.orEmpty()

    fun saveSchedules(list: List<RecordingSchedule>) {
        prefs().edit().putString("schedules", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
        LiveTvRecordScheduler.rescan()
    }

    fun saveSeriesRules(list: List<SeriesRule>) {
        prefs().edit().putString("series", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    fun recordings(): List<LiveTvRecording> = LiveTvEngine.store.recordings()

    /** Schedules one recording (or reschedules the same id). */
    fun schedule(
        channel: LiveChannel,
        title: String,
        startMs: Long,
        stopMs: Long,
        seriesId: String? = null,
    ): RecordingSchedule {
        val s = storeSettings()
        val pre = s.recordPreMinutes * 60_000L
        val post = s.recordPostMinutes * 60_000L
        val rec = RecordingSchedule(
            id = "rec|" + channel.key + "|" + startMs,
            channelKey = channel.key,
            channelName = channel.name,
            title = title,
            startMs = startMs - pre,
            stopMs = stopMs + post,
            seriesId = seriesId,
        )
        saveSchedules(schedules().filterNot { it.id == rec.id } + rec)
        Logs.log("LiveTv", "scheduled recording: $title on ${channel.name}")
        return rec
    }

    fun cancelSchedule(id: String) {
        saveSchedules(schedules().filterNot { it.id == id })
        LiveTvRecordScheduler.cancel(id)
    }

    /** Series rules add every new airing they find in the guide. Call after each guide refresh. */
    fun applySeriesRules() {
        val rules = seriesRules()
        if (rules.isEmpty()) return
        val s = storeSettings()
        val keep = s.recordSeriesKeep
        val now = System.currentTimeMillis()
        rules.forEach { rule ->
            val ch = LiveTvEngine.channelOf(rule.channelKey) ?: return@forEach
            val guide = runCatching {
                LiveTvEngine.guideWindow(ch, now - 3_600_000L, now + 7 * 86_400_000L)
            }.getOrDefault(emptyList())
            guide.filter { p ->
                p.stopMs > now && p.title.contains(rule.title, ignoreCase = true)
            }.take(8).forEach { p ->
                val exists = schedules().any { it.channelKey == ch.key && it.startMs <= p.startMs && it.stopMs >= p.stopMs } ||
                    recordings().any { it.channelKey == ch.key && it.programStartMs == p.startMs }
                if (!exists) {
                    if (keep > 0) {
                        val count = recordings().count { it.seriesId == rule.id } +
                            schedules().count { it.seriesId == rule.id }
                        if (count >= keep) return@forEach
                    }
                    schedule(ch, p.title, p.startMs, p.stopMs, rule.id)
                }
            }
        }
    }

    private fun storeSettings() = LiveTvEngine.store.settings()
}
