package com.hikari.app.music

import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * VxMusic lossless backend port (login-free, no Hilt, blocking).
 * Backend: clashflac / qobuz-compatible search + track-url resolver.
 * Strict matching like VxMusic: wrong remix/slowed/live variant or duration >8s
 * mismatch returns null so caller falls back to YouTube (keeps remixes playable).
 */
object VxLosslessApi {
    private const val TAG = "HikariVxLossless"
    // Public fallback from VxMusic when no BuildConfig secret is present.
    private const val BACKEND = "https://qobuz-backend.opabcg.workers.dev/"
    private const val MAX_DURATION_DIFF = 8

    /**
     * Once a full tier walk proves the backend serves no FLAC at all (it
     * answers success for lossless tiers while handing back its MP3 copy),
     * later songs go straight to MP3 instead of re-probing dead tiers on
     * every track. Reset on quality change and on process restart.
     */
    @Volatile
    private var losslessDead = false

    fun resetProbe() { losslessDead = false }

    data class VxStream(
        val url: String,
        val mimeType: String = "audio/flac",
        val bitDepth: Int = 16,
        val samplingRate: Double = 44.1,
        val formatId: Int = 6,
        val bitrateKbps: Int? = null,
        val trackId: Long = 0,
        val durationSeconds: Int = 0,
    )

    private data class Candidate(
        val id: Long,
        val title: String,
        val duration: Int,
        val performer: String,
        val performersText: String,
        val album: String,
        val albumArtist: String,
        val hires: Boolean,
    )

    fun resolveStreamBlocking(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        preferred: Int,
    ): VxStream? {
        if (preferred == VxQuality.YOUTUBE || title.isBlank() || artist.isBlank()) return null
        return try {
            val cand = findBestVerifiedMatch(title, artist, durationSec, album) ?: return null
            val tiers = if (losslessDead && preferred != VxQuality.MP3_320) listOf(VxQuality.MP3_320)
                else VxQuality.attemptOrder(preferred)
            var losslessFailed = false
            for (q in tiers) {
                val s = fetchTrackUrl(cand, q, title, artist, durationSec)
                if (s != null && s.url.isNotBlank()) {
                    if (!s.mimeType.contains("flac", ignoreCase = true) && losslessFailed) {
                        losslessDead = true
                        Logs.log(TAG, "backend serves no FLAC (MP3 served for lossless tiers) — probing off for this session")
                    }
                    return s
                }
                if (q != VxQuality.MP3_320) losslessFailed = true
            }
            null
        } catch (e: Exception) {
            Logs.log(TAG, "lossless resolve failed: " + e.message)
            null
        }
    }

    // ------------------------------------------------------------ search

