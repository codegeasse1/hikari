package com.hikari.app.music

import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JioSaavn 320k fallback with strict title, artist, script, and duration verification. */
object VxJioSaavnApi {
    private const val TAG = "HikariVxJio"
    private const val BASE = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"

    private val STOP_WORDS = setOf(
        "the", "and", "feat", "ft", "featuring", "with", "dj", "official",
        "music", "records", "topic", "vevo", "studio", "prod", "by", "song",
    )

    data class JioStream(val url: String, val bitrateKbps: Int = 320)

    fun resolveBlocking(title: String, artist: String, durationSec: Int?, slackSec: Int = 6): JioStream? {
        return try {
            if (title.isBlank() || artist.isBlank()) return null
            val q = (title + " " + artist).trim()
            val url = BASE + "?__call=search.getResults&_format=json&_marker=0&ctx=wap6dot0&p=0&n=10&q=" + URLEncoder.encode(q, "UTF-8")
            val text = Http.getStringQuiet(url, mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"))
                ?: return null
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = root.optJSONArray("results") ?: return null
            var best: String? = null
            var bestScore = -1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val song = o.optString("song").replace("&amp;", "&")
                val pa = o.optString("primary_artists").replace("&amp;", "&")
                val enc = o.optString("encrypted_media_url")
                if (enc.isBlank()) continue
                val score = score(title, artist, durationSec, song, pa, o.optString("duration").toIntOrNull(), slackSec)
                if (score != null && score > bestScore) { bestScore = score; best = enc }
            }
            if (best == null) {
                Logs.log(TAG, "jio: no exact match for \"" + q + "\"")
                return null
            }
            val dec = decrypt(best) ?: return null
            val finalUrl = dec.replace(Regex("""_96(?:_p)?\.mp4"""), "_320.mp4")
                .replace(Regex("""_48(?:_p)?\.mp4"""), "_320.mp4")
                .replace(Regex("""_160(?:_p)?\.mp4"""), "_320.mp4")
                .replace("http://", "https://")
            JioStream(finalUrl, 320)
        } catch (e: Exception) {
            Logs.log(TAG, "jio failed: " + e.message)
            null
        }
    }

    private fun stripNoise(s: String): String =
        s.replace(Regex("""\s*[\[(][^\]\)]*[\])]"""), " ")
            .replace(Regex("""(?i)\s*[-–—|]\s*(official|music|audio|video|lyrics?|mv|topic).*$"""), " ")
            .trim()

    private fun norm(s: String): String =
        Normalizer.normalize(stripNoise(s).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> =
        norm(s).split(' ').filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    private fun hasCyrillic(s: String): Boolean = s.any { it in '\u0400'..'\u04FF' }

    private fun score(t: String, a: String, dur: Int?, cs: String, ca: String, cdur: Int?, slackSec: Int): Int? {
        // Never match a Cyrillic (Russian) title against a non-Cyrillic track.
        if (hasCyrillic(t) != hasCyrillic(cs)) return null

        val tt = norm(t)
        val ct = norm(cs)
        if (tt.isBlank() || ct.isBlank()) return null

        // Require exact normalized title equality (no loose substring matches).
        if (tt != ct) return null

        // Require non-blank artists with genuine non-stopword overlap.
        val na = tokens(a)
        val nca = tokens(ca)
        if (na.isEmpty() || nca.isEmpty()) return null
        val shared = na.intersect(nca)
        if (shared.isEmpty()) return null

        if (dur != null && dur > 0) {
            if (cdur == null || cdur <= 0 || kotlin.math.abs(dur - cdur) > slackSec) return null
        } else {
            // Without duration metadata, require full artist token match.
            if (!na.containsAll(nca) && !nca.containsAll(na)) return null
        }

        return 1500 + shared.size * 100
    }

    private fun decrypt(enc: String): String? {
        return try {
            val c = Cipher.getInstance("DES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES"))
            val raw = runCatching { java.util.Base64.getDecoder().decode(enc.trim()) }.getOrNull()
                ?: runCatching { android.util.Base64.decode(enc.trim(), android.util.Base64.DEFAULT) }.getOrNull()
                ?: return null
            String(c.doFinal(raw), Charsets.UTF_8).trim()
        } catch (e: Exception) { null }
    }
}
