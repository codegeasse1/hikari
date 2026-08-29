package com.hikari.app.nuvio

import com.hikari.app.HikariApp
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
 * Adapts a Nuvio JS provider to Hikari's ContentProvider contract. Nuvio
 * providers have no catalogs/search of their own — they resolve sources purely
 * from a TMDB id + mediaType (+ season/episode for series), which is exactly
 * how the official NuvioMobile app calls them. Hikari resolves the TMDB id via
 * [TmdbResolver] and plays whatever the provider returns.
 */
class NuvioScraper(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()
    }

    override suspend fun catalogs(): List<CatalogRef> = emptyList()
    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = emptyList()
    override suspend fun search(query: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getMeta(item: MediaItem): MediaItem = item
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = null

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val resolved = TmdbResolver.resolve(item)
            if (resolved == null) {
                streamErrors[config.id] =
                    "Couldn't resolve a TMDB id for this title (needed by nuvio providers)."
                return@withContext emptyList()
            }
            val source = runCatching { File(config.url).readText() }.getOrNull()
            if (source.isNullOrBlank()) {
                streamErrors[config.id] = "Scraper file missing — reinstall this extension."
                return@withContext emptyList()
            }
            // Series: nuvio needs the season + episode numbers. The episode's
            // id/name usually carries them ("S2E5", "2x3", …).
            val season = if (episode == null) null else seasonOf(episode)
            val epNum = episode?.number ?: 1
            val payload = NuvioRuntime.getStreams(
                HikariApp.instance,
                source,
                config.id,
                resolved.tmdbId,
                resolved.mediaType,
                season,
                epNum,
            )
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            if (parsed == null) {
                streamErrors[config.id] = "Nuvio runtime returned an unreadable result."
                return@withContext emptyList()
            }
            if (!parsed.optBoolean("ok", false)) {
                val err = parsed.optString("error").ifBlank { "no sources found" }
                streamErrors[config.id] = if (err == "timeout")
                    "Nuvio provider timed out after 60s."
                else
                    err.take(400)
                return@withContext emptyList()
            }
            val data = parsed.optJSONArray("data")
            if (data == null || data.length() == 0) {
                streamErrors[config.id] = "Provider returned no sources for this title."
                return@withContext emptyList()
            }
            val out = mutableListOf<StreamSource>()
            for (i in 0 until data.length()) {
                val s = data.optJSONObject(i) ?: continue
                toStreamSource(s)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) streamErrors.remove(config.id)
            out.distinctBy { it.url }
        }

    private fun toStreamSource(s: JSONObject): StreamSource? {
        // Some providers wrap url+headers in a nested object.
        var url = s.optString("url")
        var headers = s.optJSONObject("headers")
        if (url.isBlank() && s.has("url")) {
            val nested = s.optJSONObject("url")
            if (nested != null) {
                url = nested.optString("url")
                headers = nested.optJSONObject("headers") ?: headers
            }
        }
        if (url.isBlank() && s.optString("externalUrl").isBlank() && s.optString("ytId").isBlank()) {
            return null
        }
        val name = s.optString("name").ifBlank { s.optString("title") }.ifBlank { config.name }
        val quality = s.optString("quality").ifBlank { "" }
        val displayName = if (quality.isNotBlank() && !name.contains(quality, true)) "$name $quality" else name

        val h = LinkedHashMap<String, String>()
        if (headers != null) {
            headers.keys().forEach { k ->
                val v = headers.optString(k).filter { it.code < 128 }
                if (v.isNotBlank()) h[k] = v
            }
        }
        // Always send a browser UA unless the provider explicitly set one.
        h.putIfAbsent("User-Agent", Http.UA)

        val subs = mutableListOf<SubtitleSource>()
        val subArr = runCatching { s.getJSONArray("subtitles") }.getOrNull()
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val st = subArr.optJSONObject(i) ?: continue
                val su = st.optString("url")
                if (su.isBlank()) continue
                subs.add(
                    SubtitleSource(
                        st.optString("lang").ifBlank { st.optString("language") }.ifBlank { "Sub" },
                        su,
                    )
                )
            }
        }

        val isTorrent = s.optBoolean("isTorrent", false) ||
            url.startsWith("magnet:", true) || url.startsWith("torrent:", true)
        val infoHash = s.optString("infoHash").ifBlank { null }
            ?: infoHashOf(url)
        val isM3u8 = s.optBoolean("isM3u8", false) || url.contains(".m3u8", true)
        val isMpd = s.optBoolean("isMpd", false) || url.contains(".mpd", true)

        return StreamSource(
            name = displayName,
            url = if (isTorrent) url else Http.normalizeDriveUrl(url),
            headers = h,
            subtitles = subs,
            isTorrent = isTorrent,
            infoHash = infoHash,
            isM3u8 = isM3u8,
            isMpd = isMpd,
            fileIdx = if (s.has("fileIdx")) s.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
            trackers = runCatching {
                val a = s.getJSONArray("trackers")
                (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } }
            }.getOrDefault(emptyList()),
            ytId = s.optString("ytId").ifBlank { null },
            externalUrl = s.optBoolean("externalUrl", false),
        )
    }

    private fun infoHashOf(url: String): String? {
        Regex("""[?&]xt=urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
        Regex("""urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** Best-effort season number from the episode's id or name. */
    private fun seasonOf(ep: Episode): Int {
        val text = listOfNotNull(ep.id, ep.name).firstOrNull { it.isNotBlank() } ?: return 1
        Regex("""(?i)[sS]\s*(\d+)\s*[eE]\s*(\d+)""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""(?i)season\s+(\d+)""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""(?:^|[^\d])(\d+)\s*[xX:.\-]\s*\d+(?:$|[^\d])""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        return 1
    }
}
