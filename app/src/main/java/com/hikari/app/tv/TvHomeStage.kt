package com.hikari.app.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.hikari.app.data.MediaType
import com.hikari.app.i18n.tr
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.components.keptListState
import kotlinx.coroutines.delay

/** The card the remote is currently on, for the fixed hero to describe. */
@Stable
class TvHomeFocus {
    var item by mutableStateOf<MediaItem?>(null)
}

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
    @Composable
    fun infoHeight(): Dp {
        val h = LocalConfiguration.current.screenHeightDp
        return (h * 0.37f).coerceIn(168f, 290f).dp
    }
}

private class TvStageArt(val model: Any?, val wide: Boolean)

/**
 * Nuvio-style full-bleed backdrop: artwork anchored on the top-right with
 * smooth horizontal and vertical dark gradients so the left metadata and
 * bottom rows sit on a rich cinema stage, identical to the reference video.
 */
@Composable
fun TvHomeBackdropLayer(item: MediaItem?, modifier: Modifier = Modifier) {
    val bg = Color(0xFF090A0F)
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
                        PosterLoader.model(tmdb.replace("/w1280/", "/original/")),
                        true,
                    )
                } else {
                    val hero = Artwork.tvHeroModel(item)
                    TvStageArt(hero.first, hero.second)
                }
            }
            Crossfade(
                targetState = art,
                animationSpec = tween(380),
                label = "tvHomeArt",
            ) { a ->
                if (a.model != null) {
                    Box(Modifier.fillMaxSize()) {
                        PosterImage(
                            model = a.model,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            alignment = Artwork.CINEMA_ALIGNMENT,
                            modifier = Modifier
                                .fillMaxWidth(0.74f)
                                .fillMaxHeight(0.72f)
                                .align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
        // বাম থেকে ডানে স্মুথ ডার্ক গ্রেডিয়েন্ট (ভিডিওর মতো টেক্সট এরিয়া ডার্ক রাখবে)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to bg,
                        0.30f to bg.copy(alpha = 0.96f),
                        0.52f to bg.copy(alpha = 0.58f),
                        0.76f to bg.copy(alpha = 0.15f),
                        1.0f to Color.Transparent,
                    )
                )
        )
        // নিচ থেকে উপরে স্মুথ ডার্ক গ্রেডিয়েন্ট (যাতে নিচের রোগুলো পরিষ্কার ফুটে ওঠে)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to bg.copy(alpha = 0.35f),
                        0.18f to Color.Transparent,
                        0.46f to bg.copy(alpha = 0.32f),
                        0.66f to bg.copy(alpha = 0.92f),
                        1.0f to bg,
                    )
                )
        )
    }
}

/**
 * Video-identical Hero Info Block:
 * 1. Title / TMDB Logo
 * 2. Type • Genres • Year
 * 3. [TV-MA / R] [ONGOING / RELEASED] [IMDb] 8.0
 * 4. 3-line synopsis
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
            .padding(start = 18.dp, end = 12.dp, bottom = 6.dp),
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
                        .fillMaxWidth(0.68f)
                        .heightIn(max = 62.dp),
                )
            } else {
                Text(
                    text = item.title.uppercase(),
                    color = Color.White,
                    fontSize = 28.sp * ts,
                    lineHeight = 33.sp * ts,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Dot-separated line: "Series  •  Action & Adventure  •  2022"
            val typeLabel = when (item.type) {
                MediaType.SERIES -> tr("Series")
                MediaType.MOVIE -> tr("Movie")
                else -> item.rawType.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
            }
            val genreLine = item.genres.take(2).joinToString(" & ")
            val dotParts = listOfNotNull(
                typeLabel?.takeIf { it.isNotBlank() },
                genreLine.takeIf { it.isNotBlank() },
                item.year?.toString(),
            )
            if (dotParts.isNotEmpty()) {
                Text(
                    text = dotParts.joinToString("  •  "),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 13.sp * ts,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
            }

            // Badges Row: [TV-MA / R]  [ONGOING / RELEASED]  [IMDb] 8.0
            val ageChip = if (item.type == MediaType.SERIES) "TV-MA" else "R"
            val statusChip = if (item.type == MediaType.SERIES) "ONGOING" else "RELEASED"
            val rating = item.rating?.takeIf { it > 0 }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvMetaOutlineBadge(text = ageChip, scale = ts)
                TvMetaOutlineBadge(text = statusChip, scale = ts)
                if (rating != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFF5C518))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = "IMDb",
                                color = Color.Black,
                                fontSize = 10.sp * ts,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = "%.1f".format(rating),
                            color = Color.White,
                            fontSize = 12.5.sp * ts,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val overview = item.overview?.trim().orEmpty()
            if (overview.isNotEmpty()) {
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 13.5.sp * ts,
                    lineHeight = 19.sp * ts,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvMetaOutlineBadge(text: String, scale: Float) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.35f)),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp * scale,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Video-style horizontal shelf where the focused card smoothly expands from a
 * 2:3 portrait poster into a wide 16:9 landscape card with backdrop art and
 * bottom-left title/logo.
 */
