package com.hikari.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.desktop.engine.EngineStream
import com.hikari.desktop.engine.EngineSubtitle
import com.hikari.desktop.playback.JavaFxPlayerEngine
import com.hikari.desktop.playback.PlayerEngine
import com.hikari.desktop.playback.SubtitleCue
import com.hikari.desktop.playback.SubtitleParser
import com.hikari.desktop.playback.fetchSubtitleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
internal fun PlayerScreen(
    title: String,
    streams: List<EngineStream>,
    initial: EngineStream,
    subtitles: List<EngineSubtitle>,
    engine: PlayerEngine,
    videoSurface: @Composable () -> Unit,
    onClose: () -> Unit,
    onOpenExternal: (EngineStream) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by engine.state().collectAsState()
    var active by remember(initial.url) { mutableStateOf(initial) }
    var streamMenuOpen by remember { mutableStateOf(false) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    var rateMenuOpen by remember { mutableStateOf(false) }
    var cueSet by remember { mutableStateOf(emptyList<SubtitleCue>()) }
    var activeSubtitle by remember { mutableStateOf<EngineSubtitle?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(initial.url) {
        active = initial
        withContext(Dispatchers.IO) { engine.open(initial, title) }
    }

    fun loadSubtitle(track: EngineSubtitle?) {
        activeSubtitle = track
        cueSet = emptyList()
        if (track == null) return
        scope.launch(Dispatchers.IO) {
            val text = fetchSubtitleText(track.url)
            val cues = if (text.isNullOrBlank()) emptyList() else SubtitleParser.parse(text)
            withContext(Dispatchers.Main) { cueSet = cues }
        }
    }

    LaunchedEffect(subtitles) {
        if (activeSubtitle == null && subtitles.size == 1) loadSubtitle(subtitles.first())
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    val activeCue = SubtitleParser.activeAt(cueSet, state.positionMs)

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.buffering && state.error == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            videoSurface()
            if (activeCue != null) {
                Text(
                    activeCue.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 48.dp, vertical = 20.dp)
                        .background(Color(0x99000000), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (state.error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Couldn't play this stream", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.error ?: "",
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenExternal(active) }) { Text("Open externally") }
                        OutlinedButton(onClick = onClose) { Text("Back") }
                    }
                }
            }
        }

        if (controlsVisible) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0B0E1A)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatClock(state.positionMs), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                    Slider(
                        value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                        onValueChange = { frac ->
                            engine.seekTo((frac * state.durationMs).toLong())
                        },
                        modifier = Modifier.weight(1f),
                        enabled = state.durationMs > 0 && state.error == null,
                    )
                    Text(formatClock(state.durationMs), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { engine.seekBy(-10_000) }) { Text("−10s", color = Color.White) }
                    TextButton(onClick = { engine.toggle() }) {
                        Text(if (state.playing) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = { engine.seekBy(30_000) }) { Text("+30s", color = Color.White) }
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { rateMenuOpen = true }) {
                            Text("${state.rate}x", color = Color.White)
                        }
                        DropdownMenu(expanded = rateMenuOpen, onDismissRequest = { rateMenuOpen = false }) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(if (r == state.rate) "✓ ${r}x" else "${r}x") },
                                    onClick = {
                                        engine.setRate(r)
                                        rateMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { subtitleMenuOpen = true }) {
                            Text(if (activeSubtitle == null) "CC" else "CC ●", color = Color.White)
                        }
                        DropdownMenu(expanded = subtitleMenuOpen, onDismissRequest = { subtitleMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (activeSubtitle == null) "✓ Off" else "Off") },
                                onClick = {
                                    loadSubtitle(null)
                                    subtitleMenuOpen = false
                                },
                            )
                            subtitles.forEach { track ->
                                DropdownMenuItem(
                                    text = { Text(if (track == activeSubtitle) "✓ ${track.label}" else track.label) },
                                    onClick = {
                                        loadSubtitle(track)
                                        subtitleMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { streamMenuOpen = true }) {
                            Text(
                                (active.quality ?: active.name).take(14),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(expanded = streamMenuOpen, onDismissRequest = { streamMenuOpen = false }) {
                            streams.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (s == active) "✓ " else "") + (s.quality ?: s.name),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        active = s
                                        scope.launch(Dispatchers.IO) {
                                            engine.open(s, title)
                                            withContext(Dispatchers.Main) {
                                                loadSubtitle(activeSubtitle?.let { t -> subtitles.firstOrNull { it.url == t.url } })
                                            }
                                        }
                                        streamMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { controlsVisible = false }) { Text("Hide", color = Color.White) }
                }
                if (activeSubtitle != null && cueSet.isEmpty()) {
                    Text(
                        "Subtitle loaded, waiting for cues…",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            TextButton(
                onClick = { controlsVisible = true },
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0B0E1A)),
            ) {
                Text("Show controls", color = Color.White)
            }
        }
    }
}

/** The video surface composable for the real engine (SwingPanel over JFXPanel). */
@Composable
internal fun JavaFxVideoSurface(engine: PlayerEngine) {
    val fx = engine as? JavaFxPlayerEngine ?: return
    var panel by remember { mutableStateOf<java.awt.Component?>(null) }
    LaunchedEffect(fx) {
        withContext(Dispatchers.IO) {
            var waited = 0
            var found: java.awt.Component? = null
            while (waited < 10_000 && found == null) {
                found = fx.surface()
                if (found == null) {
                    Thread.sleep(100)
                    waited += 100
                }
            }
            withContext(Dispatchers.Main) { panel = found }
        }
    }
    val current = panel
    if (current != null) {
        SwingPanel(
            factory = { current },
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
