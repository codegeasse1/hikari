package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.hikari.app.data.MediaItem
import com.hikari.app.i18n.tr
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.components.PosterImage

/**
 * The television home hero: full-bleed cinematic backdrop with the title,
 * meta line, overview and Play / View Details actions — the streaming-box
 * convention from the reference shots.
 *
 * STATIC by design: no auto-advance carousel, no crossfading pager. A moving
 * hero repaints a full-screen bitmap several times a second, which is exactly
 * the jank a 1GB television box cannot afford; Left/Right on the focused hero
 * steps through the featured titles instead, one decode at a time. It also
 * draws only gradients over the art — no blur, no glass — for the same reason.
 */
@Composable
fun TvCinematicHero(
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    /** Ids (see [MediaItem.uniqueId]) the user has bookmarked — the hero's
     *  bookmark button reads them. Null toggle hides the button. */
    bookmarkedIds: Set<String> = emptySet(),
    onToggleBookmark: ((MediaItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    val item = items[index.coerceIn(items.indices)]
    // The overview's [more] expansion resets per title, like the index does.
    var overviewExpanded by remember(item) { mutableStateOf(false) }
    // The reference hero draws the title's transparent TMDB wordmark when one
    // exists (see HeroMetadataLayer's logo branch) and the text title only
    // otherwise. One lookup per hero title, cached for the hero's lifetime —
    // a miss simply keeps the text title, so this can only ever add.
    val heroLogos = remember(items) { mutableStateMapOf<String, String?>() }
    // TMDB owns the hero art (desktop parity): the title's own backdrop at full
    // file resolution, warmed for the whole rotation up front so stepping to
    // the next title paints high-quality art instead of flashing provider
    // art first. A title TMDB has nothing for keeps its provider art. A miss
    // is retried on the next rotation (see Artwork.retryMiss): a null entry
    // simply stays resolvable.
    val heroTmdb = remember(items) { mutableStateMapOf<String, String>() }
    androidx.compose.runtime.LaunchedEffect(items) {
        items.take(8).map { heroItem ->
            async {
                if (!heroTmdb.containsKey(heroItem.uniqueId)) {
                    runCatching {
                        com.hikari.app.data.TmdbScreenshots.backdropsFor(heroItem).firstOrNull()
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                        heroTmdb[heroItem.uniqueId] = it
                    }
                }
            }
        }.awaitAll()
    }
    val heroId = item.uniqueId
    androidx.compose.runtime.LaunchedEffect(heroId) {
        if (!heroLogos.containsKey(heroId)) {
            heroLogos[heroId] = runCatching { com.hikari.app.data.TmdbMeta.logo(item) }.getOrNull()
        }
    }
    val heroLogo = heroLogos[heroId]
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.background
    // Adaptive TV type (see TvUi.scale): 1.0 on phones, so the phone hero
    // never moves.
    val ts = TvUi.scale()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A television banner, not a cinema screen: about a third of the
        // viewport (capped well below fullscreen) so shelves stay visible
        // under it, the way the reference client's home looks. The user's own
        // hero-size setting still scales it from here.
        val h = (((maxHeight * 0.24f).value * scale.coerceIn(0.5f, 1.5f)).coerceIn(120f, 300f)).dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(h)
                .clip(RoundedCornerShape(TvDesktop.CornerLarge))
                .border(1.dp, TvDesktop.GlassEdge, RoundedCornerShape(TvDesktop.CornerLarge))
                // Left/Right steps through featured titles. Preview (not the
                // plain handler) so the focus system never sees the keypress
                // and the hero keeps focus while browsing.
                .onPreviewKeyEvent { event ->
                    // One press, one step: without the type check every press
                    // stepped TWICE (Down + Up), and a held key machine-gunned
                    // through the list — each step a full-bleed decode the box
                    // cannot afford (see tvPress's repeat note).
                    if (event.type != KeyEventType.KeyDown ||
                        event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            index = (index - 1 + items.size) % items.size
                            true
                        }
                        Key.DirectionRight -> {
                            index = (index + 1) % items.size
                            true
                        }
                        else -> false
                    }
                }
                .clickable { onOpen(item) }
                .tvFocusRing(TvDesktop.CornerLarge),
        ) {
            // Stepping between titles crossfades the art (the reference's
            // screen-state crossfade): an instant swap reads as a flicker on a
            // full-bleed image. Keyed on the title's id with per-key art, so
            // the outgoing backdrop paints until it has faded out. A title
            // that left the list falls back to the current art for both
            // states — a plain swap, never a blank.
            androidx.compose.animation.Crossfade(
                targetState = item.uniqueId,
                label = "tvHeroArt",
            ) { id ->
                // TMDB owns the hero (desktop parity): the title's own
                // backdrop, full file, painted SHARP — never blurred. The old
                // blurred fill is what read as low quality on every TV photo.
                // A title TMDB has nothing for keeps its provider art, sharp
                // and top-anchored like the desktop banner.
                val tmdbArt = heroTmdb[id]?.replace("/w1280/", "/original/")
                val fadeArt = if (tmdbArt != null) {
                    com.hikari.app.ui.PosterLoader.model(tmdbArt) to true
                } else {
                    remember(id) {
                        Artwork.tvHeroModel(items.firstOrNull { it.uniqueId == id } ?: item)
                    }
                }
                PosterImage(
                    model = fadeArt.first,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to bg.copy(alpha = 0.92f),
                            0.45f to bg.copy(alpha = 0.55f),
                            0.75f to Color.Transparent,
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to bg,
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.62f)
                    .padding(start = (28.dp * ts), end = (12.dp * ts), bottom = (22.dp * ts)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.rating?.takeIf { it > 0 }?.let { r ->
                        TvHeroScoreChip("%.1f".format(r))
                    }
                    Text(
                        tvHeroMeta(item),
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = MaterialTheme.typography.labelLarge.fontSize * ts,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!heroLogo.isNullOrBlank()) {
                    PosterImage(
                        model = heroLogo,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .heightIn(max = 84.dp),
                    )
                } else {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = MaterialTheme.typography.displaySmall.fontSize * ts,
                        lineHeight = 40.sp * ts,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.overview?.isNotBlank() == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * ts,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = if (overviewExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { overviewExpanded = !overviewExpanded },
                    )
                    if (!overviewExpanded) {
                        Text(
                            tr("[more]"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable { overviewExpanded = true },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The reference look: a solid white pill with a dark label,
                    // not the theme accent — the hero's primary action reads at
                    // a glance across the room.
                    Button(
                        onClick = { onOpen(item) },
                        modifier = Modifier.tvFocusRing(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Watch Now"), fontWeight = FontWeight.Bold)
                    }
                    if (onToggleBookmark != null) {
                        val saved = item.uniqueId in bookmarkedIds
                        OutlinedButton(
                            onClick = { onToggleBookmark(item) },
                            modifier = Modifier.tvFocusRing(24.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                            ),
                            border = BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.5f),
                            ),
                            contentPadding = PaddingValues(
                                horizontal = 14.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Icon(
                                if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = tr("Bookmark"),
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onOpen(item) },
                            modifier = Modifier.tvFocusRing(24.dp),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Text(tr("View Details"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (items.size > 1) {
                TvHeroDots(
                    count = items.size,
                    active = index,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 24.dp),
                )
            }
        }
    }
}

/** "2026 · Action · Comedy" — year, then genres, each already translated. */
private fun tvHeroMeta(item: MediaItem): String {
    val parts = ArrayList<String>(4)
    item.year?.let { parts += it.toString() }
    parts += item.genres.take(3)
    return parts.joinToString(" · ").ifBlank { item.type.name.lowercase() }
}

@Composable
private fun TvHeroScoreChip(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Text(
            "★ $text",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFC107),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TvHeroDots(count: Int, active: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count.coerceAtMost(8)) {
            Box(
                Modifier
                    .size(width = if (i == active) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (i == active) Color.White
                        else Color.White.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

/**
 * The television home's ambient backdrop: the first featured title's own
 * TMDB backdrop, full-bleed behind the whole feed with a glass dim over it,
 * so shelves read over art like the desktop client. ONE image, fetched once
 * per feed — the hero card's own rotation stays the only other art on
 * screen, with its own treatment untouched on top.
 */
@Composable
fun TvHomeBackdrop(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val first = items.first()
    var tmdbArt by remember(items) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(items) {
        tmdbArt = runCatching {
            com.hikari.app.data.TmdbScreenshots.backdropsFor(first).firstOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val fallback = remember(first) { Artwork.tvHeroModel(first) }
    val wide = tmdbArt != null || fallback.second
    val model = if (tmdbArt != null) com.hikari.app.ui.PosterLoader.model(tmdbArt) else fallback.first
    if (model == null) return
    val bg = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxSize()) {
        // The desktop tonal treatment (edge scrims melting into the page),
        // then a uniform glass dim so shelf text reads over bright art.
        Cs3BackdropArt(model = model, wide = wide, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(bg.copy(alpha = 0.45f))
        )
    }
}
