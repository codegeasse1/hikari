package com.hikari.app.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.tv.TvUi
import com.hikari.app.tv.tvPress

/**
 * Shared state of the television music UI. [playerOpen] is read by AppRoot so the navigation rail
 * steps out of the way while the full-screen player is on screen (like the reference TV player).
 */
object TvMusicUi {
    var playerOpen by mutableStateOf(false)
}

/** Light text on the dark TV music pages. */
internal val TvMusicMuted = Color.White.copy(alpha = 0.68f)

/** Ink used on white (selected / filled) controls. */
internal val TvMusicInk = Color(0xFF0C0C16)

internal fun MSong.asItem(): MItem = MItem(
    kind = MKind.SONG,
    id = id,
    title = title,
    subtitle = artist,
    thumb = thumb,
    song = this,
)

/**
 * The one focus look of the TV music pages: the card grows a little while the D-pad is on it (the
 * app-wide TV focus ring from TvFocusProvider is drawn by the click indication on top of that).
 *
 * [previewPass] must be false for a row that holds another focusable control (the song's menu
 * button): the row then only reacts to a press the inner control did not take.
 */
@Composable
fun Modifier.tvMusicFocus(
    shape: Shape,
    focusedScale: Float = 1.06f,
    previewPass: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(150),
        label = "tvMusicFocusScale",
    )
    val focusCallback by rememberUpdatedState(onFocused)
    return this
        .onFocusChanged { state ->
            val now = state.hasFocus
            if (now && !focused) focusCallback()
            focused = now
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .tvPress(previewPass = previewPass, onClick = onClick)
        .clickable(onClick = onClick)
}

// ---------------------------------------------------------------------- buttons

@Composable
fun TvMusicPill(
    text: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val s = TvUi.scale()
    Row(
        modifier
            .tvMusicFocus(RoundedCornerShape(50), focusedScale = 1.08f, onClick = onClick)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) TvMusicInk else Color.White,
                modifier = Modifier.size(20.dp * s),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (selected) TvMusicInk else Color.White,
            fontSize = 16.sp * s,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun TvMusicIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    filled: Boolean = false,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val s = TvUi.scale()
    val d = size * s
    Box(
        modifier
            .size(d)
            .tvMusicFocus(CircleShape, focusedScale = 1.12f, onClick = onClick)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (filled) TvMusicInk else tint,
            modifier = Modifier.size(d * 0.5f),
        )
    }
}

/** Home / Search / Library, at the top of every music page. */
@Composable
fun TvMusicTabs(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        "Home" to Icons.Filled.Home,
        "Search" to Icons.Filled.Search,
        "Library" to Icons.Filled.LibraryMusic,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, entry ->
            TvMusicPill(entry.first, selected = tab == i, icon = entry.second) { onTab(i) }
        }
    }
}

@Composable
fun TvMusicSectionTitle(title: String, modifier: Modifier = Modifier) {
    val s = TvUi.scale()
    Text(
        title,
        modifier = modifier,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp * s,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------- cards and rows

@Composable
fun TvMusicCard(
    thumb: String,
    title: String,
    subtitle: String,
    circle: Boolean,
    width: Dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val s = TvUi.scale()
    Column(
        Modifier
            .width(width)
            .tvMusicFocus(RoundedCornerShape(14.dp), onFocused = onFocused, onClick = onClick)
            .padding(8.dp),
    ) {
        MusicCover(
            thumb,
            Modifier.fillMaxWidth().aspectRatio(1f),
            if (circle) CircleShape else RoundedCornerShape(10.dp),
            500,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp * s,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = TvMusicMuted,
                fontSize = 13.sp * s,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TvMusicItemCard(item: MItem, width: Dp, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    TvMusicCard(
        thumb = item.thumb,
        title = item.title,
        subtitle = item.subtitle,
        circle = item.kind == MKind.ARTIST,
        width = width,
        onFocused = onFocused,
        onClick = onClick,
    )
}

@Composable
fun TvMusicSongRow(
    song: MSong,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playlistId: String? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val s = TvUi.scale()
    val st by MusicPlayer.state.collectAsState()
    val current = st.song?.id == song.id
    Row(
        modifier
            .fillMaxWidth()
            .tvMusicFocus(
                RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                previewPass = false,
                onFocused = onFocused,
                onClick = onClick,
            )
            .background(Color.White.copy(alpha = 0.07f))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                (index + 1).toString(),
                color = if (current) TvMusicPrimary else TvMusicMuted,
                fontSize = 15.sp * s,
                modifier = Modifier.width(34.dp * s),
                maxLines = 1,
            )
        }
        MusicCover(song.thumb, Modifier.size(52.dp * s), RoundedCornerShape(8.dp), 200)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp * s,
                color = if (current) TvMusicPrimary else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                fontSize = 14.sp * s,
                color = TvMusicMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MusicSongMenu(song, playlistId)
    }
}

/** A plain one-line focusable row (search suggestions, recent searches). */
@Composable
fun TvMusicTextRow(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = TvUi.scale()
    Row(
        modifier
            .fillMaxWidth()
            .tvMusicFocus(RoundedCornerShape(12.dp), focusedScale = 1.02f, onClick = onClick)
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TvMusicMuted, modifier = Modifier.size(22.dp * s))
        Spacer(Modifier.width(16.dp))
        Text(text, color = Color.White, fontSize = 17.sp * s, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The accent of the TV music pages (the TV reference purple, lightened to read on dark art). */
internal val TvMusicPrimary = Color(0xFFB9AEFF)

// ---------------------------------------------------------------------- shelves

/**
 * One shelf: a title and a row of cards. A song card plays the whole shelf from that song (when
 * the shelf is all songs) and then calls [onPlayed] so the page can open the player; any other
 * card opens its page through [onOpen].
 */
@UnstableApi
@Composable
fun TvMusicShelf(
    title: String,
    items: List<MItem>,
    startPad: Dp,
    onOpen: (MItem) -> Unit,
    onFocused: (MItem) -> Unit,
    onPlayed: () -> Unit,
) {
    val ctx = LocalContext.current
    val s = TvUi.scale()
    val songs = remember(items) { items.mapNotNull { it.song } }
    val allSongs = items.isNotEmpty() && songs.size == items.size
    Column(Modifier.fillMaxWidth()) {
        TvMusicSectionTitle(title.ifBlank { "More" }, Modifier.padding(start = startPad, bottom = 2.dp))
        LazyRow(
            contentPadding = PaddingValues(start = maxOf(startPad - 8.dp, 0.dp), end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(items) { i, item ->
                TvMusicItemCard(item, 166.dp * s, onFocused = { onFocused(item) }) {
                    val song = item.song
                    if (item.kind == MKind.SONG && song != null) {
                        if (allSongs) MusicPlayer.playQueue(ctx, songs, i) else MusicPlayer.playSong(ctx, song)
                        onPlayed()
                    } else {
                        onOpen(item)
                    }
                }
            }
        }
    }
}
