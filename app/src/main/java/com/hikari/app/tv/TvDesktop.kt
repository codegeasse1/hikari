package com.hikari.app.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The desktop client's visual language, translated onto the television layout.
 *
 * Every value here mirrors that client's exact tokens: the Navy triple
 * (Background 0xFF0C0C16 / SurfaceCard 0xFF161624 / SurfaceElevated 0xFF20202E,
 * divider 0xFF2A2A38), white-on-navy text, the hairline border, 8dp corners
 * and the spacing scale. The ACCENT is the reference's own purple
 * (0xFF6C5CE7), hardcoded — one-to-one with the reference means the TV does
 * not follow the phone's Settings accent.
 *
 * TV-only overlay (tv/ package): the phone theme is untouched.
 */
object TvDesktop {

    /** Page background: the reference's exact Navy. */
    val Background: Color = Color(0xFF0C0C16)

    /** Card surface: the reference's exact card Navy. */
    val SurfaceCard: Color = Color(0xFF161624)

    /** Elevated surface: the reference's exact elevated Navy. */
    val SurfaceElevated: Color = Color(0xFF20202E)

    val TextPrimary: Color = Color.White
    val TextMuted: Color = Color.White.copy(alpha = 0.7f)

    /** Hairline divider/border. */
    val Divider: Color = Color(0xFF2A2A38)

    /** Glass hairline for floating bars and lit cards. */
    val GlassEdge: Color = Color.White.copy(alpha = 0.14f)

    /** The 8dp corner language (cards, thumbs, chips). */
    val CornerCard: Dp = 8.dp
    val CornerLarge: Dp = 12.dp
    val CornerPill: Dp = 24.dp

    /** The spacing scale. */
    val SpaceXS: Dp = 8.dp
    val SpaceS: Dp = 12.dp
    val SpaceM: Dp = 16.dp
    val SpaceL: Dp = 20.dp
    val SpaceXL: Dp = 24.dp

    /** 2:3 poster geometry, same as the reference. */
    const val PosterAspect: Float = 2f / 3f

    /** The reference's own purple accent, hardcoded for one-to-one TV. */
    val Purple: Color = Color(0xFF6C5CE7)

    /** The accent on TV (the reference purple, not the phone's setting). */
    val accent: Color
        @Composable get() = Purple
}

/**
 * The reference's hover treatment, translated to the remote: focus IS hover
 * on a television.
 *
 * A focused card grows slightly and gains an accent ring — the same two cues
 * the desktop client's hovered poster gives (its blurred glow + scale). The
 * glow is a crisp ring rather than a blur on purpose: a full-screen blur
 * repaints a bitmap these boxes cannot afford (see TvCinematicHero), while a
 * ring costs one stroked round-rect.
 */
@Composable
fun Modifier.tvFocusGlow(corner: Dp = TvDesktop.CornerCard): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tvFocusScale",
    )
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        label = "tvFocusRing",
    )
    val accent = TvDesktop.accent
    // No clip on the card itself: the corner curve would reach into the
    // title/year lines and bite their edges (the cards' own artwork boxes
    // already round themselves). The ring below carries its own shape.
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = 2.dp,
            color = accent.copy(alpha = 0.85f * ring),
            shape = RoundedCornerShape(corner),
        )
}
