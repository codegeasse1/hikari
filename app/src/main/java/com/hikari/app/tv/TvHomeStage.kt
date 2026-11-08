package com.hikari.app.tv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.data.MediaItem
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.components.PosterImage
import kotlinx.coroutines.delay

/**
 * The Nuvio-style television Home.
 *
 *   ┌ header bar (provider pill stays here) ─────────────────────┐
 *   │ TITLE / logo                                  backdrop art │
 *   │ date | genres | ★ rating                      (fixed,      │
 *   │ overview ...                                   full-bleed) │
 *   │ Row title                                                  │
 *   │ [poster][poster][ FOCUSED CARD expands ][poster]           │
 *   │ next row peeks and fades out ...                           │
 *   └────────────────────────────────────────────────────────────┘
 *
 * The backdrop and the info text are FIXED: they do not scroll with the rows.
 * They follow whichever card the remote is on (see [TvHomeFocus]).
 */

/** The card the remote is currently on, for the fixed hero to describe. */
@Stable
class TvHomeFocus {
    var item by mutableStateOf<MediaItem?>(null)
}

/**
 * Holds back a fast-changing [item] until it has been steady for a moment, so
 * walking quickly along a row does not thrash the full-screen backdrop and the
 * TMDB lookups. The very first item shows immediately. [resetKey] drops the
 * held item (a provider switch must not keep the previous provider's art).
 */
@Composable
fun rememberTvSettled(item: MediaItem?, resetKey: Any? = null): MediaItem? {
    var settled by remember(resetKey) { mutableStateOf(item) }
    LaunchedEffect(item?.uniqueId, resetKey) {
        if (item == null) return@LaunchedEffect
        if (settled == null) {
            settled = item
        } else {
            delay(140)
            settled = item
        }
    }
    return settled
}

object TvHomeMetrics {
    /** Height of the fixed info block (title, meta line, overview) under the
     *  header bar. The rows start right below it. */
    @Composable
    fun infoHeight(): Dp {
        val h = LocalConfiguration.current.screenHeightDp
        return (h * 0.34f).coerceIn(150f, 300f).dp
    }
}

private class TvStageArt(val model: Any?, val wide: Boolean)

/**
 * Full-bleed backdrop behind the whole Home: the focused title's own TMDB
 * backdrop (provider art until it lands), cross-faded when the title changes,
 * with the scrims that keep the info text and the rows readable.
 */
@Composable
fun TvHomeBackdropLayer(item: MediaItem?, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxSize().background(bg)) {
        if (item != null) {
            var tmdbArt by remember(item.uniqueId) { mutableStateOf<String?>(null) }
            LaunchedEffect(item.uniqueId) {
                tmdbArt = runCatching {
                    com.hikari.app.data.TmdbScreenshots.backdropsFor(item).firstOrNull()
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            val tmdb = tmdbArt
            val art = remember(item.uniqueId, tmdb) {
                if (tmdb != null) {
                    TvStageArt(
                        com.hikari.app.ui.PosterLoader.model(tmdb.replace("/w1280/", "/original/")),
                        true,
                    )
                } else {
                    val hero = Artwork.tvHeroModel(item)
                    TvStageArt(hero.first, hero.second)
                }
            }
            Crossfade(targetState = art, label = "tvHomeArt") { a ->
                Cs3BackdropArt(
                    model = a.model,
                    wide = a.wide,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Light glass dim over the art.
        Box(Modifier.fillMaxSize().background(bg.copy(alpha = 0.22f)))
        // Left scrim: the info text's backdrop.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to bg.copy(alpha = 0.80f),
                        0.50f to bg.copy(alpha = 0.34f),
                        0.80f to Color.Transparent,
                    )
                )
        )
        // Bottom scrim: the rows sit on a calm dark area, the art stays above.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.40f to bg.copy(alpha = 0.30f),
                        0.62f to bg.copy(alpha = 0.86f),
                        1f to bg,
                    )
                )
        )
    }
}

/**
 * The fixed info block: wordmark (or title), "year | genres | ★ rating" and the
 * overview — bottom-aligned inside the block the caller sizes with
 * [TvHomeMetrics.infoHeight], so it sits right above the first row.
 */
@Composable
fun TvHomeHeroInfo(item: MediaItem?, modifier: Modifier = Modifier) {
    val ts = TvUi.scale()
    var logo by remember(item?.uniqueId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.uniqueId) {
        val current = item ?: return@LaunchedEffect
        logo = runCatching { com.hikari.app.data.TmdbMeta.logo(current) }.getOrNull()
    }
    Column(
        modifier = modifier
            .fillMaxWidth(0.56f)
            .padding(start = 20.dp, end = 12.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item != null) {
            val logoUrl = logo
            if (!logoUrl.isNullOrBlank()) {
                PosterImage(
                    model = logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .heightIn(max = 72.dp),
                )
            } else {
                Text(
                    item.title.uppercase(),
                    color = Color.White,
                    fontSize = 30.sp * ts,
                    lineHeight = 36.sp * ts,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            val genreLine = item.genres.take(3).joinToString(" / ")
            val parts = listOfNotNull(item.year?.toString(), genreLine.takeIf { it.isNotBlank() })
            val rating = item.rating?.takeIf { it > 0 }
            if (parts.isNotEmpty() || rating != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    parts.forEachIndexed { i, part ->
                        if (i > 0) TvHeroDivider()
                        Text(
                            part,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp * ts,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (i == parts.lastIndex) Modifier.weight(1f, fill = false) else Modifier,
                        )
                    }
                    if (rating != null) {
                        if (parts.isNotEmpty()) TvHeroDivider()
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFF5C518))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "\u2605 " + "%.1f".format(rating),
                                color = Color.Black,
                                fontSize = 12.sp * ts,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            val overview = item.overview?.trim().orEmpty()
            if (overview.isNotEmpty()) {
                Text(
                    overview,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 15.sp * ts,
                    lineHeight = 21.sp * ts,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvHeroDivider() {
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(14.dp)
            .background(Color.White.copy(alpha = 0.5f))
    )
}

/** The soft fade at the bottom edge: the next row peeks in and melts away. */
@Composable
fun TvHomeBottomFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to bg.copy(alpha = 0.92f),
                )
            )
    )
}
