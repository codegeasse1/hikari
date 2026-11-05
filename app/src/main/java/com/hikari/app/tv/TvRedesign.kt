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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.data.MediaItem
import com.hikari.app.i18n.tr
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.components.SettingsThemeTile
import kotlinx.coroutines.launch

@Composable
@Suppress("UNUSED_PARAMETER")
fun TvSettingsSourceCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  // Kept for call-site compatibility; the tile is always theme-drawn now.
  iconRes: Int? = null,
) {
  Surface(
    tonalElevation = 2.dp,
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    modifier = modifier
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .tvPress(previewPass = true, onClick = onClick),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SettingsThemeTile(icon, title, 52.dp)
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
  // A cinema card is ALWAYS a full-bleed 16:9 frame, but only genuinely
  // wide art paints SHARP in it (the reference's own contract: TMDB
  // backdrops sharp, posters blurred). A portrait poster sharp-cropped into
  // 16:9 is what beheaded subjects and cut-off title text on the TV shelves —
  // so a poster-only title fills BLURRED behind the same hairline, atmosphere
  // without the crop. No letterbox, no dark side bars.
  //
  // The desktop language: 8dp corners, a true-black card that separates from
  // the page by its hairline (never a grey fill), and the focus glow + scale
  // while the remote sits on it (see tvFocusGlow — focus IS hover on a TV).
  val hero = Artwork.heroModel(item)
  val art = hero.first
  val artWide = hero.second
  val cardShape = RoundedCornerShape(TvDesktop.CornerCard)
  // No clip on the outer Column: the corner curve used to reach down into the
  // title/year lines and bite their edges off. Only the artwork itself is
  // rounded (see the image Box below).
  Column(
    modifier.width(cardWidth).clickable(onClick = onClick).tvPress(previewPass = true, onClick = onClick).tvFocusGlow(TvDesktop.CornerCard),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(cardShape).background(TvDesktop.SurfaceElevated).border(1.dp, TvDesktop.GlassEdge, cardShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Filled.Movie, contentDescription = null, tint = TvDesktop.TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
      // PosterImage, not raw AsyncImage: a request that fails once is
      // re-issued with backoff instead of parking the card on its
      // placeholder forever (the whole shelf of blank clappers on a
      // flaky mobile network).
      com.hikari.app.ui.components.PosterImage(model = art, contentDescription = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(cardShape).then(if (!artWide) Modifier.blur(16.dp, BlurredEdgeTreatment.Unbounded) else Modifier), contentScale = ContentScale.Crop, alignment = Artwork.CINEMA_ALIGNMENT)
      if (!artWide && art != null) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(cardShape).background(Color.Black.copy(alpha = 0.32f)))
        // Batch 137: a poster-only title keeps a RECOGNIZABLE card. The sharp
        // 2:3 poster sits centered (the frame is Center-aligned) over the
        // blurred atmosphere fill — instead of a full-bleed blur with nothing
        // readable, which is the "selecting another provider and all become
        // blur" report (freshly picked rows have no wide art cached yet).
        com.hikari.app.ui.components.PosterImage(model = Artwork.model(item), contentDescription = item.title, modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(TvDesktop.CornerCard)).border(1.dp, TvDesktop.GlassEdge, RoundedCornerShape(TvDesktop.CornerCard)), contentScale = ContentScale.Crop)
      }
      item.rating?.takeIf { it > 0 }?.let { r ->
        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(TvDesktop.CornerCard), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          Text("★ " + "%.1f".format(r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
      }
    }
    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TvDesktop.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
    val sub = buildList {
      item.year?.let { add(it.toString()) }
      if (item.genres.isNotEmpty()) add(item.genres.first())
    }.joinToString(" • ")
    Text(sub.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = TvDesktop.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp))
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
  // Desktop language, like the cinema card above: 8dp corners, true-black
  // surfaces, focus glow + scale while the remote sits on the card.
  val cardShape = RoundedCornerShape(TvDesktop.CornerCard)
  Column(
    modifier.width(cardWidth).clickable(onClick = onClick).tvPress(previewPass = true, onClick = onClick).tvFocusGlow(TvDesktop.CornerCard),
  ) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(cardShape).background(TvDesktop.SurfaceElevated).border(1.dp, TvDesktop.GlassEdge, cardShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Filled.Movie, contentDescription = null, tint = TvDesktop.TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
      com.hikari.app.ui.components.PosterImage(model = art, contentDescription = item.title, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(cardShape), contentScale = ContentScale.Crop)
      item.rating?.takeIf { it > 0 }?.let { r ->
        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(TvDesktop.CornerCard), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          Text("★ " + "%.1f".format(r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
      }
    }
    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TvDesktop.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
    val sub = buildList {
      item.year?.let { add(it.toString()) }
      if (item.genres.isNotEmpty()) add(item.genres.first())
    }.joinToString(" • ")
    Text(sub.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = TvDesktop.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp))
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
  /** ScrollKeeper slot for this row (see ScrollKeeper): Back from a title
   *  lands where the row was, anchored to the exact poster. Blank keeps the
   *  legacy title slot. */
  keeperKey: String = "",
) {
  if (items.isEmpty()) return
  val seen = LinkedHashSet<String>()
  val unique = items.filter { it.uniqueId.let { k -> if (seen.add(k)) true else false } }
  val rowKeys = androidx.compose.runtime.remember(unique) { unique.map { it.uniqueId } }
  // The shelf's own scroll position, so the ‹ › arrows below move THIS row
  // (each row keeps its own — one shared state would drag every shelf along).
  // Held across visits too (see ScrollKeeper).
  val rowState = if (keeperKey.isBlank()) com.hikari.app.ui.components.keptListState("tvRow:$title:$providerName")
  else com.hikari.app.ui.components.keptListState(keeperKey, itemKeys = rowKeys)
  val rowScope = androidx.compose.runtime.rememberCoroutineScope()
  Column(Modifier.padding(top = 22.dp)) {
    // The reference shelf header, one-to-one: bold title, the purple View
    // All pill, the two scroll chevrons — over the same kept scroll state.
    Cs3ShelfHeader(
      title = tr(title),
      itemCount = unique.size,
      scroll = rowState,
      onViewAll = onShowAll,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
    if (providerName.isNotBlank()) Text(providerName.uppercase(), style = MaterialTheme.typography.labelSmall, color = TvDesktop.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    Spacer(Modifier.height(10.dp))
    LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      items(unique, key = { it.uniqueId }) { item ->
        if (vertical) TvVerticalCard(item = item, onClick = { onOpen(item) })
        else TvCinemaCard(item = item, onClick = { onOpen(item) })
      }
    }
  }
}
