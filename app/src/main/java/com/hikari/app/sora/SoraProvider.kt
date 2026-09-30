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

    private val browseId = "browse"

    override suspend fun catalogs(): List<CatalogRef> = listOf(
        CatalogRef(config.id, MediaType.SERIES, browseId, "Browse"),
    )

    override suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (!moduleFile.exists()) return@withContext emptyList()
            // page > 1: Sora search has no page arg — return empty rather than
            // re-fetching the same first page forever.
            if (page > 1) return@withContext emptyList()
            val seeds = listOf("", "a", "the", "1")
            for (q in seeds) {
                val payload = SoraRuntime.search(moduleFile, config.id, q)
                val data = dataOf(payload) ?: continue
                val items = toItems(data)
                if (items.isNotEmpty()) {
                    catalogErrors.remove(config.id)
                    lastOutcome.remove(config.id)
                    return@withContext items
                }
            }
            catalogErrors[config.id] =
                "Browse returned nothing — try Search for this source."
            emptyList()
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
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = title,
                type = MediaType.UNKNOWN,
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
            val data = dataOf(payload) as? JSONArray ?: return@withContext null
            val out = ArrayList<Episode>()
            for (i in 0 until minOf(data.length(), MAX_EPISODES)) {
                val o = data.optJSONObject(i) ?: continue
                val link = o.optString("href").trim()
                if (link.isBlank()) continue
                val number = o.optInt("number", -1).takeIf { it > 0 } ?: (out.size + 1)
                out += Episode(
                    number = number,
                    id = link,
                    name = o.optString("title").trim().ifBlank { null },
                    season = 1,
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
            val link = episode?.id?.takeIf { it.isNotBlank() }
                ?: item.id.takeIf { it.isNotBlank() }
                ?: return@withContext fail("✗ No playable link for this title.")
            val payload = SoraRuntime.streams(moduleFile, config.id, link)
            val data = dataOf(payload) as? JSONObject ?: run {
                val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
                return@withContext fail("✗ " + (err?.takeIf { it.isNotBlank() } ?: "no sources found"))
            }
            val out = mapStreams(data)
            if (out.isEmpty()) return@withContext fail("✗ No playable sources for this title.")
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - started) / 1000}s"
            val distinct = out.distinctBy { it.url }
            com.hikari.app.net.StreamProbe.warmAsync(distinct)
            distinct
        }

    private fun mapStreams(data: JSONObject): List<StreamSource> {
        val arr = data.optJSONArray("streams") ?: return emptyList()
        val subUrl = data.optString("subtitles").trim()
        val out = ArrayList<StreamSource>()
        for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("streamUrl").trim().ifBlank { o.optString("url").trim() }
            if (raw.isBlank()) continue
            val title = o.optString("title").trim()
            val quality = o.optString("quality").trim()
            val base = title.ifBlank { config.name }
            val name = if (quality.isNotBlank() && !base.contains(quality, true)) "$base $quality" else base
            val headers = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.keys()?.forEach { k ->
                val v = o.optJSONObject("headers")?.optString(k) ?: return@forEach
                val clean = v.filter { it.code in 32..126 }
                if (clean.isNotBlank()) headers.putIfAbsent(k, clean)
            }
            headers.putIfAbsent("User-Agent", Http.UA)
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
        return out
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
