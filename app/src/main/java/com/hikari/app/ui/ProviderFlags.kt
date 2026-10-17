package com.hikari.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Country/language flags for extension rows — REAL flag images, not text.
 *
 * The image is the country's actual flag (flagcdn, w80 PNG) with the emoji
 * flag as the offline fallback, so a row never shows a broken tile. The
 * mapping reads what the repo listing (or the installed name) already says —
 * language words ("Hindi", "English", "Bangla", …), Bangladesh markers
 * ("BD", "Bangla", "Dhaka", "BDIX") and a small table of well-known
 * extension names whose country the name alone declares. Anything that names
 * no country gets NO flag — never guessed.
 */
object ProviderFlags {
    /** Real flag image for an ISO code ("BD" → flagcdn w80 PNG). */
    fun urlOf(code: String): String =
        "https://flagcdn.com/w80/" + code.lowercase() + ".png"

    /** Emoji flag for an ISO code — the offline fallback, never networked. */
    fun emojiOf(code: String): String {
        val up = code.uppercase()
        if (up.length != 2 || up.any { it !in 'A'..'Z' }) return "🏳"
        return String(Character.toChars(0x1F1E6 + (up[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (up[1] - 'A')))
    }

    private fun has(hay: String, vararg words: String): Boolean = words.any { w ->
        Regex("(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(hay)
    }

    // Well-known installed names whose country the name alone declares (the
    // reference provider list flags these: Emwbd/Cinefreak/Mlsbd/Banglaplex/
    // FibWatch/MovieLinkBD/BDIX on BD; Rtally/Movies4u/Castle/HDHub4U on IN;
    // UHDmovies/4K HDHUB on GB). Static name knowledge, not per-extension
    // behaviour — the rows play exactly as before either way.
    private fun aliasOf(hay: String): String? = when {
        "hdhub4u" in hay -> "IN"
        "4khdhub" in hay -> "GB"
        "uhdmovies" in hay -> "GB"
        "movies4u" in hay -> "IN"
        "rtally" in hay -> "IN"
        "castle tv" in hay || "castletv" in hay -> "IN"
        "cinefreak" in hay -> "BD"
        "fibwatch" in hay -> "BD"
        "emwbd" in hay -> "BD"
        "mlsbd" in hay -> "BD"
        "movielinkbd" in hay -> "BD"
        "banglaplex" in hay -> "BD"
        "bdix" in hay -> "BD"
        else -> null
    }

    /** (match words, ISO code, language label), highest priority first. */
    private val flagTable: List<Triple<List<String>, String, String>> = listOf(
        Triple(listOf("hindi", "hindi-audio", "bollywood"), "IN", "Hindi"),
        Triple(listOf("tamil"), "IN", "Tamil"),
        Triple(listOf("telugu"), "IN", "Telugu"),
        Triple(listOf("malayalam"), "IN", "Malayalam"),
        Triple(listOf("kannada"), "IN", "Kannada"),
        Triple(listOf("bengali"), "IN", "Bengali"),
        Triple(listOf("marathi", "punjabi", "gujarati", "desi"), "IN", "Desi"),
        Triple(listOf("english", "hollywood"), "GB", "English"),
        Triple(listOf("spanish", "espanol", "español", "latino"), "ES", "Spanish"),
        Triple(listOf("french", "francais", "français"), "FR", "French"),
        Triple(listOf("german", "deutsch"), "DE", "German"),
        Triple(listOf("italian"), "IT", "Italian"),
        Triple(listOf("portuguese", "brasileiro"), "BR", "Portuguese"),
        Triple(listOf("russian"), "RU", "Russian"),
        Triple(listOf("turkish"), "TR", "Turkish"),
        Triple(listOf("arabic"), "SA", "Arabic"),
        Triple(listOf("japanese", "anime"), "JP", "Japanese"),
        Triple(listOf("korean", "kdrama", "k-drama"), "KR", "Korean"),
        Triple(listOf("chinese", "donghua", "cdrama", "c-drama"), "CN", "Chinese"),
        Triple(listOf("indonesian", "indo"), "ID", "Indonesian"),
        Triple(listOf("vietnamese"), "VN", "Vietnamese"),
        Triple(listOf("thai"), "TH", "Thai"),
    )

    /**
     * (ISO code, language label) for the given texts, or null when nothing
     * names a country. Bangladesh is checked before the Indian languages:
     * "Bangla" is Bangladesh's own word while "Bengali" alone stays Indian.
     *
     * Two passes: whole-word matches first ("Hindi" in "Hindi Audio"), then
     * substring matches for longer tokens ("donghua" inside "DonghuaStream",
     * "anime" inside "AllAnime" — the word-boundary pass misses names that
     * glue the token to the rest). Short tokens stay word-only so "in" or
     * "bd" can never match inside an unrelated word. Anything that names no
     * country still gets NO flag — matched from real tokens only, never
     * guessed.
     */
    fun pairOf(vararg texts: String?): Pair<String, String>? {
        val hay = texts.filterNotNull().joinToString(" ").lowercase()
        if (hay.isBlank()) return null
        aliasOf(hay)?.let { code ->
            return code to when (code) {
                "BD" -> "Bangla"
                "GB" -> "English"
                else -> "Desi"
            }
        }
        if (has(hay, "bangladesh", "bangla", "dhaka") ||
            Regex("""bd([^a-z]|$)""").containsMatchIn(hay)
        ) return "BD" to "Bangla"
        for ((words, code, label) in flagTable) {
            if (words.any { has(hay, it) }) return code to label
        }
        for ((words, code, label) in flagTable) {
            if (words.any { it.length >= 4 && it in hay }) return code to label
        }
        return null
    }

    /** ISO country code for the given texts, or null when none is named. */
    fun codeOf(vararg texts: String?): String? = pairOf(*texts)?.first

    /**
     * "🇮🇳 Hindi"-style label for the given texts, or null when none is
     * named — the same strings the extension rows always printed.
     */
    fun flagOf(vararg texts: String?): String? {
        val (code, label) = pairOf(*texts) ?: return null
        return emojiOf(code) + " " + label
    }
}

/**
 * A real flag image ([ProviderFlags.urlOf]) with the emoji flag as the
 * offline fallback, so the row shows a flag with or without network.
 */
@Composable
fun FlagIcon(
    code: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var failed by remember(code) { mutableStateOf(false) }
    if (failed) {
        Text(
            ProviderFlags.emojiOf(code),
            fontSize = (size.value * 0.72f).sp,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = ProviderFlags.urlOf(code),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
}
