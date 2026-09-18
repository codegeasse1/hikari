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
import coil.compose.AsyncImage
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.theme.rememberGlassTokens
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item -> PosterCard(item, onClick = { onClick(item) }) }
        }
    }
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
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    val style = rememberPosterStyle()
    Column(
        Modifier
            .width(120.dp)
            .clip(style.shape())
            .clickable(onClick = onClick)
    ) {
        PosterArt(
            model = Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
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
fun HeroBanner(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState { items.size }
    // Auto-advance every 6s, but stand still while the user is dragging so a
    // swipe never fights the timer (the clock restarts after the drag ends).
    LaunchedEffect(pagerState, items) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6000)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
            }
        }
    }
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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
                    if (metaLine.isNotBlank()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
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
            .clip(style.shape())
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

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
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
    val shape = RoundedCornerShape(26.dp)
    // A short fade + scale so the panel appears rather than blinks.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 170)) }
    val a = appear.value

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
