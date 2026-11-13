package com.hikari.app.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.ui.components.hikariWobble

/** Full Settings tab beside Library, VxMusic-style rows. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun MusicSettingsTab(onMenu: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Providers") } },
        )
        MusicSettingsContent()
    }
}

@UnstableApi
@Composable
fun MusicSettingsContent() {
    LazyColumn(Modifier.fillMaxSize().hikariWobble().padding(horizontal = 16.dp)) {
        item {
            VxSettingsInline()
            Spacer(Modifier.height(96.dp))
        }
    }
}

@UnstableApi
@Composable
fun VxSettingsInline() {
    val ctx = LocalContext.current
    var prefer by remember { mutableStateOf(VxQuality.preferLossless(ctx)) }
    var streamQ by remember { mutableStateOf(VxQuality.streamQuality(ctx)) }
    var dlQ by remember { mutableStateOf(VxQuality.downloadQuality(ctx)) }
    var bitPerfect by remember { mutableStateOf(VxQuality.bitPerfect(ctx)) }
    var clarity by remember { mutableStateOf(VxQuality.studioClarity(ctx)) }
    var eqOn by remember { mutableStateOf(VxSettings.eqEnabled(ctx)) }
    var gains by remember { mutableStateOf(VxSettings.eqGains(ctx)) }
    var wavy by remember { mutableStateOf(VxSettings.wavy(ctx)) }
    var xfade by remember { mutableStateOf(VxSettings.crossfade(ctx)) }
    var xsecs by remember { mutableStateOf(VxSettings.xfadeSecs(ctx).toFloat()) }
    var lyricAnim by remember { mutableStateOf(VxSettings.lyricsAnim(ctx)) }
    var showLyricPick by remember { mutableStateOf(false) }
    var amoled by remember { mutableStateOf(VxSettings.amoled(ctx)) }
    var liquid by remember { mutableStateOf(VxSettings.liquidGlass(ctx)) }
    var dynC by remember { mutableStateOf(VxSettings.dynamicColor(ctx)) }
    var dynNp by remember { mutableStateOf(VxSettings.dynNowPlaying(ctx)) }
    var appFont by remember { mutableStateOf(VxSettings.appFont(ctx)) }
    var accent by remember { mutableStateOf(VxSettings.accent(ctx)) }
    Column {
        Text("Experimental", style = MaterialTheme.typography.labelLarge)
        VxRow("Liquid Glass", "iOS-style translucent materials", liquid) {
            liquid = it; VxSettings.setLiquidGlass(ctx, it)
        }
        VxRow("Lyrics Animation", lyricAnim, null, true) { showLyricPick = true }
        VxRow("Equalizer", if (eqOn) "On • 15 bands" else "Shape your sound across 15 frequencies", eqOn) {
            eqOn = it; VxSettings.setEqEnabled(ctx, it); VxAudioFx.apply(ctx)
        }
        if (eqOn) {
            Text("Bands (dB, -8..+8)", style = MaterialTheme.typography.bodySmall)
            gains.forEachIndexed { i, g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(VxSettings.eqLabel(VxSettings.EQ_FREQS[i]), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Slider(
                        value = g,
                        onValueChange = { gains = gains.toMutableList().also { l -> l[i] = it } },
                        onValueChangeFinished = { VxSettings.setEqGains(ctx, gains) },
                        valueRange = -8f..8f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(String.format("%.1f", g), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { gains = List(15) { 0f }; VxSettings.setEqPreset(ctx, gains); eqOn = true }) { Text("Flat") }
                TextButton(onClick = { gains = VxSettings.STUDIO_MASTER; VxSettings.setEqPreset(ctx, gains); eqOn = true }) { Text("Studio Master") }
            }
        }
        VxRow("Wavy Seekbar", "Multi-layer fluid wavy progress slider", wavy) { wavy = it; VxSettings.setWavy(ctx, it) }
        VxRow("Studio Master Clarity", "Crystal-clear open sound", clarity) { clarity = it; VxQuality.setStudioClarity(ctx, it); VxAudioFx.apply(ctx) }
        Spacer(Modifier.height(8.dp))
        Text("Audio & Streaming", style = MaterialTheme.typography.labelLarge)
        Text("Streaming", style = MaterialTheme.typography.bodyMedium)
        VxQuality.STREAM_OPTIONS.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { streamQ = q; VxQuality.setStreamQuality(ctx, q) }.padding(vertical = 2.dp)) {
                RadioButton(selected = streamQ == q, onClick = { streamQ = q; VxQuality.setStreamQuality(ctx, q) })
                Spacer(Modifier.width(8.dp))
                Text(if (q == -1) "YouTube (Opus/AAC) • JioSaavn 320k fallback" else VxQuality.labelFor(q))
            }
        }
        Text("Downloads", style = MaterialTheme.typography.bodyMedium)
        VxQuality.DOWNLOAD_OPTIONS.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { dlQ = q; VxQuality.setDownloadQuality(ctx, q) }.padding(vertical = 2.dp)) {
                RadioButton(selected = dlQ == q, onClick = { dlQ = q; VxQuality.setDownloadQuality(ctx, q) })
                Spacer(Modifier.width(8.dp))
                Text(if (q == -1) "YouTube (Opus/AAC)" else VxQuality.labelFor(q))
            }
        }
        VxRow("Prefer lossless (FLAC)", "Falls back to YouTube/JioSaavn for remixes", prefer) { prefer = it; VxQuality.setPreferLossless(ctx, it) }
        VxRow("Bit-Perfect Mode", "Bypass DSP, direct passthrough", bitPerfect) { bitPerfect = it; VxQuality.setBitPerfect(ctx, it); VxAudioFx.apply(ctx) }
        VxRow("Crossfade", "Blend end of track into next (" + xsecs.toInt() + "s)", xfade) { xfade = it; VxSettings.setCrossfade(ctx, it) }
        if (xfade) {
            androidx.compose.material3.Slider(value = xsecs, onValueChange = { xsecs = it }, onValueChangeFinished = { VxSettings.setXfadeSecs(ctx, xsecs.toInt()) }, valueRange = 1f..12f, steps = 10, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text("Appearance", style = MaterialTheme.typography.labelLarge)
        VxRow("AMOLED Mode", "Pure black background", amoled) { amoled = it; VxSettings.setAmoled(ctx, it) }
        VxRow("Dynamic Color", "Use wallpaper colors", dynC) { dynC = it; VxSettings.setDynamicColor(ctx, it) }
        VxRow("Dynamic Now Playing", "Match player artwork", dynNp) { dynNp = it; VxSettings.setDynNowPlaying(ctx, it) }
        VxRow("Use Application Font", "Custom look across app", appFont) { appFont = it; VxSettings.setAppFont(ctx, it) }
        Text("Accent: " + accent, style = MaterialTheme.typography.bodyMedium)
        VxSettings.ACCENTS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    TextButton(onClick = { accent = a; VxSettings.setAccent(ctx, a) }) {
                        Text(if (a == accent) "• " + a else a)
                    }
                }
            }
        }
        Text(
            "Applies live: theme + accent + font change the whole app, player rows apply to the Now Playing screen.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (showLyricPick) {
        AlertDialog(
            onDismissRequest = { showLyricPick = false },
            title = { Text("Lyrics Animation") },
            text = {
                Column {
                    VxSettings.LYRIC_ANIMS.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            lyricAnim = a; VxSettings.setLyricsAnim(ctx, a); showLyricPick = false
                        }.padding(vertical = 4.dp)) {
                            RadioButton(selected = lyricAnim == a, onClick = {
                                lyricAnim = a; VxSettings.setLyricsAnim(ctx, a); showLyricPick = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(a)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLyricPick = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun VxRow(title: String, subtitle: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun VxRow(title: String, subtitle: String, checked: Nothing?, arrow: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text(">")
    }
}
