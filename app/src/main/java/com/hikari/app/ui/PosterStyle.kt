package com.hikari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.Ratings
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The visual treatments a poster card can wear — Settings → App Layout →
 * Poster styling → Poster effect.
 *
 * These are the "signature" card styles the reference clients are known for,
 * built from nothing but what a card already draws: the artwork it is loading,
 * the accent colour, and one animation clock. None of them costs an extra
 * network request or a second copy of the image, so switching one on is instant
 * and works offline over a cached poster.
 *
 *  - [GLOW]      the coloured halo behind the art, pushed harder than the blur
 *                slider's quiet default — this is the reference client's look.
 *  - [TILT]      the card leans back in 3D, as if laid on a table seen from the
 *                side, with a matching highlight.
 *  - [SHEEN]     a band of light sweeps across the art every couple of seconds,
 *                the way a glossy print does when you tilt it.
 *  - [AURA]      a breathing accent-gradient ring around the card.
 *  - [SPOTLIGHT] an accent spotlight behind the card plus a bottom scrim, so
 *                the artwork reads as lit from the page rather than pasted on.
 *  - [FRAME]     a gallery mat: the art inset behind a hairline frame with a
 *                soft accent tint, like a print in a mount.
 */
object PosterEffects {
    const val NONE = "none"
    const val GLOW = "glow"
    const val TILT = "tilt"
    const val SHEEN = "sheen"
    const val AURA = "aura"
    const val SPOTLIGHT = "spotlight"
    const val FRAME = "frame"

    /** Every effect, in the order Settings lists them. */
    val ALL = listOf(NONE, GLOW, TILT, SHEEN, AURA, SPOTLIGHT, FRAME)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else NONE

    fun label(key: String): String = when (normalize(key)) {
        GLOW -> "Glow"
        TILT -> "3D tilt"
        SHEEN -> "Sheen"
        AURA -> "Aura ring"
        SPOTLIGHT -> "Spotlight"
        FRAME -> "Gallery frame"
        else -> "None"
    }

    /** One line on what the effect does, shown under its name in Settings. */
    fun description(key: String): String = when (normalize(key)) {
        GLOW -> "Soft coloured halo around the artwork"
        TILT -> "Cards lean back in 3D with a highlight"
        SHEEN -> "A band of light sweeps across the art"
        AURA -> "A breathing accent ring around the card"
        SPOTLIGHT -> "Accent spotlight behind, scrim over the bottom"
        FRAME -> "Art inset behind a hairline gallery frame"
        else -> "Plain artwork, no effect"
    }

    /** True when this effect animates (so the card runs an animation clock). */
    fun animated(key: String): Boolean = normalize(key) == SHEEN || normalize(key) == AURA

    /** True when this effect wants the blurred halo behind the art. */
    fun haloed(key: String): Boolean = normalize(key) == GLOW || normalize(key) == SPOTLIGHT
}

/**
 * The user's poster styling — Settings → App Layout → Poster styling.
 *
 * These are the knobs the reference client offers on its artwork: the dynamic
 * iOS-style blur behind a poster, the corner rounding, whether the title and the
 * score are drawn over the art at all, and which of the [PosterEffects] the card
 * wears. Every grid in the app reads them from [rememberPosterStyle] and renders
 * through [PosterArt], so a change in Settings is visible on the very next frame
 * everywhere at once.
 */
data class PosterStyle(
    /** Blur radius in dp for the coloured halo behind the art (0 = none). */
    val blur: Int = 0,
    /** Corner rounding in dp. */
    val corner: Int = 14,
    /** Draw the title under the poster. */
    val showTitles: Boolean = true,
    /** Draw the score badge on the poster (only where a score is known). */
    val showRatings: Boolean = false,
    /** The glass hairline + frosted backing every card in the app shares. */
    val glass: Boolean = true,
    /** One of [PosterEffects] — the signature look drawn over the card. */
    val effect: String = PosterEffects.NONE,
)

/** The live poster style, collected from the store. Remembered flows, so the
 *  subscriptions survive recomposition. */