@Composable
fun TvExpandingMediaRow(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    keeperKey: String,
    onItemFocus: ((MediaItem) -> Unit)? = null,
    showViewAll: Boolean = true,
    onClick: (MediaItem) -> Unit,
    onQuickPlay: ((MediaItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val ts = TvUi.scale()
    val uniqueItems = remember(items) { items.distinctBy { it.uniqueId } }
    val itemKeys = remember(uniqueItems) { uniqueItems.map { it.uniqueId } }
    val rowState = keptListState(keeperKey, itemKeys = itemKeys)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (providerName.isNotBlank() && !title.contains(providerName, ignoreCase = true)) {
                    "$providerName - $title"
                } else {
                    title
                },
                color = Color.White,
                fontSize = 16.sp * ts,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showViewAll && onShowAll != null) {
                TextButton(onClick = onShowAll) {
                    Text(
                        text = tr("Show all"),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp * ts,
                    )
                }
            }
        }

        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uniqueItems, key = { it.uniqueId }) { item ->
                TvExpandingPosterCard(
                    item = item,
                    scale = ts,
                    onFocus = { onItemFocus?.invoke(item) },
                    onClick = { onClick(item) },
                    onQuickPlay = onQuickPlay?.let { qp -> { qp(item) } },
                )
            }
        }
    }
}

@Composable
private fun TvExpandingPosterCard(
    item: MediaItem,
    scale: Float,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onQuickPlay: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }

    // ফোকাস করলে কার্ডটি 118.dp পোর্ট্রেট থেকে 268.dp ওয়াইড ল্যান্ডস্কেপে এক্সপ্যান্ড হবে
    val cardWidth by animateDpAsState(
        targetValue = if (focused) (268.dp * scale) else (118.dp * scale),
        animationSpec = tween(durationMillis = 240),
        label = "tv-card-width",
    )
    val cardHeight = 176.dp * scale
    val shape = RoundedCornerShape(12.dp)

    var tmdbBackdrop by remember(item.uniqueId) { mutableStateOf<String?>(null) }
    var tmdbLogo by remember(item.uniqueId) { mutableStateOf<String?>(null) }

    LaunchedEffect(focused, item.uniqueId) {
        if (focused) {
            if (tmdbBackdrop == null) {
                tmdbBackdrop = runCatching {
                    com.hikari.app.data.TmdbScreenshots.backdropsFor(item).firstOrNull()
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (tmdbLogo == null) {
                tmdbLogo = runCatching {
                    com.hikari.app.data.TmdbMeta.logo(item)
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    val portraitModel = remember(item) { Artwork.model(item) }
    val expandedModel = remember(item, tmdbBackdrop) {
        when {
            !tmdbBackdrop.isNullOrBlank() -> PosterLoader.model(tmdbBackdrop!!)
            !item.backdropUrl.isNullOrBlank() -> PosterLoader.model(item.backdropUrl)
            else -> Artwork.tvHeroModel(item).first ?: portraitModel
        }
    }

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged { state ->
                val nowFocused = state.hasFocus
                if (focused != nowFocused) {
                    focused = nowFocused
                    if (nowFocused) onFocus()
                }
            }
            .clip(shape)
            .background(Color(0xFF161822))
            .border(
                width = if (focused) 2.2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.10f),
                shape = shape,
            )
            .tvPress(onClick = onClick, onHold = onQuickPlay)
            .clickable(onClick = onClick),
    ) {
        PosterImage(
            model = if (focused && expandedModel != null) expandedModel else portraitModel,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            alignment = if (focused) Artwork.CINEMA_ALIGNMENT else Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        )

        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                val logoUrl = tmdbLogo
                if (!logoUrl.isNullOrBlank()) {
                    PosterImage(
                        model = logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .widthIn(max = 170.dp)
                            .heightIn(max = 38.dp),
                    )
                } else {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 13.5.sp * scale,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The soft fade at the bottom edge: the next row peeks in and melts away. */
@Composable
fun TvHomeBottomFade(modifier: Modifier = Modifier) {
    val bg = Color(0xFF090A0F)
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to bg.copy(alpha = 0.94f),
                )
            )
    )
}
