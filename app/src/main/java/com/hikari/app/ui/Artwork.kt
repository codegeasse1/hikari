package com.hikari.app.ui

import androidx.compose.runtime.mutableStateOf
import com.hikari.app.HikariApp
import com.hikari.app.data.IptvMark
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TmdbMeta
import java.io.File
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Artwork fill-in for items an extension shipped without a poster.
 *
 * Plenty of catalogs (regional/Indian ones especially) return a bare title with
 * no `poster`/`image` at all, and those cells used to sit on the placeholder
 * icon forever. This asks TMDB — and, when TMDB has nothing, IMDb — for the
 * missing art and hands the result to Coil through the same [PosterLoader]
 * path as every other poster.
 *
 * It is a *fallback*: an item that already has art never touches the network
 * here. And because the answers arrive asynchronously, [revision] is read on
 * every lookup so a cell repaints itself the moment its artwork lands
 * (the same trick [PosterLoader] uses).
 *
 * Results — including misses — are cached on disk. A miss is re-tried after
 * [MISS_TTL_MS] rather than forever, so a title that gets art on TMDB later
 * eventually picks it up without hammering the API in the meantime.
 */
object Artwork {

    private class Entry(val poster: String?, val backdrop: String?, val at: Long)

    private const val MISS_TTL_MS = 3L * 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 800
    /** v2: the v1 cache is full of MIS-misses recorded while the IMDb fallback's
     *  title match was too strict (it rejected "Ramayana Part 2" for the catalog's
     *  "Ramayana: Part Two"), and a miss is cached for [MISS_TTL_MS] — three days
     *  of identical placeholder cells after the matcher was fixed. A new filename
     *  drops those stale misses so the fix is visible immediately. */
    private const val CACHE_FILE = "artwork-cache-v2.json"

    private val memory = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val revisionCounter = AtomicLong(0L)
    private val revision = mutableStateOf(0L)

    @Volatile
    private var loaded = false

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "hikari-artwork").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val cacheFile: File?
        get() = runCatching { File(HikariApp.instance.filesDir, CACHE_FILE) }.getOrNull()

    /** Grid/row cell model: the item's own art, else the art we looked up. */
    fun model(item: MediaItem): Any? {
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        return PosterLoader.model(poster(item) ?: backdrop(item))
    }

    /** Wide-hero model: the item's own backdrop, then the wide art we looked
     *  up, then the item's own poster. Asking the lookup for the backdrop
     *  BEFORE falling back to the item's portrait poster matters for banners:
     *  plenty of catalog entries ship a poster only, and using that in a 16:9
     *  hero crops it to a narrow strip (which is what beheads the subject). */
    fun backdropModel(item: MediaItem): Any? {
        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        backdrop(item)?.let { return PosterLoader.model(it) }
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        return PosterLoader.model(poster(item))
    }

    /**
     * Hero art as (model, isWide). [isWide] is true only for genuinely wide
     * (backdrop-shaped) art; when only a portrait poster exists the caller must
     * NOT centre-crop it into a 16:9 frame — see `HeroArtwork`. Like the other
     * lookups this reads [revision], so a hero swaps to the real backdrop the
     * moment its lookup lands.
     */
    fun heroModel(item: MediaItem): Pair<Any?, Boolean> {
        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) to true }
        backdrop(item)?.let { return PosterLoader.model(it) to true }
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) to false }
        return PosterLoader.model(poster(item)) to false
    }

    /** Looked-up poster for [item] (null while the lookup is still running). */
    fun poster(item: MediaItem): String? = lookup(item) { it.poster }

    /** Looked-up wide art for [item] (null while the lookup is still running). */
    fun backdrop(item: MediaItem): String? = lookup(item) { it.backdrop }

    private fun lookup(item: MediaItem, pick: (Entry) -> String?): String? {
        // Snapshot read: repaints the calling cell when a lookup completes.
        revision.value
        val key = keyOf(item) ?: return null
        loadOnce()
        val e = memory[key]
        if (e != null) {
            val v = pick(e)?.takeIf { it.isNotBlank() }
            if (v != null) return v
            if (System.currentTimeMillis() - e.at < MISS_TTL_MS) return null
            // A stale miss: this title may have gained art since. Re-ask once.
            enqueue(key, item)
            return null
        }
        enqueue(key, item)
        return null
    }

    private fun keyOf(item: MediaItem): String? {
        val title = item.title.trim().lowercase()
        if (title.isBlank()) return null
        val year = item.year?.toString().orEmpty()
        return "$title|$year|${item.type.name}"
    }

    private fun enqueue(key: String, item: MediaItem) {
        // Single-flight: a catalog that scrolls past the same title ten times
        // must produce one lookup, not ten.
        if (!inFlight.add(key)) return
        executor.execute {
            try {
                // The lookup is suspending; this worker is a plain thread, so
                // bridge into it. Blocking a daemon artwork thread is fine.
                //
                // A LIVE TV CHANNEL never reaches TMDB (see [IptvMark]): a
                // channel has no TMDB entry, so the title match returned an
                // unrelated film's poster — which is what put film artwork on
                // IPTV rows. Its tile is drawn locally, offline, from the
                // channel's own name instead ([IptvArt]).
                val res = if (IptvMark.of(item)) {
                    runCatching { IptvArt.tile(item)?.let { Pair<String?, String?>(it, null) } }
                        .getOrNull()
                } else {
                    runCatching { runBlocking { TmdbMeta.artwork(item) } }.getOrNull()
                }
                memory[key] = Entry(res?.first, res?.second, System.currentTimeMillis())
                saveCache()
                revision.value = revisionCounter.incrementAndGet()
            } finally {
                inFlight.remove(key)
            }
        }
    }

    // ---- disk cache (survives restarts; bounded) ----

    private fun loadOnce() {
        if (loaded) return
        loaded = true
        val f = cacheFile ?: return
        runCatching {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k")
                if (k.isBlank()) continue
                memory[k] = Entry(
                    poster = o.optString("p").takeIf { it.isNotBlank() },
                    backdrop = o.optString("b").takeIf { it.isNotBlank() },
                    at = o.optLong("t"),
                )
            }
        }
    }

    private fun saveCache() {
        val f = cacheFile ?: return
        runCatching {
            val arr = JSONArray()
            var kept = 0
            for ((k, v) in memory.entries.sortedByDescending { it.value.at }) {
                if (kept >= MAX_ENTRIES) break
                arr.put(
                    JSONObject()
                        .put("k", k)
                        .put("p", v.poster.orEmpty())
                        .put("b", v.backdrop.orEmpty())
                        .put("t", v.at)
                )
                kept++
            }
            f.parentFile?.mkdirs()
            f.writeText(arr.toString())
        }
    }
}