@Composable
fun rememberPosterStyle(): PosterStyle {
    val app = LocalContext.current.applicationContext as HikariApp
    val blurFlow = remember { app.store.posterBlurFlow() }
    val cornerFlow = remember { app.store.posterCornerFlow() }
    val titlesFlow = remember { app.store.posterShowTitlesFlow() }
    val ratingsFlow = remember { app.store.posterShowRatingsFlow() }
    val glassFlow = remember { app.store.posterGlassFlow() }
    val effectFlow = remember { app.store.posterEffectFlow() }
    val blur by blurFlow.collectAsState(initial = 0)
    val corner by cornerFlow.collectAsState(initial = 14)
    val titles by titlesFlow.collectAsState(initial = true)
    val ratings by ratingsFlow.collectAsState(initial = false)
    val glass by glassFlow.collectAsState(initial = true)
    val effect by effectFlow.collectAsState(initial = PosterEffects.NONE)
    return PosterStyle(
        blur = blur.coerceIn(0, 24),
        corner = corner.coerceIn(0, 28),
        showTitles = titles,
        showRatings = ratings,
        glass = glass,
        effect = PosterEffects.normalize(effect),
    )
}

/** The rounded shape a poster at [style] uses. */
fun PosterStyle.shape(): RoundedCornerShape = RoundedCornerShape(corner.dp)

/**
 * The score a poster's badge should print for [item], or null when the badges
 * are switched off or nothing is known about the title yet.
 *
 * This is the ONE place a poster grid asks for a score, so every grid warms and
 * repaints identically: the cell registers itself for a background lookup
 * ([Ratings.ensure] — cheap once the title is on file), reads the revision so
 * only the cells showing that title repaint when the answer lands
 * ([Ratings.revision]), and prints whatever the cache holds
 * ([Ratings.cachedBadge] — IMDb first, then TMDB's own average). A grid that
 * only did the cache read showed a score for the titles the user had already
 * opened and a blank corner for the rest.
 *
 * The item's own TMDB average ([MediaItem.rating], carried by every TMDB-sourced
 * row) is the last fallback, so a row that came in with scores prints them on
 * the first frame instead of waiting for a lookup.
 */
@Composable
fun rememberPosterScore(item: MediaItem, style: PosterStyle): String? {
    if (!style.showRatings) return null
    LaunchedEffect(item.uniqueId) {
        // The warm-up queue is bounded, and a Home feed composes more posters
        // than it will take at once; a refused title used to stay bare for the
        // life of its cell, because nothing asks twice. Ask again until it is
        // taken — a refused call is one memory read, and once the title is on
        // file (score or scoreless answer) the first call already returns.
        var attempt = 0
        while (attempt < 14 && !Ratings.ensure(item)) {
            attempt++
            delay(900L + attempt * 500L)
        }
    }
    Ratings.revision(item)
    return Ratings.cachedBadge(item)
        ?: item.rating?.takeIf { it > 0.0 }
            ?.let { ((it * 10f).roundToInt() / 10f).toString() }
}

/**
 * One poster cell, styled.
 *
 * This is the single place poster styling is applied, so every grid — Home's
 * rows, Show All, Search, Library, a collection's TMDB source — looks the same
 * and honours the same settings.
 *
 * The "iOS-style blur" is the artwork itself, enlarged a touch and blurred,
 * sitting behind the crisp poster as a soft coloured halo: that is what gives
 * the reference client's cards their glow, and it costs one extra draw of an
 * image the card is loading anyway. `Modifier.blur` is a no-op below Android 12
 * (its `RenderEffect` does not exist there), which is why the toggle simply
 * changes nothing on an old device rather than failing.
 *
 * @param model   the Coil model (see [Artwork.model])
 * @param rating  TMDB score to badge, when [PosterStyle.showRatings] is on and
 *                the item actually has one
 * @param imdb    the score already on file for this title ([Ratings] — the IMDb
 *                number when one could be resolved, otherwise TMDB's average),
 *                which wins over [rating] when present — it is the number the
 *                score badge is *for*, and the one the reference client prints
 * @param overlay extra content on top of the art (a heart badge, a kebab menu…)
 */
