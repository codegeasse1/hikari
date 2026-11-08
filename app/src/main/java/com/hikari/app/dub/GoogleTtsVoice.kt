package com.hikari.app.dub

import android.content.Context
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Method 1 — Google Translate's own speaker button.
 *
 * Google Translate plays the translated sentence through
 * `translate.google.com/translate_tts`, which returns a small MP3. That is the
 * same free, key-less service the app already uses for text translation
 * ([com.hikari.app.data.Translator]), so no account or API key is involved.
 *
 * Limits worth knowing:
 *  - The endpoint accepts roughly 200 characters per request, so a long cue is
 *    split at sentence / word boundaries and the MP3 pieces are joined.
 *  - It is unofficial. If Google answers 429/403 the voice backs off for a
 *    while and [DubEngine] falls over to [LocalTtsVoice].
 *  - Every clip is cached on disk (by language + text), so rewinding or
 *    re-watching never asks Google again.
 */
class GoogleTtsVoice(context: Context) : DubVoice {

    override val label = "Google Translate voice (online)"

    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }

    @Volatile private var blockedUntil = 0L
    @Volatile private var failStreak = 0

    override suspend fun isAvailable(lang: String): Boolean =
        System.currentTimeMillis() >= blockedUntil

    override suspend fun synthesize(text: String, lang: String): File? =
        withContext(Dispatchers.IO) {
            val out = File(dir, key("g", lang, text) + ".mp3")
            if (out.exists() && out.length() > 0) return@withContext out
            if (System.currentTimeMillis() < blockedUntil) return@withContext null

            val bytes = java.io.ByteArrayOutputStream()
            for (piece in chunk(text, MAX_CHARS)) {
                // Two public "clients" of the same endpoint: if one is refused, try the other.
                var part: ByteArray? = null
                for (client in CLIENTS) {
                    val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=" + client +
                        "&tl=" + URLEncoder.encode(lang, "UTF-8") +
                        "&q=" + URLEncoder.encode(piece, "UTF-8")
                    val got = Http.getBytes(url, mapOf("Referer" to "https://translate.google.com/"))
                    if (got != null && got.size >= 200) {
                        part = got
                        break
                    }
                }
                if (part == null) {
                    // Rate-limited or blocked: back off 20s, then 40s, 80s … (max 5 min).
                    failStreak++
                    blockedUntil = System.currentTimeMillis() +
                        (20_000L shl (failStreak - 1).coerceAtMost(4)).coerceAtMost(300_000L)
                    return@withContext null
                }
                bytes.write(part)
            }
            failStreak = 0
            val tmp = File(dir, out.name + ".part")
            tmp.writeBytes(bytes.toByteArray())
            if (!tmp.renameTo(out)) return@withContext null
            trimCache()
            out
        }

    /** Splits [text] into pieces of at most [max] characters, preferring
     *  sentence ends, then commas, then spaces. */
    internal fun chunk(text: String, max: Int): List<String> {
        val t = text.trim()
        if (t.length <= max) return listOf(t)
        val parts = ArrayList<String>()
        var rest = t
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？', '।'))
            if (cut < max / 3) cut = window.lastIndexOfAny(charArrayOf(',', ';', ':', '،', '、'))
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut < max / 3) cut = max - 1
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts.filter { it.isNotBlank() }
    }

    /** Keeps the clip cache (raw + edited clips) under ~120 MB, oldest first. */
    private fun trimCache() = DubCache.trim(dir, MAX_CACHE_BYTES)

    companion object {
        private const val MAX_CHARS = 180
        private const val MAX_CACHE_BYTES = 120L * 1024 * 1024
        private val CLIENTS = listOf("tw-ob", "gtx")

        internal fun key(kind: String, lang: String, text: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest("$kind|$lang|$text".toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
