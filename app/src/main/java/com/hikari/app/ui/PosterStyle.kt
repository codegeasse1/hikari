package com.hikari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlin.math.roundToInt

/**
 * The user's poster styling — Settings → Appearance → Poster & icons.
 *
 * These are the knobs the reference client offers on its artwork: the dynamic
 * iOS-style blur behind a poster, the corner rounding, and whether the title
 * and the score are drawn over the art at all. Every grid in the app reads them
 * from [rememberPosterStyle] and renders through [PosterArt], so a change in
 * Settings is visible on the very next frame everywhere at once.
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
    val blur by blurFlow.collectAsState(initial = 0)
    val corner by cornerFlow.collectAsState(initial = 14)
    val titles by titlesFlow.collectAsState(initial = true)
    val ratings by ratingsFlow.collectAsState(initial = false)
    val glass by glassFlow.collectAsState(initial = true)
    return PosterStyle(
        blur = blur.coerceIn(0, 24),
        corner = corner.coerceIn(0, 28),
        showTitles = titles,
        showRatings = ratings,
        glass = glass,
    )
}

/** The rounded shape a poster at [style] uses. */
fun PosterStyle.shape(): RoundedCornerShape = RoundedCornerShape(corner.dp)

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
 * @param overlay extra content on top of the art (a heart badge, a kebab menu…)
 */
@Composable
fun PosterArt(
    model: Any?,
    contentDescription: String?,
    style: PosterStyle,
    modifier: Modifier = Modifier,
    rating: Double? = null,
    contentScale: ContentScale = ContentScale.Crop,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = style.shape()
    val glass = rememberGlassTokens()
    Box(modifier = modifier) {
        if (style.blur > 0) {
            PosterImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    // Grown past the cell so the halo peeks out around the art
                    // instead of being hidden behind it.
                    .scale(1.08f)
                    .blur(style.blur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .alpha(0.75f),
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
            )
            if (style.showRatings && rating != null && rating > 0.0) {
                RatingBadge(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                )
            }
            overlay()
        }
    }
}

/** The score chip: one decimal, a star, on a dark translucent pill so it stays
 *  readable over any artwork. */
@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            tint = Color(0xFFFFC94D),
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            ((rating * 10f).roundToInt() / 10f).toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
