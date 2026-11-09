package com.hikari.app.player

import java.util.Locale

/**
 * The default video quality and audio language (Settings -> Player -> Default quality & audio)
 * and the rules the player uses to find them: in a stream's own tracks, and in the names of the
 * servers that ship one language each ("MovieBox (Hindi Audio) 1080p").
 *
 * Kept free of any player or Android state so the rules read on their own.
 */
object DefaultPlayback {

    /** Quality choices in pixels of height. 0 = Auto (adaptive): the player decides by itself. */
    val QUALITIES: List<Int> = listOf(0, 2160, 1440, 1080, 720, 480, 360, 240)

    fun qualityLabel(height: Int): String = if (height <= 0) "Auto (adaptive)" else "${height}p"

    /** Audio languages on offer: ISO 639-1 code to the English name. A blank code means "no default". */
    val AUDIO_LANGS: List<Pair<String, String>> = listOf(
        "hi" to "Hindi",
        "en" to "English",
        "ja" to "Japanese",
        "ta" to "Tamil",
        "te" to "Telugu",
        "ml" to "Malayalam",
        "kn" to "Kannada",
        "bn" to "Bengali",
        "mr" to "Marathi",
        "pa" to "Punjabi",
        "gu" to "Gujarati",
        "ur" to "Urdu",
        "ko" to "Korean",
        "zh" to "Chinese",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "it" to "Italian",
        "ar" to "Arabic",
        "tr" to "Turkish",
        "th" to "Thai",
        "id" to "Indonesian",
    )

    /** The English name of a language code, or null for a blank / unknown code. */
    fun audioName(code: String): String? = AUDIO_LANGS.firstOrNull { it.first == code }?.second

    /** What a settings row shows for a language code. */
    fun audioLabel(code: String): String = audioName(code) ?: "Stream default"

    private val ISO3 = mapOf(
        "hin" to "hi", "eng" to "en", "jpn" to "ja", "tam" to "ta", "tel" to "te",
        "mal" to "ml", "kan" to "kn", "ben" to "bn", "mar" to "mr", "pan" to "pa",
        "guj" to "gu", "urd" to "ur", "kor" to "ko", "zho" to "zh", "chi" to "zh",
        "spa" to "es", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de",
        "por" to "pt", "rus" to "ru", "ita" to "it", "ara" to "ar", "tur" to "tr",
        "tha" to "th", "ind" to "id",
    )

    /** The 2-letter code of a track's language tag ("hi", "hin", "hi-IN", "en_US"); "" when unknown. */
    fun codeOf(language: String?): String {
        val raw = language?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (raw.isEmpty() || raw == "und" || raw == "mul" || raw == "zxx") return ""
        val primary = raw.split('-', '_').first()
        return ISO3[primary] ?: primary
    }

    /** True when an audio track carrying [language] / [label] is the language [code]. */
    fun trackMatches(code: String, language: String?, label: String?): Boolean {
        if (code.isBlank()) return false
        if (codeOf(language) == code) return true
        val name = audioName(code) ?: return false
        return !label.isNullOrBlank() && label.contains(name, ignoreCase = true)
    }

    private val SUB_WORD = Regex("\\bsub(s|bed|title|titles)?\\b")

    /**
     * True when a server's audio tag ("Hindi Audio", "Hindi Dub") names the language [code].
     * "English Sub" does NOT count: that names the subtitles, not the voice.
     */
    fun tagMatches(code: String, tag: String?): Boolean {
        val name = audioName(code) ?: return false
        if (tag.isNullOrBlank() || !tag.contains(name, ignoreCase = true)) return false
        val low = tag.lowercase(Locale.ROOT)
        return !(SUB_WORD.containsMatchIn(low) && !low.contains("dub"))
    }

    /**
     * The quality height a rendition counts as. A wide-screen film is often stored cropped
     * (1920x800 is still the "1080p" release), so the width counts as well as the height.
     */
    fun effectiveHeight(width: Int, height: Int): Int {
        if (width <= 0) return height
        return maxOf(height, width * 9 / 16)
    }

    /**
     * Which of [heights] to use for [wanted]: the best one at or below it, and when everything is
     * above it the lowest one (the next one up). Entries <= 0 are ignored. Returns the index into
     * [heights], or -1 when [wanted] is Auto or nothing is usable.
     */
    fun pickQuality(heights: List<Int>, wanted: Int): Int {
        if (wanted <= 0) return -1
        var below = -1
        var above = -1
        for (i in heights.indices) {
            val h = heights[i]
            if (h <= 0) continue
            if (h <= wanted) {
                if (below < 0 || h > heights[below]) below = i
            } else {
                if (above < 0 || h < heights[above]) above = i
            }
        }
        return if (below >= 0) below else above
    }
}
