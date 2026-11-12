package com.hikari.app.ui.components
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.isActive
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
import com.hikari.app.ui.rememberVisibleItems
import com.hikari.app.tv.Cs3ShelfHeader
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvUi
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.shape
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import com.hikari.app.tv.tvTextFieldKeys

/** How many automatic re-requests a poster gets before its cell settles on the
 *  placeholder icon. Two is enough to ride out a dropped connection or a CDN
 *  hiccup without hammering an image that is genuinely gone. */
private const val POSTER_RETRIES = 4

@Composable
fun MediaRow(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    onShowAll: (() -> Unit)? = null,
    /** Poster hold-menu play target (detail autoplay); plain detail open when null. */
    onQuickPlay: ((MediaItem) -> Unit)? = null,
    /** Home rows draw no Show All entry (user request): the shelf headers
     *  stay clean and titles stay one tap away. Collection rows keep theirs. */
    showViewAll: Boolean = true,
    /** ScrollKeeper slot for this row's horizontal scroll (see ScrollKeeper):
     *  Back from a title lands where the row was. Blank keeps a plain state. */
    keeperKey: String = "",
    /** TV Home only: called when the remote lands on a card, so the fixed hero
     *  can describe it. Null everywhere else. */
    onItemFocus: ((MediaItem) -> Unit)? = null,
) {
    val style = rememberPosterStyle()
    key(style.posterType) {
        if (TvMode.current()) {
            com.hikari.app.tv.TvCinemaRow(title = title, providerName = providerName, items = items, onOpen = onClick, onShowAll = if (showViewAll) onShowAll else null, vertical = !style.isCinema(true), keeperKey = keeperKey, onItemFocus = onItemFocus)
        } else if (style.isCinema(false)) {
            MediaRowCinema(title, providerName, items, onClick, showViewAll, onShowAll, keeperKey)
        } else {
            MediaRowVertical(title, providerName, items, style, onClick, showViewAll, onShowAll, onQuickPlay, keeperKey)
        }
    }
}

@Composable
private fun MediaRowVertical(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    style: PosterStyle,
    onClick: (MediaItem) -> Unit,
    showViewAll: Boolean,
    onShowAll: (() -> Unit)?,
    onQuickPlay: ((MediaItem) -> Unit)?,
    keeperKey: String,
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
                    tr(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onShowAll != null && showViewAll) {
                TextButton(onClick = onShowAll) {
                    Text(tr("Show All"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val uniqueItems = rememberVisibleItems(items)
        val rowKeys = remember(uniqueItems) { uniqueItems.map { it.uniqueId } }
        // Instant vertical: warm portrait art with the row, symmetric to cinema.
        LaunchedEffect(uniqueItems) {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.hikari.app.ui.Artwork.warmRow(uniqueItems)
                }
            }
        }
        LazyRow(
            state = if (keeperKey.isBlank()) androidx.compose.foundation.lazy.rememberLazyListState()
            else keptListState(keeperKey, itemKeys = rowKeys),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uniqueItems, key = { it.uniqueId }) { item ->
                PosterCard(item, style, onClick = { onClick(item) }, onQuickPlay = onQuickPlay)
            }
        }
    }
}

