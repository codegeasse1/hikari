package com.hikari.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.ui.SourceLangs

/**
 * Short language code chip (EN · HI · BN · CN · JA · KO …) drawn next to an
 * engine's name — Extensions rows, the Select Language dialog, and the Manga
 * tab's engine cards all show the same chip from the same stored language,
 * so the row always agrees with its Source language group.
 *
 * Multi-language packs read `Multi`; rows with no known language draw
 * nothing rather than a wrong guess.
 */
@Composable
fun LangTag(code: String?, modifier: Modifier = Modifier) {
    val short = shortLangCode(code) ?: return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = modifier,
    ) {
        Text(
            short,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

/** The chip text for a language group code, or null when no chip is drawn. */
fun shortLangCode(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.trim().lowercase()
    if (c == SourceLangs.MULTI || c == "all") return "Multi"
    if (c == SourceLangs.OTHER) return null
    // The store uses ISO codes; readers ask for CN for Chinese.
    if (c == "zh") return "CN"
    val short = c.take(2).uppercase()
    if (short.length < 2 || !short.all { it.isLetter() }) return null
    return short
}