    private fun findBestVerifiedMatch(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
    ): Candidate? {
        val cleanTitle = cleanForSearch(title)
        val cleanArtist = cleanForSearch(artist)
        val firstArtist = artist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map { cleanForSearch(it) }.firstOrNull { it.isNotBlank() }
        val queries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            firstArtist?.let { "$cleanTitle $it".trim() }?.takeIf { it.isNotBlank() && it != "$cleanTitle $cleanArtist" },
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() },
            title.trim().takeIf { it.isNotBlank() },
        ).distinct()
        for (q in queries) {
            val url = BACKEND + "api/search?q=" + URLEncoder.encode(q, "UTF-8") + "&type=track&limit=15"
            val text = runCatching { Http.getStringQuiet(url) }.getOrNull() ?: continue
            val items = parseSearch(text)
            if (items.isEmpty()) continue
            var best: Candidate? = null
            var bestScore = Int.MIN_VALUE
            for (c in items) {
                val score = verifiedMatchScore(c, title, artist, durationSec, album) ?: continue
                if (score > bestScore) { bestScore = score; best = c }
            }
            if (best != null) return best
        }
        return null
    }

    private fun parseSearch(text: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return out
        if (!root.optBoolean("success")) return out
        val items = root.optJSONObject("results")?.optJSONObject("tracks")?.optJSONArray("items") ?: return out
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id == 0L) continue
            val performerObj = o.optJSONObject("performer")
            val albumObj = o.optJSONObject("album")
            out.add(
                Candidate(
                    id = id,
                    title = o.optString("title"),
                    duration = o.optInt("duration", 0),
                    performer = performerObj?.optString("name").orEmpty(),
                    performersText = o.optString("performers"),
                    album = albumObj?.optString("title").orEmpty(),
                    albumArtist = albumObj?.optJSONObject("artist")?.optString("name").orEmpty(),
                    hires = o.optBoolean("hires", false),
                )
            )
        }
        return out
    }

    private fun fetchTrackUrl(c: Candidate, quality: Int, title: String, artist: String, durationSec: Int?): VxStream? {
        val sb = StringBuilder(BACKEND + "api/track/" + c.id + "/url?")
        sb.append("quality=").append(quality)
        sb.append("&fallback=true")
        sb.append("&title=").append(URLEncoder.encode(title, "UTF-8"))
        if (c.performer.isNotBlank()) sb.append("&artist=").append(URLEncoder.encode(c.performer, "UTF-8"))
        else if (artist.isNotBlank()) sb.append("&artist=").append(URLEncoder.encode(artist, "UTF-8"))
        if (c.duration > 0) sb.append("&duration=").append(c.duration)
        else if ((durationSec ?: 0) > 0) sb.append("&duration=").append(durationSec)
        val text = runCatching { Http.getStringQuiet(sb.toString()) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (!root.optBoolean("success")) return null
        val d = root.optJSONObject("data") ?: return null
        val url = d.optString("url")
        if (url.isBlank()) return null
        val mime = d.optString("mime_type", "audio/flac").ifBlank { "audio/flac" }
        // The public backend reports success for lossless tiers while serving
        // its MP3 copy (fmt=5). Never accept a non-FLAC file for a lossless
        // request — it would badge as FLAC yet sound byte-identical anyway.
        if (quality != VxQuality.MP3_320 && !mime.contains("flac", ignoreCase = true)) return null
        val formatId = d.optInt("format_id", 6)
        val bitDepth = d.optInt("bit_depth", 16).takeIf { it > 0 } ?: 16
        val sampleRate = d.optDouble("sampling_rate", 44.1).takeIf { it > 0 } ?: 44.1
        val dur = d.optInt("duration", 0).takeIf { it > 0 } ?: c.duration
        val bitrate = when (formatId) {
            VxQuality.MAX_HI_RES -> ((bitDepth * sampleRate * 2).toInt())
            VxQuality.HI_RES_96 -> ((bitDepth * sampleRate * 2).toInt())
            VxQuality.CD_LOSSLESS -> 1411
            VxQuality.MP3_320 -> 320
            else -> null
        }
        return VxStream(
            url = url,
            mimeType = mime,
            bitDepth = bitDepth,
            samplingRate = sampleRate,
            formatId = formatId,
            bitrateKbps = bitrate,
            trackId = c.id,
            durationSeconds = dur,
        )
    }

    // ------------------------------------------------------------ matching (ported from VxMusic)

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val MULTI_SPACE = Regex("\\s+")
    private val TOPIC_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
    private val PIPE_NOISE = Regex("""\s*\|.*$""")
    private val FEAT_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring)\s+.*$""")
    private val BRACKET_NOISE = Regex("""(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+))\s*[\])]""")
    private val TRAIL_NOISE = Regex("""(?i)\s*[-–—]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song)\s*$""")
    private val ARTIST_NOISE = setOf("the", "and", "feat", "ft", "featuring", "with", "x", "topic")
    private val VARIANT_PATTERNS = listOf(
        "live" to Regex("\\blive\\b"),
        "acoustic" to Regex("\\bacoustic\\b"),
        "karaoke" to Regex("\\bkaraoke\\b"),
        "instrumental" to Regex("\\binstrumental\\b"),
        "tribute" to Regex("\\btribute\\b"),
        "cover" to Regex("\\bcover\\b"),
        "remix" to Regex("\\bremix(?:ed)?\\b"),
        "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
        "demo" to Regex("\\bdemo\\b"),
        "slowed" to Regex("\\bslowed\\b"),
        "reverb" to Regex("\\breverb\\b"),
        "sped-up" to Regex("\\bsped up\\b"),
        "nightcore" to Regex("\\bnightcore\\b"),
    )

    private fun cleanForSearch(raw: String): String {
        var s = raw.replace(TOPIC_SUFFIX, "").replace(PIPE_NOISE, "")
            .replace(FEAT_CLAUSE, " ").replace(BRACKET_NOISE, " ").replace(TRAIL_NOISE, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeText(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(DIACRITICS, "").lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ").replace(MULTI_SPACE, " ").trim()

    private fun normalizeTitle(raw: String, artist: String): String {
        var c = cleanForSearch(raw)
        if (artist.isNotBlank()) {
            val ca = cleanForSearch(artist).ifBlank { artist }
            c = c.replaceFirst(Regex("^\\s*" + Regex.escape(ca) + "\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE), "")
            c = c.replace(Regex("(?i)\\s*[-–—:]\\s*" + Regex.escape(ca) + "\\s*$"), "")
        }
        return normalizeText(c)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun identityVariants(raw: String, artist: String): Set<String> {
        val s = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("^\\s*" + Regex.escape(artist) + "\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE), ""
        )
        val n = normalizeText(s)
        return VARIANT_PATTERNS.mapNotNullTo(LinkedHashSet()) { (name, re) -> name.takeIf { re.containsMatchIn(n) } }
    }

    private fun verifiedMatchScore(c: Candidate, title: String, artist: String, dur: Int?, album: String?): Int? {
        val matchArtist = cleanForSearch(artist).ifBlank { artist }
        val targetTitle = normalizeTitle(title, matchArtist)
        val candTitle = normalizeTitle(c.title, matchArtist)
        if (targetTitle.isBlank()) return null
        val primaries = listOf(c.performer, c.albumArtist).map(::normalizeText).filter { it.isNotBlank() }
        val targetArtists = matchArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText).filter { it.isNotBlank() }
        val artistExact = primaries.any { id -> targetArtists.any { ta -> id == ta } }
        val dist = levenshtein(targetTitle, candTitle)
        val exact = targetTitle == candTitle
        val maxFuzz = (targetTitle.length / 5).coerceIn(1, 2)
        val fuzzy = artistExact && dist <= maxFuzz
        if (!exact && !fuzzy) return null
        if (identityVariants(title, matchArtist) != identityVariants(c.title, matchArtist)) return null
        if (!artistMatch(matchArtist, c)) return null
        val durDiff = if (dur != null && dur > 0) {
            if (c.duration <= 0) return null
            kotlin.math.abs(c.duration - dur).also { if (it > MAX_DURATION_DIFF) return null }
        } else null
        var score = 1000 - dist * 50
        if (artistExact) score += 300
        if (!album.isNullOrBlank() && normalizeTitle(album, "") == normalizeTitle(c.album, "")) score += 120
        if (durDiff != null) score += (MAX_DURATION_DIFF - durDiff) * 10
        return score
    }

    private fun artistMatch(targetArtist: String, c: Candidate): Boolean {
        val target = normalizeText(targetArtist)
        if (target.isBlank()) return false
        val ids = listOf(c.performer, c.albumArtist).map(::normalizeText).filter { it.isNotBlank() }
        if (ids.any { it == target }) return true
        val parts = targetArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText).filter { it.isNotBlank() }
        if (ids.any { id -> parts.any { ta -> id == ta } }) return true
        val targetTokens = target.split(' ').filter { it !in ARTIST_NOISE }.toSet()
        if (targetTokens.isEmpty()) return false
        if (ids.any { id -> targetTokens.all(id.split(' ').toSet()::contains) }) return true
        return targetTokens.all((ids.flatMap { it.split(' ') }).toSet()::contains)
    }
}
