package com.hikari.app.ui.components
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.RatingBadge
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvUi
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.shape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many automatic re-requests a poster gets before its cell settles on the
 *  placeholder icon. Two is enough to ride out a dropped connection or a CDN
 *  hiccup without hammering an image that is genuinely gone. */
private const val POSTER_RETRIES = 2

@Composable
fun MediaRow(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    onShowAll: (() -> Unit)? = null,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Catalog/row names come from the extensions, so they are
                    // English strings from outside the app: tr() translates the
                    // ones the i18n files know and leaves the rest untouched.
                    tr(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    providerName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (onShowAll != null) {
                TextButton(onClick = onShowAll) {
                    Text(tr("Show All"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // The style is read ONCE for the row and handed to every cell. Read per
        // cell it opened one DataStore collection per poster on screen, so a
        // Home feed with three rows of twelve visible cards had ~36 live
        // subscriptions to the same preferences, all re-mapping on every
        // settings write and all allocating inside the scroll path. One read per
        // row is the same look with a fraction of the work.
        val style = rememberPosterStyle()
        // A row's items come from an extension, and an extension is free to
        // list the same title twice (or to hand back two entries that share an
        // id). Compose does not warn about a duplicated key — it throws, taking
        // the whole screen with it — so the key is the item's own identity, with
        // literal repeats dropped before they are drawn. Deduped ONCE per row
        // rather than on every recomposition of it (a row recomposes on every
        // scroll step): this is a fresh list otherwise.
        val uniqueItems = remember(items) { items.distinctBy { it.uniqueId } }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uniqueItems, key = { it.uniqueId }) { item ->
                PosterCard(item, style, onClick = { onClick(item) })
            }
        }
    }
}

/**
 * The frame a hero card is drawn in.
 *
 * On a phone that is simply the card's own aspect ratio at full width — the
 * shape the design asks for. On a television it is the same card CAPPED to
 * about half the screen's height, because a 16:9 hero across a 848dp-wide
 * television screen wants 477dp of a 540dp-tall display: the banner then IS the
 * page, and nothing below it is ever seen (the "the hero banner takes the whole
 * screen" half of the TV-layout report). Capping keeps the artwork's shape —
 * `ContentScale.Crop` takes care of the crop — while leaving the rows
 * underneath on screen.
 *
 * [aspect] is width/height, and [horizontalInsets] is the width the card's own
 * padding takes off the screen (a carousel's peek-in, for example).
 */
@Composable
private fun heroFrame(aspect: Float, horizontalInsets: Float = 0f): Modifier {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    val natural = (width - horizontalInsets) / aspect
    if (!TvMode.current()) return Modifier.fillMaxWidth().aspectRatio(aspect)
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    return Modifier
        .fillMaxWidth()
        .height(natural.coerceAtMost(height * 0.52f).dp)
}

/**
 * [AsyncImage] with a little self-healing: a request that FAILS (dropped
 * connection, a 503 from the CDN, a momentarily busy decoder) is re-issued
 * after a growing delay. Coil never retries by itself, so without this a poster
 * that failed once stayed blank until its row happened to be scrolled out of
 * view and back — the "it loads some fine, but when I scroll down some images
 * just don't load" report. The re-request is a new [coil.request.ImageRequest]
 * carrying a retry parameter, which is what makes Coil's AsyncImage restart it.
 */
@Composable
fun PosterImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    var attempt by remember(model) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    AsyncImage(
        model = PosterLoader.retryModel(model, attempt),
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        onError = {
            if (attempt < POSTER_RETRIES) {
                scope.launch {
                    delay(700L * (attempt + 1))
                    attempt++
                }
            }
        },
    )
}

/**
 * Artwork for a wide hero/banner frame.
 *
 * [wide] art (a real backdrop, 16:9-ish) is cropped to fill the frame with the
 * TOP edge kept, so a slightly taller backdrop loses its bottom rather than
 * the top of the frame.
 *
 * A PORTRAIT poster in that same frame must not simply be cropped: filling a
 * 16:9 box with a 2:3 poster keeps only the middle ~38% of the image, which
 * slices the top of the frame off (the "the banner is cut / the head is
 * chopped off" report). Instead the poster is shown the way streaming apps do
 * it — a dimmed, zoomed copy of itself fills the frame behind, and the whole
 * poster is drawn intact at the right edge, in front of it.
 */
@Composable
fun HeroArtwork(
    model: Any?,
    wide: Boolean,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    if (wide) {
        PosterImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
        return
    }
    Box(modifier) {
        // Backdrop fill: the poster itself, scaled past the frame edges and
        // dimmed, so the banner keeps an image behind the text without any
        // hard crop line. (Not Modifier.blur — it is a no-op below API 31, and
        // a scaled, dimmed copy looks the same everywhere.)
        PosterImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f
                    scaleY = 1.35f
                    alpha = 0.55f
                },
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        PosterImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .aspectRatio(2f / 3f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    style: PosterStyle,
    onClick: () -> Unit,
) {
    // With the score badge switched on this warms the ratings cache for the
    // title and repaints the cell when the answer lands (see
    // rememberPosterScore). Off, it costs nothing at all.
    val badge = rememberPosterScore(item, style)
    // On a television the same card is drawn at a size that reads from a sofa,
    // sized from THIS screen's height rather than a constant (see
    // TvUi.posterWidth) so three or four cells never fill a landscape display.
    val cardWidth = if (TvMode.current()) TvUi.posterWidth() else 120.dp
    Column(
        Modifier
            .width(cardWidth)
            // Deliberately NOT clipped to the poster's rounding: the outer
            // corner curve reached down into the title and bit the first and
            // last letters off it at high corner values. The artwork applies
            // the rounding to itself (see PosterArt).
            .clickable(onClick = onClick)
    ) {
        PosterArt(
            model = Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
            imdb = badge,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        if (style.showTitles) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
            )
        }
    }
}

@Composable
fun ShimmerRow() {
    val transition = rememberInfiniteTransition(label = I18n.t("shimmer"))
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = tr("alpha")
    )
    val tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Column(Modifier.padding(top = 20.dp)) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .size(width = 120.dp, height = 16.dp)
                .background(tint, RoundedCornerShape(4.dp))
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(5) {
                Box(
                    Modifier
                        .width(120.dp)
                        .aspectRatio(2f / 3f)
                        .background(tint, RoundedCornerShape(12.dp))
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Movie,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        // tr() here (not at every call site) so an error/empty state written
        // anywhere in the app is translated: an unknown string — a raw error
        // from an extension, say — comes back unchanged.
        Text(tr(title), style = MaterialTheme.typography.titleMedium)
        Text(
            tr(subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (actionLabel != null && action != null) {
            Button(onClick = action, modifier = Modifier.padding(top = 16.dp)) {
                Text(tr(actionLabel))
            }
        }
    }
}

/** A rounded, translucent (glass-style) search field — matches the floating
 *  bottom-nav bar look instead of a plain outlined box. Translucent surface +
 *  large pill radius + soft shadow, same idiom as AppBottomBar. */
@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    /** Optional controls rendered at the end of the field (e.g. the search
     *  screen's translate button). Shown after the clear button. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val glass = rememberGlassTokens()
    val fieldShape = RoundedCornerShape(30.dp)
    Surface(
        shape = fieldShape,
        color = if (glass.dark) glass.fillTop else MaterialTheme.colorScheme.surface,
        shadowElevation = if (glass.dark) 0.dp else 4.dp,
        modifier = modifier.border(1.dp, glass.border, fieldShape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Clear"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * The shapes Home's featured banner can take — Settings → App Layout →
 * Featured banner.
 *
 * All four show the same titles; they differ in how much of the page the banner
 * owns and how much of each title it tells you. [COMPACT] gets out of the way as
 * fast as possible, [SPOTLIGHT] is the full cinematic treatment, and
 * [SHOWCASE] puts the poster beside the details so the artwork is never cropped.
 */
object HeroStyles {
    const val CAROUSEL = "carousel"
    const val SPOTLIGHT = "spotlight"
    const val COMPACT = "compact"
    const val SHOWCASE = "showcase"

    val ALL = listOf(CAROUSEL, SPOTLIGHT, COMPACT, SHOWCASE)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else CAROUSEL

    fun label(key: String): String = when (normalize(key)) {
        SPOTLIGHT -> "Spotlight"
        COMPACT -> "Compact strip"
        SHOWCASE -> "Showcase"
        else -> "Carousel"
    }

    fun description(key: String): String = when (normalize(key)) {
        SPOTLIGHT -> "Full-width cinematic banner with the plot"
        COMPACT -> "A short strip, so the feed starts sooner"
        SHOWCASE -> "Poster beside the details, never cropped"
        else -> "Wide 16:9 cards that peek in from the sides"
    }
}

/**
 * What the featured banner should draw: which shape, and which of the optional
 * lines. The three flags are the "overlay metadata" switches — a plot summary,
 * the score and the type/genre/year line can each be turned off, so a banner can
 * be pure artwork if that is what the user wants.
 */
data class HeroConfig(
    val style: String = HeroStyles.CAROUSEL,
    val showOverview: Boolean = true,
    val showRating: Boolean = true,
    val showMeta: Boolean = true,
)

/** The featured banner, in whichever shape [config] asks for. */
@Composable
fun HeroBanner(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    if (items.isEmpty()) return
    when (HeroStyles.normalize(config.style)) {
        HeroStyles.SPOTLIGHT -> HeroSpotlight(items, onClick, modifier, config)
        HeroStyles.COMPACT -> HeroCompact(items, onClick, modifier, config)
        HeroStyles.SHOWCASE -> HeroShowcase(items, onClick, modifier, config)
        else -> HeroCarousel(items, onClick, modifier, config)
    }
}

/** A carousel of wide, movie-shaped featured cards for the top of Home — the
 *  backdrop art with the title/metadata and a "View Details" pill over a bottom
 *  scrim, and pagination dots while more than one featured title exists.
 *  Swiping left/right moves between featured titles (the dots track it), and
 *  tapping anywhere on a card opens the title that card shows.
 *
 *  The cards are 16:9 and inset from the screen edges so the neighbours peek
 *  in: that is what makes it read as a "poster carousel" instead of the tall
 *  portrait hero that used to eat the top third of Home. */
@Composable
private fun HeroCarousel(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState { items.size }
    // Auto-advance every 6s, but stand still while the user is dragging so a
    // swipe never fights the timer (the clock restarts after the drag ends).
    // Not at all on a television — see [HeroAutoAdvance].
    if (!TvMode.current()) {
        LaunchedEffect(pagerState, items) {
            if (items.size <= 1) return@LaunchedEffect
            while (true) {
                delay(6000)
                if (!pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
                }
            }
        }
    }
    val frame = heroFrame(16f / 9f, horizontalInsets = 40f)
    Column(modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            // The neighbours peek in at the sides: a wide, movie-shaped card
            // that clearly belongs to a carousel, instead of the old
            // full-bleed portrait hero that filled a third of the screen.
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val item = items[page]
            val heroScore = if (config.showRating) rememberHeroScore(item) else null
            Box(
                Modifier
                    .then(frame)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onClick(item) }
            ) {
                val hero = Artwork.heroModel(item)
                HeroArtwork(
                    model = hero.first,
                    wide = hero.second,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // Darkens the top (for the overlaid app bar) and the bottom (for
                // the title/button) so the hero text always reads.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                0.30f to Color.Transparent,
                                0.55f to Color.Black.copy(alpha = 0.35f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaLine = buildList {
                        when (item.type) {
                            MediaType.MOVIE -> add("Movie")
                            MediaType.SERIES -> add("Series")
                            else -> {}
                        }
                        item.genres.take(2).forEach { add(it) }
                        item.year?.let { add(it.toString()) }
                    }.joinToString("  ·  ")
                    if (config.showMeta && metaLine.isNotBlank()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (config.showOverview && !item.overview.isNullOrBlank()) {
                        Text(
                            item.overview!!.trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (heroScore != null) {
                            HeroScoreChip(heroScore)
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { onClick(item) },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tr("View Details"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val active = pagerState.currentPage.coerceIn(0, items.lastIndex)
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(width = if (i == active) 16.dp else 6.dp, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == active) Color.White else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

/** The score chip a featured banner can wear, when a score is known. The lookup
 *  is the same one the poster grids use ([rememberPosterScore]), so a title's
 *  score is fetched once for the whole app and a banner does not open a second
 *  round of lookups. */
@Composable
private fun rememberHeroScore(item: MediaItem): String? =
    rememberPosterScore(item, PosterStyle(showRatings = true))

/** The score chip itself: the poster badge, enlarged for a banner. */
@Composable
private fun HeroScoreChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF5C518),
            maxLines = 1,
        )
    }
}

/** "Movie · Action, Drama · 2024" — the one line every banner shape shares. */
private fun heroMetaLine(item: MediaItem): String = buildList {
    when (item.type) {
        MediaType.MOVIE -> add("Movie")
        MediaType.SERIES -> add("Series")
        else -> {}
    }
    item.genres.take(2).forEach { add(it) }
    item.year?.let { add(it.toString()) }
}.joinToString("  ·  ")

/** The pager dots under a banner. Grown in place rather than replaced, so the
 *  row never reflows as it advances. */
@Composable
private fun HeroDots(active: Int, count: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Row(
        modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .size(width = if (i == active) 16.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i == active) Color.White else Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

/** Auto-advance a banner's pager, standing still while the user is dragging so
 *  a swipe never fights the timer. */
@Composable
private fun HeroAutoAdvance(pagerState: androidx.compose.foundation.pager.PagerState, count: Int, everyMs: Long) {
    // A television's hero stands still. The remote is how a viewer moves
    // through things there, and a banner that slides sideways every few seconds
    // under a focused screen is the "the hero keeps jittering" report — on a
    // weak TV box the animation also drops frames while it does it.
    if (TvMode.current()) return
    LaunchedEffect(pagerState, count) {
        if (count <= 1) return@LaunchedEffect
        while (true) {
            delay(everyMs)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % count)
            }
        }
    }
}

/**
 * The SPOTLIGHT banner: one edge-to-edge cinematic frame per featured title,
 * taller than the carousel and carrying the whole story — title, metadata, a
 * two-line plot summary, the score and the action pill — over a deep bottom
 * scrim so the text always reads, whatever the artwork is doing underneath.
 */
@Composable
private fun HeroSpotlight(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    val pagerState = rememberPagerState { items.size }
    HeroAutoAdvance(pagerState, items.size, 7000L)
    val frame = heroFrame(3f / 2f)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = items[page]
            val heroScore = if (config.showRating) rememberHeroScore(item) else null
            val hero = Artwork.heroModel(item)
            Box(
                Modifier
                    .then(frame)
                    .clickable { onClick(item) }
            ) {
                HeroArtwork(
                    model = hero.first,
                    wide = hero.second,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                0.32f to Color.Transparent,
                                0.52f to Color.Black.copy(alpha = 0.30f),
                                1f to Color.Black.copy(alpha = 0.95f),
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaLine = heroMetaLine(item)
                    if (config.showMeta && metaLine.isNotBlank()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (config.showOverview && !item.overview.isNullOrBlank()) {
                        Text(
                            item.overview!!.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.74f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (heroScore != null) {
                            HeroScoreChip(heroScore)
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { onClick(item) },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tr("View Details"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        HeroDots(
            active = pagerState.currentPage.coerceIn(0, items.lastIndex),
            count = items.size,
        )
    }
}

/**
 * The COMPACT strip: a short, edge-to-edge band — art, title, one metadata line,
 * a play chip on the right and a hairline progress bar instead of dots. For
 * someone who came for the rows underneath, this is the featured banner that
 * costs the least screen.
 */
@Composable
private fun HeroCompact(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    val pagerState = rememberPagerState { items.size }
    HeroAutoAdvance(pagerState, items.size, 5200L)
    Column(modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = items[page]
            val heroScore = if (config.showRating) rememberHeroScore(item) else null
            val hero = Artwork.heroModel(item)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clickable { onClick(item) }
            ) {
                HeroArtwork(
                    model = hero.first,
                    wide = hero.second,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // Reads left to right, so the scrim is too: the copy sits on the
                // left, the play chip on the right, and neither needs a caption
                // box under it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.88f),
                                0.55f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.20f),
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.72f)
                        .padding(start = 18.dp, end = 8.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaLine = heroMetaLine(item)
                    if (config.showMeta && metaLine.isNotBlank()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (config.showOverview && !item.overview.isNullOrBlank()) {
                        Text(
                            item.overview!!.trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.66f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (heroScore != null) {
                        HeroScoreChip(heroScore)
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = tr("View Details"),
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        // A hairline progress bar instead of dots: it costs 2dp of height and
        // still says "there are more of these".
        val active = pagerState.currentPage.coerceIn(0, items.lastIndex)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 16.dp, end = 16.dp)) {
            for (i in items.indices) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (i == active) Color.White else Color.White.copy(alpha = 0.22f)
                        )
                )
            }
        }
    }
}

/**
 * The SHOWCASE banner: the poster kept at its own 2:3 shape beside the details,
 * on a glass card. Nothing is cropped and nothing is darkened, so a title whose
 * artwork matters more than its backdrop is the one this shape is for.
 */
@Composable
private fun HeroShowcase(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    val pagerState = rememberPagerState { items.size }
    HeroAutoAdvance(pagerState, items.size, 7000L)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp,
        ) { page ->
            val item = items[page]
            val heroScore = if (config.showRating) rememberHeroScore(item) else null
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(item) },
            ) {
                Row(Modifier.padding(12.dp)) {
                    PosterArt(
                        model = Artwork.model(item),
                        contentDescription = item.title,
                        style = rememberPosterStyle(),
                        rating = item.rating,
                        imdb = heroScore,
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(2f / 3f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val metaLine = heroMetaLine(item)
                        if (config.showMeta && metaLine.isNotBlank()) {
                            Text(
                                metaLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        if (config.showOverview && !item.overview.isNullOrBlank()) {
                            Text(
                                item.overview!!.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (heroScore != null) {
                                RatingBadge(heroScore)
                                Spacer(Modifier.width(8.dp))
                            }
                            Surface(
                                onClick = { onClick(item) },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        tr("View Details"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        HeroDots(
            active = pagerState.currentPage.coerceIn(0, items.lastIndex),
            count = items.size,
        )
    }
}

/** The "Continue Watching" row: landscape cards with the saved progress bar,
 *  an "Xh Ym left" badge and the episode label — mirrors the History entries. */
@Composable
fun ContinueWatchingRow(
    entries: List<HistoryEntry>,
    backdropOf: (HistoryEntry) -> String?,
    onClick: (HistoryEntry) -> Unit,
    /** When non-null each card gets a small ✕ that removes just that entry.
     *  Continue Watching is fed from the same watch-history store as the
     *  History tab, so removing here removes it from both. */
    onRemove: ((HistoryEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    // Defensive dedupe: a duplicate Compose key would crash the whole row.
    val unique = remember(entries) { entries.distinctBy { it.uniqueKey } }
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            tr("Continue Watching"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(unique, key = { it.uniqueKey }) { h ->
                ContinueWatchingCard(
                    h = h,
                    backdrop = backdropOf(h),
                    removable = onRemove != null,
                    onClick = { onClick(h) },
                    onRemove = { onRemove?.invoke(h) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    h: HistoryEntry,
    backdrop: String?,
    removable: Boolean = false,
    onClick: () -> Unit,
    onRemove: () -> Unit = {},
) {
    val style = rememberPosterStyle()
    val fraction = if (h.durationMs > 0L) {
        (h.positionMs.toFloat() / h.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remaining = (h.durationMs - h.positionMs).coerceAtLeast(0L)
    Column(
        Modifier
            .width(230.dp)
            // Not clipped: the rounding lives on the thumbnail itself, so the
            // title under it cannot be eaten by the corner curve.
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(style.shape())
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            HeroArtwork(
                model = PosterLoader.model(backdrop ?: h.posterUrl),
                wide = !backdrop.isNullOrBlank(),
                contentDescription = h.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            ) {
                if (h.episodeName.isNotBlank() || h.episodeId.isNotBlank()) {
                    Text(
                        h.episodeName.ifBlank { "Episode" },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    h.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (removable) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Remove from Continue Watching"),
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (remaining > 0L) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        I18n.t("%s left").replace("%s", fmtRemaining(remaining)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun fmtRemaining(ms: Long): String {
    val m = ms / 60_000L
    return if (m >= 60L) "${m / 60}h ${m % 60}m" else "${m}m"
}

/**
 * The one corner radius the app rounds a box with.
 *
 * Every card is drawn at this radius, and so is every box INSIDE one — the
 * action buttons, the pickers, the small icon tiles, the form fields. Mixing
 * radii on one screen is the thing that reads as unfinished: a card rounded at
 * 26.dp with a 4.dp field, or a half-rounded row, sitting in it looks like two
 * designs laid on top of each other. A short box (a button, a chip, a tile) ends
 * up a capsule at this radius, which is what "fully rounded" should look like,
 * and a tall one keeps the card's own curve — so everything inside a card
 * matches the card it is inside.
 */
val GlassCornerRadius = 26.dp
val GlassShape = RoundedCornerShape(GlassCornerRadius)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberGlassTokens()
    Column(
        modifier
            .fillMaxWidth()
            // The shadow is for the LIGHT theme only: there a translucent card
            // would otherwise float with no edge against the paper background.
            // On the dark themes the shadow would simply mud the translucent
            // fill, and the hairline below does the separating instead.
            .then(if (glass.dark) Modifier else Modifier.shadow(3.dp, shape, clip = false))
            .clip(shape)
            // A whisper of white at the top falling to half of that at the
            // bottom — the falloff is what reads as glass (see GlassTokens).
            .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)), shape)
            .border(1.dp, glass.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/**
 * The app's modal panel: a dimming scrim over the page with a glass card in the
 * middle — the same fill/edge/size recipe as [GlassCard], so a dialog looks like
 * the rest of Hikari instead of the platform's grey Material box.
 *
 * Tapping the scrim anywhere dismisses (so every dialog can be cancelled with a
 * single tap), while taps INSIDE the card are swallowed by an empty clickable —
 * otherwise they would fall through to the scrim and close the dialog the moment
 * someone reached for a row.
 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberGlassTokens()
    val shape = GlassShape
    // A short fade + scale so the panel appears rather than blinks.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 170)) }
    val a = appear.value

    // A REAL window, not a Box drawn into whichever subtree happened to open
    // the dialog. That distinction is the whole fix for "the picker opens at
    // the BOTTOM of the page and I have to scroll to it": the panel used to be
    // composed inside the card that opened it — i.e. inside one item of a
    // LazyColumn — so its height was added to that card instead of being laid
    // over the screen. It looked centred in English (short labels, a card near
    // the top of a page) and landed below everything in Arabic (longer,
    // right-to-left labels, and a taller card), which is exactly the report.
    // In its own window the panel is centred on the SCREEN in every language,
    // on every screen, and it draws over the taskbar and the navigation rail
    // instead of behind them.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = a }
                .background(Color.Black.copy(alpha = 0.62f * a))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .fillMaxWidth(0.92f)
                    .graphicsLayer {
                        scaleX = 0.94f + 0.06f * a
                        scaleY = 0.94f + 0.06f * a
                    }
                    // Swallow taps: reaching for a row must not close the dialog.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .then(if (glass.dark) Modifier else Modifier.shadow(6.dp, shape, clip = false))
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                    .border(1.dp, glass.border, shape)
                    .padding(20.dp),
                content = {
                    if (title != null || showClose) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (title != null) {
                                Text(
                                    tr(title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (showClose) {
                                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    content()
                },
            )
        }
    }
}
