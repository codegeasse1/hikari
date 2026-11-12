package com.hikari.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Square artwork with a quiet placeholder. */
@Composable
fun MusicCover(
    url: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    px: Int = 400,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.clip(shape).background(cs.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
        } else {
            AsyncImage(
                model = MusicApi.hq(url, px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MusicPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** A pill with an icon, used for Play / Shuffle / Save. */
@Composable
fun MusicActionPill(
    icon: ImageVector,
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .then(if (filled) Modifier.background(cs.primary) else Modifier.border(1.dp, cs.outline, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) cs.onPrimary else cs.onSurface, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (filled) cs.onPrimary else cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun MusicTopBar(title: String, onMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Select provider", tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun MusicSectionHeader(title: String, onPlayAll: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onPlayAll != null) {
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .clip(shape)
                    .border(1.dp, cs.outline, shape)
                    .clickable(onClick = onPlayAll)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Play all", fontSize = 13.sp, color = cs.onSurface)
            }
        }
    }
}

/** Echo-style rounded block whose rows are separated by thin gaps. */
@Composable
fun MusicListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun MusicSongRow(
    song: MSong,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    playlistId: String? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val st by MusicPlayer.state.collectAsState()
    val current = st.song?.id == song.id
    Row(
        modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(song.thumb, Modifier.size(52.dp), RoundedCornerShape(8.dp), 200)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (current) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MusicSongMenu(song, playlistId)
    }
}

@Composable
fun MusicItemRow(item: MItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = when (item.kind) {
        MKind.ALBUM -> "Album"
        MKind.PLAYLIST -> "Playlist"
        MKind.ARTIST -> "Artist"
        MKind.VIDEO -> "Video"
        MKind.SONG -> ""
    }
    val sub = if (item.subtitle.isBlank()) label else if (label.isBlank()) item.subtitle else "$label • ${item.subtitle}"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(
            item.thumb,
            Modifier.size(52.dp),
            if (item.kind == MKind.ARTIST) CircleShape else RoundedCornerShape(8.dp),
            200,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MusicItemCard(item: MItem, size: Dp = 144.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.width(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(bottom = 4.dp),
    ) {
        MusicCover(
            item.thumb,
            Modifier.size(size),
            if (item.kind == MKind.ARTIST) CircleShape else RoundedCornerShape(14.dp),
            400,
        )
        Spacer(Modifier.size(6.dp))
        Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) {
            Text(item.subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The big rounded feature card at the top of Home. */
@Composable
fun MusicHeroCard(item: MItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.aspectRatio(1.05f).clip(RoundedCornerShape(32.dp)).clickable(onClick = onClick),
    ) {
        MusicCover(item.thumb, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), 800)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------- song menu

@Composable
fun MusicSongMenu(song: MSong, playlistId: String? = null) {
    val ctx = LocalContext.current
    val liked by MusicStore.liked.collectAsState()
    var open by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    val isLiked = liked.any { it.id == song.id }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = {
                open = false
                MusicPlayer.playNext(ctx, song)
            })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                open = false
                MusicPlayer.addToQueue(ctx, song)
            })
            DropdownMenuItem(text = { Text(if (isLiked) "Remove from liked" else "Add to liked") }, onClick = {
                open = false
                MusicStore.toggleLike(song)
            })
            DropdownMenuItem(text = { Text("Add to playlist") }, onClick = {
                open = false
                pick = true
            })
            if (playlistId != null) {
                DropdownMenuItem(text = { Text("Remove from this playlist") }, onClick = {
                    open = false
                    MusicStore.removeFromPlaylist(playlistId, song.id)
                })
            }
        }
    }
    if (pick) MusicPlaylistPicker(song) { pick = false }
}

@Composable
fun MusicPlaylistPicker(song: MSong, onDismiss: () -> Unit) {
    val playlists by MusicStore.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "+ New playlist",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    )
                    playlists.forEach { pl ->
                        Text(
                            pl.name + "  (" + pl.songs.size + ")",
                            modifier = Modifier.fillMaxWidth().clickable {
                                MusicStore.addToPlaylist(pl.id, song)
                                onDismiss()
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    val id = MusicStore.createPlaylist(name)
                    MusicStore.addToPlaylist(id, song)
                    onDismiss()
                }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun MusicNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