@Composable
fun PosterArt(
    model: Any?,
    contentDescription: String?,
    style: PosterStyle,
    modifier: Modifier = Modifier,
    rating: Double? = null,
    imdb: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    /** Corner the score badge sits in. Overridable because a card that already
     *  puts its own buttons in the top-right (Library's favourites) needs the
     *  badge somewhere else rather than on top of them. */
    ratingAlignment: Alignment = Alignment.TopEnd,
    /** Where the artwork itself sits inside its frame. [Alignment.TopCenter] is
     *  what the personal-catalog cover tiles ask for: a phone photo used as a
     *  poster keeps its top rather than being cut in the middle. */
    imageAlignment: Alignment = Alignment.Center,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = style.shape()
    val glass = rememberGlassTokens()
    val effect = PosterEffects.normalize(style.effect)
    // One animation clock per card, created only for the two effects that
    // animate. The animated value is read INSIDE graphicsLayer, never in
    // composition, so a moving sheen or a breathing ring invalidates the
    // card's drawing layer and nothing else — no recomposition per frame.
    val clock = if (PosterEffects.animated(effect)) {
        rememberInfiniteTransition(label = "poster-effect")
    } else {
        null
    }
    val sweep = if (clock != null) {
        clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "sweep",
        )
    } else {
        null
    }
    val breathe = if (clock != null) {
        clock.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val accentAlt = MaterialTheme.colorScheme.tertiary
    // Glow/Spotlight ask for a halo regardless of the blur slider; the other
    // effects leave the slider's own value alone.
    val halo = if (PosterEffects.haloed(effect)) maxOf(style.blur, 16) else style.blur
    val innerCorner = (style.corner - 3).coerceAtLeast(0)
    // IMDb's own yellow, not the app accent: the badge is a score from that
    // site, and the colour is what makes "9.8" read as an IMDb rating at a
    // glance instead of as one more piece of the app's chrome.
    // The cached score first (IMDb, else TMDB — see Ratings.cachedBadge), then
    // the row's own TMDB value: three chances at a number, so a poster that
    // carries any score at all prints one instead of an empty corner.
    val badge = imdb?.takeIf { it.isNotBlank() }
        ?: rating?.takeIf { it > 0.0 }?.let { ((it * 10f).roundToInt() / 10f).toString() }
    Box(
        modifier = modifier.then(
            if (effect == PosterEffects.TILT) {
                Modifier.graphicsLayer {
                    rotationY = -7f
                    rotationX = 5f
                    // Camera close to the card: a small rotation then reads as a
                    // real lean rather than a squash.
                    cameraDistance = 14f * density
                }
            } else {
                Modifier
            }
        )
    ) {
        if (effect == PosterEffects.SPOTLIGHT) {
            // The accent light the card is standing in, larger than the card so
            // it spills onto the page.
            Box(
                Modifier
                    .matchParentSize()
                    .scale(1.20f)
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.40f), Color.Transparent)),
                        shape,
                    )
            )
        }
        if (halo > 0) {
            PosterImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    // Grown past the cell so the halo peeks out around the art
                    // instead of being hidden behind it.
                    .scale(1.08f)
                    .blur(halo.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .alpha(0.78f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (style.glass) Modifier.border(1.dp, glass.border, shape) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Behind the artwork: a cell whose image 403s/404s still reads as a
            // poster slot instead of a blank rectangle.
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(28.dp),
            )
            PosterImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = imageAlignment,
            )
            if (effect == PosterEffects.SPOTLIGHT) {
                // Darkens the foot of the art, which is what makes the card look
                // lit from above instead of uniformly flat.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            )
                        )
                )
            }
            if (effect == PosterEffects.FRAME) {
                // A whisper of accent tint over the art, then the mount itself.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.14f), Color.Transparent, accentAlt.copy(alpha = 0.10f))
                            )
                        )
                )
            }
            if (effect == PosterEffects.SHEEN && sweep != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationX = -size.width + sweep.value * size.width * 2f
                            rotationZ = 16f
                        }
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.17f), Color.Transparent)
                            )
                        )
                )
            }
            if (style.showRatings && badge != null) {
                RatingBadge(
                    text = badge,
                    modifier = Modifier
                        .align(ratingAlignment)
                        .padding(5.dp),
                )
            }
            overlay()
        }
        if (effect == PosterEffects.FRAME) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(4.dp)
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(innerCorner.dp),
                    )
            )
        }
        if (effect == PosterEffects.AURA && breathe != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = breathe.value }
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(accent, accentAlt, accent)),
                        shape,
                    )
            )
        }
    }
}

/**
 * The score chip: IMDb's yellow number on a dark translucent pill so it stays
 * readable over any artwork. Deliberately SMALL and tucked into the poster's
 * top-right corner — a grid is for the art, and a score the size of the title
 * would be the loudest thing on the page.
 */
@Composable
fun RatingBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF5C518),
            maxLines = 1,
        )
    }
}
