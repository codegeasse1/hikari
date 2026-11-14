package com.hikari.app.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.hikari.app.tv.TvUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private val PlayerBase = Color(0xFF08080E)

private fun tvFmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}

// ====================================================================== NOW PLAYING BAR

@UnstableApi
@Composable
fun TvMusicNowPlaying(startPad: Dp, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val st by MusicPlayer.state.collectAsState()
    val song = st.song ?: return
    val s = TvUi.scale()
    var frac by remember { mutableStateOf(0f) }
    LaunchedEffect(st.index, st.playing) {
        while (true) {
            val d = MusicPlayer.duration(song.durationSec)
            frac = if (d > 0) (MusicPlayer.position().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = maxOf(startPad - 8.dp, 0.dp), end = 32.dp, bottom = 14.dp)
            .tvMusicFocus(RoundedCornerShape(18.dp), focusedScale = 1.015f, previewPass = false, onClick = onOpen)
            .background(Color(0xF020202E)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicCover(song.thumb, Modifier.size(52.dp * s), RoundedCornerShape(10.dp), 200)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp * s,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subText = when {
                    st.error != null -> st.error!!
                    st.buffering -> "Loading… • " + song.artist
                    else -> song.artist
                }
                Text(
                    subText,
                    color = if (st.error != null) Color(0xFFFF8A80) else TvMusicMuted,
                    fontSize = 14.sp * s,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvMusicIconButton(Icons.Filled.SkipPrevious, "Previous", size = 44.dp) { MusicPlayer.previous() }
            Spacer(Modifier.width(8.dp))
            TvMusicIconButton(
                if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (st.playing) "Pause" else "Play",
                size = 48.dp,
                filled = true,
            ) { MusicPlayer.togglePlay() }
            Spacer(Modifier.width(8.dp))
            TvMusicIconButton(Icons.Filled.SkipNext, "Next", size = 44.dp) { MusicPlayer.next() }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(TvMusicPrimary))
        }
    }
}

// ====================================================================== SEEK BAR

@Composable
private fun TvMusicSeekBar(frac: Float, onSeekBy: (Long) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (focused) 10.dp else 5.dp, tween(120), label = "tvSeekThickness")
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeekBy(-10_000L)
                        true
                    }
                    Key.DirectionRight -> {
                        onSeekBy(10_000L)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            Modifier
                .fillMaxWidth(frac.coerceIn(0f, 1f))
                .height(thickness)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}

// ====================================================================== FULL PLAYER

