package com.hikari.app.iptv

import com.hikari.app.data.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parsed-guide storage: one JSON file per source per day plus a channels file,
 * so a 200 MB guide never sits in memory whole. Only programmes inside the
 * refresh window are parsed and kept; day files hold the rest for the guide,
 * calendar and catch-up lookups. Loaded day files stay in a small LRU.
 */
class EpgStore(private val store: LiveTvStore) {

    /** Source id -> its channel list. */
    @Volatile private var channelCache: Map<String, List<XmltvChannel>> = emptyMap()

    /** "sourceId|day" -> xmltvId -> programmes. Bounded (see [DAY_CACHE_MAX]). */
    private val dayCache = object : LinkedHashMap<String, Map<String, List<EpgProgram>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<EpgProgram>>>?) =
            size > DAY_CACHE_MAX
    }

    /**
     * Builds the guide's channel filter from the playlist: keep the guide ids
     * a playlist channel claims (its tvg-id, its name, or a hand assignment),
     * so a 200 MB guide only keeps the few thousand shows the user can open.
     * Null = keep everything (no channels yet, or nothing matched — a full
     * guide the lookups then filter is more useful than an empty one).
     */
    fun wantedIds(channels: List<LiveChannel>, overrides: Set<String>): ((Set<String>) -> Set<String>?)? {
        if (channels.isEmpty()) return null
        val ids = HashSet<String>()
        val names = HashSet<String>()
        channels.forEach { c ->
            c.tvgId?.takeIf { it.isNotBlank() }?.let { ids += it.lowercase() }
            names += LiveTvEngine.normName(c.name)
            c.tvgName?.takeIf { it.isNotBlank() }?.let { names += LiveTvEngine.normName(it) }
        }
        overrides.forEach { o -> if (o.isNotBlank()) ids += o.lowercase() }
        if (ids.isEmpty() && names.isEmpty()) return null
        return { guideIds ->
            val keep = guideIds.filter { gid ->
                gid.lowercase() in ids || LiveTvEngine.normName(gid) in names
            }.toSet()
            keep.ifEmpty { null }
        }
    }

    fun channelsOf(sourceId: String): List<XmltvChannel> {        channelCache[sourceId]?.let { return it }
        val f = File(store.epgDir(sourceId), "channels.json")
        if (!f.isFile) return emptyList()
        val list = runCatching {
            val a = JSONArray(f.readText())
            List(a.length()) { i ->
                val o = a.optJSONObject(i) ?: return@List null
                val namesArr = o.optJSONArray("names")
                XmltvChannel(
                    o.optString("id"),
                    List(namesArr?.length() ?: 0) { k -> namesArr!!.optString(k) }.filter { it.isNotBlank() },
                    o.optString("icon").ifBlank { null },
                )
            }.filterNotNull()
        }.getOrDefault(emptyList())
        channelCache = channelCache + (sourceId to list)
        return list
    }

    /** Programmes for [xmltvId] overlapping [fromMs, toMs]. */
    fun programs(sourceId: String, xmltvId: String, fromMs: Long, toMs: Long): List<EpgProgram> {
        if (xmltvId.isBlank()) return emptyList()
        val out = ArrayList<EpgProgram>()
        dayKeys(fromMs, toMs).forEach { day ->
            dayBucket(sourceId, day)[xmltvId]?.forEach { p ->
                if (p.stopMs > fromMs && p.startMs < toMs) out += p
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun dayBucket(sourceId: String, day: String): Map<String, List<EpgProgram>> {
        val key = "$sourceId|$day"
        synchronized(dayCache) { dayCache[key]?.let { return it } }
        val f = File(store.epgDir(sourceId), "day-$day.json")
        val map: Map<String, List<EpgProgram>> = if (!f.isFile) emptyMap() else runCatching {
            val root = JSONObject(f.readText())
            val out = HashMap<String, List<EpgProgram>>()
            root.keys().forEach { id ->
                val arr = root.optJSONArray(id) ?: return@forEach
                out[id] = List(arr.length()) { i ->
                    arr.optJSONObject(i)?.let { EpgProgram.fromJson(it) }
                }.filterNotNull()
            }
            out
        }.getOrDefault(emptyMap())
        synchronized(dayCache) { dayCache[key] = map }
        return map
    }

    /**
     * Parses [chunks] (already unzipped text pieces) keeping only programmes in
     * [windowStartMs, windowEndMs] for channels matching [wantIds] (null = all).
     * Writes day buckets + channels.json; returns (channelCount, programCount).
     */
    fun ingest(
        sourceId: String,
        chunks: Sequence<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        wantIds: ((Set<String>) -> Set<String>?)? = null,
    ): Pair<Int, Int> {
        val dir = store.epgDir(sourceId)
        var programCount = 0
        val dayWriters = HashMap<String, HashMap<String, MutableList<EpgProgram>>>()
        val result = XmltvParser.parse(
            chunks = chunks,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            keepUnlisted = null,
            onPrograms = { id, list ->
                programCount += list.size
                list.forEach { p ->
                    dayWriters.getOrPut(dayKey(p.startMs)) { HashMap() }.getOrPut(id) { ArrayList() } += p
                }
            },
            selectChannels = { channels -> wantIds?.invoke(channels.keys) },
        )
        runCatching {
            val a = JSONArray()
            result.channels.values.forEach { c ->
                a.put(JSONObject().put("id", c.id)
                    .put("names", JSONArray().also { na -> c.displayNames.forEach { na.put(it) } })
                    .put("icon", c.icon.orEmpty()))
            }
            File(dir, "channels.json").writeText(a.toString())
        }
        channelCache = channelCache + (sourceId to result.channels.values.toList())
        dayWriters.forEach { (day, byChannel) ->
            val f = File(dir, "day-$day.json")
            val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject() else JSONObject()
            byChannel.forEach { (id, list) ->
                val arr = root.optJSONArray(id) ?: JSONArray().also { root.put(id, it) }
                val seen = HashSet<Long>()
                for (i in 0 until arr.length()) seen.add(arr.optJSONObject(i)?.optLong("startMs") ?: -1L)
                list.forEach { p ->
                    if (seen.add(p.startMs)) arr.put(p.toJson())
                }
            }
            runCatching { f.writeText(root.toString()) }
            synchronized(dayCache) { dayCache.remove("$sourceId|$day") }
        }
        if (result.error != null) Logs.log("LiveTv", "guide $sourceId parsed with a break: ${result.error}")
        return result.channels.size to programCount
    }

    /** Prunes day files fully older than [beforeMs] (keeps the store small). */
    fun prune(sourceId: String, beforeMs: Long) {
        val dir = store.epgDir(sourceId)
        val cutoff = dayKey(beforeMs)
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("day-") && f.name < "day-$cutoff.json" }
                ?.forEach { it.delete() }
        }
        synchronized(dayCache) { dayCache.keys.filter { it.startsWith("$sourceId|") }.forEach { dayCache.remove(it) } }
    }

    fun clear(sourceId: String) {
        store.clearEpg(sourceId)
        channelCache = channelCache - sourceId
        synchronized(dayCache) { dayCache.keys.filter { it.startsWith("$sourceId|") }.forEach { dayCache.remove(it) } }
    }

    // ---- days (UTC) ----

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun dayKey(ms: Long): String = synchronized(dayFormat) { dayFormat.format(Date(ms)) }

    private fun dayKeys(fromMs: Long, toMs: Long): List<String> {
        val out = ArrayList<String>()
        var day = dayKey(fromMs)
        val end = dayKey(toMs - 1)
        var guard = 0
        while (guard++ < 40) {
            out += day
            if (day >= end) break
            val next = runCatching {
                synchronized(dayFormat) { dayFormat.parse(day)?.time ?: 0L } + 86_400_000L
            }.getOrDefault(0L)
            if (next <= 0) break
            day = dayKey(next)
        }
        return out
    }

    companion object {
        private const val DAY_CACHE_MAX = 24
    }
}
