package com.hikari.app.music

import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JioSaavn 320k fallback (VxMusic port, blocking). */
object VxJioSaavnApi {
    private const val TAG = "HikariVxJio"
    private const val BASE = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"

    data class JioStream(val url: String, val bitrateKbps: Int = 320)

    fun resolveBlocking(title: String, artist: String, durationSec: Int?): JioStream? {
        return try {
            val q = (title + " " + artist).trim().ifBlank { title }
            if (q.isBlank()) return null
            val url = BASE + "?__call=search.getResults&_format=json&_marker=0&ctx=wap6dot0&p=0&n=10&q=" + URLEncoder.encode(q, "UTF-8")
            val text = Http.getStringQuiet(url, mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")) ?: return null
            // Response is JSON with "results": [...]
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
                val score = score(title, artist, durationSec, song, pa, o.optString("duration").toIntOrNull())
                if (score != null && score > bestScore) { bestScore = score; best = enc }
            }
            // Fallback: first encrypted url even without score (keeps Indian catalog working).
            if (best == null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i)?.optString("encrypted_media_url").orEmpty()
                    if (e.isNotBlank()) { best = e; break }
                }
            }
            if (best == null) return null
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

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun score(t: String, a: String, dur: Int?, cs: String, ca: String, cdur: Int?): Int? {
        val tt = norm(t); val ct = norm(cs)
        if (tt.isBlank() || ct.isBlank()) return null
        if (tt != ct && !(tt.contains(ct) || ct.contains(tt))) return null
        if (dur != null && dur > 0 && cdur != null && cdur > 0 && kotlin.math.abs(dur - cdur) > 15) return null
        var s = 1000
        if (a.isNotBlank() && ca.isNotBlank()) {
            val na = norm(a); val nca = norm(ca)
            if (nca.contains(na) || na.contains(nca)) s += 300
        }
        return s
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