@Composable
private fun MediaRowCinema(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    showViewAll: Boolean,
    onShowAll: (() -> Unit)?,
    keeperKey: String,
) {
    val uniqueItems = rememberVisibleItems(items)
    val rowKeys = remember(uniqueItems) { uniqueItems.map { it.uniqueId } }
    LaunchedEffect(uniqueItems) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.hikari.app.ui.Artwork.warmRow(uniqueItems)
            }
        }
    }
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onShowAll != null && showViewAll) {
                TextButton(onClick = onShowAll) {
                    Text(tr("Show All"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        LazyRow(
            state = if (keeperKey.isBlank()) androidx.compose.foundation.lazy.rememberLazyListState()
            else keptListState(keeperKey, itemKeys = rowKeys),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uniqueItems, key = { it.uniqueId }) { item ->
                com.hikari.app.tv.TvCinemaCard(
                    item = item,
                    onClick = { onClick(item) },
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    subColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun heroFrame(aspect: Float, horizontalInsets: Float = 0f, scale: Float = 1f): Modifier {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    val natural = (width - horizontalInsets) / aspect
    val s = scale.coerceIn(0.5f, 1.5f)
    // Settings → App Layout → Featured banner → Header height: height only, on
    // top of the size slider (1f = the height the banner always had).
    val hf = com.hikari.app.ui.HeaderPrefs.heightFactorFor(s)
    if (!TvMode.current()) {
        return if (s >= 1f) {
            Modifier.fillMaxWidth().aspectRatio(aspect / (s * hf))
        } else {
            val side = ((width - horizontalInsets) * (1f - s) / 2f).dp
            Modifier.fillMaxWidth().padding(horizontal = side).aspectRatio(aspect / hf)
        }
    }
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    return Modifier
        .fillMaxWidth()
        .height((natural * s * hf).coerceAtMost(height * 0.52f * s * hf).dp)
}

@Composable
fun PosterImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    /** Fires once the retries are exhausted and the image is definitively
     *  dead (a hotlink-guarded CDN, an expired signed URL). Callers that
     *  have a fallback model swap to it here. */
    onFinalError: (() -> Unit)? = null,
) {
    val epoch = PosterLoader.retryEpoch.value
    var attempt by remember(model, epoch) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Keep the request model stable: otherwise each recomposition makes Coil restart its painter.
    val imageModel = remember(model, attempt, epoch) {
        PosterLoader.retryModel(model, attempt, epoch)
    }
    AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        onError = { state ->
            // Decode/network failure census (File decodes and 200-with-garbage
            // bypass the Image interceptor, so this is the only line they get).
            com.hikari.app.data.Logs.traceSampledEvery(
                "Poster", "coil-error", 20L,
            ) {
                val err = (state as? coil.compose.AsyncImagePainter.State.Error)
                    ?.result?.throwable
                "coil ${if (attempt >= POSTER_RETRIES) "final" else "retry#${attempt + 1}"} " +
                    "model=${describePosterModel(model)} " +
                    "err=${err?.javaClass?.simpleName}: ${err?.message?.take(160)}"
            }
            if (attempt < POSTER_RETRIES) {
                scope.launch {
                    delay(700L * (attempt + 1))
                    attempt++
                }
            } else {
                onFinalError?.invoke()
            }
        },
    )
}

/** Staggered grid reveal: cards fade + scale in one after another instead of
 *  popping in all at once. New rows animate as result pages stream in; the
 *  delay is capped so long grids never take forever. Keyed on [index] within
 *  the current list — pair with stable item keys so scrolled-in tiles don't
 *  re-animate. Exit is intentionally instant (lazy grids can't animate removals
 *  without holding dead tiles); moves are handled by Modifier.animateItem at
 *  the call site where it matters. */
@Composable
fun StaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(((index % 24) * 30).coerceAtMost(360).toLong())
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(animationSpec = tween(240)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(240)),
        modifier = modifier,
    ) {
        content()
    }
}

/** Hero (shared element) plumbing for the poster-travel animation.
 *
 *  AppNav wraps the NavHost in a SharedTransitionLayout and every destination
 *  in [HeroScope]; both scopes are published through these locals so ANY
 *  poster tile can opt in with one modifier ([Modifier.heroPoster]) without
 *  threading scope parameters through rows, cards and screens. Outside a
 *  destination (or before navigation 2.8) the modifier is a no-op. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalHeroSharedScope =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }

val LocalHeroAnimatedScope =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedContentScope?> { null }

/** Publishes this destination's AnimatedContentScope (see AppNav). */
@Composable
fun androidx.compose.animation.AnimatedContentScope.HeroScope(
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalHeroAnimatedScope provides this,
    ) {
        content()
    }
}

/** Makes this poster art the travelling hero: on navigation to a screen that
 *  marks the same [key] (see DetailScreen), the tile visually flies from the
 *  grid into its detail position — and back on return. Mismatched keys simply
 *  don't animate (no crash, no freeze), so grids whose items can't be rebuilt
 *  on the detail side degrade to the plain slide. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
fun Modifier.heroPoster(key: String): Modifier = composed {
    val sts = LocalHeroSharedScope.current
    val avs = LocalHeroAnimatedScope.current
    if (sts == null || avs == null || key.isBlank()) return@composed this
    // The shared-transition crash ("current bounds not set yet") fires when a
    // hero registers without laid-out bounds (an off-screen or zero-size tile
    // joining a navigation). Keep the modifier inert until THIS tile reports a
    // real size, so only on-screen tiles ever join the transition — anything
    // else degrades to the plain slide, never a crash.
    var laidOut by remember(key) { mutableStateOf(false) }
    val heroState = with(sts) { rememberSharedContentState(key = "poster:$key") }
    this
        .onSizeChanged { laidOut = it.width > 0 && it.height > 0 }
        .then(
            if (laidOut) with(sts) {
                sharedElement(
                    state = heroState,
                    animatedVisibilityScope = avs,
                )
            } else Modifier
        )
}

/** Infinite auto-scrolling poster carousel ("marquee") with a parallax glow.
 *
 *  The strip holds [copies] repeats of the cards and slides them by exactly
 *  one copy width on a seamless loop: reaching the end snaps back to zero,
 *  which renders identically because every copy is the same. Each section
 *  passes its own [speedDpPerSec] and [reverse], so stacked rows drift
 *  against each other instead of marching in lockstep (the parallax read),
 *  while a glow layer slides behind the posters at 45% speed for depth.
 *  Touching the strip pauses it; it resumes a moment after release.
 *  Phone only — callers must not place this on TV (a 1GB box cannot afford
 *  always-on repaint loops). */
@Composable
fun AutoScrollPosterRow(
    title: String,
    providerName: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    /** Poster widths per second; every row gets its own speed so stacked
     *  marquee rows drift against each other. */
    speedDpPerSec: Float = 34f,
    /** Alternate direction per section: stacked rows counter-scroll. */
    reverse: Boolean = false,
    onShowAll: (() -> Unit)? = null,
) {
    val cards = remember(items) {
        val distinct = items.distinctBy { it.uniqueId }
        if (distinct.isEmpty()) emptyList()
        else buildList { do { addAll(distinct) } while (size < 8) }
    }
    if (cards.isEmpty()) return
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onShowAll != null) {
                TextButton(onClick = onShowAll) {
                    Text(tr("Show All"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Instant vertical: warm portrait art with the row, like MediaRow.
        val warm = rememberVisibleItems(cards)
        LaunchedEffect(warm) {
            runCatching {
                withContext(Dispatchers.IO) { Artwork.warmRow(warm) }
            }
        }
        val density = LocalDensity.current
        val speedPx = with(density) { speedDpPerSec.dp.toPx() }.coerceAtLeast(1f)
        // Full strip width in px; one copy is exactly a third of it (every
        // copy carries its own trailing gap, so copies tile seamlessly).
        var stripPx by remember { mutableIntStateOf(0) }
        val copies = 3
        val offset = remember(cards, reverse) { Animatable(if (reverse) 1f else 0f) }
        var paused by remember(cards) { mutableStateOf(false) }
        val pausedNow by rememberUpdatedState(paused)
        LaunchedEffect(stripPx, speedPx, reverse) {
            val span = stripPx / copies.toFloat()
            if (span <= 0f) return@LaunchedEffect
            offset.snapTo(if (reverse) span else 0f)
            // Release grace: after a touch ends the row holds still briefly
            // before gliding again (the timer lives here, in a normal scope,
            // because the gesture scope below forbids delay()).
            var coolDown = false
            while (isActive) {
                if (pausedNow) {
                    coolDown = true
                    delay(120)
                    continue
                }
                if (coolDown) {
                    coolDown = false
                    delay(2500)
                    continue
                }
                val target = if (reverse) 0f else span
                if (abs(offset.value - target) < 0.5f) {
                    // End of the loop renders identically to the start.
                    offset.snapTo(if (reverse) span else 0f)
                    continue
                }
                val durMs = ((abs(target - offset.value) / speedPx) * 1000)
                    .toInt().coerceAtLeast(1)
                offset.animateTo(target, tween(durMs, easing = LinearEasing))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(cards) {
                    // Touch pauses the glide; release resumes it (after the
                    // grace period in the loop above). Only restricted-scope
                    // calls are allowed in here — no delay(), no launch.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        paused = true
                        try {
                            waitForUpOrCancellation()
                        } catch (_: Exception) {
                            // Gesture stolen mid-press: treat as a release.
                        }
                        paused = false
                    }
                }
        ) {
            // Parallax glow: one soft disc per copy, sliding at 45% speed.
            // offset is read inside graphicsLayer only, so the slide never
            // recomposes — it just moves the layer.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(196.dp)
                    .graphicsLayer {
                        val span = stripPx / copies.toFloat()
                        translationX =
                            if (span > 0f) -((offset.value * 0.45f) % span) else 0f
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val spanDp = with(density) { (stripPx / copies.toFloat()).toDp() }
                repeat(copies) {
                    Box(
                        Modifier
                            .width(spanDp.coerceAtLeast(0.dp))
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(150.dp)
                                .background(
                                    Brush.radialGradient(
                                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                        1f to Color.Transparent,
                                    ),
                                    RoundedCornerShape(75.dp),
                                )
                        )
                    }
                }
            }
            // The poster strip itself, sliding at full speed.
            Row(
                Modifier
                    .onSizeChanged { stripPx = it.width }
                    .graphicsLayer { translationX = -offset.value },
            ) {
                repeat(copies) { copy ->
                    key(copy) {
                        Row(
                            Modifier.padding(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            cards.forEach { card ->
                                key(card.uniqueId) {
                                    AutoScrollPosterCard(card, onClick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One marquee card: a plain 2:3 poster with its title. No [Modifier.heroPoster]
 *  here on purpose — the strip holds several copies of every card and shared
 *  elements require unique keys. */
@Composable
private fun AutoScrollPosterCard(item: MediaItem, onClick: (MediaItem) -> Unit) {
    Column(
        Modifier
            .width(112.dp)
            .clickable { onClick(item) }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = Artwork.model(item),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Short, secret-free description of a Coil model for the coil-error lines. */
private fun describePosterModel(model: Any?): String = when (model) {
    null -> "<null>"
    is java.io.File -> "file:${model.name} len=${runCatching { model.length() }.getOrDefault(-1)}"
    is String -> com.hikari.app.data.Logs.safeUrl(model)
    is coil.request.ImageRequest -> "req(" + describePosterModel(model.data) + ")"
    else -> model.javaClass.simpleName
}

@Composable
fun HeroArtwork(
    model: Any?,
    wide: Boolean,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    // Kept for call compatibility; the fill is always a top-anchored crop now.
    fillAlignment: Alignment = Alignment.Center,
    onFinalError: (() -> Unit)? = null,
) {
    if (model == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    PosterImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT,
        onFinalError = onFinalError,
    )
}

@Composable
fun PosterCard(
    item: MediaItem,
    style: PosterStyle,
    onClick: () -> Unit,
    /** Hold-menu play action (opens detail with autoplay); plain open when null. */
    onQuickPlay: ((MediaItem) -> Unit)? = null,
) {
    val badge = rememberPosterScore(item, style)
    val cardWidth = if (TvMode.current()) TvUi.posterWidth() else 120.dp
    val quickMenu = rememberQuickMenuState()
    val latestClick by rememberUpdatedState(onClick)
    val latestPlay by rememberUpdatedState(onQuickPlay)
    val latestItem by rememberUpdatedState(item)
    var quickLibraryItem by remember { mutableStateOf<MediaItem?>(null) }
    Column(
        Modifier
            .width(cardWidth)
            .clickable(onClick = onClick)
    ) {
        // Lookahead-crash guard: aspectRatio (sizing) and the shared-element
        // hero must live on DIFFERENT nodes. Same-node aspect+sharedElement
        // crashes lookahead measurement mid-transition
        // ("LookaheadDelegate has not been measured yet"). The outer box sizes;
        // the inner full-size box is what travels.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
        Box(
            Modifier
                .fillMaxSize()
                .heroPoster(item.uniqueId)
                .quickMenuGesture(quickMenu) { slot ->
                    when (slot) {
                        0 -> latestClick()
                        1 -> {
                            val play = latestPlay
                            if (play != null) play(item) else latestClick()
                        }
                        // Slot 2 opens the "which categories?" sheet (the same
                        // one the detail screen's heart opens); slot 3 (Close)
                        // dismisses with no action, so an opened menu never
                        // forces a pick.
                        2 -> quickLibraryItem = latestItem
                        else -> {}
                    }
                }
        ) {
            PosterArt(
                model = Artwork.model(item),
                contentDescription = item.title,
                style = style,
                rating = item.rating,
                imdb = badge,
                item = item,
                modifier = Modifier.fillMaxSize(),
            )
            if (quickMenu.visible) QuickMenuOverlay(quickMenu.selected)
            }
        }
        quickLibraryItem?.let { picked ->
            QuickAddToLibrarySheet(item = picked, onDismiss = { quickLibraryItem = null })
        }
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
    /** A second, usually quieter action beside [actionLabel] — Home's
     *  "Verify site" next to "Retry" for an extension behind a bot wall. */
    action2Label: String? = null,
    action2: (() -> Unit)? = null,
    /** The engine's own words for WHY it is empty — an exception name and
     *  message, an HTTP status, a "could not be loaded". Shown small and quiet
     *  under [subtitle], verbatim: it is the one line that makes an empty
     *  catalog diagnosable instead of mysterious (see CatalogViewModel). */
    detail: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Television empty states read white/muted on the black page.
        val tv = TvMode.current()
        Icon(
            Icons.Filled.Movie,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            tr(title),
            style = MaterialTheme.typography.titleMedium,
            color = if (tv) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
        )
        Text(
            tr(subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (!detail.isNullOrBlank()) {
            Text(
                tr(detail),
                style = MaterialTheme.typography.labelSmall,
                color = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        if (actionLabel != null && action != null) {
            Button(onClick = action, modifier = Modifier.padding(top = 16.dp)) {
                Text(tr(actionLabel))
            }
        }
        if (action2Label != null && action2 != null) {
            TextButton(onClick = action2, modifier = Modifier.padding(top = 4.dp)) {
                Text(tr(action2Label))
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
    /** When set, the inner text field takes focus through this requester. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** Keyboard action key (default: unchanged). The search screen uses Search. */
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val glass = rememberGlassTokens()
    // Television search field: flat elevated-black pill with a hairline edge
    // (the reference search bar); phones keep the glass pill.
    val tv = TvMode.current()
    val fieldShape = RoundedCornerShape(30.dp)
    Surface(
        shape = fieldShape,
        color = if (tv) com.hikari.app.tv.TvDesktop.SurfaceElevated
        else if (glass.dark) glass.fillTop else MaterialTheme.colorScheme.surface,
        shadowElevation = if (glass.dark || tv) 0.dp else 4.dp,
        modifier = modifier.border(
            1.dp,
            if (tv) com.hikari.app.tv.TvDesktop.GlassEdge else glass.border,
            fieldShape,
        )
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
                tint = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (tv) com.hikari.app.tv.TvDesktop.TextPrimary
                        else MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Clear"),
                        tint = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

object HeroStyles {
    const val CAROUSEL = "carousel"
    const val SPOTLIGHT = "spotlight"
    const val COMPACT = "compact"
    const val SHOWCASE = "showcase"
    /** Full-width cinematic header: big centred title, one pill button, morphing dots. */
    const val IMMERSIVE = "immersive"

    val ALL = listOf(IMMERSIVE, CAROUSEL, SPOTLIGHT, COMPACT, SHOWCASE)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else CAROUSEL

    fun label(key: String): String = when (normalize(key)) {
        IMMERSIVE -> "Immersive"
        SPOTLIGHT -> "Spotlight"
        COMPACT -> "Compact strip"
        SHOWCASE -> "Showcase"
        else -> "Carousel"
    }

    fun description(key: String): String = when (normalize(key)) {
        IMMERSIVE -> "Edge-to-edge artwork with a big centred title"
        SPOTLIGHT -> "Full-width cinematic banner with the plot"
        COMPACT -> "A short strip, so the feed starts sooner"
        SHOWCASE -> "Poster beside the details, never cropped"
        else -> "Wide 16:9 cards that peek in from the sides"
    }
}

data class HeroConfig(
    val style: String = HeroStyles.CAROUSEL,
    val showOverview: Boolean = true,
    val showRating: Boolean = true,
    val showMeta: Boolean = true,
    val scale: Float = 1f,
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
    if (TvMode.current()) {
        HeroStaticCard(item = items.first(), onClick = onClick, modifier = modifier, config = config)
        return
    }
    when (HeroStyles.normalize(config.style)) {
        HeroStyles.IMMERSIVE -> HeroImmersive(items, onClick, modifier, config)
        HeroStyles.SPOTLIGHT -> HeroSpotlight(items, onClick, modifier, config)
        HeroStyles.COMPACT -> HeroCompact(items, onClick, modifier, config)
        HeroStyles.SHOWCASE -> HeroShowcase(items, onClick, modifier, config)
        else -> HeroCarousel(items, onClick, modifier, config)
    }
}

@Composable
private fun HeroImmersive(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState { items.size }
    LaunchedEffect(pagerState, items) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6500)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(
                    (pagerState.currentPage + 1) % items.size,
                    animationSpec = tween(
                        durationMillis = 800,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                )
            }
        }
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenW = configuration.screenWidthDp.toFloat()
    val screenH = configuration.screenHeightDp.toFloat()
    val s = config.scale.coerceIn(0.5f, 1.5f)
    val verticalFactor = s * com.hikari.app.ui.HeaderPrefs.heightFactorFor(s)
    val heroHeight = (screenW * 1.1f * verticalFactor).coerceIn(180f, screenH * 0.75f).dp
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(heroHeight)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val heroScore = if (config.showRating) rememberHeroScore(item) else null
            val heroEn = rememberHeroEnriched(item)
            // 0 while this page is centred, +1 / -1 one page to the right / left.
            val offsetOf = { (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val o = kotlin.math.abs(offsetOf()).coerceAtMost(1f)
                        alpha = 1f - 0.6f * o
                    }
                    .clickable { onClick(item) }
            ) {
                val hero = Artwork.heroModel(item)
                HeroArtwork(
                    model = hero.first,
                    wide = hero.second,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // Soft top (so the art grows out of the app bar) and a long
                // bottom fade into the page colour (so it melts into the feed).
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to bg.copy(alpha = 0.85f),
                                0.12f to Color.Transparent,
                                0.45f to Color.Transparent,
                                0.72f to bg.copy(alpha = 0.55f),
                                1f to bg,
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 34.dp)
                        .graphicsLayer {
                            val o = offsetOf()
                            translationX = o * size.width * 0.35f
                            alpha = (1f - kotlin.math.abs(o) * 1.6f).coerceIn(0f, 1f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        item.title,
                        style = (
                            if (item.title.length > 24) MaterialTheme.typography.headlineMedium
                            else MaterialTheme.typography.displaySmall
                            ).copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.6f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 3f),
                                blurRadius = 14f,
                            )
                        ),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaLine = buildList {
                        when (item.type) {
                            MediaType.MOVIE -> add("Movie")
                            MediaType.SERIES -> add("Series")
                            else -> {}
                        }
                        (if (item.genres.isNotEmpty()) item.genres else heroEn?.genres.orEmpty()).take(1).forEach { add(it) }
                        (item.year ?: heroEn?.year)?.let { add(it.toString()) }
                    }.joinToString("  \u2022  ")
                    if ((config.showMeta && metaLine.isNotBlank()) || heroScore != null) {
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (heroScore != null) {
                                HeroScoreChip(heroScore)
                                Spacer(Modifier.width(8.dp))
                            }
                            if (config.showMeta && metaLine.isNotBlank()) {
                                Text(
                                    metaLine,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                        Text(
                            heroOverview(item)!!.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onClick(item) },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text(
                            tr("View Details"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Follows the finger: the dot nearest the pager's position
                // stretches into a pill, the others shrink back.
                val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                items.indices.forEach { i ->
                    val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .size(width = (6f + 18f * near).dp, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.38f + 0.62f * near))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCarousel(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState { items.size }
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
    val frame = heroFrame(16f / 9f, horizontalInsets = 40f, scale = config.scale)
    Column(modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
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
                    val heroEn = rememberHeroEnriched(item)
                    val metaLine = buildList {
                        when (item.type) {
                            MediaType.MOVIE -> add("Movie")
                            MediaType.SERIES -> add("Series")
                            else -> {}
                        }
                        (if (item.genres.isNotEmpty()) item.genres else heroEn?.genres.orEmpty()).take(2).forEach { add(it) }
                        (item.year ?: heroEn?.year)?.let { add(it.toString()) }
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
                    if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                        Text(
                            heroOverview(item)!!.trim(),
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

@Composable
private fun HeroStaticCard(
    item: MediaItem,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    val heroScore = if (config.showRating) rememberHeroScore(item) else null
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .then(heroFrame(16f / 9f, horizontalInsets = 40f, scale = config.scale))
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
                val heroEn = rememberHeroEnriched(item)
                val metaLine = buildList {
                    when (item.type) {
                        MediaType.MOVIE -> add("Movie")
                        MediaType.SERIES -> add("Series")
                        else -> {}
                    }
                    (if (item.genres.isNotEmpty()) item.genres else heroEn?.genres.orEmpty()).take(2).forEach { add(it) }
                    (item.year ?: heroEn?.year)?.let { add(it.toString()) }
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
                if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                    Text(
                        heroOverview(item)!!.trim(),
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
}

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
@Composable
private fun heroMetaLine(item: MediaItem): String {
    val enriched = rememberHeroEnriched(item)
    return heroMetaLineBuilt(item, enriched)
}

private fun heroMetaLineBuilt(item: MediaItem, enriched: HeroEnriched? = null): String = buildList {
    when (item.type) {
        MediaType.MOVIE -> add("Movie")
        MediaType.SERIES -> add("Series")
        else -> {}
    }
    (if (item.genres.isNotEmpty()) item.genres else enriched?.genres.orEmpty()).take(2).forEach { add(it) }
    (item.year ?: enriched?.year)?.let { add(it.toString()) }
}.joinToString("  ·  ")

private data class HeroEnriched(val year: Int?, val overview: String?, val genres: List<String>)
private val heroEnrichCache = ConcurrentHashMap<String, HeroEnriched?>()
private val heroEnrichStates = ConcurrentHashMap<String, androidx.compose.runtime.MutableState<HeroEnriched?>>()
private val heroEnrichRunning = ConcurrentHashMap.newKeySet<String>()
private val heroEnrichMiss = ConcurrentHashMap.newKeySet<String>()
private fun heroEnrichKey(item: MediaItem): String = item.providerId + "|" + item.type.name + "|" + item.id
@Composable
private fun rememberHeroEnriched(item: MediaItem): HeroEnriched? {
    val key = heroEnrichKey(item)
    val holder = remember(key) { heroEnrichStates.getOrPut(key) { mutableStateOf(heroEnrichCache[key]) } }
    if ((item.overview.isNullOrBlank() || item.year == null) && holder.value == null && !heroEnrichMiss.contains(key) && heroEnrichRunning.add(key)) {
        LaunchedEffect(key) {
            try {
                val got = withContext(Dispatchers.IO) { enrichHeroItem(item) }
                if (got != null) { heroEnrichCache[key] = got; holder.value = got } else heroEnrichMiss.add(key)
            } finally { heroEnrichRunning.remove(key) }
        }
    }
    return holder.value
}
@Composable
private fun heroOverview(item: MediaItem): String? {
    val enriched = rememberHeroEnriched(item)
    return item.overview?.takeIf { it.isNotBlank() } ?: enriched?.overview
}
private suspend fun enrichHeroItem(item: MediaItem): HeroEnriched? = runCatching {
    val resolved = com.hikari.app.nuvio.TmdbResolver.resolve(item) ?: return null
    val details = com.hikari.app.nuvio.TmdbResolver.apiGet("/" + resolved.mediaType + "/" + resolved.tmdbId, emptyMap<String, String>()) ?: return null
    val overview = details.optString("overview").ifBlank { null }
    val date = details.optString("first_air_date").ifBlank { details.optString("release_date") }
    val year = date.take(4).toIntOrNull()
    val genres = runCatching {
        val arr = details.getJSONArray("genres")
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name").ifBlank { null } }.take(2)
    }.getOrDefault(emptyList())
    if (overview == null && year == null && genres.isEmpty()) null else HeroEnriched(year, overview, genres)
}.getOrNull()

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

@Composable
private fun HeroSpotlight(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    config: HeroConfig = HeroConfig(),
) {
    val pagerState = rememberPagerState { items.size }
    HeroAutoAdvance(pagerState, items.size, 7000L)
    val frame = heroFrame(3f / 2f, scale = config.scale)
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
                    if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                        Text(
                            heroOverview(item)!!.trim(),
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
                    .height(
                        (
                            148f * config.scale.coerceIn(0.5f, 1.5f) *
                                com.hikari.app.ui.HeaderPrefs.heightFactorFor(config.scale)
                            ).coerceAtLeast(72f).dp
                    )
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
                    if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                        Text(
                            heroOverview(item)!!.trim(),
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
                Box {
                    val backdrop = Artwork.backdropModel(item)
                    if (backdrop != null) {
                        HeroArtwork(
                            model = backdrop,
                            wide = true,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                        )
                        // Steady dim so the poster and the text read over any
                        // art, bright or dark.
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.30f),
                                    1f to Color.Black.copy(alpha = 0.55f),
                                )
                            )
                        )
                    }
                Row(Modifier.padding(12.dp)) {
                    PosterArt(
                        model = Artwork.model(item),
                        contentDescription = item.title,
                        style = rememberPosterStyle(),
                        rating = item.rating,
                        imdb = heroScore,
                        modifier = Modifier
                            .width((96f * config.scale.coerceIn(0.5f, 1.5f)).dp)
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
                        if (config.showOverview && !heroOverview(item).isNullOrBlank()) {
                            Text(
                                heroOverview(item)!!.trim(),
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
                } // Box: backdrop art behind the detail row above
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
    /** When non-null the TV header shows the reference's "Clear History"
     *  action beside the shelf header (it clears the same shared store). */
    onClearAll: (() -> Unit)? = null,
    /** TV Home only: called when the remote lands on a card. */
    onItemFocus: ((HistoryEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    // Defensive dedupe: a duplicate Compose key would crash the whole row.
    val unique = remember(entries) { entries.distinctBy { it.uniqueKey } }
    val nsfwOn = com.hikari.app.ui.rememberNsfwEnabled()
    val shown = remember(unique, nsfwOn) {
        if (nsfwOn) unique
        else unique.filter { !com.hikari.app.data.NsfwGate.isAdultText(it.title, emptyList()) }
    }
    if (TvMode.current()) {
        // The reference Continue Watching header: bold title, the "Clear
        // History" action and the two scroll chevrons over the same rail.
        val tvRowState = androidx.compose.foundation.lazy.rememberLazyListState()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 22.dp)
        ) {
            Cs3ShelfHeader(
                title = tr("Continue Watching"),
                itemCount = shown.size,
                scroll = tvRowState,
                onViewAll = null,
                leadingExtra = if (onClearAll != null) {
                    {
                        androidx.compose.material3.TextButton(onClick = onClearAll) {
                            Text(
                                tr("Clear History"),
                                color = com.hikari.app.tv.TvDesktop.TextMuted,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else null,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(
                state = tvRowState,
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown, key = { it.uniqueKey }) { h ->
                    ContinueWatchingCard(
                        h = h,
                        backdrop = backdropOf(h),
                        removable = onRemove != null,
                        onClick = { onClick(h) },
                        onRemove = { onRemove?.invoke(h) },
                        onFocus = { onItemFocus?.invoke(h) },
                    )
                }
            }
        }
        return
    }
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            tr("Continue Watching"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (TvMode.current()) com.hikari.app.tv.TvDesktop.TextPrimary
            else Color.Unspecified,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shown, key = { it.uniqueKey }) { h ->
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
    onFocus: () -> Unit = {},
) {
    val style = rememberPosterStyle()
    val fraction = if (h.durationMs > 0L) {
        (h.positionMs.toFloat() / h.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remaining = (h.durationMs - h.positionMs).coerceAtLeast(0L)
    Column(
        Modifier
            .width(230.dp)
            .onFocusChanged { if (it.hasFocus) onFocus() }
            // Not clipped: the rounding lives on the thumbnail itself, so the
            // title under it cannot be eaten by the corner curve.
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(style.shape())
                .background(
                    if (TvMode.current()) com.hikari.app.tv.TvDesktop.SurfaceElevated
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    if (TvMode.current()) Modifier.border(
                        1.dp,
                        com.hikari.app.tv.TvDesktop.GlassEdge,
                        style.shape(),
                    ) else Modifier
                )
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
                if ((h.episodeName.isNotBlank() || h.episodeId.isNotBlank()) &&
                    h.type != com.hikari.app.data.MediaType.MOVIE
                ) {
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
    val tv = TvMode.current()
    val cardShape = if (tv) RoundedCornerShape(com.hikari.app.tv.TvDesktop.CornerLarge) else shape
    Column(
        modifier
            .fillMaxWidth()
            .then(if (glass.dark || tv) Modifier else Modifier.shadow(3.dp, shape, clip = false))
            .clip(cardShape)
            // A whisper of white at the top falling to half of that at the
            // bottom — the falloff is what reads as glass (see GlassTokens).
            .then(
                if (tv) Modifier.background(com.hikari.app.tv.TvDesktop.SurfaceElevated)
                else Modifier.background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)), shape)
            )
            .border(
                1.dp,
                if (tv) com.hikari.app.tv.TvDesktop.GlassEdge else glass.border,
                cardShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = true,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberGlassTokens()
    val tv = TvMode.current()
    val shape = if (tv) RoundedCornerShape(com.hikari.app.tv.TvDesktop.CornerLarge) else GlassShape

    // ---- Television SAFE FRAME --------------------------------------------
    // The reported bug: on a television every picker (Type / Year / Genre,
    // Mark, ...) came up half cut — its right side and its bottom ran past the
    // edge of the picture. Three things caused it, and all three are closed
    // here, for every dialog that goes through GlassDialog:
    //   1. The panel was a fixed 92% of the DIALOG WINDOW. A dialog is its own
    //      window and on some boxes that window is not the same size as the
    //      screen the app itself fits into, so "92% of the window" could be
    //      wider than the visible picture. The panel is now laid out inside a
    //      frame that has exactly the size of the activity's own window (the
    //      area the whole app already fits in), anchored to its top-left.
    //   2. Pickers asked for a list up to 440dp tall on top of the title, the
    //      search field and the padding — taller than a 540dp television. The
    //      panel now has a hard ceiling (frame height minus margins) and the
    //      list inside it takes whatever is left and scrolls.
    //   3. Televisions crop the picture's edges. The panel now keeps a margin
    //      from every edge (at least 32dp, or the user's own TV edge setting
    //      from Settings -> TV & Remote when that is larger).
    // Phones are untouched: all of this is skipped when [tv] is false.
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    val hostConfig = androidx.compose.ui.platform.LocalConfiguration.current
    val hostDensity = androidx.compose.ui.platform.LocalDensity.current
    val tvApp = remember { runCatching { com.hikari.app.HikariApp.instance }.getOrNull() }
    val tvEdgeSetting by (tvApp?.store?.tvOverscanFlow() ?: kotlinx.coroutines.flow.flowOf(0))
        .collectAsState(initial = 0)
    val hostDecor = glassDialogHostActivity(hostContext)?.window?.decorView
    val decorWpx = hostDecor?.width ?: 0
    val decorHpx = hostDecor?.height ?: 0
    val frameWdp: Float =
        if (decorWpx > 0 && hostDensity.density > 0f) decorWpx / hostDensity.density
        else hostConfig.screenWidthDp.toFloat()
    val frameHdp: Float =
        if (decorHpx > 0 && hostDensity.density > 0f) decorHpx / hostDensity.density
        else hostConfig.screenHeightDp.toFloat()
    val edgeXdp = maxOf(tvEdgeSetting, 32).toFloat()
    val edgeYdp = maxOf(tvEdgeSetting, 28).toFloat()
    val cardMaxWdp = (frameWdp - 2f * edgeXdp).coerceIn(240f, 720f)
    val cardMaxHdp = (frameHdp - 2f * edgeYdp).coerceAtLeast(200f)

    // A short fade + scale so the panel appears rather than blinks.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 170)) }
    val a = appear.value

    LaunchedEffect(tv, initialFocus) {
        val target = initialFocus ?: return@LaunchedEffect
        if (!tv) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // The panel itself. Pulled into a local so a phone can draw it straight
    // into the centred window (exactly as before) while a television draws it
    // inside the safe frame below.
    val card: @Composable () -> Unit = {
        Column(
            modifier
                .then(
                    if (tv) Modifier
                        .widthIn(max = cardMaxWdp.dp)
                        .heightIn(max = cardMaxHdp.dp)
                        .fillMaxWidth()
                    else Modifier.fillMaxWidth(0.92f)
                )
                .graphicsLayer {
                    scaleX = 0.94f + 0.06f * a
                    scaleY = 0.94f + 0.06f * a
                }
                // Swallow taps: reaching for a row must not close the dialog.
                // …and for the same reason as the scrim, it must not be a
                // focus target on a television: it wraps every row, so the
                // D-pad used to stop on it and OK did nothing at all (its
                // handler is empty by design).
                .then(if (tv) Modifier.focusProperties { canFocus = false } else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .then(if (glass.dark && !tv) Modifier else Modifier.shadow(6.dp, shape, clip = false))
                .clip(shape)
                .background(
                    if (tv) com.hikari.app.tv.TvDesktop.SurfaceElevated
                    else MaterialTheme.colorScheme.surface
                )
                .then(
                    if (tv) Modifier
                    else Modifier.background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                )
                .border(
                    1.dp,
                    if (tv) com.hikari.app.tv.TvDesktop.GlassEdge else glass.border,
                    shape,
                )
                .padding(if (tv) 16.dp else 20.dp),
            content = {
                if (title != null || showClose) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (title != null) {
                            Text(
                                tr(title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (tv) com.hikari.app.tv.TvDesktop.TextPrimary
                                else MaterialTheme.colorScheme.onSurface,
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
                                    tint = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
                .then(if (tv) Modifier.focusProperties { canFocus = false } else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            // Television: the safe frame below is pinned to the window's
            // physical top-left (AbsoluteAlignment, so a right-to-left
            // language does not flip it to the other corner) and centres the
            // panel inside itself.
            contentAlignment = if (tv) androidx.compose.ui.AbsoluteAlignment.TopLeft
            else Alignment.Center,
        ) {
            if (tv) {
                Box(
                    Modifier.size(frameWdp.dp, frameHdp.dp),
                    contentAlignment = Alignment.Center,
                ) { card() }
            } else {
                card()
            }
        }
    }
}

/** The Activity behind a Compose [context] (which is usually a ContextWrapper
 *  chain), or null. [GlassDialog] reads its window size on a television. */
private fun glassDialogHostActivity(context: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun VerificationNudge(
    waiting: Boolean,
    onOpenWebView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    // "Armed" = this wait has not shown its chip yet. It is set by the wait
    // STARTING, so one long wait shows the nudge once, however long it lasts.
    var armed by remember { mutableStateOf(true) }
    LaunchedEffect(waiting) {
        if (!waiting) {
            armed = true
            visible = false
            return@LaunchedEffect
        }
        if (!armed) return@LaunchedEffect
        armed = false
        delay(WAIT_MS)
        if (!waiting) return@LaunchedEffect
        visible = true
        delay(SHOW_MS)
        visible = false
    }
    if (!visible) return
    Surface(
        onClick = {
            visible = false
            onOpenWebView()
        },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Not loading? Tap to verify in the WebView"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
                onClick = { visible = false },
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = tr("Dismiss"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** Ten seconds of no answer before the nudge appears, and three seconds of it
 *  being on screen (see [VerificationNudge]). */
private const val WAIT_MS = 10_000L
private const val SHOW_MS = 3_000L
