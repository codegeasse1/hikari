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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
    /** Height of the compact header bar (search / provider / profile pills). */
    private const val HEADER_BAR_DP = 60f

    /** Fixed chrome of two stacked shelves, in dp: the focused shelf's title
     *  and paddings plus the NEXT shelf's title line. Poster height is solved
     *  from what is left, so the next shelf's title and the top of its posters
     *  always show under the focused shelf (the Nuvio "peek"). */
    private const val SHELF_CHROME_DP = 92f

    /** How much of the next shelf's poster height peeks in under the focused
     *  shelf (0.40 = the top 40% of its cards). */
    private const val PEEK_FRACTION = 0.40f

    /** Poster width as a fraction of its height (collapsed card). */
    const val CARD_ASPECT = 0.68f

    /** Focused card width as a multiple of its height (landscape backdrop). */
    const val EXPANDED_ASPECT = 1.62f

    @Composable
    fun infoHeight(): Dp {
        val h = LocalConfiguration.current.screenHeightDp
        return (h * 0.35f).coerceIn(150f, 260f).dp
    }

    /** True where the info block is short, so the header trims itself. */
    @Composable
    fun compactInfo(): Boolean = infoHeight().value < 190f

    /** Poster height for the shelves, sized so ONE full shelf plus the peek of
     *  the next one fit in the space under the header and the info block. */
    @Composable
    fun cardHeight(): Dp {
        val h = LocalConfiguration.current.screenHeightDp
        val rowsArea = h - HEADER_BAR_DP - infoHeight().value
        val solved = (rowsArea - SHELF_CHROME_DP) / (1f + PEEK_FRACTION)
        // Settings -> Customize -> Poster cards: the size chosen for the shape
        // in use (1f at "Default").
        return (solved.coerceIn(96f, 210f) * com.hikari.app.ui.PosterSizing.factor()).dp
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
                                .fillMaxWidth(0.80f)
                                .fillMaxHeight(0.80f)
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
 * The TV home header — laid out like the reference Home: the title logo, one
 * "Movie · Thriller [18+]" line, the rating badges, the plot, one
 * "2026 · 1h 50m · United States" line, then the Discover / bookmark / next
 * buttons, with the carousel dots under them and the "Starring …" credit on
 * the right. Everything beyond the catalogue row's own fields comes from the
 * same lookups the detail page makes (see [tvLoadHeroMeta]), for every title;
 * nothing is invented while they are still running.
 *
 * The buttons are optional: leave [onDiscover] null and none are drawn.
 * [onActionsFocused] fires when the remote lands on one of them, so the Home
 * can hand the header back to the featured carousel.
 */
@Composable
fun TvHomeHeroInfo(
    item: MediaItem?,
    modifier: Modifier = Modifier,
    bookmarked: Boolean = false,
    dotCount: Int = 0,
    dotActive: Int = -1,
    onDiscover: (() -> Unit)? = null,
    onToggleBookmark: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onActionsFocused: (() -> Unit)? = null,
) {
    val ts = TvUi.scale()
    val compact = TvHomeMetrics.compactInfo()
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

    val ratingsFlow = remember {
        runCatching { HikariApp.instance.store.showDetailRatingFlow() }.getOrNull()
            ?: kotlinx.coroutines.flow.flowOf(true)
    }
    val showRatings by ratingsFlow.collectAsState(initial = true)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.56f)
                .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item != null) {
                val details = meta?.extras?.details

                // 1. The title: its TMDB logo, or the name in plain capitals.
                val logoUrl = logo
                if (!logoUrl.isNullOrBlank()) {
                    PosterImage(
                        model = logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .heightIn(max = if (compact) 38.dp else 52.dp),
                    )
                } else {
                    Text(
                        text = item.title.uppercase(),
                        color = Color.White,
                        fontSize = (if (compact) 22.sp else 26.sp) * ts,
                        lineHeight = (if (compact) 26.sp else 31.sp) * ts,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 2. "Movie · Thriller" + the age-rating chip.
                val typeLabel = when (item.type) {
                    MediaType.SERIES -> tr("TV Show")
                    MediaType.MOVIE -> tr("Movie")
                    else -> item.rawType.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
                }
                val firstGenre = item.genres.firstOrNull()
                    ?: details?.genres.orEmpty().firstOrNull()
                val typeLine = listOfNotNull(
                    typeLabel?.takeIf { it.isNotBlank() },
                    firstGenre?.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                val certLabel = details?.certification?.trim()?.takeIf { it.isNotEmpty() }
                if (typeLine.isNotBlank() || certLabel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (typeLine.isNotBlank()) {
                            Text(
                                text = typeLine,
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 12.sp * ts,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (certLabel != null) TvCertChip(certLabel)
                    }
                }

                // 3. Rating badges: TMDB, IMDb, Rotten Tomatoes, Metacritic.
                if (showRatings) {
                    val badges = meta?.ratings.orEmpty()
                        .filter {
                            it.source == RatingSource.TMDB ||
                                it.source == RatingSource.IMDB ||
                                it.source == RatingSource.TOMATOMETER ||
                                it.source == RatingSource.METACRITIC ||
                                it.source == RatingSource.ANILIST
                        }
                        .sortedBy {
                            when (it.source) {
                                RatingSource.TMDB -> 0
                                RatingSource.IMDB -> 1
                                RatingSource.TOMATOMETER -> 2
                                RatingSource.METACRITIC -> 3
                                else -> 4
                            }
                        }
                        .take(4)
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        TvHeroRatingBadges(badges, ts)
                    }
                }

                // 4. The plot: TMDB's localized text first (like the detail
                // page), the catalogue row's own blurb only where that is blank.
                val overview = meta?.extras?.overview?.trim().orEmpty()
                    .ifEmpty { item.overview?.trim().orEmpty() }
                if (overview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 12.sp * ts,
                        lineHeight = 16.sp * ts,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 5. "2026 · 1h 50m · United States".
                val infoLine = listOfNotNull(
                    (item.year ?: details?.year)?.toString(),
                    tvHeroRuntime(details?.runtimeMinutes),
                    details?.country?.trim()?.takeIf { it.isNotEmpty() },
                ).joinToString("  ·  ")
                if (infoLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = infoLine,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp * ts,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 6. Discover / bookmark / next.
                if (onDiscover != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvHeroPillButton(
                            label = tr("Discover"),
                            ts = ts,
                            onClick = onDiscover,
                            onFocused = onActionsFocused,
                        )
                        if (onToggleBookmark != null) {
                            TvHeroCircleButton(
                                icon = if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                description = tr("Bookmark"),
                                onClick = onToggleBookmark,
                                onFocused = onActionsFocused,
                            )
                        }
                        if (onNext != null) {
                            TvHeroCircleButton(
                                icon = Icons.Filled.ChevronRight,
                                description = tr("Next"),
                                onClick = onNext,
                                onFocused = onActionsFocused,
                            )
                        }
                    }
                }
            }
        }

        // The "Starring …" credit, bottom right (never for anime, where the
        // lookup returns characters, not actors).
        val extras = meta?.extras
        val starring = if (item != null && extras != null && !extras.castIsCharacters) {
            extras.cast.take(3).map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        } else {
            ""
        }
        if (starring.isNotEmpty()) {
            Text(
                text = tr("Starring") + " " + starring,
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp * ts,
                lineHeight = 14.sp * ts,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = 12.dp)
                    .widthIn(max = 240.dp),
            )
        }

        // Carousel dots, centred under the buttons.
        if (dotCount > 1) {
            TvHomeHeroDots(
                count = dotCount,
                active = dotActive,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
            )
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
    RatingSource.ANILIST -> "AniList"
    RatingSource.TOMATOMETER -> "RT"
    RatingSource.POPCORN -> "RT"
    RatingSource.METACRITIC -> "Metacritic"
    RatingSource.LETTERBOXD -> "LB"
}

/** The small tinted rating pills (TMDB 6.8 · IMDb 7.0 · RT 86% · Metacritic
 *  68), display-only: the header never takes D-pad focus away from the
 *  shelves because of them. */
@Composable
private fun TvHeroRatingBadges(ratings: List<TitleRating>, scale: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.forEach { r ->
            val tint = tvHeroRatingColor(r.source)
            val shape = RoundedCornerShape(6.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(shape)
                    .background(tint.copy(alpha = 0.16f))
                    .border(0.5.dp, tint.copy(alpha = 0.40f), shape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = tvHeroRatingLabel(r.source),
                    fontSize = 10.5.sp * scale,
                    fontWeight = FontWeight.ExtraBold,
                    color = tint,
                    maxLines = 1,
                )
                Text(
                    text = r.value,
                    fontSize = 11.5.sp * scale,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Discover": a dark glass pill that turns solid white under the remote. */
@Composable
private fun TvHeroPillButton(
    label: String,
    ts: Float,
    onClick: () -> Unit,
    onFocused: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .height(32.dp)
            .onFocusChanged { st ->
                val now = st.hasFocus
                if (focused != now) {
                    focused = now
                    if (now) onFocused?.invoke()
                }
            }
            .clip(shape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.50f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.20f), shape)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Color(0xFF0A0B10) else Color.White,
            fontSize = 12.5.sp * ts,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** The round icon buttons next to Discover (bookmark, next title). */
@Composable
private fun TvHeroCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onFocused: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(32.dp)
            .onFocusChanged { st ->
                val now = st.hasFocus
                if (focused != now) {
                    focused = now
                    if (now) onFocused?.invoke()
                }
            }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.50f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.20f), CircleShape)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (focused) Color(0xFF0A0B10) else Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** Tiny carousel dots; the lit one is a wider white bar. */
@Composable
private fun TvHomeHeroDots(count: Int, active: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until count.coerceAtMost(8)) {
            Box(
                Modifier
                    .size(width = if (i == active) 14.dp else 4.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i == active) Color.White else Color.White.copy(alpha = 0.35f)
                    )
            )
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
    val cardHeight = TvHomeMetrics.cardHeight()
    // Settings -> Customize -> Poster cards: Landscape draws the cards wide
    // even at rest (the size is already part of cardHeight above).
    val landscapeCards = com.hikari.app.ui.PosterSizing.landscape()

    // Tight shelf chrome (see TvHomeMetrics.SHELF_CHROME_DP): the title line is
    // a fixed 28dp strip, so the next shelf's title and poster tops fit on
    // screen under this one.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(start = 18.dp, end = 14.dp),
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
                // A plain focusable label instead of a TextButton: the Material
                // button forces a 40-48dp touch target, which made every shelf
                // header tall and pushed the next shelf off screen.
                Text(
                    text = tr("Show all"),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp * ts,
                    maxLines = 1,
                    modifier = Modifier
                        .tvFocusRing(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onShowAll)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }

        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uniqueItems, key = { it.uniqueId }) { item ->
                TvExpandingPosterCard(
                    item = item,
                    scale = ts,
                    cardHeight = cardHeight,
                    landscape = landscapeCards,
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
    cardHeight: Dp,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onQuickPlay: (() -> Unit)?,
    landscape: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // Poster cards (Settings -> Customize): in landscape the card is wide at
    // rest too, so "wide" is true whether or not the remote is on it.
    val wide = focused || landscape

    // Poster-shaped at rest, landscape backdrop while focused; both derive
    // from the solved card height so they follow the screen size.
    val cardWidth by animateDpAsState(
        targetValue = if (wide) {
            cardHeight * TvHomeMetrics.EXPANDED_ASPECT
        } else {
            cardHeight * TvHomeMetrics.CARD_ASPECT
        },
        animationSpec = tween(durationMillis = 240),
        label = "tv-card-width",
    )
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
        // Landscape at rest, title with no wide art at all: the poster is drawn
        // whole on a blurred copy of itself (never a beheaded crop).
        val restHero = if (landscape && !focused) Artwork.tvHeroModel(item) else null
        val posterOnlyLandscape = restHero != null && !restHero.second &&
            tmdbBackdrop.isNullOrBlank() && item.backdropUrl.isNullOrBlank()
        if (posterOnlyLandscape) {
            PosterImage(
                model = restHero?.first ?: portraitModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(16.dp, BlurredEdgeTreatment.Unbounded),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
            )
            PosterImage(
                model = portraitModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f),
            )
        } else {
            PosterImage(
                model = if (wide && expandedModel != null) expandedModel else portraitModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                alignment = if (wide) Artwork.CINEMA_ALIGNMENT else Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (wide) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight * 0.48f)
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
                            .widthIn(max = cardHeight * 0.95f)
                            .heightIn(max = cardHeight * 0.22f),
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
            .height(30.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to bg.copy(alpha = 0.80f),
                )
            )
    )
}
