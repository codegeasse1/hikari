package com.hikari.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class GlassTokens(
    /** Fill at the card's top edge. */
    val fillTop: Color,
    /** Fill at the card's bottom edge — a whisper darker. That tiny falloff is
     *  what makes a flat panel read as glass instead of paint. */
    val fillBottom: Color,
    /** The hairline that separates a card from the page. This is what the
     *  roundy-glass look is actually made of: without it, translucent cards on
     *  a near-black page turn into muddy rectangles. */
    val border: Color,
    /** True for the dark, glass and AMOLED themes. */
    val dark: Boolean,
)

@Composable
fun rememberGlassTokens(): GlassTokens {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        if (dark) {
            GlassTokens(
                fillTop = Color.White.copy(alpha = 0.095f),
                fillBottom = Color.White.copy(alpha = 0.045f),
                border = Color.White.copy(alpha = 0.115f),
                dark = true,
            )
        } else {
            GlassTokens(
                fillTop = Color.White,
                fillBottom = Color.White,
                border = Color.Black.copy(alpha = 0.08f),
                dark = false,
            )
        }
    }
}
