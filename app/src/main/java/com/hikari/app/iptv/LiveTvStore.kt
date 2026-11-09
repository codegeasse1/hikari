package com.hikari.app.iptv

import android.content.Context
import com.hikari.app.data.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Live TV persistence: one SharedPreferences file plus plain JSON files under
 * `filesDir/livetv/`. No database dependency; every blob is human-readable and
 * exportable (see [exportAll]/[importAll]).
 */
class LiveTvStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val root: File get() = File(context.filesDir, "livetv").apply { mkdirs() }
    val epgRoot: File get() = File(root, "epg").apply { mkdirs() }
    val recordingsRoot: File get() = File(root, "recordings").apply { mkdirs() }
    val vodRoot: File get() = File(root, "vod").apply { mkdirs() }

    // ---- sources ----

    @Synchronized
    fun sources(): List<LiveTvSource> =
        prefs.getString(KEY_SOURCES, null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                List(a.length()) { i -> a.optJSONObject(i)?.let { LiveTvSource.fromJson(it) } }.filterNotNull()
            }.getOrDefault(emptyList())
        }.orEmpty()

    @Synchronized
    fun saveSources(list: List<LiveTvSource>) {
        prefs.edit().putString(KEY_SOURCES, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    @Synchronized
    fun updateSources(block: (List<LiveTvSource>) -> List<LiveTvSource>): List<LiveTvSource> {
        val next = block(sources())
        saveSources(next)
        return next
    }

    @Synchronized
    fun epgSources(): List<LiveTvEpgSource> =
        prefs.getString(KEY_EPGS, null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                List(a.length()) { i -> a.optJSONObject(i)?.let { LiveTvEpgSource.fromJson(it) } }.filterNotNull()
            }.getOrDefault(emptyList())
        }.orEmpty()

    @Synchronized
    fun saveEpgSources(list: List<LiveTvEpgSource>) {
        prefs.edit().putString(KEY_EPGS, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    // ---- settings / state ----

    @Synchronized
    fun settings(): LiveTvSettings =
        prefs.getString(KEY_SETTINGS, null)?.let { raw ->
            runCatching { LiveTvSettings.fromJson(JSONObject(raw)) }.getOrDefault(LiveTvSettings())
        } ?: LiveTvSettings()

    @Synchronized
    fun saveSettings(s: LiveTvSettings) {
        prefs.edit().putString(KEY_SETTINGS, s.toJson().toString()).apply()
    }

    @Synchronized
    fun updateSettings(block: (LiveTvSettings) -> LiveTvSettings): LiveTvSettings {
        val next = block(settings())
        saveSettings(next)
        return next
    }

    @Synchronized
    fun userState(): LiveUserState =
        prefs.getString(KEY_STATE, null)?.let { raw ->
            runCatching { LiveUserState.fromJson(JSONObject(raw)) }.getOrDefault(LiveUserState())
        } ?: LiveUserState()

    @Synchronized
    fun saveUserState(s: LiveUserState) {
        prefs.edit().putString(KEY_STATE, s.toJson().toString()).apply()
    }

    @Synchronized
    fun updateUserState(block: (LiveUserState) -> LiveUserState): LiveUserState {
        val next = block(userState())
        saveUserState(next)
        return next
    }

    // ---- EPG day buckets ----

    /** Directory holding one source's parsed guide: channels.json + day-*.json. */
    fun epgDir(sourceId: String): File =
        File(epgRoot, sourceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")).apply { mkdirs() }

    fun clearEpg(sourceId: String) {
        runCatching {
            epgDir(sourceId).listFiles()?.forEach { it.delete() }
        }.onFailure { Logs.log("LiveTv", "clear epg $sourceId failed: ${it.message}") }
    }

    // ---- recordings ----

    fun recordingDir(id: String): File =
        File(recordingsRoot, id.replace(Regex("[^a-zA-Z0-9_-]"), "_")).apply { mkdirs() }

    fun recordings(): List<LiveTvRecording> {
        val out = ArrayList<LiveTvRecording>()
        recordingsRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val info = File(dir, "info.json")
            if (!info.isFile) return@forEach
            runCatching { LiveTvRecording.fromJson(JSONObject(info.readText()), dir.absolutePath) }
                .getOrNull()?.let { out += it }
        }
        return out.sortedByDescending { it.startedAtMs }
    }

    // ---- export / import ----

    /** Everything needed to rebuild this device's Live TV elsewhere. */
    fun exportAll(): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("savedAt", System.currentTimeMillis())
        o.put("sources", JSONArray().also { a -> sources().forEach { a.put(it.toJson()) } })
        o.put("epgs", JSONArray().also { a -> epgSources().forEach { a.put(it.toJson()) } })
        o.put("settings", settings().toJson())
        o.put("state", userState().toJson())
        return o
    }

    data class ImportResult(val sources: Int, val epgs: Int)

    /** Merges an export into this device (sources matched by id). */
    fun importAll(o: JSONObject): ImportResult {
        var sn = 0
        var en = 0
        o.optJSONArray("sources")?.let { a ->
            val incoming = List(a.length()) { i -> a.optJSONObject(i)?.let { LiveTvSource.fromJson(it) } }.filterNotNull()
            if (incoming.isNotEmpty()) {
                updateSources { cur ->
                    val byId = cur.associateBy { it.id }.toMutableMap()
                    incoming.forEach {
                        // Keep this device's freshness fields for sources it already had.
                        val keep = byId[it.id]
                        byId[it.id] = if (keep == null) it.copy(lastUpdatedMs = 0L, lastError = null, channelCount = 0)
                        else it.copy(lastUpdatedMs = keep.lastUpdatedMs, lastError = keep.lastError, channelCount = keep.channelCount)
                    }
                    sn = byId.size
                    byId.values.toList()
                }
            }
        }
        o.optJSONArray("epgs")?.let { a ->
            val incoming = List(a.length()) { i -> a.optJSONObject(i)?.let { LiveTvEpgSource.fromJson(it) } }.filterNotNull()
            if (incoming.isNotEmpty()) {
                val cur = epgSources().associateBy { it.id }.toMutableMap()
                incoming.forEach { cur[it.id] = it.copy(lastUpdatedMs = 0L, lastError = null, programCount = 0) }
                en = cur.size
                saveEpgSources(cur.values.toList())
            }
        }
        o.optJSONObject("settings")?.let { saveSettings(LiveTvSettings.fromJson(it)) }
        o.optJSONObject("state")?.let { saveUserState(LiveUserState.fromJson(it)) }
        return ImportResult(sn, en)
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val PREFS = "hikari_livetv"
        const val FORMAT = 1
        private const val KEY_SOURCES = "sources"
        private const val KEY_EPGS = "epgs"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_STATE = "state"
    }
}
