package com.hikari.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.tv.TvUi

private val TvSetRowMax = 900.dp

/**
 * Television version of the phone's Music Settings tab ([VxSettingsInline]).
 * Same options, same stored values (VxSettings / VxQuality), but built for a remote:
 * every row is focusable, OK toggles or selects, and Left / Right changes a value.
 */
@UnstableApi
@Composable
fun TvMusicSettings(startPad: Dp, tabs: @Composable () -> Unit) {
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
    var xsecs by remember { mutableStateOf(VxSettings.xfadeSecs(ctx)) }
    var lyricAnim by remember { mutableStateOf(VxSettings.lyricsAnim(ctx)) }
    var lyricOpen by remember { mutableStateOf(false) }
    var amoled by remember { mutableStateOf(VxSettings.amoled(ctx)) }
    var liquid by remember { mutableStateOf(VxSettings.liquidGlass(ctx)) }
    var dynC by remember { mutableStateOf(VxSettings.dynamicColor(ctx)) }
    var dynNp by remember { mutableStateOf(VxSettings.dynNowPlaying(ctx)) }
    var appFont by remember { mutableStateOf(VxSettings.appFont(ctx)) }
    var accent by remember { mutableStateOf(VxSettings.accent(ctx)) }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Box(Modifier.padding(start = startPad)) { tabs() }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = maxOf(startPad - 8.dp, 0.dp), end = 48.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ------------------------------------------------------------ Experimental
            item { TvSetHeader("Experimental") }
            item {
                TvSetSwitchRow("Liquid Glass", "iOS-style translucent materials", liquid) {
                    liquid = it; VxSettings.setLiquidGlass(ctx, it)
                }
            }
            item {
                TvSetNavRow("Lyrics Animation", lyricAnim, lyricOpen) { lyricOpen = !lyricOpen }
            }
            if (lyricOpen) {
                items(VxSettings.LYRIC_ANIMS) { a ->
                    TvSetChoiceRow(a, lyricAnim == a, indent = true) {
                        lyricAnim = a; VxSettings.setLyricsAnim(ctx, a); lyricOpen = false
                    }
                }
            }
            item {
                TvSetSwitchRow(
                    "Equalizer",
                    if (eqOn) "On \u2022 15 bands" else "Shape your sound across 15 frequencies",
                    eqOn,
                ) {
                    eqOn = it; VxSettings.setEqEnabled(ctx, it); VxAudioFx.apply(ctx)
                }
            }
            if (eqOn) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                        TvMusicPill("Flat") {
                            gains = List(15) { 0f }; VxSettings.setEqPreset(ctx, gains); eqOn = true
                        }
                        TvMusicPill("Studio Master") {
                            gains = VxSettings.STUDIO_MASTER; VxSettings.setEqPreset(ctx, gains); eqOn = true
                        }
                    }
                }
                items(VxSettings.EQ_FREQS.size) { i ->
                    TvSetStepRow(
                        title = VxSettings.eqLabel(VxSettings.EQ_FREQS[i]) + " Hz",
                        subtitle = "Left / Right to change, OK to reset (-8 to +8 dB)",
                        value = String.format("%+.1f dB", gains[i]),
                        indent = true,
                        onStep = { d ->
                            val v = (gains[i] + d * 0.5f).coerceIn(-8f, 8f)
                            gains = gains.toMutableList().also { l -> l[i] = v }
                            VxSettings.setEqGains(ctx, gains)
                        },
                        onPress = {
                            gains = gains.toMutableList().also { l -> l[i] = 0f }
                            VxSettings.setEqGains(ctx, gains)
                        },
                    )
                }
            }
            item {
                TvSetSwitchRow("Wavy Seekbar", "Multi-layer fluid wavy progress slider", wavy) {
                    wavy = it; VxSettings.setWavy(ctx, it)
                }
            }
            item {
                TvSetSwitchRow("Studio Master Clarity", "Crystal-clear open sound", clarity) {
                    clarity = it; VxQuality.setStudioClarity(ctx, it); VxAudioFx.apply(ctx)
                }
            }

            // ------------------------------------------------------------ Audio & Streaming
            item { Spacer(Modifier.height(8.dp)) }
            item { TvSetHeader("Audio & Streaming") }
            item { TvSetLabel("Streaming") }
            items(VxQuality.STREAM_OPTIONS) { q ->
                TvSetChoiceRow(
                    if (q == -1) "YouTube (Opus/AAC) \u2022 JioSaavn 320k fallback" else VxQuality.labelFor(q),
                    streamQ == q,
                ) { streamQ = q; VxQuality.setStreamQuality(ctx, q) }
            }
            item { TvSetLabel("Downloads") }
            items(VxQuality.DOWNLOAD_OPTIONS) { q ->
                TvSetChoiceRow(
                    if (q == -1) "YouTube (Opus/AAC)" else VxQuality.labelFor(q),
                    dlQ == q,
                ) { dlQ = q; VxQuality.setDownloadQuality(ctx, q) }
            }
            item {
                TvSetSwitchRow("Prefer lossless (FLAC)", "Falls back to YouTube/JioSaavn for remixes", prefer) {
                    prefer = it; VxQuality.setPreferLossless(ctx, it)
                }
            }
            item {
                TvSetSwitchRow("Bit-Perfect Mode", "Bypass DSP, direct passthrough", bitPerfect) {
                    bitPerfect = it; VxQuality.setBitPerfect(ctx, it); VxAudioFx.apply(ctx)
                }
            }
            item {
                TvSetSwitchRow("Crossfade", "Blend end of track into next (" + xsecs + "s)", xfade) {
                    xfade = it; VxSettings.setCrossfade(ctx, it)
                }
            }
            if (xfade) {
                item {
                    TvSetStepRow(
                        title = "Crossfade length",
                        subtitle = "Left / Right to change (1 to 12 seconds)",
                        value = xsecs.toString() + " s",
                        indent = true,
                        onStep = { d ->
                            xsecs = (xsecs + d).coerceIn(1, 12)
                            VxSettings.setXfadeSecs(ctx, xsecs)
                        },
                    )
                }
            }

            // ------------------------------------------------------------ Appearance
            item { Spacer(Modifier.height(8.dp)) }
            item { TvSetHeader("Appearance") }
            item {
                TvSetSwitchRow("AMOLED Mode", "Pure black background", amoled) {
                    amoled = it; VxSettings.setAmoled(ctx, it)
                }
            }
            item {
                TvSetSwitchRow("Dynamic Color", "Use wallpaper colors", dynC) {
                    dynC = it; VxSettings.setDynamicColor(ctx, it)
                }
            }
            item {
                TvSetSwitchRow("Dynamic Now Playing", "Match player artwork", dynNp) {
                    dynNp = it; VxSettings.setDynNowPlaying(ctx, it)
                }
            }
            item {
                TvSetSwitchRow("Use Application Font", "Custom look across app", appFont) {
                    appFont = it; VxSettings.setAppFont(ctx, it)
                }
            }
            item { TvSetLabel("Accent: " + accent) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(VxSettings.ACCENTS) { a ->
                        TvMusicPill(a, selected = a == accent) { accent = a; VxSettings.setAccent(ctx, a) }
                    }
                }
            }
            item {
                val s = TvUi.scale()
                Text(
                    "Applies live: theme + accent + font change the whole app, player rows apply to the Now Playing screen.",
                    color = TvMusicMuted,
                    fontSize = 13.sp * s,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- rows

@Composable
private fun TvSetHeader(text: String) {
    TvMusicSectionTitle(text, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp))
}

@Composable
private fun TvSetLabel(text: String) {
    val s = TvUi.scale()
    Text(
        text,
        color = TvMusicMuted,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp * s,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun TvSetTitles(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val s = TvUi.scale()
    Column(modifier) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp * s, maxLines = 1)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = TvMusicMuted, fontSize = 13.sp * s, maxLines = 2)
        }
    }
}

