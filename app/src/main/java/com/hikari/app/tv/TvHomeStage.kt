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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import com.hikari.app.HikariApp
import com.hikari.app.data.AnimeMetadataRepository
import com.hikari.app.data.RatingSource
import com.hikari.app.data.Ratings
import com.hikari.app.data.TitleExtras
import com.hikari.app.data.TitleRating
import com.hikari.app.data.TmdbMeta
import com.hikari.app.i18n.tr
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.components.keptListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        return (h * 0.44f).coerceIn(196f, 320f).dp
    }
}

private class TvStageArt(val model: Any?, val wide: Boolean)

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

/** What the TV home header shows beyond the catalogue row's own fields — the
 *  very same two lookups the title's detail page makes. */
private class TvHeroMeta(
    val extras: TitleExtras?,
    val ratings: List<TitleRating>,
)

/**
 * Per-session memory of those lookups. Moving focus across a shelf must not
 * re-request a title the box already knows, so every answer is kept; a failed
 * lookup is remembered for two minutes so a flaky network is not hammered
 * while the user scrolls.
 */
private object TvHeroMetaCache {
    private const val MAX = 64
    private const val MISS_TTL_MS = 120_000L
    private val hits = HashMap<String, TvHeroMeta>()
    private val misses = HashMap<String, Long>()

    @Synchronized
    fun get(id: String): TvHeroMeta? = hits[id]

    @Synchronized
    fun put(id: String, value: TvHeroMeta) {
        if (hits.size >= MAX) hits.clear()
        hits[id] = value
        misses.remove(id)
    }

    @Synchronized
    fun updateRatings(id: String, ratings: List<TitleRating>) {
        val cur = hits[id] ?: return
        hits[id] = TvHeroMeta(cur.extras, ratings)
    }

    @Synchronized
    fun recentlyMissed(id: String): Boolean {
        val at = misses[id] ?: return false
        return System.currentTimeMillis() - at < MISS_TTL_MS
    }

    @Synchronized
    fun markMiss(id: String) {
        if (misses.size >= MAX) misses.clear()
        misses[id] = System.currentTimeMillis()
    }
}

/**
 * The detail page's own data path (DetailViewModel.loadShelves), run for the
 * focused home title: the same "is TMDB allowed for this kind of row" gate,
 * then [TmdbMeta.extras] (age rating, runtime, overview) and [Ratings.load]
 * (IMDb / TMDB badges). So the header shows what the detail page shows.
 */
private suspend fun tvLoadHeroMeta(
    item: MediaItem,
    onRatings: (List<TitleRating>) -> Unit,
): TvHeroMeta? = withContext(Dispatchers.IO) {
    val app = HikariApp.instance
    val store = app.store
    val native = runCatching { store.nativeProviders() }.getOrDefault(emptySet())
    val tmdbOk = if (item.providerId in native) {
        false
    } else {
        val anime = AnimeMetadataRepository.isAnimeItem(
            item,
            runCatching { app.providers.byId(item.providerId)?.config?.type }.getOrNull(),
        )
        runCatching { if (anime) store.animeTmdb() else store.videoTmdb() }.getOrDefault(true)
    }
    val ex = if (tmdbOk) runCatching { TmdbMeta.extras(item) }.getOrNull() else null
    val ratings = runCatching {
        Ratings.load(
            item,
            ex?.details?.imdbId,
            ex?.details?.rating,
            ex?.details?.voteCount,
            onUpdate = onRatings,
        )
    }.getOrDefault(emptyList())
    if (ex == null && ratings.isEmpty()) null else TvHeroMeta(ex, ratings)
}

/** "1h 41m" / "24m" / "2h" from TMDB's runtime in minutes. */
private fun tvHeroRuntime(minutes: Int?): String? {
    val m = minutes?.takeIf { it > 0 } ?: return null
    return when {
        m < 60 -> "${m}m"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }
}

/**
 * The TV home header — laid out like the title's detail page header:
 * provider name, title logo, "TV Series [TV-14] • 24m • Genres", the IMDb /
 * TMDB badges and the plot. Everything beyond the catalogue row's own fields
 * comes from the same lookups the detail page makes (see [tvLoadHeroMeta]),
 * for every title; nothing is invented while they are still running.
 */
