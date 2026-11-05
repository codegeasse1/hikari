package com.hikari.app.cs3

import com.hikari.app.data.Episode
import com.hikari.app.data.StreamSource
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Direct extraction for MovieLinkBD watch pages (movielinkbd.li).
 *
 * The site rebuilt its watch pages around an inline player
 * (`#mlbdInlinePlayerData`): the file list — MLBD CDN urls per quality, with
 * audio/type/file names — is embedded as an XOR-encrypted base64 JSON blob,
 * and playback appends a short-lived `mg` token fetched from the file host's
 * `/g` endpoint. A plugin version from before that rebuild parses the page
 * fine but finds no player embeds, which is exactly the recorded
 * "page parsed OK, but produced no stream links" — while the same title plays
 * wherever the new layout is understood.
 *
 * This replicates the page's own player bootstrap without any JS: read the
 * blob, decode it (the same LCG stream the page's script uses), pick the
 * episode's sources, fetch the `mg` token, and hand the file urls over as
 * servers. Only ever called for movielinkbd.li pages (see [matches]), and
 * fully bounded — a layout change back is just an empty list, never a hang.
 */
object MlbdResolver {

    fun matches(pageUrl: String): Boolean {
        val host = runCatching {
            java.net.URI(pageUrl).host?.lowercase()
        }.getOrNull() ?: return false
        return host == "movielinkbd.li" || host.endsWith(".movielinkbd.li")
    }

    suspend fun resolve(pageUrl: String, episode: Episode?): List<StreamSource> {
        val html = runCatching {
            withTimeoutOrNull(12_000L) { app.get(pageUrl, referer = pageUrl).text }
        }.getOrNull() ?: return emptyList()
        val decoded = extractBlob(html) ?: return emptyList()
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        val episodes = root.optJSONArray("episodes") ?: return emptyList()
        if (episodes.length() == 0) return emptyList()
        val entry = pickEpisode(episodes, episode) ?: return emptyList()
        val sources = entry.optJSONArray("sources") ?: return emptyList()
        if (sources.length() == 0) return emptyList()
        // The mg token lives on the file host named by the /p/ urls (see the
        // page script: fetch("https://"+host+"/g")). One fetch for the whole
        // page; when it fails the raw urls are still tried as-is.
        val fileHost = (0 until sources.length())
            .mapNotNull { sources.optJSONObject(it)?.optString("url") }
            .firstOrNull { it.contains("/p/") }
            ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        val token = fileHost?.let { fetchMg(it) }
        val out = ArrayList<StreamSource>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val url = s.optString("url").trim()
            val dl = s.optString("download_url").trim()
            if (url.isBlank() && dl.isBlank()) continue
            val quality = s.optInt("quality", 0)
            val audio = s.optString("audio").trim()
            val name = s.optString("name").trim().ifBlank { "MovieLinkBD" }
            val details = listOf(
                (if (quality > 0) quality.toString() + "p" else null),
                audio.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
            val headers = mapOf("Referer" to pageUrl)
            if (url.isNotBlank()) {
                out += StreamSource(
                    name = name,
                    url = withMg(url, token),
                    headers = headers,
                    details = details,
                )
            }
            if (dl.isNotBlank() && dl != url) {
                out += StreamSource(
                    name = "$name (alt)",
                    url = withMg(dl, token),
                    headers = headers,
                    details = details,
                )
            }
        }
        return out
    }

    private fun pickEpisode(episodes: org.json.JSONArray, episode: Episode?): JSONObject? {
        if (episodes.length() == 1) return episodes.optJSONObject(0)
        if (episode == null) {
            for (i in 0 until episodes.length()) {
                val e = episodes.optJSONObject(i) ?: continue
                if (e.optString("kind").equals("movie", ignoreCase = true)) return e
            }
            return episodes.optJSONObject(0)
        }
        for (i in 0 until episodes.length()) {
            val e = episodes.optJSONObject(i) ?: continue
            if (e.optInt("number", -1) != episode.number) continue
            if (episode.season > 1 && e.optInt("season", -1) != episode.season) continue
            return e
        }
        for (i in 0 until episodes.length()) {
            val e = episodes.optJSONObject(i) ?: continue
            if (e.optString("id") == episode.id) return e
        }
        return null
    }

    private fun withMg(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        if (url.contains("mg=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "mg=" + token
    }

    private suspend fun fetchMg(fileHost: String): String? {
        return runCatching {
            withTimeoutOrNull(6_000L) {
                val text = app.get("https://" + fileHost + "/g", referer = "https://" + fileHost + "/").text
                JSONObject(text).optString("g").trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * The page's encrypted player blob: `<script id="mlbdInlinePlayerData"
     * type="application/json" data-s="<hex>" data-r="<rounds>"><base64></script>`,
     * decoded with the same LCG the page's own script runs (see the site's
     * mlbd-details-player.js: `r = imul(r, 1103515245) + 12345`, per byte
     * `charCode ^ (r >>> 16 & 255)`).
     *
     * The params are read off the matched script TAG itself, in ANY attribute
     * order — the site serves `data-s`/`data-r` after the id, and a
     * behind-the-tag search for them silently yields nothing (which is exactly
     * how a page full of streams reported "produced no stream links").
     */
    private fun extractBlob(html: String): String? {
        val tag = Regex(
            """<script[^>]*id=["']mlbdInlinePlayerData["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value ?: return null
        val sHex = Regex("""data-s=["']([0-9a-fA-F]+)["']""").find(tag)?.groupValues?.getOrNull(1)
            ?: return null
        val rounds = Regex("""data-r=["'](\d+)["']""").find(tag)?.groupValues?.getOrNull(1)
            ?.toIntOrNull() ?: return null
        val body = Regex(
            """id=["']mlbdInlinePlayerData["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return decodeBlob(body, sHex, rounds)
    }

    private fun decodeBlob(b64: String, sHex: String, rounds: Int): String? = runCatching {
        // parseInt(hex) >>> 0: the low 32 bits, whatever the magnitude.
        var r = sHex.toULongOrNull(16)?.toUInt()?.toInt() ?: return null
        repeat(rounds) { r = r * 1103515245 + 12345 }
        val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            r = r * 1103515245 + 12345
            out[i] = (raw[i].toInt() xor ((r ushr 16) and 255)).toByte()
        }
        String(out, Charsets.UTF_8)
    }.getOrNull()
}
