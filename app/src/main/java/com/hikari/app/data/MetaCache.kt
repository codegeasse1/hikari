package com.hikari.app.data

import android.content.Context
import com.hikari.app.HikariApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The on-disk cache behind "every catalogue, episode list and detail page opens
 * instantly".
 *
 * WHY this exists. Every one of those three reads goes through a PROVIDER, and a
 * provider's first answer is expensive for reasons that have nothing to do with
 * the network: an Aniyomi extension has to class-load its APK before it can make
 * a request (the [com.hikari.app.aniyomi.AniyomiExtensionManager] registers
 * singletons, then ART verifies the extension's dex), a CloudStream/.hiki plugin
 * has to spin its runtime and its site session up, and a nuvio engine boots a
 * QuickJS VM. So the second time a user opens the same catalogue, the same
 * series, or the same detail page, the answer is *known* but was being thrown
 * away at the end of the process — the reported "clicking a series in an
 * aniyomi/skystream extension takes too long to show its catalogue, and the
 * episodes take even longer".
 *
 * WHAT it is: a small JSON file per (kind, key) under `filesDir/metacache/`,
 * with the time it was written, plus an in-process map so a hit costs one map
 * lookup. It deliberately does NOT try to be a database: the values are exactly
 * the objects the repository already returns (`MediaItem`, `Episode`), and the
 * callers decide freshness — [ContentRepository] paints a catalogue page or an
 * episode list from here and then replaces it with the engine's own fresh answer
 * a moment later, which is the stale-while-revalidate shape every streaming app
 * uses.
 *
 * It is NOT the source of truth for anything the user owns: library, history,
 * progress and watch state live in their own stores. Losing this directory costs
 * a re-fetch and nothing else, which is why trimming it (see [trim]) is safe.
 *
 * Callers are on an IO thread already (every entry point runs in one), so the
 * file work here is synchronous on purpose — a suspend wrapper would only add a
 * dispatch per read.
 */
object MetaCache {

    /** Catalogue page: painted for up to this long before it is re-fetched. */
    const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L

    /** Enriched title meta: it changes rarely, and it is what the detail page's
     *  header waits on, so a cached one is served outright inside this window. */
    const val META_TTL_MS = 3 * 24 * 60 * 60 * 1000L

    /** Episode list: painted for up to this long, then refreshed (an ongoing
     *  series can gain episodes, so this is deliberately the shortest window). */
    const val EPISODES_TTL_MS = 12 * 60 * 60 * 1000L

    /** How many files the directory may hold before the oldest are dropped. */
    private const val MAX_FILES = 1500

    private data class Payload(val json: String, val at: Long)

    /** Small in-process mirror of the files. Access-ordered and bounded, because
     *  a cached catalogue page can be a few hundred KB of JSON — the disk is the
     *  real store, this is only so a hit costs nothing. */
    private val mem = object : LinkedHashMap<String, Payload>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Payload>?) =
            size > 256
    }

    private fun memGet(key: String): Payload? = synchronized(mem) { mem[key] }
    private fun memPut(key: String, value: Payload) { synchronized(mem) { mem[key] = value } }
    private fun memDrop(key: String) { synchronized(mem) { mem.remove(key) } }

    private fun ctx(): Context = HikariApp.instance.applicationContext

    private fun dir(): File = File(ctx().filesDir, "metacache").apply { mkdirs() }

    /**
     * One file per key. Two integer hashes rather than one so a collision needs
     * two unrelated keys to agree twice — the same trick [MangaStore] uses for
     * its chapter files, which is plenty for a cache whose worst case is a
     * re-fetch.
     */
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

    fun cachedMeta(key: String, ttlMs: Long = META_TTL_MS): MediaItem? {
        val o = read(key, ttlMs) ?: return null
        return runCatching { itemOf(o.getJSONObject("item")) }.getOrNull()
    }

    fun putMeta(key: String, item: MediaItem) {
        write(key, JSONObject().put("item", itemJson(item)))
    }

    // ---- Episode lists -------------------------------------------------------

    fun episodesKey(uniqueId: String): String = "eps|$uniqueId"

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
                    image = e.stringOrNull("image"),
                    season = e.optInt("season", 1),
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
                    e.image?.let { put("image", it) }
                    put("season", e.season)
                }
            )
        }
        write(key, JSONObject().put("episodes", arr))
    }

    // ---- Serialization -------------------------------------------------------

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun itemJson(i: MediaItem): JSONObject = JSONObject().apply {
        put("providerId", i.providerId)
        put("id", i.id)
        put("title", i.title)
        put("type", i.type.name)
        i.posterUrl?.let { put("posterUrl", it) }
        i.year?.let { put("year", it) }
        i.overview?.let { put("overview", it) }
        if (i.genres.isNotEmpty()) put("genres", JSONArray(i.genres))
        i.backdropUrl?.let { put("backdropUrl", it) }
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
        posterUrl = o.stringOrNull("posterUrl"),
        year = if (o.isNull("year")) null else o.optInt("year"),
        overview = o.stringOrNull("overview"),
        genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            .orEmpty(),
        backdropUrl = o.stringOrNull("backdropUrl"),
        rawType = o.optString("rawType", ""),
        rating = if (o.isNull("rating")) null else o.optDouble("rating"),
        originalTitle = o.optString("originalTitle", ""),
        nsfw = o.optBoolean("nsfw", false),
        quality = o.stringOrNull("quality"),
    )
}
