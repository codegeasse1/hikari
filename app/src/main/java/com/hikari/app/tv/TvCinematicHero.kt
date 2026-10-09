package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    val item = items[index.coerceIn(items.indices)]
    val art = remember(item) { Artwork.heroModel(item) }
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.background
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val h = (((maxHeight * 0.52f).value * scale.coerceIn(0.5f, 1.5f)).coerceIn(160f, 720f)).dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(h)
                .clip(RoundedCornerShape(20.dp))
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
                .clickable { onOpen(item) },
        ) {
            PosterImage(
                model = art.first,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT,
            )
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
                    .padding(start = 28.dp, end = 12.dp, bottom = 22.dp),
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
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 40.sp,
                )
                if (item.overview?.isNotBlank() == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onOpen(item) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Play"), fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onOpen(item) },
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(tr("View Details"), fontWeight = FontWeight.Bold)
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
