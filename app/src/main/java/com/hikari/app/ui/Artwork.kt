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

    /**
     * The ONE crop anchor every 16:9 cinema frame uses — the home rail, Show
     * All grids, search grids, collection grids and the detail banner. Near the
     * top so faces and title art stay in frame, a touch below pure top so a
     * tall sky does not eat the whole frame. Every site that crops wide art
     * must use this; a second anchor anywhere is the "same poster looks
     * different here" bug.
     */
    val CINEMA_ALIGNMENT: androidx.compose.ui.Alignment =
        androidx.compose.ui.BiasAlignment(0f, -0.65f)

    private class Entry(val poster: String?, val backdrop: String?, val at: Long)

    private const val MISS_TTL_MS = 3L * 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 800
    /** v7: v6 keys split row vs detail by exact title + type ("Dexter:
     *  Resurrection (Season 2)|2025|UNKNOWN" vs "...|SERIES"), so rows stuck
     *  on poster-only never saw the wide art the detail header found. v7
     *  drops those stale answers and keys by season-normalized title + year
     *  (no type), so every surface shares one entry. */
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
        // A manga cover goes through its EXTENSION's own client rather than
        // Coil's bare request (see PosterLoader.coverModel(url, providerId)): a manga
        // CDN is the kind that refuses a request with no Referer, which is why
        // these covers were the blank tiles in a grid. `null` for every other
        // provider, so nothing else in the app changes path.
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
     *
     * TMDB first, provider art second: the provider's own backdrop is frequently
     * a low-res thumb that stretches into a blurry band full-bleed (the
     * screenshot-proven donghua detail pages), while TMDB carries the same
     * high-quality backdrop the reference client shows. Applies on phones and
     * televisions alike — every hero/cinema surface paints from here.
     */
    fun heroModel(item: MediaItem, skipExtensionBackdrop: Boolean = false): Pair<Any?, Boolean> {
        // A live TV channel wears its OWN logo: the generated initials tile
        // must never shadow a real `tvg-logo` here. The channel grid draws
        // cinema cards from this (see CatalogScreen's iptvCinema path), and the
        // lookup below always answers a drawn tile for a channel — so without
        // this guard every logoed channel sat on initials instead of its logo.
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

    /**
     * Starts the TMDB/IMDb artwork lookup for [item] WITHOUT subscribing the
     * caller to the result.
     *
     * The normal lookups kick off lazily on first read, which on the
     * television detail page meant the TMDB round-trip only started once the
     * provider's own detail response had landed — the backdrop then arrived a
     * beat after everything else. The detail page (and the home hero) call
     * this the moment the title opens, from nav args alone, so the art is
     * already in flight — ideally already cached — when the page paints.
     * Single-flight and disk-cached like everything else here, so calling it
     * early never costs a duplicate request.
     */
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

    /**
     * Warms a whole row (cinema + vertical): first 20 items prefetch in the
     * background and stale misses retry, so switching poster shape paints real
     * 16:9/2:3 instantly instead of blurred portraits that only heal after a
     * detail open. Bounded, single-flighted, never throws.
     */
    fun warmRow(items: List<MediaItem>) {
        runCatching {
            items.take(20).forEach { prefetch(it); retryMiss(it) }
        }
    }

    /**
     * Hero art for the TELEVISION layout as (model, isWide). Same as
     * [heroModel] now that it is TMDB-first everywhere (see above): kept as a
     * named entry point so television callers keep reading their intent.
     */
    fun tvHeroModel(item: MediaItem): Pair<Any?, Boolean> = heroModel(item)

    /**
     * Re-runs the artwork lookup for [item] when the cached answer is a MISS.
     *
     * A miss is cached for days (see [MISS_TTL_MS]), so a title the matcher
     * could not resolve in an older build keeps showing provider art long
     * after the matcher learned it — the television detail page proved it:
     * TMDB ratings and overview (resolved fresh on every open) showed while
     * the banner stayed on the provider image (a stale miss). Television
     * calls this on open so a healed title picks up its TMDB art by itself.
     *
     * Only a FULL miss retries: a title with a real poster but no backdrop
     * keeps its answer, otherwise every open would re-ask the API for titles
     * TMDB genuinely has no backdrop for. And a miss fresher than a minute
     * stays cached (a dead network on open must not re-fire on every
     * recompose) — anything older re-asks once, single-flighted like the
     * normal lookup.
     */
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
            // Poster-only answers retry for a backdrop once an hour: a title
            // the matcher could not resolve wide art for keeps its poster,
            // but a newly indexed banner (or a healed matcher) still heals
            // the row instead of parking it on blurred portrait forever.
            if (e.backdrop.isNullOrBlank() &&
                System.currentTimeMillis() - e.at > 3_600_000L
            ) {
                enqueue(key, item)
            }
            return
        }
        enqueue(key, item)
    }

    /**
     * The row paints what the detail screen settled on. The header banks
     * wide art per title id (see TmdbScreenshots); rows keyed by the same
     * canonical title+year read that art here the moment the header writes
     * it, so a cinema card heals to the detail background without a detail
     * open healing it first. Fill-only for the portrait half (never swaps
     * a settled poster); the wide half follows the header (what the user
     * asked the row to paint). Wakes every observing row via [revision].
     * Never throws.
     */
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
            memory[key] = Entry(p, b, System.currentTimeMillis())
            saveCache()
            revision.value = revisionCounter.incrementAndGet()
        }
    }

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

    /** Cache key shared by every surface: season-normalized title + year.
     *  Deliberately WITHOUT the media type — catalog rows arrive UNKNOWN
     *  while the detail page is SERIES/MOVIE, and one entry must serve both,
     *  or rows keep a poster-only answer while detail holds the backdrop.
     *  Normalization mirrors TmdbMeta.queryVariants (season/part suffixes
     *  stripped, spaces collapsed), so "Dexter: Resurrection (Season 2)"
     *  and "Dexter: Resurrection" resolve to the same key. */
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
        var t = raw.trim().lowercase()
        if (t.isBlank()) return null
        t = t.replace(SEASON_BIT, " ").replace(SPACES, " ").trim()
            .trim('-', '–', '—', ':', '：', '|', '.', ',', '_').trim()
        if (t.length < 2) t = raw.trim().lowercase()
        return t.takeIf { it.length >= 2 }
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
                            // Detail-header parity: the detail page paints the
                            // plural TMDB/anime-banner list first
                            // (TmdbScreenshots.backdropsFor), while rows only had
                            // this single lookup — so rows stayed blurred
                            // poster-only on titles whose wide art resolves
                            // there. Union them: the anime/TMDB portrait wins
                            // for the vertical poster, and any /images backdrop
                            // fills the landscape the single lookup missed.
                    val shots = TmdbScreenshots.backdropsFor(item)
                    val poster = art?.first
                        ?: TmdbScreenshots.firstPosterCached(item.uniqueId)
                        ?: TmdbScreenshots.allPostersCached(item.uniqueId).firstOrNull()
                        ?: siblingPosterCached(item)
                    // Detail-header parity, extended: the detail page
                    // caches wide art under its own SERIES/MOVIE id
                    // while rows carry UNKNOWN, so a row also reads
                    // the caches its detail page wrote before falling
                    // back to poster-only.
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
