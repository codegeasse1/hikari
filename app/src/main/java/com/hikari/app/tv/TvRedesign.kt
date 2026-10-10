package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hikari.app.data.MediaItem
import com.hikari.app.i18n.tr
import com.hikari.app.ui.Artwork

@Composable
fun TvSettingsSourceCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    tonalElevation = 2.dp,
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    modifier = modifier
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .tvPress(previewPass = true, onClick = onClick),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
fun TvCertChip(label: String, modifier: Modifier = Modifier) {
  val v = label.trim().uppercase(java.util.Locale.US)
  val tint = when {
    v == "R" || v == "NC-17" || v == "TV-MA" || v == "MA" || v.startsWith("R18") -> Color(0xFFE5484D)
    v.startsWith("PG") || v == "M" -> Color(0xFFFFB020)
    v.startsWith("G") || v.startsWith("TV-Y") || v == "U" -> Color(0xFF34C759)
    else -> {
      val n = Regex("\\d+").find(v)?.value?.toIntOrNull()
      when {
        n == null -> Color(0xFF8A94A6)
        n >= 18 -> Color(0xFFE5484D)
        n >= 12 -> Color(0xFFFFB020)
        else -> Color(0xFF34C759)
      }
    }
  }
  val shape = RoundedCornerShape(9.dp)
  Box(
    modifier.clip(shape).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.45f), shape).padding(horizontal = 8.dp, vertical = 2.dp)
  ) {
    Text(label, color = tint, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

@Composable
fun TvCinemaCard(
  item: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp = 280.dp,
) {
  // A cinema card is ALWAYS a full-bleed 16:9 crop: genuinely wide art fills
  // the frame, and a portrait poster is cropped into it anchored at the TOP
  // (faces/title art, never the middle hair strip). No letterbox, no dark
  // side bars — a narrow poster floating between blanks is never the look.
  val hero = Artwork.heroModel(item)
  val art = hero.first
  // No clip on the outer Column: the corner curve used to reach down into the
  // title/year lines and bite their edges off. Only the artwork itself is
  // rounded (see the image Box below).
  Column(
    modifier.width(cardWidth).clickable(onClick = onClick).tvPress(previewPass = true, onClick = onClick),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
      AsyncImage(model = art, contentDescription = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop, alignment = Artwork.CINEMA_ALIGNMENT)
      item.rating?.takeIf { it > 0 }?.let { r ->
        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          Text("★ " + "%.1f".format(r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
      }
    }
    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
    val sub = buildList {
      item.year?.let { add(it.toString()) }
      if (item.genres.isNotEmpty()) add(item.genres.first())
    }.joinToString(" • ")
    Text(sub.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp))
  }
}

@Composable
fun TvVerticalCard(
  item: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp = 150.dp,
) {
  val art = Artwork.model(item)
  Column(
    modifier.width(cardWidth).clickable(onClick = onClick).tvPress(previewPass = true, onClick = onClick),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
      AsyncImage(model = art, contentDescription = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
      item.rating?.takeIf { it > 0 }?.let { r ->
        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          Text("★ " + "%.1f".format(r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
      }
    }
    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
    val sub = buildList {
      item.year?.let { add(it.toString()) }
      if (item.genres.isNotEmpty()) add(item.genres.first())
    }.joinToString(" • ")
    Text(sub.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp))
  }
}

@Composable
fun TvCinemaRow(
  title: String,
  providerName: String,
  items: List<MediaItem>,
  onOpen: (MediaItem) -> Unit,
  onShowAll: (() -> Unit)? = null,
  vertical: Boolean = false,
) {
  if (items.isEmpty()) return
  // The shelf's own scroll position, so the ‹ › arrows below move THIS row
  // (each row keeps its own — one shared state would drag every shelf along).
  val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
  val rowScope = androidx.compose.runtime.rememberCoroutineScope()
  Column(Modifier.padding(top = 22.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(tr(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (providerName.isNotBlank()) Text(providerName.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      // Shelf arrows, in the reference shape: step the row by a page without
      // stealing focus (the remote stays where it was — only the tiles move).
      androidx.compose.material3.IconButton(
        onClick = {
          rowScope.launch {
            val first = rowState.firstVisibleItemIndex
            rowState.animateScrollToItem((first - 4).coerceAtLeast(0))
          }
        },
        modifier = Modifier.tvPress(previewPass = true, onClick = {
          rowScope.launch {
            val first = rowState.firstVisibleItemIndex
            rowState.animateScrollToItem((first - 4).coerceAtLeast(0))
          }
        })
      ) {
        Icon(Icons.Filled.ChevronLeft, contentDescription = tr("Scroll left"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      androidx.compose.material3.IconButton(
        onClick = {
          rowScope.launch {
            val first = rowState.firstVisibleItemIndex
            rowState.animateScrollToItem(first + 4)
          }
        },
        modifier = Modifier.tvPress(previewPass = true, onClick = {
          rowScope.launch {
            val first = rowState.firstVisibleItemIndex
            rowState.animateScrollToItem(first + 4)
          }
        })
      ) {
        Icon(Icons.Filled.ChevronRight, contentDescription = tr("Scroll right"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (onShowAll != null) TextButton(onClick = onShowAll, modifier = Modifier.tvPress(previewPass = true, onClick = onShowAll)) { Text(tr("Show All"), fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(10.dp))
LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      val seen = LinkedHashSet<String>()
      val unique = items.filter { it.uniqueId.let { k -> if (seen.add(k)) true else false } }
      items(unique, key = { it.uniqueId }) { item ->
        if (vertical) TvVerticalCard(item = item, onClick = { onOpen(item) })
        else TvCinemaCard(item = item, onClick = { onOpen(item) })
      }
    }
  }
}
