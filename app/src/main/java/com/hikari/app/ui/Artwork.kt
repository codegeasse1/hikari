package com.hikari.app.ui

import androidx.compose.runtime.mutableStateOf
import com.hikari.app.HikariApp
import com.hikari.app.data.IptvMark
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TmdbMeta
import com.hikari.app.data.TmdbScreenshots
import java.io.File
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

object Artwork {

    val CINEMA_ALIGNMENT: androidx.compose.ui.Alignment =
        androidx.compose.ui.BiasAlignment(0f, -0.65f)

    private class Entry(val poster: String?, val backdrop: String?, val at: Long)

    private const val MISS_TTL_MS = 3L * 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 1500
    private const val CACHE_FILE = "artwork-cache-v7.json"

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
        // Butter: warm the TMDB entry the moment a row paints its own art,
        // so the detail header's TMDB art is usually resolved before the tap.
        // Single-flight + memory-checked, so repeats are nearly free.
        prefetch(item)
        val mangaPid = item.providerId
            .takeIf { com.hikari.app.manga.MangaExtensionManager.isMangaProviderId(it) }
        if (mangaPid != null) {
            item.posterUrl?.takeIf { it.isNotBlank() }?.let {
                return PosterLoader.coverModel(it, mangaPid)
            }
        }
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        return PosterLoader.model(poster(item) ?: backdrop(item))
    }

    fun backdropModel(item: MediaItem): Any? {
        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        backdrop(item)?.let { return PosterLoader.model(it) }
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) }
        return PosterLoader.model(poster(item))
    }

    fun heroModel(item: MediaItem, skipExtensionBackdrop: Boolean = false): Pair<Any?, Boolean> {
        if (IptvMark.of(item) && item.rawType == "channel") {
            item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) to false }
        }
        backdrop(item)?.let { return PosterLoader.model(it) to true }
        if (!skipExtensionBackdrop) {
            item.backdropUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) to true }
        }
        poster(item)?.let { return PosterLoader.model(it) to false }
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { return PosterLoader.model(it) to false }
        return PosterLoader.model(poster(item)) to false
    }

    /** Looked-up poster for [item] (null while the lookup is still running). */
    fun poster(item: MediaItem): String? = lookup(item) { it.poster }

    /** Looked-up wide art for [item] (null while the lookup is still running). */
    fun backdrop(item: MediaItem): String? = lookup(item) { it.backdrop }

    fun prefetch(item: MediaItem) {
        val key = keyOf(item) ?: return
        loadOnce()
        val e = memory[key]
        if (e != null) {
            if (System.currentTimeMillis() - e.at < MISS_TTL_MS) return
            // A stale miss: this title may have gained art since. Re-ask once.
            enqueue(key, item)
            return
        }
        enqueue(key, item)
    }

    fun warmRow(items: List<MediaItem>) {
        runCatching {
            items.take(20).forEach { prefetch(it); retryMiss(it) }
        }
    }

    fun tvHeroModel(item: MediaItem): Pair<Any?, Boolean> = heroModel(item)

    fun retryMiss(item: MediaItem) {
        val key = keyOf(item) ?: return
        loadOnce()
        val e = memory[key]
        if (e != null) {
            val fullMiss = e.poster.isNullOrBlank() && e.backdrop.isNullOrBlank()
            if (fullMiss) {
                if (System.currentTimeMillis() - e.at < 60_000L) return
                enqueue(key, item)
                return
            }
            if (e.backdrop.isNullOrBlank() &&
                System.currentTimeMillis() - e.at > 3_600_000L
            ) {
                enqueue(key, item)
            }
            return
        }
        enqueue(key, item)
    }

    fun noteHeaderArt(item: MediaItem, poster: String?, backdrop: String?) {
        val key = runCatching { keyOf(item) }.getOrNull() ?: return
        runCatching {
            loadOnce()
            val cur = memory[key]
            val p = poster?.takeIf { it.isNotBlank() } ?: cur?.poster
            // Follow the header's wide art, but never with a portrait the
            // header banked as a cover fallback: that would un-heal the row.
            var b = backdrop?.takeIf { it.isNotBlank() } ?: cur?.backdrop
            if (b != null && (b == p || b == cur?.poster)) b = cur?.backdrop
            if (p == cur?.poster && b == cur?.backdrop) return
            val e = Entry(p, b, System.currentTimeMillis())
            memory[key] = e
            runCatching { keysFor(item) }.getOrNull()?.forEach { k ->
                if (k != key) memory[k] = e
            }
            saveCache()
            revision.value = revisionCounter.incrementAndGet()
        }
    }

    private fun lookup(item: MediaItem, pick: (Entry) -> String?): String? {
        // Snapshot read: repaints the calling cell when a lookup completes.
        revision.value
        loadOnce()
        val keys = runCatching { keysFor(item) }.getOrNull().orEmpty()
        if (keys.isEmpty()) return null
        for (key in keys) {
            memory[key]?.let { e ->
                pick(e)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            }
        }
        val key = keys.first()
        val e = memory[key]
        if (e != null) {
            if (System.currentTimeMillis() - e.at < MISS_TTL_MS) return null
            // A stale miss: this title may have gained art since. Re-ask once.
            enqueue(key, item)
            return null
        }
        enqueue(key, item)
        return null
    }

    private fun keyOf(item: MediaItem): String? {
        val canon = canonicalTitle(item.title) ?: return null
        val year = item.year?.toString().orEmpty()
        return "$canon|$year"
    }

    private val SEASON_BIT = Regex(
        "(?i)[\\(\\[]?\\b(?:season|series|part|cour|book|volume)\\s*\\d{1,3}\\b[\\)\\]]?" +
            "|\\b\\d{1,3}\\s*(?:st|nd|rd|th)\\s+season\\b" +
            "|[\\(\\[]\\s*20\\d{2}\\s*[\\)\\]]"
    )
    private val SPACES = Regex("\\s+")

    private fun canonicalTitle(raw: String): String? {
        val plain = runCatching {
            com.hikari.app.data.TmdbMeta.queryVariants(raw).lastOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw
        var t = plain.trim().lowercase()
        if (t.isBlank()) return null
        t = t.replace(SEASON_BIT, " ").replace(SPACES, " ").trim()
            .trim('-', '–', '—', ':', '：', '|', '.', ',', '_').trim()
        if (t.length < 2) t = raw.trim().lowercase()
        return t.takeIf { it.length >= 2 }
    }

    /** Every title this item is known under: the row title, the search title,
     *  the original and every alternate. Two surfaces that spell the same
     *  title differently must still share one entry, or the row never sees
     *  what the detail banked. */
    private fun titlesFor(item: MediaItem): List<String> {
        val out = LinkedHashSet<String>()
        out.add(item.title)
        if (item.searchTitle.isNotBlank()) out.add(item.searchTitle)
        if (item.originalTitle.isNotBlank()) out.add(item.originalTitle)
        item.allTitles.forEach { if (it.isNotBlank()) out.add(it) }
        return out.filter { it.isNotBlank() }.take(6)
    }

    private fun keysFor(item: MediaItem): List<String> {
        val out = ArrayList<String>(12)
        val year = item.year?.takeIf { it > 0 }?.toString().orEmpty()
        for (t in titlesFor(item)) {
            val canon = runCatching { canonicalTitle(t) }.getOrNull() ?: continue
            val yearful = "$canon|$year"
            if (!out.contains(yearful)) out.add(yearful)
            if (year.isNotEmpty()) {
                val yearless = "$canon|"
                if (!out.contains(yearless)) out.add(yearless)
            }
            if (out.size >= 12) break
        }
        return out
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
                // A saved TORRENT rides on an IPTV-type row but is not a
                // live channel: it reaches TMDB like anything else, so a
                // magnet named e.g. "Digger 2026" resolves real artwork
                // instead of wearing a "MAG/LIVE" tile.
                val res = if (IptvMark.of(item) && item.rawType != "torrent") {
                    // A saved network stream is a file, not a live channel: its local
                    // tile skips the LIVE marker (see [IptvArt]).
                    runCatching { IptvArt.tile(item, live = item.rawType != "stream")?.let { Pair<String?, String?>(it, null) } }
                        .getOrNull()
                } else {
                    runCatching {
                        runBlocking {
                            val art = TmdbMeta.artwork(item)
                    val shots = TmdbScreenshots.backdropsFor(item)
                    val poster = art?.first
                        ?: TmdbScreenshots.firstPosterCached(item.uniqueId)
                        ?: TmdbScreenshots.allPostersCached(item.uniqueId).firstOrNull()
                        ?: siblingPosterCached(item)
                    val backdrop = art?.second ?: shots.firstOrNull()
                        ?: TmdbScreenshots.firstCached(item.uniqueId)
                        ?: TmdbScreenshots.allCached(item.uniqueId).firstOrNull()
                        ?: siblingBackdropCached(item)
                            if (poster == null && backdrop == null) null
                            else poster to backdrop
                        }
                    }.getOrNull()
                }
                memory[key] = Entry(res?.first, res?.second, System.currentTimeMillis())
                runCatching { keysFor(item) }.getOrNull()?.forEach { k ->
                    if (k == key) return@forEach
                    val cur = memory[k]
                    val merged = Entry(
                        res?.first ?: cur?.poster,
                        res?.second ?: cur?.backdrop,
                        System.currentTimeMillis(),
                    )
                    if (merged.poster != null || merged.backdrop != null) memory[k] = merged
                }
                saveCache()
                revision.value = revisionCounter.incrementAndGet()
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Detail-header art cached under a sibling type's id: row ids carry
     *  UNKNOWN while detail carries SERIES/MOVIE (uniqueId embeds the type),
     *  so a row must also read the caches its detail page wrote. Offline,
     *  bounded, never throws. */
    private fun siblingIds(item: MediaItem): List<String> {
        val uid = item.uniqueId
        val tag = "|${item.type.name}|"
        if (!uid.contains(tag)) return emptyList()
        return listOf("MOVIE", "SERIES", "UNKNOWN").filter { it != item.type.name }
            .map { uid.replace(tag, "|$it|") }
    }

    private fun siblingBackdropCached(item: MediaItem): String? {
        for (sid in siblingIds(item)) {
            TmdbScreenshots.firstCached(sid)?.takeIf { it.isNotBlank() }?.let { return it }
            TmdbScreenshots.allCached(sid).firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun siblingPosterCached(item: MediaItem): String? {
        for (sid in siblingIds(item)) {
            TmdbScreenshots.firstPosterCached(sid)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
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
