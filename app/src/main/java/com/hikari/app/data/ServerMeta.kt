package com.hikari.app.data

/**
 * Best-effort server-row details for EVERY engine.
 *
 * Torrentio/Stremio rows carry a rich `details` blob (quality, codec, size,
 * seeders). Most other providers send only a name ("Purstream | hd |
 * Dual-Audio") and blank details. This object never invents a fact: it parses
 * what the name/details/url already say (resolution, HDR/DV, codec, audio
 * language, file size) into one uniform line, and merges it with the
 * provider's own details without duplicating tokens.
 */
object ServerMeta {
    private val sizeRe = Regex("""(\d+(?:\.\d+)?\s*(?:GB|MB))""", RegexOption.IGNORE_CASE)
    private val seedRe = Regex("""(👥\s*\d+|⛁\s*\d+|\b\d+\s*seeders?\b)""", RegexOption.IGNORE_CASE)

    private fun qualityOf(text: String): String? {
        val t = text
        if (Regex("""(^|[^a-z0-9])(4k|2160p|uhd)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "4K"
        if (Regex("""(^|[^0-9])1080p""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "1080p"
        if (Regex("""\bFHD\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "1080p"
        if (Regex("""(^|[^0-9])720p""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "720p"
        if (Regex("""(^|[^a-z0-9])hd([^a-z0-9]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "HD"
        if (Regex("""(^|[^0-9])480p""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "480p"
        if (Regex("""(^|[^0-9])360p""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "360p"
        if (Regex("""(^|[^a-z0-9])(hd)?cam([^a-z0-9]|$)|predvd""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "CAM"
        return null
    }

    private fun hdrOf(text: String): String? {
        if (Regex("""dolby.?vision|([^a-z0-9]dv[^a-z0-9])""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "DV"
        if (Regex("""(^|[^a-z0-9])hdr(10\+?)?([^a-z0-9]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "HDR"
        return null
    }

    private fun codecOf(text: String): String? {
        if (Regex("""hevc|h\.?265|x265""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "HEVC"
        if (Regex("""\bav1\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "AV1"
        if (Regex("""h\.?264|x264|\bavc\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "AVC"
        return null
    }

    private val langs = listOf(
        "hindi", "english", "tamil", "telugu", "malayalam", "kannada",
        "bengali", "marathi", "punjabi", "gujarati", "urdu", "french",
        "spanish", "german", "arabic", "japanese", "korean", "original"
    )

    private fun audioOf(text: String): String? {
        val low = text.lowercase()
        if ("multi" in low && "audio" in low) return "Multi-Audio"
        if ("dual" in low && "audio" in low) return "Dual-Audio"
        for (l in langs) {
            if (Regex("""(^|[^a-z])${Regex.escape(l)}([^a-z]|$)""").containsMatchIn(low)) {
                return l.replaceFirstChar { it.uppercase() }
            }
        }
        if (Regex("""(^|[^a-z])dubbed?([^a-z]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Dub"
        if (Regex("""(^|[^a-z])subbed?([^a-z]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Sub"
        return null
    }

    private fun containsToken(haystack: String, token: String): Boolean {
        if (token.isBlank()) return true
        return haystack.contains(token, ignoreCase = true)
    }

    /**
     * The uniform details line for [name]+[details] (no host — the caller
     * prepends it). Returns "" when nothing is known. Never invents size /
     * codec: only what the texts already state.
     */
    fun enrichedDetails(name: String, details: String): String {
        val base = details.trim()
        val joint = "$name $base"
        val parts = mutableListOf<String>()
        qualityOf(joint)?.let { if (!containsToken(base, it) && !containsToken(base, qualityAlias(it, base))) parts.add(it) }
        hdrOf(joint)?.let { if (!containsToken(base, it)) parts.add(it) }
        codecOf(joint)?.let { if (!containsToken(base, it)) parts.add(it) }
        audioOf(joint)?.let {
            if (!containsToken(base, it) && !containsToken(base, it.substringBefore("-"))) parts.add(it)
        }
        sizeRe.find(joint)?.let { m ->
            val size = m.groupValues[1].trim().uppercase().replace("\\s+".toRegex(), " ")
            if (!containsToken(base, size) && !sizeRe.containsMatchIn(base)) parts.add(size)
        }
        if (base.isBlank()) return parts.joinToString(" • ")
        if (parts.isEmpty()) return base
        // Seeder lines (Torrentio) already multi-line — append extras on one line.
        return if (base.contains("\n")) "$base • ${parts.joinToString(" • ")}" else "$base • ${parts.joinToString(" • ")}"
    }

    private fun qualityAlias(q: String, base: String): String = when (q) {
        "4K" -> if (base.contains("2160", true)) "2160" else "4K-UNLIKELY"
        "1080p" -> if (base.contains("FHD", true)) "FHD" else "1080p-UNLIKELY"
        "HD" -> if (base.contains("720", true)) "720" else "HD-UNLIKELY"
        else -> q
    }

    /** True when the row advertises more than one audio language inside one stream. */
    fun isMultiAudio(name: String, details: String): Boolean {
        val joint = "$name $details".lowercase()
        return ("multi" in joint && "audio" in joint) || ("dual" in joint && "audio" in joint)
    }

    /** Seeder fragment if the texts carry one (Torrentio-style), else null. */
    fun seederOf(name: String, details: String): String? =
        seedRe.find("$name $details")?.groupValues?.getOrNull(1)?.trim()
}
