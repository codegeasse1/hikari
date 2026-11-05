package com.hikari.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The real country flag image for an ISO code from [ProviderFlags.codeOf]
 * ("in" → 🇮🇳): the reference provider list's flags, drawn from a flag CDN
 * with an emoji fallback when offline or when the fetch fails, so a row never
 * shows a blank gap where its flag belongs.
 */
@Composable
fun FlagIcon(
    code: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var failed by remember(code) { mutableStateOf(false) }
    if (!failed) {
        AsyncImage(
            model = "https://flagcdn.com/w80/${code.lowercase()}.png",
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = modifier.size(size),
        )
    } else {
        Text(
            text = isoToEmoji(code),
            fontSize = (size.value * 0.85f).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = modifier.size(size),
        )
    }
}

/** Regional-indicator emoji for a 2-letter ISO code ("in" → 🇮🇳). */
private fun isoToEmoji(code: String): String {
    val upper = code.trim().uppercase()
    if (upper.length != 2 || !upper.all { it in 'A'..'Z' }) return "🏳"
    return String(Character.toChars(0x1F1E6 + (upper[0] - 'A'))) +
        String(Character.toChars(0x1F1E6 + (upper[1] - 'A')))
}