/** On / off row: OK on the remote flips it. */
@Composable
private fun TvSetSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .widthIn(max = TvSetRowMax)
            .fillMaxWidth()
            .tvMusicFocus(RoundedCornerShape(12.dp), focusedScale = 1.02f) { onChange(!checked) }
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSetTitles(title, subtitle, Modifier.weight(1f))
        // onCheckedChange = null keeps the switch itself out of the focus order: the row is the control.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One option of a pick-one list. */
@Composable
private fun TvSetChoiceRow(label: String, selected: Boolean, indent: Boolean = false, onClick: () -> Unit) {
    val s = TvUi.scale()
    Row(
        Modifier
            .padding(start = if (indent) 20.dp else 0.dp)
            .widthIn(max = TvSetRowMax)
            .fillMaxWidth()
            .tvMusicFocus(RoundedCornerShape(12.dp), focusedScale = 1.02f, onClick = onClick)
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            color = if (selected) TvMusicPrimary else Color.White,
            fontSize = 16.sp * s,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 1,
        )
    }
}

/** A row that opens a list below it (Lyrics Animation). */
@Composable
private fun TvSetNavRow(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    val s = TvUi.scale()
    Row(
        Modifier
            .widthIn(max = TvSetRowMax)
            .fillMaxWidth()
            .tvMusicFocus(RoundedCornerShape(12.dp), focusedScale = 1.02f, onClick = onClick)
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSetTitles(title, subtitle, Modifier.weight(1f))
        Text(if (expanded) "v" else ">", color = Color.White, fontSize = 20.sp * s)
    }
}

/** A value you change with Left / Right on the remote (equalizer bands, crossfade length). */
@Composable
private fun TvSetStepRow(
    title: String,
    subtitle: String,
    value: String,
    indent: Boolean = false,
    onStep: (Int) -> Unit,
    onPress: () -> Unit = {},
) {
    val s = TvUi.scale()
    Row(
        Modifier
            .padding(start = if (indent) 20.dp else 0.dp)
            .widthIn(max = TvSetRowMax)
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionLeft && e.key != Key.DirectionRight) {
                    false
                } else {
                    if (e.type == KeyEventType.KeyDown) onStep(if (e.key == Key.DirectionLeft) -1 else 1)
                    true
                }
            }
            .tvMusicFocus(RoundedCornerShape(12.dp), focusedScale = 1.02f, onClick = onPress)
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSetTitles(title, subtitle, Modifier.weight(1f))
        Text(
            "\u2039  " + value + "  \u203A",
            color = TvMusicPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp * s,
            maxLines = 1,
        )
    }
}
