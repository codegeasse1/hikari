package com.hikari.app.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.ui.components.hikariWobble

/** Full Settings tab beside Library, VxMusic-style rows. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun MusicSettingsTab(onMenu: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Providers") } },
        )
        // Reuse the full Vx settings content as a screen (same rows as dialog).
        MusicSettingsContent()
    }
}

@UnstableApi
@Composable
fun MusicSettingsContent() {
    // Host the dialog content inline: toggles write to the same prefs.
    // Wrapped in LazyColumn with the global wobble so it scrolls like everywhere else.
    LazyColumn(Modifier.fillMaxSize().hikariWobble().padding(horizontal = 16.dp)) {
        item {
            VxSettingsInline()
            Spacer(Modifier.height(96.dp))
        }
    }
}

@androidx.compose.runtime.Composable
fun VxSettingsInline() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var prefer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxQuality.preferLossless(ctx)) }
    var streamQ by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxQuality.streamQuality(ctx)) }
    var dlQ by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxQuality.downloadQuality(ctx)) }
    var bitPerfect by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxQuality.bitPerfect(ctx)) }
    var clarity by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxQuality.studioClarity(ctx)) }
    var eqOn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.eqEnabled(ctx)) }
    var gains by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.eqGains(ctx)) }
    var wavy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.wavy(ctx)) }
    var xfade by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.crossfade(ctx)) }
    var xsecs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.xfadeSecs(ctx).toFloat()) }
    var lyricAnim by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.lyricsAnim(ctx)) }
    var amoled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.amoled(ctx)) }
    var liquid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.liquidGlass(ctx)) }
    var dynC by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.dynamicColor(ctx)) }
    var dynNp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.dynNowPlaying(ctx)) }
    var appFont by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.appFont(ctx)) }
    var accent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(VxSettings.accent(ctx)) }
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column {
        androidx.compose.material3.Text("Experimental", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        VxRow("Liquid Glass", "iOS-style translucent materials", liquid) { liquid = it; VxSettings.setLiquidGlass(ctx, it) }
        VxRow("Lyrics Animation", lyricAnim, null, true) { }
        VxRow("Equalizer", if (eqOn) "On \u2022 15 bands" else "Shape your sound across 15 frequencies", eqOn) { eqOn = it; VxSettings.setEqEnabled(ctx, it) }
        if (eqOn) {
            gains.forEachIndexed { i, g ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text(VxSettings.eqLabel(VxSettings.EQ_FREQS[i]), modifier = androidx.compose.ui.Modifier.width(44.dp), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Slider(value = g, onValueChange = { gains = gains.toMutableList().also { l -> l[i] = it } }, onValueChangeFinished = { VxSettings.setEqGains(ctx, gains) }, valueRange = -8f..8f, modifier = androidx.compose.ui.Modifier.weight(1f))
                    androidx.compose.material3.Text(String.format("%.1f", g), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = androidx.compose.ui.Modifier.width(36.dp))
                }
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = { gains = List(15) { 0f }; VxSettings.setEqPreset(ctx, gains); eqOn = true }) { androidx.compose.material3.Text("Flat") }
                androidx.compose.material3.TextButton(onClick = { gains = VxSettings.STUDIO_MASTER; VxSettings.setEqPreset(ctx, gains); eqOn = true }) { androidx.compose.material3.Text("Studio Master") }
            }
        }
        VxRow("Wavy Seekbar", "Multi-layer fluid wavy progress slider", wavy) { wavy = it; VxSettings.setWavy(ctx, it) }
        VxRow("Studio Master Clarity", "Crystal-clear open sound", clarity) { clarity = it; VxQuality.setStudioClarity(ctx, it); VxAudioFx.apply(ctx) }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
        androidx.compose.material3.Text("Audio & Streaming", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        androidx.compose.material3.Text("Streaming", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        VxQuality.STREAM_OPTIONS.forEach { q ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = androidx.compose.ui.Modifier.fillMaxWidth().clickable { streamQ = q; VxQuality.setStreamQuality(ctx, q) }.padding(vertical = 2.dp)) {
                androidx.compose.material3.RadioButton(selected = streamQ == q, onClick = { streamQ = q; VxQuality.setStreamQuality(ctx, q) })
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
                androidx.compose.material3.Text(if (q == -1) "YouTube (Opus/AAC) \u2022 JioSaavn 320k fallback" else VxQuality.labelFor(q))
            }
        }
        androidx.compose.material3.Text("Downloads", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        VxQuality.DOWNLOAD_OPTIONS.forEach { q ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = androidx.compose.ui.Modifier.fillMaxWidth().clickable { dlQ = q; VxQuality.setDownloadQuality(ctx, q) }.padding(vertical = 2.dp)) {
                androidx.compose.material3.RadioButton(selected = dlQ == q, onClick = { dlQ = q; VxQuality.setDownloadQuality(ctx, q) })
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
                androidx.compose.material3.Text(if (q == -1) "YouTube (Opus/AAC)" else VxQuality.labelFor(q))
            }
        }
        VxRow("Prefer lossless (FLAC)", "Falls back to YouTube/JioSaavn for remixes", prefer) { prefer = it; VxQuality.setPreferLossless(ctx, it) }
        VxRow("Bit-Perfect Mode", "Bypass DSP, direct passthrough", bitPerfect) { bitPerfect = it; VxQuality.setBitPerfect(ctx, it); VxAudioFx.apply(ctx) }
        VxRow("Crossfade", "Blend end of track into next (" + xsecs.toInt() + "s)", xfade) { xfade = it; VxSettings.setCrossfade(ctx, it) }
        if (xfade) {
            androidx.compose.material3.Slider(value = xsecs, onValueChange = { xsecs = it }, onValueChangeFinished = { VxSettings.setXfadeSecs(ctx, xsecs.toInt()) }, valueRange = 1f..12f, steps = 10, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
        androidx.compose.material3.Text("Appearance", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        VxRow("AMOLED Mode", "Pure black background", amoled) { amoled = it; VxSettings.setAmoled(ctx, it) }
        VxRow("Dynamic Color", "Use wallpaper colors", dynC) { dynC = it; VxSettings.setDynamicColor(ctx, it) }
        VxRow("Dynamic Now Playing", "Match player artwork", dynNp) { dynNp = it; VxSettings.setDynNowPlaying(ctx, it) }
        VxRow("Use Application Font", "Custom look across app", appFont) { appFont = it; VxSettings.setAppFont(ctx, it) }
        androidx.compose.material3.Text("Accent: " + accent, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        VxSettings.ACCENTS.chunked(4).forEach { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    androidx.compose.material3.TextButton(onClick = { accent = a; VxSettings.setAccent(ctx, a) }) {
                        androidx.compose.material3.Text(if (a == accent) "\u2022 " + a else a)
                    }
                }
            }
        }
    }
}

@Composable
private fun VxRow(title: String, subtitle: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
            androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun VxRow(title: String, subtitle: String, checked: Nothing?, arrow: Boolean, onOpen: () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = androidx.compose.ui.Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp)) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
            androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Text(">")
    }
}
