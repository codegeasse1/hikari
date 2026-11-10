package com.hikari.app.data

import android.content.Context
import com.hikari.app.HikariApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MetaCache {

    /** Catalogue page: painted for up to this long before it is re-fetched. */
    const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L

    /** Enriched title meta: it changes rarely, and it is what the detail page's
     *  header waits on, so a cached one is served outright inside this window. */
    const val META_TTL_MS = 3 * 24 * 60 * 60 * 1000L

    /** Episode list: painted for up to this long, then refreshed (an ongoing
     *  series can gain episodes, so this is deliberately the shortest window). */
    const val EPISODES_TTL_MS = 12 * 60 * 60 * 1000L

    @Volatile
    private var episodesEpoch: Int = -1

    private fun prefs() = ctx().getSharedPreferences("metacache", Context.MODE_PRIVATE)

    /** The current extension-build epoch, 0 when nothing has ever bumped it. */
    fun episodesEpoch(): Int {
        val held = episodesEpoch
        if (held >= 0) return held
        val read = runCatching { prefs().getInt("episodes_epoch", 0) }.getOrDefault(0)
        episodesEpoch = read
        return read
    }

    fun bumpEpisodesEpoch() {
        val next = episodesEpoch() + 1
        episodesEpoch = next
        runCatching { prefs().edit().putInt("episodes_epoch", next).apply() }
        synchronized(mem) {
            val doomed = mem.keys.filter { it.startsWith("eps|") }
            doomed.forEach { memDrop(it) }
        }
    }

    /** How many files the directory may hold before the oldest are dropped. */
    private const val MAX_FILES = 1500

    private data class Payload(val json: String, val at: Long)

    /** Small in-process mirror of the files. Catalogue pages can be tens of MB
     *  of JSON (posters sometimes arrive as inline base64 data URIs), which is
     *  what OOMed phones — so this mirror is BYTE-bounded, not just
     *  count-bounded. The disk is the real store. */
    private val mem = LinkedHashMap<String, Payload>(64, 0.75f, true)
    private var memBytes = 0L
    private const val MEM_MAX = 256
    /** Total JSON held in memory (UTF-16 chars ~= 2 bytes each). */
    private const val MEM_BUDGET = 6L * 1024 * 1024
    /** Anything bigger than this never enters memory at all (disk only). */
    private const val MEM_SINGLE_MAX = 2L * 1024 * 1024

    private fun memGet(key: String): Payload? = synchronized(mem) { mem[key] }
    private fun memPut(key: String, value: Payload) = synchronized(mem) {
        val prev = mem.put(key, value)
        if (prev != null) memBytes -= prev.json.length * 2L
        if (value.json.length * 2L > MEM_SINGLE_MAX) {
            // Giant: disk only, never resident.
            mem.remove(key)
            return
        }
        memBytes += value.json.length * 2L
        while ((memBytes > MEM_BUDGET || mem.size > MEM_MAX) && mem.isNotEmpty()) {
            val eldest = mem.entries.iterator().next()
            memBytes -= eldest.value.json.length * 2L
            mem.remove(eldest.key)
        }
    }
    private fun memDrop(key: String) = synchronized(mem) {
        val prev = mem.remove(key)
        if (prev != null) memBytes -= prev.json.length * 2L
    }

    /** Bumped every time an episode list lands (see [putEpisodes]): poster
     *  Dub/Sub pills collect this so a grid that is already on screen gains
     *  its pills the moment some title's episodes arrive, without polling. */
    private val _epRevision = MutableStateFlow(0)
    val epRevision: StateFlow<Int> = _epRevision.asStateFlow()

    private fun ctx(): Context = HikariApp.instance.applicationContext

    private fun dir(): File = File(ctx().filesDir, "metacache").apply { mkdirs() }

    /** Inline `data:` payloads (base64 posters) must never sit in the memory
     *  mirror: one catalogue page of them is tens of MB. Big ones spill to a
     *  sidecar file and the JSON keeps a tiny token instead. */
    private const val DATA_TOKEN = "mcf:"
    private const val DATA_MIN = 8192

    private fun sha1Hex(s: String): String = runCatching {
        val d = java.security.MessageDigest.getInstance("SHA-1")
        d.update(s.toByteArray(Charsets.UTF_8))
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull() ?: (s.hashCode().toUInt().toString(16) + s.length.toString(16))

    private fun storeUrl(u: String?): String? {
        if (u == null || u.length < DATA_MIN || !u.startsWith("data:", ignoreCase = true)) return u
        val h = sha1Hex(u)
        runCatching {
            val f = File(dir(), "u$h.txt")
            if (!f.exists()) f.writeText(u)
        }
        return DATA_TOKEN + h
    }

    private fun resolveUrl(u: String?): String? {
        if (u == null || !u.startsWith(DATA_TOKEN)) return u
        return runCatching {
            File(dir(), "u" + u.removePrefix(DATA_TOKEN) + ".txt")
                .takeIf { it.exists() }?.readText()
        }.getOrNull()
    }

    private fun file(key: String): File {
        val a = key.hashCode().toUInt().toString(16)
        val b = (key.hashCode() * 31 + key.length).toUInt().toString(16)
        return File(dir(), "k$a-$b.json")
    }

    private fun read(key: String, ttlMs: Long): JSONObject? {
        val now = System.currentTimeMillis()
        val held = memGet(key)
        val payload = held ?: runCatching {
            val f = file(key)
            if (!f.exists()) return@runCatching null
            val o = JSONObject(f.readText())
            Payload(o.optString("p"), o.optLong("at")).also { memPut(key, it) }
        }.getOrNull() ?: return null
        if (ttlMs > 0 && now - payload.at > ttlMs) {
            memDrop(key)
            return null
        }
        return runCatching { JSONObject(payload.json) }.getOrNull()
    }

    private fun write(key: String, payload: JSONObject) {
        val at = System.currentTimeMillis()
        val json = payload.toString()
        memPut(key, Payload(json, at))
        runCatching {
            file(key).writeText(JSONObject().put("at", at).put("p", json).toString())
            trim()
        }
    }

    /** Keeps the directory bounded, newest first, so the cache can never grow
     *  without limit on a phone that browses for months. */
    private fun trim() {
        val all = dir().listFiles() ?: return
        if (all.size <= MAX_FILES) return
        val doomed = all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES)
        doomed.forEach { runCatching { it.delete() } }
    }

    // ---- Catalogue pages -----------------------------------------------------

    fun catalogKey(providerId: String, ref: CatalogRef, page: Int): String =
        "cat|$providerId|${ref.type}|${ref.id}|$page"

    fun cachedCatalog(key: String, ttlMs: Long = CATALOG_TTL_MS): List<MediaItem>? {
        val o = read(key, ttlMs) ?: return null
        val arr = o.optJSONArray("items") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            runCatching { itemOf(arr.getJSONObject(i)) }.getOrNull()
        }
        return list.takeIf { it.isNotEmpty() }
    }

    fun putCatalog(key: String, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.forEach { arr.put(itemJson(it)) }
        write(key, JSONObject().put("items", arr))
    }

    // ---- Title meta ----------------------------------------------------------

    fun metaKey(uniqueId: String): String = "meta|$uniqueId"

    fun mangaMetaKey(providerId: String, mangaUrl: String): String = "metamanga|$providerId|$mangaUrl"

    fun cachedMeta(key: String, ttlMs: Long = META_TTL_MS): MediaItem? {
        val o = read(key, ttlMs) ?: return null
        return runCatching { itemOf(o.getJSONObject("item")) }.getOrNull()
    }

    fun putMeta(key: String, item: MediaItem) {
        write(key, JSONObject().put("item", itemJson(item)))
    }

    // ---- Episode lists -------------------------------------------------------

