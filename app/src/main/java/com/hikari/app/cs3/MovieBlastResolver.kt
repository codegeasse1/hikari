package com.hikari.app.cs3

import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct MovieBlast fallback for MOVIE playback.
 *
 * The MovieBlast .cs3 plugin's movie path resolves links through an encrypted
 * loader that hands Hikari an empty-but-"completed" result, so every MovieBlast
 * movie reported "No playable sources" (its series path works fine — episodes
 * play). This resolves movies straight from the MovieBlast API the same way the
 * app's own scraper does: search the title, fetch the media detail, then build
 * time-signed CDN URLs. Runs as a parallel job in getStreams and its sources
 * are merged with the plugin's, deduped by URL.
 */
object MovieBlastResolver {

    private const val BASE = "https://app.cloud-mb.xyz"
    private const val TOKEN = "jdvhhjv255vghhghdhvfch2565656jhdcghfdf"
    private const val APP_ID = "com.movieblast"
    private const val SIGN_SECRET = "GJ8reydarI7Jqat9rvbAJKNQ9gY4DoEQF2H5nfuI1gi"

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
        "x-request-x" to APP_ID,
    )

    private val searchHeaders = apiHeaders + mapOf(
        "hash256" to "86dc03244adddb3cbedbf0ae36074a736ee293a64774b18e82a6244eafd0df30",
        "packagename" to APP_ID,
    )

    private val playbackHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
        "Referer" to "MovieBlast",
        "Accept-Encoding" to "identity",
        "Connection" to "Keep-Alive",
        "Icy-MetaData" to "1",
        "x-request-x" to APP_ID,
    )

    /** Movies only: series episodes already play through the plugin. */
    fun resolve(item: MediaItem, episode: Episode?): List<StreamSource> {
        if (episode != null || item.title.isBlank()) return emptyList()
        return try {
            val match = searchMovie(item) ?: return emptyList()
            val id = match.optLong("id")
            if (id <= 0) return emptyList()
            val body = Http.getString("$BASE/api/media/detail/$id/$TOKEN", apiHeaders)
                ?: return emptyList()
            buildSources(JSONObject(body))
        } catch (t: Throwable) {
            android.util.Log.w("MovieBlast", "fallback failed for ${item.title}", t)
            emptyList()
        }
    }

    private fun searchMovie(item: MediaItem): JSONObject? {
        // Title goes in the URL PATH — JS encodeURIComponent semantics, so a
        // space must be %20, never URLEncoder's '+'.
        val query = URLEncoder.encode(item.title.trim(), "UTF-8").replace("+", "%20")
        val body = Http.getString("$BASE/api/search/$query/$TOKEN", searchHeaders)
            ?: return null
        val arr = JSONObject(body).optJSONArray("search") ?: return null
        return findBestMatch(item, arr)
    }

    private fun findBestMatch(item: MediaItem, results: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val name = r.optString("name").ifBlank { r.optString("original_name") }
            var score = titleSimilarity(item.title, name)
            val rel = r.optString("release_date")
            if (item.year != null && rel.isNotBlank()) {
                if (rel.take(4).toIntOrNull() == item.year) score += 0.2
            }
            // This resolver only fetches MOVIE details (media/detail), so prefer
            // a "movie" match over a same-named series when scores tie.
            if (r.optString("type").contains("serie", ignoreCase = true)) score -= 0.1
            if (score > bestScore && score > 0.4) {
                bestScore = score
                best = r
            }
        }
        return best
    }

    private fun buildSources(detail: JSONObject): List<StreamSource> {
        val videos = detail.optJSONArray("videos") ?: return emptyList()
        val subs = parseSubtitles(detail.optJSONArray("substitles"))
        val sources = LinkedHashMap<String, StreamSource>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optInt("status", 1) == 0) continue
            if (v.optInt("downloadonly", 0) == 1) continue
            val raw = v.optString("link").trim()
            if (raw.isBlank()) continue
            val https = if (raw.startsWith("http", true)) raw else "https://$raw"
            val signed = signUrl(https) ?: continue
            val server = v.optString("server").ifBlank { "Stream" }
            val lang = v.optString("lang").ifBlank { "EN" }
            val q = matchQuality(server)
            val name = buildString {
                append("MovieBlast - ").append(server)
                if (lang.isNotBlank()) append(" (").append(lang).append(")")
                if (q.isNotBlank()) append(" · ").append(q)
            }
            val isHls = v.optInt("hls", 0) == 1 || https.contains(".m3u8", true)
            sources.putIfAbsent(
                signed,
                StreamSource(
                    name = name,
                    url = signed,
                    headers = playbackHeaders,
                    subtitles = subs,
                    isM3u8 = isHls,
                )
            )
        }
        return sources.values.toList()
    }

    /** Same HMAC-SHA256 time-signed URL the scraper and the app build. */
    private fun signUrl(url: String): String? = try {
        val uri = URI(url)
        val path = uri.rawPath
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal((path + ts).toByteArray(Charsets.UTF_8))
        val b64 = android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)
        val enc = URLEncoder.encode(b64, "UTF-8")
        "$url?verify=$ts-$enc"
    } catch (t: Throwable) {
        null
    }

    private fun matchQuality(s: String): String {
        val v = s.lowercase()
        return when {
            v.contains("2160") || v.contains("4k") -> "4K"
            v.contains("1440") -> "2K"
            v.contains("1080") -> "1080p"
            v.contains("720") -> "720p"
            v.contains("480") -> "480p"
            v.contains("360") -> "360p"
            else -> ""
        }
    }

    private fun parseSubtitles(arr: JSONArray?): List<SubtitleSource> {
        if (arr == null) return emptyList()
        val out = mutableListOf<SubtitleSource>()
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.isNotBlank()) out.add(SubtitleSource("Sub", v.trim()))
                is JSONObject -> {
                    val url = v.optString("url").ifBlank { v.optString("link") }.trim()
                    if (url.isBlank()) continue
                    val lang = v.optString("lang").ifBlank {
                        v.optString("language").ifBlank { v.optString("name").ifBlank { "Sub" } }
                    }
                    out.add(SubtitleSource(lang, url))
                }
            }
        }
        return out
    }

    private fun titleSimilarity(a: String, b: String): Double {
        val n1 = normalizeTitle(a)
        val n2 = normalizeTitle(b)
        if (n1 == n2) return 1.0
        val w1 = n1.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val w2 = n2.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w1.isEmpty() || w2.isEmpty()) return 0.0
        val set2 = w2.toSet()
        val inter = w1.count { it in set2 }
        val union = (w1 + w2).toSet().size
        return inter.toDouble() / union
    }

    private fun normalizeTitle(t: String): String =
        t.lowercase()
            .replace(Regex("\\b(the|a|an)\\b"), "")
            .replace(Regex("[:\\-_]"), " ")
            .replace(Regex("\\s+"), " ")
            // \\p{L}\\p{N} = Unicode letters/numbers (Java \\w is ASCII-only and
            // would strip non-Latin titles like Korean/Hindi ones entirely).
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
}