@UnstableApi
@Composable
fun TvMusicFullPlayer(onClose: () -> Unit) {
    val st by MusicPlayer.state.collectAsState()
    val liked by MusicStore.liked.collectAsState()
    val song = st.song
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val s = TvUi.scale()
    var mode by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var lyrics by remember(song.id) { mutableStateOf<MLyrics?>(null) }
    var lyricsState by remember(song.id) { mutableStateOf(0) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(st.index, st.playing) {
        while (true) {
            pos = MusicPlayer.position()
            dur = MusicPlayer.duration(song.durationSec)
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
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { playFocus.requestFocus() }
    }

    val seekBy: (Long) -> Unit = { delta ->
        if (dur > 0L) {
            val target = (MusicPlayer.position() + delta).coerceIn(0L, dur)
            MusicPlayer.seekTo(target)
            pos = target
        }
    }

    Box(Modifier.fillMaxSize().background(PlayerBase)) {
        if (song.thumb.isNotBlank()) {
            Crossfade(targetState = song.thumb, animationSpec = tween(500), label = "tvPlayerArt") { url ->
                AsyncImage(
                    model = MusicApi.hq(url, 1200),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val dim by animateFloatAsState(if (mode == 0) 0.12f else 0.66f, tween(300), label = "tvPlayerDim")
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.38f to Color.Transparent,
                    0.78f to PlayerBase.copy(alpha = 0.88f),
                    1f to PlayerBase.copy(alpha = 0.98f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.50f),
                    0.55f to Color.Transparent,
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvMusicIconButton(Icons.Filled.KeyboardArrowDown, "Close", size = 48.dp, onClick = onClose)
                Spacer(Modifier.width(14.dp))
                Text(
                    "Now Playing",
                    color = Color.White,
                    fontSize = 18.sp * s,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
                if (mode != 0) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.5f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.38f))
                            .padding(16.dp),
                    ) {
                        if (mode == 1) {
                            TvMusicQueuePanel(st)
                        } else {
                            TvMusicLyricsPanel(lyrics, lyricsState, pos, s)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicCover(song.thumb, Modifier.size(72.dp * s), RoundedCornerShape(10.dp), 300)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontSize = 30.sp * s,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 18.sp * s,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (st.error != null) {
                        Text(
                            st.error + " — press Play to retry",
                            color = Color(0xFFFF8A80),
                            fontSize = 14.sp * s,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            TvMusicSeekBar(frac, seekBy)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tvFmt(pos), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp * s)
                val rightLabel = when {
                    st.error != null -> "Failed"
                    st.buffering -> "Loading…"
                    else -> tvFmt(dur)
                }
                Text(
                    rightLabel,
                    color = if (st.error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp * s,
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvMusicIconButton(
                    if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (st.playing) "Pause" else "Play",
                    modifier = Modifier.focusRequester(playFocus),
                    size = 64.dp,
                    filled = true,
                ) { MusicPlayer.togglePlay() }
                Spacer(Modifier.width(14.dp))
                TvMusicIconButton(Icons.Filled.SkipPrevious, "Previous", size = 48.dp) { MusicPlayer.previous() }
                Spacer(Modifier.width(10.dp))
                TvMusicIconButton(Icons.Filled.SkipNext, "Next", size = 48.dp) { MusicPlayer.next() }
                Spacer(Modifier.width(10.dp))
                TvMusicIconButton(
                    Icons.Filled.Shuffle,
                    "Shuffle",
                    size = 48.dp,
                    tint = if (st.shuffle) TvMusicPrimary else Color.White,
                ) { MusicPlayer.toggleShuffle() }
                Spacer(Modifier.width(10.dp))
                TvMusicIconButton(
                    if (st.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat",
                    size = 48.dp,
                    tint = if (st.repeat == Player.REPEAT_MODE_OFF) Color.White else TvMusicPrimary,
                ) { MusicPlayer.cycleRepeat() }
                Spacer(Modifier.width(10.dp))
                val isLiked = liked.any { it.id == song.id }
                TvMusicIconButton(
                    if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Like",
                    size = 48.dp,
                    tint = if (isLiked) TvMusicPrimary else Color.White,
                ) { MusicStore.toggleLike(song) }

                Spacer(Modifier.weight(1f))

                TvMusicPill("Artwork", selected = mode == 0, icon = Icons.Filled.MusicNote) { mode = 0 }
                Spacer(Modifier.width(10.dp))
                TvMusicPill("Lyrics", selected = mode == 2, icon = Icons.Filled.Notes) {
                    mode = if (mode == 2) 0 else 2
                }
                Spacer(Modifier.width(10.dp))
                TvMusicPill("Queue", selected = mode == 1, icon = Icons.Filled.QueueMusic) {
                    mode = if (mode == 1) 0 else 1
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- side panels

@UnstableApi
@Composable
private fun TvMusicQueuePanel(st: MusicPlayer.State) {
    val s = TvUi.scale()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem(max(0, st.index - 1)) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(st.queue) { i, q ->
            val current = i == st.index
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvMusicFocus(
                        RoundedCornerShape(12.dp),
                        focusedScale = 1.02f,
                        previewPass = false,
                        onClick = { MusicPlayer.jumpTo(i) },
                    )
                    .background(if (current) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicCover(q.thumb, Modifier.size(46.dp * s), RoundedCornerShape(8.dp), 200)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        q.title,
                        color = if (current) TvMusicPrimary else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp * s,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        q.artist,
                        color = TvMusicMuted,
                        fontSize = 13.sp * s,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!current) {
                    TvMusicIconButton(Icons.Filled.Close, "Remove", size = 38.dp) { MusicPlayer.removeAt(i) }
                }
            }
        }
    }
}

@Composable
private fun TvMusicLyricsPanel(lyrics: MLyrics?, state: Int, pos: Long, s: Float) {
    val synced = lyrics?.synced.orEmpty()
    val listState = rememberLazyListState()
    val active = if (synced.isEmpty()) -1 else synced.indexOfLast { it.ms <= pos }
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(max(0, active - 2))
    }
    when {
        state == 1 -> Text("Loading lyrics…", color = TvMusicMuted, fontSize = 18.sp * s)
        lyrics == null -> Text("No lyrics found for this song", color = TvMusicMuted, fontSize = 18.sp * s)
        synced.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(synced) { i, line ->
                Text(
                    line.text.ifBlank { "♪" },
                    color = if (i == active) Color.White else Color.White.copy(alpha = 0.45f),
                    fontSize = (if (i == active) 28.sp else 22.sp) * s,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
        else -> {
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> if (scroll.canScrollForward) {
                                scope.launch { scroll.animateScrollBy(260f) }
                                true
                            } else false
                            Key.DirectionUp -> if (scroll.canScrollBackward) {
                                scope.launch { scroll.animateScrollBy(-260f) }
                                true
                            } else false
                            else -> false
                        }
                    },
            ) {
                for (line in lyrics.plain.lines()) {
                    Text(
                        line,
                        color = Color.White,
                        fontSize = 20.sp * s,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
