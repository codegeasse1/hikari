package com.hikari.app.sora

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts a Sora module (one self-contained script under
 * `filesDir/sora/modules/<id>/module.js`) to Hikari's ContentProvider
 * contract:
 *
 *   search()        <- searchResults(keyword)
 *   getMeta()       <- extractDetails(url)      -> [{description, aliases, airdate}]
 *   getEpisodes()   <- extractEpisodes(url)     -> [{href, number}]
 *   getStreams()    <- extractStreamUrl(url)    -> {streams: [{title, streamUrl, headers?}], subtitles?}
 *
 * Sora modules have no native catalogue endpoint, so Browse is built by
 * calling searchResults with an empty keyword (and a few letter seeds).
 * A title with no episodes resolves as a movie and plays through the
 * detail URL itself.
 *
 * The item's `id` is the module's own href, carried through verbatim.
 */
class SoraProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        val streamErrors = ConcurrentHashMap<String, String>()
        val lastOutcome = ConcurrentHashMap<String, String>()
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val MAX_ITEMS = 120
        private const val MAX_EPISODES = 2000
        private const val MAX_STREAMS = 60
    }

    private val moduleFile: File get() = File(config.url)

    // Sora modules only export searchResults — no dedicated home endpoint.
    // We still expose a Browse catalogue that calls search with an empty
    // keyword (and a couple of letter seeds if empty returns nothing), so
    // the Home screen is not stuck on "No catalog from …" the way a pure
    // searchOnly source is. Playback is unchanged.
    override val searchOnly = false

    private val episodeCache = ConcurrentHashMap<String, List<Episode>>()

    // Multiple home rows — Sora has no native catalogue, so each row is a
    // different search seed. Show All pages through further seeds so a row is
    // not stuck at the first 10–20 hits.
    private val catalogSeeds: List<Pair<String, List<String>>> = listOf(
        "browse" to listOf("", "a", "the", "1", "2024", "2025"),
        "popular" to listOf("popular", "top", "trending", "best"),
        "movies" to listOf("movie", "film", "cinema"),
        "series" to listOf("series", "show", "drama", "episode"),
        "action" to listOf("action", "adventure", "war"),
        "romance" to listOf("romance", "love", "romantic"),
    )

    override suspend fun catalogs(): List<CatalogRef> = listOf(
        CatalogRef(config.id, MediaType.SERIES, "browse", "Browse"),
        CatalogRef(config.id, MediaType.SERIES, "popular", "Popular"),
        CatalogRef(config.id, MediaType.MOVIE, "movies", "Movies"),
        CatalogRef(config.id, MediaType.SERIES, "series", "Series"),
        CatalogRef(config.id, MediaType.SERIES, "action", "Action"),
        CatalogRef(config.id, MediaType.SERIES, "romance", "Romance"),
    )

    override suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (!moduleFile.exists()) return@withContext emptyList()
            val seeds = catalogSeeds.firstOrNull { it.first == ref.id }?.second
                ?: catalogSeeds.first().second
            // Page N picks the N-th seed (1-based). When we run out of seeds the
            // row ends ("That's everything") instead of repeating the first page.
            val idx = (page.coerceAtLeast(1) - 1).coerceAtMost(seeds.lastIndex)
            if (page > seeds.size) return@withContext emptyList()
            val seen = LinkedHashSet<String>()
            val out = ArrayList<MediaItem>()
            // Try this page's seed first, then a couple of neighbours so a
            // barren seed still fills the row.
            val tryOrder = listOf(idx) + ((idx + 1) until seeds.size) + ((idx - 1) downTo 0)
            for (i in tryOrder.distinct()) {
                val q = seeds[i]
                val payload = SoraRuntime.search(moduleFile, config.id, q)
                val data = dataOf(payload) ?: continue
                for (item in toItems(data)) {
                    if (item.id in seen) continue
                    seen += item.id
                    out += item
                    if (out.size >= MAX_ITEMS) break
                }
                if (out.isNotEmpty()) break
            }
            if (out.isNotEmpty()) {
                catalogErrors.remove(config.id)
                lastOutcome.remove(config.id)
            } else if (page == 1) {
                catalogErrors[config.id] =
                    "Browse returned nothing — try Search for this source."
            }
            out
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            if (!moduleFile.exists()) return@withContext emptyList()
            val payload = SoraRuntime.search(moduleFile, config.id, query)
            val data = dataOf(payload) ?: run {
                noteFailure(payload, "Search")
                return@withContext emptyList()
            }
            lastOutcome.remove(config.id)
            toItems(data)
        }

    private fun noteFailure(payload: String, what: String) {
        val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
        val msg = if (err.isNullOrBlank()) "$what returned nothing" else err
        catalogErrors[config.id] = "$what failed — $msg".take(200)
        lastOutcome[config.id] = "✗ ${msg.take(72)}"
    }

    private fun toItems(data: Any?): List<MediaItem> {
        val arr = data as? JSONArray ?: return emptyList()
        val out = ArrayList<MediaItem>(minOf(arr.length(), MAX_ITEMS))
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("href").trim()
            val title = o.optString("title").trim().ifBlank { o.optString("name").trim() }
            if (link.isBlank() || title.isBlank()) continue
            // SERIES so ContentRepository.episodesFor actually asks the module
            // (UNKNOWN used to short-circuit to null → "Play" only, no episodes).
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = title,
                type = MediaType.SERIES,
                posterUrl = o.optString("image").ifBlank { o.optString("imageURL") }
                    .ifBlank { o.optString("poster") }.ifBlank { null },
                rawType = "anime",
            )
        }
        return out
    }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        if (item.id.isBlank() || !moduleFile.exists()) return@withContext item
        val payload = SoraRuntime.details(moduleFile, config.id, item.id)
        val data = dataOf(payload)
        val first = (data as? JSONArray)?.optJSONObject(0) ?: (data as? JSONObject)
        if (first == null) return@withContext item
        val aliases = first.optString("aliases").trim()
        val title = aliases.substringBefore(',').trim().ifBlank { item.title }
        MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = title,
            type = item.type,
            posterUrl = item.posterUrl,
            overview = first.optString("description").trim()
                .ifBlank { item.overview.orEmpty() }.ifBlank { null },
            genres = emptyList(),
            rawType = item.rawType,
            originalTitle = item.originalTitle,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? =
        withContext(Dispatchers.IO) {
            if (item.id.isBlank() || !moduleFile.exists()) return@withContext null
            episodeCache[item.id]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            val payload = SoraRuntime.episodes(moduleFile, config.id, item.id)
            val data = episodesArray(dataOf(payload)) ?: return@withContext null
            val out = ArrayList<Episode>()
            for (i in 0 until minOf(data.length(), MAX_EPISODES)) {
                val o = data.optJSONObject(i) ?: continue
                val link = o.optString("href").trim()
                    .ifBlank { o.optString("url").trim() }
                    .ifBlank { o.optString("link").trim() }
                    .ifBlank { o.optString("id").trim() }
                if (link.isBlank()) continue
                val number = o.optInt("number", -1).takeIf { it > 0 }
                    ?: o.optInt("episode", -1).takeIf { it > 0 }
                    ?: o.optInt("ep", -1).takeIf { it > 0 }
                    ?: o.optString("number").toIntOrNull()?.takeIf { it > 0 }
                    ?: (out.size + 1)
                val season = o.optInt("season", 0).takeIf { it > 0 } ?: 1
                out += Episode(
                    number = number,
                    id = link,
                    name = o.optString("title").trim()
                        .ifBlank { o.optString("name").trim() }
                        .ifBlank { null },
                    season = season,
                )
            }
            if (out.isEmpty()) return@withContext null
            episodeCache[item.id] = out
            out
        }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            if (!moduleFile.exists()) {
                return@withContext fail("✗ Module file missing — reinstall this extension.")
            }
            val primary = episode?.id?.takeIf { it.isNotBlank() }
            val fallback = item.id.takeIf { it.isNotBlank() }
            if (primary == null && fallback == null) {
                return@withContext fail("✗ No playable link for this title.")
            }
            fun tryStreams(link: String): List<StreamSource> {
                val payload = SoraRuntime.streams(moduleFile, config.id, link)
                val data = dataOf(payload) ?: return emptyList()
                return mapStreamsAny(data)
            }
            var out = primary?.let { tryStreams(it) }.orEmpty()
            // Episode href sometimes is a relative path the module can't resolve —
            // fall back to the series page URL (item.id).
            if (out.isEmpty() && fallback != null && fallback != primary) {
                out = tryStreams(fallback)
            }
            if (out.isEmpty()) {
                return@withContext fail("✗ No playable sources for this title.")
            }
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - started) / 1000}s"
            val distinct = out.distinctBy { it.url }
            com.hikari.app.net.StreamProbe.warmAsync(distinct)
            distinct
        }

    private fun mapStreamsAny(data: Any?): List<StreamSource> {
        when (data) {
            is String -> {
                val t = data.trim()
                if (t.startsWith("http", true) || t.startsWith("magnet:", true)) {
                    return listOf(
                        StreamSource(
                            name = config.name,
                            url = if (t.startsWith("magnet:", true)) t else Http.normalizeDriveUrl(t),
                            headers = mapOf("User-Agent" to Http.UA),
                            isTorrent = t.startsWith("magnet:", true),
                            isM3u8 = t.contains(".m3u8", true),
                            isMpd = t.contains(".mpd", true),
                            provider = "Sora",
                            providerId = config.id,
                            providerName = config.name,
                        ),
                    )
                }
                // Sometimes the module returns a JSON string still.
                runCatching { JSONObject(t) }.getOrNull()?.let { return mapStreams(it) }
                runCatching { JSONArray(t) }.getOrNull()?.let { return mapStreamArray(it, "") }
                return emptyList()
            }
            is JSONArray -> return mapStreamArray(data, "")
            is JSONObject -> return mapStreams(data)
            else -> return emptyList()
        }
    }

    private fun mapStreams(data: JSONObject): List<StreamSource> {
        // Canonical: { streams: [...], subtitles? }
        data.optJSONArray("streams")?.let { return mapStreamArray(it, data.optString("subtitles").trim()) }
        // Some modules put the list under results / sources / data.
        for (k in listOf("results", "sources", "data", "list")) {
            data.optJSONArray(k)?.let { return mapStreamArray(it, data.optString("subtitles").trim()) }
        }
        // Single stream object.
        if (data.has("streamUrl") || data.has("url")) {
            return mapStreamArray(JSONArray().put(data), data.optString("subtitles").trim())
        }
        return emptyList()
    }

    private fun mapStreamArray(arr: JSONArray, subUrl: String): List<StreamSource> {
        val out = ArrayList<StreamSource>()
        for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
            when (val e = arr.opt(i)) {
                is String -> {
                    val raw = e.trim()
                    if (raw.startsWith("http", true) || raw.startsWith("magnet:", true)) {
                        out += StreamSource(
                            name = config.name,
                            url = if (raw.startsWith("magnet:", true)) raw else Http.normalizeDriveUrl(raw),
                            headers = mapOf("User-Agent" to Http.UA),
                            isTorrent = raw.startsWith("magnet:", true),
                            isM3u8 = raw.contains(".m3u8", true),
                            isMpd = raw.contains(".mpd", true),
                            provider = "Sora",
                            providerId = config.id,
                            providerName = config.name,
                        )
                    }
                }
                is JSONObject -> {
                    val o = e
                    val raw = o.optString("streamUrl").trim()
                        .ifBlank { o.optString("url").trim() }
                        .ifBlank { o.optString("file").trim() }
                        .ifBlank { o.optString("link").trim() }
                    if (raw.isBlank()) continue
                    val title = o.optString("title").trim().ifBlank { o.optString("name").trim() }
                    val quality = o.optString("quality").trim()
                        .ifBlank { o.optString("resolution").trim() }
                    val base = title.ifBlank { config.name }
                    val name = if (quality.isNotBlank() && !base.contains(quality, true)) "$base $quality" else base
                    val headers = LinkedHashMap<String, String>()
                    o.optJSONObject("headers")?.keys()?.forEach { k ->
                        val v = o.optJSONObject("headers")?.optString(k) ?: return@forEach
                        val clean = v.filter { it.code in 32..126 }
                        if (clean.isNotBlank()) headers.putIfAbsent(k, clean)
                    }
                    headers.putIfAbsent("User-Agent", Http.UA)
                    // Many embed hosts require a same-site Referer; without it
                    // the player buffers forever then hits the 20s dud timer.
                    if (!headers.containsKey("Referer") && !headers.containsKey("referer")) {
                        val origin = runCatching {
                            val u = java.net.URI(raw)
                            "${u.scheme}://${u.host}/"
                        }.getOrNull()
                        if (!origin.isNullOrBlank()) headers["Referer"] = origin
                    }
                    val subs = ArrayList<SubtitleSource>()
                    o.optJSONArray("subtitles")?.let { sa ->
                        for (j in 0 until sa.length()) {
                            val st = sa.optJSONObject(j) ?: continue
                            val su = st.optString("url").ifBlank { st.optString("file") }.trim()
                            if (su.isBlank()) continue
                            subs += SubtitleSource(
                                st.optString("label").ifBlank { st.optString("lang") }.ifBlank { "Sub" },
                                su,
                            )
                        }
                    }
                    if (subUrl.isNotBlank() && subs.isEmpty()) subs += SubtitleSource("Sub", subUrl)
                    val details = listOfNotNull(
                        quality.ifBlank { null },
                        o.optString("size").trim().ifBlank { null },
                        o.optString("language").trim().ifBlank { o.optString("lang").trim() }.ifBlank { null },
                        o.optString("type").trim().ifBlank { null },
                    ).joinToString(" • ").ifBlank { "" }
                    val isTorrent = raw.startsWith("magnet:", true) || raw.startsWith("torrent:", true)
                    out += StreamSource(
                        name = name,
                        url = if (isTorrent) raw else Http.normalizeDriveUrl(raw),
                        headers = headers,
                        subtitles = subs,
                        isTorrent = isTorrent,
                        isM3u8 = raw.contains(".m3u8", true),
                        isMpd = raw.contains(".mpd", true),
                        details = details,
                        provider = "Sora",
                        providerId = config.id,
                        providerName = config.name,
                    )
                }
            }
        }
        return out
    }

    private fun episodesArray(data: Any?): JSONArray? {
        when (data) {
            is JSONArray -> return data
            is JSONObject -> {
                for (k in listOf("episodes", "list", "results", "data", "chapters")) {
                    data.optJSONArray(k)?.let { return it }
                }
            }
        }
        return null
    }

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        return emptyList()
    }

    private fun dataOf(payload: String): Any? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        return o.opt("data")
    }
}
