package com.hikari.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The one place Hikari's "roundy glass" card recipe lives.
 *
 * Every panel in the app (settings cards, extension cards, search field,
 * bottom bar) paints itself from these four values, so the whole UI reads as
 * one material: a translucent panel with a soft top-to-bottom falloff and a
 * 1px hairline edge, sitting on a near-black page with a faint accent glow
 * above it (drawn by AppRoot).
 *
 * Two reasons this is a single packed recipe instead of raw alphas at each call
 * site:
 *
 *  * it has to work on BOTH ends of the scale — on the dark/glass/AMOLED themes
 *    a card is a WHISPER of white over the page, while on the light theme the
 *    same card has to be a white panel with a soft grey edge;
 *  * the numbers are tuned together (fill vs. border vs. shadow); changing one
 *    without the others is what makes a glass UI look like dark grey boxes, so
 *    there is deliberately only one place to change them.
 *
 * [dark] is decided by the theme's own background luminance, which means a
 * future theme gets the right treatment automatically. Note the Dark Glass
 * theme's background is [Color.Transparent] — its luminance is 0, so it lands on
 * the dark side, which is correct: it is a glass theme.
 */
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
    // Built once per theme rather than once per card: this is called by every
    // panel in the app (settings cards, extensions, search field, the bottom
    // bar), i.e. hundreds of times per screen.
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