fun episodesKey(uniqueId: String): String = "eps|${episodesEpoch()}|$uniqueId"

    fun cachedEpisodes(key: String, ttlMs: Long = EPISODES_TTL_MS): List<Episode>? {
        val o = read(key, ttlMs) ?: return null
        val arr = o.optJSONArray("episodes") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val e = arr.getJSONObject(i)
                Episode(
                    number = e.getInt("number"),
                    id = e.getString("id"),
                    name = e.stringOrNull("name"),
                    image = resolveUrl(e.stringOrNull("image")),
                    season = e.optInt("season", 1),
                    overview = e.stringOrNull("overview"),
                    released = e.stringOrNull("released"),
                    rating = if (e.isNull("rating")) null else e.optDouble("rating").takeIf { it > 0.0 },
                    runtime = if (e.isNull("runtime")) null else e.optInt("runtime").takeIf { it > 0 },
                )
            }.getOrNull()
        }
        return list.takeIf { it.isNotEmpty() }
    }

    fun putEpisodes(key: String, episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val arr = JSONArray()
        episodes.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("number", e.number)
                    put("id", e.id)
                    e.name?.let { put("name", it) }
                    e.image?.let { put("image", storeUrl(it)) }
                    put("season", e.season)
                    e.overview?.let { put("overview", it) }
                    e.released?.let { put("released", it) }
                    e.rating?.let { put("rating", it) }
                    e.runtime?.let { put("runtime", it) }
                }
            )
        }
        write(key, JSONObject().put("episodes", arr))
        runCatching { _epRevision.value += 1 }
    }

    fun evictEpisodes(uniqueId: String) {
        val key = episodesKey(uniqueId)
        memDrop(key)
        runCatching { file(key).delete() }
    }

    /** True when a cached episode list exists for [uniqueId] (memory or disk),
     *  however old — the thing CloudStream never keeps, and the input a stale
     *  lookup most likely replayed. Cheap presence check: parses nothing. */
    fun hasEpisodes(uniqueId: String): Boolean {
        val key = episodesKey(uniqueId)
        if (synchronized(mem) { mem.containsKey(key) }) return true
        return runCatching { file(key).exists() }.getOrDefault(false)
    }

    // ---- Serialization -------------------------------------------------------

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun itemJson(i: MediaItem): JSONObject = JSONObject().apply {
        put("providerId", i.providerId)
        put("id", i.id)
        put("title", i.title)
        put("type", i.type.name)
        i.posterUrl?.let { put("posterUrl", storeUrl(it)) }
        i.year?.let { put("year", it) }
        i.overview?.let { put("overview", it) }
        if (i.genres.isNotEmpty()) put("genres", JSONArray(i.genres))
        i.backdropUrl?.let { put("backdropUrl", storeUrl(it)) }
        if (i.rawType.isNotEmpty()) put("rawType", i.rawType)
        i.rating?.let { put("rating", it) }
        if (i.originalTitle.isNotEmpty()) put("originalTitle", i.originalTitle)
        if (i.nsfw) put("nsfw", true)
        i.quality?.let { put("quality", it) }
    }

    private fun itemOf(o: JSONObject): MediaItem = MediaItem(
        providerId = o.getString("providerId"),
        id = o.getString("id"),
        title = o.getString("title"),
        type = runCatching { MediaType.valueOf(o.optString("type", "UNKNOWN")) }
            .getOrDefault(MediaType.UNKNOWN),
        posterUrl = resolveUrl(o.stringOrNull("posterUrl")),
        year = if (o.isNull("year")) null else o.optInt("year"),
        overview = o.stringOrNull("overview"),
        genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            .orEmpty(),
        backdropUrl = resolveUrl(o.stringOrNull("backdropUrl")),
        rawType = o.optString("rawType", ""),
        rating = if (o.isNull("rating")) null else o.optDouble("rating"),
        originalTitle = o.optString("originalTitle", ""),
        nsfw = o.optBoolean("nsfw", false),
        quality = o.stringOrNull("quality"),
    )
}
