package com.hikari.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One selectable row in the Testing panel. [key] goes back to the
 *  activity, which knows what every key means. */
data class TOption(
    val label: String,
    val sub: String?,
    val badge: String?,
    val selected: Boolean,
    val key: Int,
)

data class TServer(
    val name: String,
    val detail: String?,
    val quality: String?,
    val selected: Boolean,
    val index: Int,
)

enum class TTab { SOURCES, QUALITY, AUDIO, SUBTITLES, SPEED, MORE }

data class TSubStyle(
    val sizePct: Int,
    val colorArgb: Int,
    val bold: Boolean,
)

/** Everything the Testing overlay shows. Built fresh by the activity, so the
 *  overlay itself holds no player state — only which tab is open. */
data class TState(
    val title: String,
    val serverLine: String,
    val badge: String?,
    val posMs: Long,
    val durMs: Long,
    val endsAt: String,
    val playing: Boolean,
    val locked: Boolean,
    val captionsOn: Boolean,
    val servers: List<TServer>,
    val qualities: List<TOption>,
    val audioCurrent: TOption?,
    val audioOthers: List<TOption>,
    val soundRows: List<TOption>,
    val subOff: TOption,
    val subAuto: TOption,
    val subTracks: List<TOption>,
    val subStyle: TSubStyle,
    val speeds: List<TOption>,
    val aspects: List<TOption>,
    val zoomPct: Int,
    val enhance: List<TOption>,
)

/** Every action the Testing overlay can take, wired by the activity to the
 *  same functions the classic sheets call. */
class TActions(
    val back: () -> Unit,
    val collapse: () -> Unit,
    val toggleLock: () -> Unit,
    val togglePlay: () -> Unit,
    val back10: () -> Unit,
    val fwd10: () -> Unit,
    val toggleControls: () -> Unit,
    val seekTo: (Long) -> Unit,
    val selectServer: (Int) -> Unit,
    val selectQuality: (Int) -> Unit,
    val selectAudio: (Int) -> Unit,
    val toggleSound: (Int) -> Unit,
    val subOff: () -> Unit,
    val subAuto: () -> Unit,
    val selectSub: (Int) -> Unit,
    val openSubSearch: () -> Unit,
    val openSubAdd: () -> Unit,
    val subSyncMinus: () -> Unit,
    val subSyncPlus: () -> Unit,
    val subSyncReset: () -> Unit,
    val subSizeMinus: () -> Unit,
    val subSizePlus: () -> Unit,
    val subColor: (Int) -> Unit,
    val subBold: () -> Unit,
    val selectSpeed: (Int) -> Unit,
    val selectAspect: (Int) -> Unit,
    val zoomMinus: () -> Unit,
    val zoomPlus: () -> Unit,
    val selectEnhance: (Int) -> Unit,
    val pip: () -> Unit,
    val rotate: () -> Unit,
    val toggleCaptions: () -> Unit,
)

private val TSheetBg = Color(0xF2141418)
private val TRowBg = Color(0xFF232328)
private val TSelBg = Color(0xFF5A3D1E)
private val TAccent = Color(0xFFE8A33D)
private val TMuted = Color(0xFFA7A7B0)
private val TPillBg = Color(0xCC1E1E24)

/** Reference-style Testing overlay: main controls plus the right-side
 *  tabbed panel holding every player option. [get] is polled twice a
 *  second so time, rows and marks stay fresh. */