@Composable
fun TvHomeHeroInfo(item: MediaItem?, modifier: Modifier = Modifier) {
    val ts = TvUi.scale()
    var logo by remember(item?.uniqueId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.uniqueId) {
        val current = item ?: return@LaunchedEffect
        logo = runCatching { TmdbMeta.logo(current) }.getOrNull()
    }

    var meta by remember(item?.uniqueId) {
        mutableStateOf<TvHeroMeta?>(item?.let { TvHeroMetaCache.get(it.uniqueId) })
    }
    LaunchedEffect(item?.uniqueId) {
        val current = item ?: return@LaunchedEffect
        val id = current.uniqueId
        val known = TvHeroMetaCache.get(id)
        if (known != null) {
            meta = known
            return@LaunchedEffect
        }
        if (TvHeroMetaCache.recentlyMissed(id)) return@LaunchedEffect
        delay(500)
        val loaded = try {
            tvLoadHeroMeta(current) { list ->
                TvHeroMetaCache.updateRatings(id, list)
                TvHeroMetaCache.get(id)?.let { meta = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (loaded != null) {
            TvHeroMetaCache.put(id, loaded)
            meta = loaded
        } else {
            TvHeroMetaCache.markMiss(id)
        }
    }

    val providerName = remember(item?.providerId) {
        item?.providerId?.let { pid ->
            runCatching { HikariApp.instance.providers.byId(pid)?.config?.name?.trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
    val ratingsFlow = remember {
        runCatching { HikariApp.instance.store.showDetailRatingFlow() }.getOrNull()
            ?: kotlinx.coroutines.flow.flowOf(true)
    }
    val showRatings by ratingsFlow.collectAsState(initial = true)

    Column(
        modifier = modifier
            .fillMaxWidth(0.56f)
            .padding(start = 18.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item != null) {
            val details = meta?.extras?.details

            // Provider eyebrow, as on the detail page.
            if (!providerName.isNullOrBlank()) {
                Text(
                    text = providerName,
                    color = Color(0xFF4DA3FF),
                    fontSize = 12.sp * ts,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }

            val logoUrl = logo
            if (!logoUrl.isNullOrBlank()) {
                PosterImage(
                    model = logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .heightIn(max = 56.dp),
                )
            } else {
                Text(
                    text = item.title.uppercase(),
                    color = Color.White,
                    fontSize = 26.sp * ts,
                    lineHeight = 31.sp * ts,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))

            // One meta line, like the detail page: year, type, age-rating
            // chip, runtime, genres.
            val typeLabel = when (item.type) {
                MediaType.SERIES -> tr("TV Series")
                MediaType.MOVIE -> tr("Movie")
                else -> item.rawType.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
            }
            val metaHead = listOfNotNull(
                (item.year ?: details?.year)?.toString(),
                typeLabel?.takeIf { it.isNotBlank() },
            ).joinToString("  •  ")
            val certLabel = details?.certification?.trim()?.takeIf { it.isNotEmpty() }
            val genreLine = item.genres.take(3).ifEmpty { details?.genres.orEmpty().take(3) }
                .joinToString(", ")
            val metaTail = listOfNotNull(
                tvHeroRuntime(details?.runtimeMinutes),
                genreLine.takeIf { it.isNotBlank() },
            ).joinToString("  •  ")
            if (metaHead.isNotBlank() || certLabel != null || metaTail.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (metaHead.isNotBlank()) {
                        Text(
                            text = metaHead,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 12.sp * ts,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    if (certLabel != null) TvCertChip(certLabel)
                    if (metaTail.isNotBlank()) {
                        Text(
                            text = (if (metaHead.isNotBlank() || certLabel != null) "•  " else "") + metaTail,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp * ts,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }

            // IMDb + TMDB (+ AniList for anime) badges — the detail page's row.
            if (showRatings) {
                val badges = meta?.ratings.orEmpty()
                    .filter {
                        it.source == RatingSource.IMDB ||
                            it.source == RatingSource.TMDB ||
                            it.source == RatingSource.ANILIST
                    }
                    .sortedBy {
                        when (it.source) {
                            RatingSource.IMDB -> 0
                            RatingSource.TMDB -> 1
                            else -> 2
                        }
                    }
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TvHeroRatingBadges(badges, ts)
                }
            }

            // The plot: TMDB's localized text first (like the detail page),
            // the catalogue row's own blurb only where that is blank.
            val overview = meta?.extras?.overview?.trim().orEmpty()
                .ifEmpty { item.overview?.trim().orEmpty() }
            if (overview.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.5.sp * ts,
                    lineHeight = 17.sp * ts,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun tvHeroRatingColor(source: RatingSource): Color = when (source) {
    RatingSource.IMDB -> Color(0xFFF5C518)
    RatingSource.TMDB -> Color(0xFF01B4E4)
    RatingSource.ANILIST -> Color(0xFF02A9FF)
    RatingSource.TOMATOMETER -> Color(0xFFFA320A)
    RatingSource.POPCORN -> Color(0xFFFFB300)
    RatingSource.METACRITIC -> Color(0xFF66CC33)
    RatingSource.LETTERBOXD -> Color(0xFF00C030)
}

private fun tvHeroRatingLabel(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "IMDb"
    RatingSource.TMDB -> "TMDB"
    RatingSource.ANILIST -> "AL"
    RatingSource.TOMATOMETER -> "RT"
    RatingSource.POPCORN -> "RT"
    RatingSource.METACRITIC -> "MC"
    RatingSource.LETTERBOXD -> "LB"
}

/** The detail page's small dark rating pills, minus the click: the header is
 *  display-only, so it never takes D-pad focus away from the shelves. */
@Composable
private fun TvHeroRatingBadges(ratings: List<TitleRating>, scale: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.forEach { r ->
            val shape = RoundedCornerShape(6.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), shape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = tvHeroRatingLabel(r.source),
                    fontSize = 11.sp * scale,
                    fontWeight = FontWeight.ExtraBold,
                    color = tvHeroRatingColor(r.source),
                    maxLines = 1,
                )
                Text(
                    text = r.value,
                    fontSize = 12.sp * scale,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

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
    if (!TvMode.current()) {
        MediaRow(
            title = title,
            providerName = providerName,
            items = items,
            keeperKey = keeperKey,
            onItemFocus = onItemFocus,
            showViewAll = showViewAll,
            onClick = onClick,
            onQuickPlay = onQuickPlay,
            onShowAll = onShowAll,
        )
        return
    }
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
