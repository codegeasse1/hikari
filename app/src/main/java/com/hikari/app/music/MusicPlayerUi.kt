package com.hikari.app.music

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.max

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}

// ====================================================================== MINI PLAYER

@UnstableApi
@Composable
fun MusicMiniPlayer(onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st by MusicPlayer.state.collectAsState()
    val song = st.song ?: return
    var frac by remember { mutableStateOf(0f) }
    LaunchedEffect(st.index, st.playing) {
        while (true) {
            val d = MusicPlayer.duration(song.durationSec)
            frac = if (d > 0) (MusicPlayer.position().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceVariant)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(song.thumb, Modifier.size(44.dp), RoundedCornerShape(10.dp), 200)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { MusicPlayer.togglePlay() }) {
                Icon(
                    if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (st.playing) "Pause" else "Play",
                    tint = cs.onSurface,
                )
            }
            IconButton(onClick = { MusicPlayer.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = cs.onSurface)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(cs.onSurface.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(cs.primary))
        }
    }
}

// ====================================================================== FULL PLAYER

@UnstableApi
@Composable
fun MusicFullPlayer(onClose: () -> Unit) {
    val st by MusicPlayer.state.collectAsState()
    val liked by MusicStore.liked.collectAsState()
    val song = st.song
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(0) } // 0 artwork, 1 queue, 2 lyrics
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var lyrics by remember(song.id) { mutableStateOf<MLyrics?>(null) }
    var lyricsState by remember(song.id) { mutableStateOf(0) } // 0 idle, 1 loading, 2 none

    LaunchedEffect(st.index, st.playing) {
        while (true) {
            if (!dragging) {
                pos = MusicPlayer.position()
                dur = MusicPlayer.duration(song.durationSec)
            }
            delay(400)
        }
    }
    LaunchedEffect(song.id, mode) {
        if (mode == 2 && lyrics == null && lyricsState == 0) {
            lyricsState = 1
            val r = runCatching { MusicApi.lyrics(song) }.getOrNull()
            lyrics = r
            lyricsState = if (r == null) 2 else 0
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (Build.VERSION.SDK_INT >= 31 && song.thumb.isNotBlank()) {
            AsyncImage(
                model = MusicApi.hq(song.thumb, 400),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Text(
                    "Now Playing",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(48.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                when (mode) {
                    1 -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(Unit) { listState.scrollToItem(max(0, st.index - 1)) }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(st.queue) { i, s ->
                                val current = i == st.index
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (current) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { MusicPlayer.jumpTo(i) }
                                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MusicCover(s.thumb, Modifier.size(44.dp), RoundedCornerShape(8.dp), 200)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(s.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!current) {
                                        IconButton(onClick = { MusicPlayer.removeAt(i) }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        val l = lyrics
                        val listState = rememberLazyListState()
                        val synced = l?.synced.orEmpty()
                        val active = if (synced.isEmpty()) -1 else synced.indexOfLast { it.ms <= pos }
                        LaunchedEffect(active) {
                            if (active >= 0) listState.animateScrollToItem(max(0, active - 2))
                        }
                        when {
                            lyricsState == 1 -> Text("Loading lyrics…", color = Color.White.copy(alpha = 0.7f))
                            l == null -> Text("No lyrics found for this song", color = Color.White.copy(alpha = 0.7f))
                            synced.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(synced) { i, line ->
                                    Text(
                                        line.text.ifBlank { "♪" },
                                        color = if (i == active) Color.White else Color.White.copy(alpha = 0.45f),
                                        fontSize = if (i == active) 24.sp else 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { MusicPlayer.seekTo(line.ms) }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(l.plain.lines()) { _, line ->
                                    Text(line, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                    else -> {
                        MusicCover(
                            song.thumb,
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            RoundedCornerShape(16.dp),
                            800,
                        )
                    }
                }
            }

            val ctxQ = androidx.compose.ui.platform.LocalContext.current
            var showQuality by remember { mutableStateOf(false) }
            var vxInfo by remember(song.id) { mutableStateOf(MusicApi.lastVx) }
            LaunchedEffect(st.buffering, st.index) { vxInfo = MusicApi.lastVx }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val qLabel = if (vxInfo != null) "FLAC " + vxInfo!!.bitDepth + "-bit/" + vxInfo!!.samplingRate + "kHz" else VxQuality.shortLabelFor(VxQuality.streamQuality(ctxQ)) + " • tap for quality"
                    Text(
                        qLabel,
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.clickable { showQuality = true }.padding(vertical = 2.dp),
                    )
                }
                val isLiked = liked.any { it.id == song.id }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { MusicStore.toggleLike(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Like",
                        tint = Color.Black,
                    )
                }
            }

            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Slider(
                value = if (dragging) dragValue else frac,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    MusicPlayer.seekTo((dragValue * dur).toLong())
                    pos = (dragValue * dur).toLong()
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(if (dragging) (dragValue * dur).toLong() else pos), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Text(if (st.buffering) "Loading…" else fmt(dur), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { MusicPlayer.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (st.shuffle) cs.primary else Color.White)
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable { MusicPlayer.previous() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(30.dp)).background(Color.White).clickable { MusicPlayer.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (st.playing) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable { MusicPlayer.next() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = { MusicPlayer.cycleRepeat() }) {
                    Icon(
                        if (st.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint = if (st.repeat == Player.REPEAT_MODE_OFF) Color.White else cs.primary,
                    )
                }
            }

            var showAllSettings by remember { mutableStateOf(false) }
            if (showQuality) VxQualityDialogFull(
                onClose = { showQuality = false },
                onMore = { showQuality = false; showAllSettings = true },
            )
            if (showAllSettings) VxSettingsDialog(onClose = { showAllSettings = false })
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { mode = 0 }) {
                    Icon(Icons.Filled.MusicNote, contentDescription = "Artwork", tint = if (mode == 0) cs.primary else Color.White)
                }
                IconButton(onClick = { mode = if (mode == 1) 0 else 1 }) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "Queue", tint = if (mode == 1) cs.primary else Color.White)
                }
                IconButton(onClick = { mode = if (mode == 2) 0 else 2 }) {
                    Icon(Icons.Filled.Notes, contentDescription = "Lyrics", tint = if (mode == 2) cs.primary else Color.White)
                }
            }
        }
    }
}