@Composable
fun TestingPlayerOverlay(get: () -> TState, act: TActions) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val s = remember(tick) { get() }
    var tab by remember { mutableStateOf<TTab?>(null) }
    var subAppearance by remember { mutableStateOf(false) }
    BackHandler(enabled = tab != null) {
        if (subAppearance) subAppearance = false else tab = null
    }
    val tap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { act.toggleControls() }
    Box(Modifier.fillMaxSize()) {
        // Invisible tap catcher: tapping the picture hides the controls.
        Box(Modifier.fillMaxSize().then(tap))
        // Top bar.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = act.back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            IconButton(onClick = act.collapse) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Collapse", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = act.toggleLock) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Lock",
                    tint = if (s.locked) TAccent else Color.White,
                )
            }
        }
        // Centre play cluster.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            IconButton(onClick = act.back10, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .clickable(onClick = act.togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = act.fwd10, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        // Bottom info + seek + pill.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.serverLine, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.badge != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.badge,
                                color = Color(0xFFFFC247),
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${fmtT(s.posMs)} / ${fmtT(s.durMs)} · ends ${s.endsAt}",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            val frac = if (s.durMs > 0) (s.posMs.toFloat() / s.durMs.toFloat()).coerceIn(0f, 1f) else 0f
            Slider(
                value = frac,
                onValueChange = { f -> act.seekTo((f * s.durMs).toLong()) },
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.9f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(TPillBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TPillCc(on = s.captionsOn, onClick = act.toggleCaptions)
                    TPillIcon(Icons.Filled.GraphicEq, "Audio", s.audioOthers.isNotEmpty(), onClick = { tab = TTab.AUDIO })
                    TPillIcon(Icons.Filled.Tune, "More", false, onClick = { tab = TTab.MORE })
                    TPillIcon(Icons.Filled.Smartphone, "Rotate", false, onClick = act.rotate)
                    TPillCc(on = false, text = "PiP", onClick = act.pip)
                }
            }
        }
        // Right-side tabbed panel.
        if (tab != null) {
            val t = tab!!
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { tab = null; subAppearance = false })
                Column(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .fillMaxSize()
                        .background(TSheetBg)
                        .padding(vertical = 12.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TRowBg)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        for (tt in TTab.entries) {
                            val sel = tt == t
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (sel) Color(0xFF4A4A52) else Color.Transparent)
                                    .clickable { tab = tt; subAppearance = false }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    when (tt) {
                                        TTab.SOURCES -> "Sources"
                                        TTab.QUALITY -> "Quality"
                                        TTab.AUDIO -> "Audio"
                                        TTab.SUBTITLES -> "Subtitles"
                                        TTab.SPEED -> "Speed"
                                        TTab.MORE -> "More"
                                    },
                                    color = if (sel) Color.White else TMuted,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (t) {
                        TTab.SOURCES -> TSourceList(s, act)
                        TTab.QUALITY -> TQualityList(s, act)
                        TTab.AUDIO -> TAudioList(s, act)
                        TTab.SUBTITLES -> TSubList(s, act, subAppearance, onAppearance = { subAppearance = it })
                        TTab.SPEED -> TSpeedList(s, act)
                        TTab.MORE -> TMoreList(s, act)
                    }
                }
            }
        }
    }
}

private fun fmtT(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    else "$m:${sec.toString().padStart(2, '0')}"
}

@Composable
private fun TPillCc(on: Boolean, onClick: () -> Unit, text: String = "CC") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (on) TAccent else Color.Transparent)
        )
    }
}

@Composable
private fun TPillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, dot: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (dot) TAccent else Color.Transparent)
        )
    }
}

@Composable
private fun TSection(label: String) {
    Text(
        label,
        color = TMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun TRow(opt: TOption, onClick: (Int) -> Unit, chevron: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (opt.selected) TSelBg else TRowBg)
            .clickable { onClick(opt.key) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(opt.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (opt.sub != null) {
                Text(opt.sub, color = TMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (opt.badge != null) {
            Text(opt.badge, color = TAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        if (opt.selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = TAccent, modifier = Modifier.size(18.dp).padding(start = 4.dp))
        } else if (chevron) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TSourceList(s: TState, act: TActions) {
    TSection("IN YOUR ORDER")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(s.servers.size, key = { s.servers[it].index }) { i ->
            val v = s.servers[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (v.selected) TSelBg else TRowBg)
                    .clickable { act.selectServer(v.index) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = TAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v.detail != null) {
                        Text(v.detail, color = TMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (v.quality != null) {
                    Text(v.quality, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TQualityList(s: TState, act: TActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(s.qualities.size, key = { it }) { i ->
            TRow(s.qualities[i], onClick = act.selectQuality)
        }
    }
}

@Composable
private fun TAudioList(s: TState, act: TActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (s.audioCurrent != null) {
            item { TRow(s.audioCurrent, onClick = act.selectAudio) }
        }
        if (s.audioOthers.isNotEmpty()) {
            item { TSection("ON ANOTHER SERVER") }
            items(s.audioOthers.size, key = { "a${s.audioOthers[it].key}" }) { i ->
                TRow(s.audioOthers[i], onClick = act.selectAudio, chevron = true)
            }
        }
        if (s.soundRows.isNotEmpty()) {
            item { TSection("SOUND") }
            items(s.soundRows.size, key = { "s${s.soundRows[it].key}" }) { i ->
                TRow(s.soundRows[i], onClick = act.toggleSound)
            }
        }
    }
}

@Composable
private fun TSubList(s: TState, act: TActions, appearance: Boolean, onAppearance: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TRowBg)
                .padding(3.dp),
        ) {
            for ((label, sel, fn) in listOf(
                Triple("Tracks", !appearance, { onAppearance(false) }),
                Triple("Appearance", appearance, { onAppearance(true) }),
            )) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Color(0xFF4A4A52) else Color.Transparent)
                        .clickable(onClick = fn)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (sel) Color.White else TMuted, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!appearance) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TRowBg)
                            .clickable(onClick = act.openSubSearch)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sync subtitles", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TAccent)
                    }
                }
                item { TRow(s.subOff, onClick = { act.subOff() }) }
                item { TRow(s.subAuto, onClick = { act.subAuto() }) }
                items(s.subTracks.size, key = { "t${s.subTracks[it].key}" }) { i ->
                    TRow(s.subTracks[i], onClick = act.selectSub, chevron = true)
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TRowBg)
                            .clickable(onClick = act.openSubAdd)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = TMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add external subtitle", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Text size", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TCircleBtn(Icons.Filled.Remove, act.subSizeMinus)
                        Text("${s.subStyle.sizePct}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        TCircleBtn(Icons.Filled.Add, act.subSizePlus)
                    }
                }
                item {
                    Text("Colour", color = TMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                    Row(Modifier.padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in listOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF4DD8FF.toInt(), 0xFF5DFF7A.toInt(), 0xFFFF7AC0.toInt())) {
                            val sel = s.subStyle.colorArgb == c
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .clickable { act.subColor(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (sel) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (s.subStyle.bold) TSelBg else TRowBg)
                            .clickable(onClick = act.subBold)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bold", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (s.subStyle.bold) Icon(Icons.Filled.Check, contentDescription = null, tint = TAccent, modifier = Modifier.size(18.dp))
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sync", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TCircleBtn(Icons.Filled.Remove, act.subSyncMinus)
                        Spacer(Modifier.width(10.dp))
                        TCircleBtn(Icons.Filled.Add, act.subSyncPlus)
                        Spacer(Modifier.width(10.dp))
                        Text("Reset", color = TAccent, fontSize = 14.sp, modifier = Modifier.clickable(onClick = act.subSyncReset))
                    }
                }
            }
        }
    }
}

@Composable
private fun TCircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFF33333B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TSpeedList(s: TState, act: TActions) {
    val cur = s.speeds.firstOrNull { it.selected }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                (cur?.label ?: "1×").trimEnd('×') + "×",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 40.sp,
                modifier = Modifier.weight(1f),
            )
            Text("Ends ${s.endsAt}", color = TMuted, fontSize = 12.sp)
        }
        val rows = s.speeds.chunked(6)
        for (row in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(TRowBg).padding(4.dp)) {
                for (o in row) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (o.selected) Color(0xFF4A4A52) else Color.Transparent)
                            .clickable { act.selectSpeed(o.key) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(o.label, color = if (o.selected) Color.White else TMuted, fontSize = 13.sp, fontWeight = if (o.selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TMoreList(s: TState, act: TActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TSection("PICTURE") }
        item {
            Text("Aspect ratio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TRowBg)
                    .padding(4.dp),
            ) {
                for (o in s.aspects) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (o.selected) Color(0xFF4A4A52) else Color.Transparent)
                            .clickable { act.selectAspect(o.key) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(o.label, color = if (o.selected) Color.White else TMuted, fontSize = 13.sp, fontWeight = if (o.selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Zoom", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Crop the black bars, or shrink the picture", color = TMuted, fontSize = 12.sp)
                }
                TCircleBtn(Icons.Filled.Remove, act.zoomMinus)
                Text("${s.zoomPct}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
                TCircleBtn(Icons.Filled.Add, act.zoomPlus)
            }
        }
        if (s.enhance.isNotEmpty()) {
            item { TSection("VIDEO ENHANCE") }
            items(s.enhance.size, key = { "e${s.enhance[it].key}" }) { i ->
                TRow(s.enhance[i], onClick = act.selectEnhance)
            }
        }
    }
}
