package com.hikari.app.player

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.theme.inkOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    val audioTracks: List<TOption>,
    val audioOthers: List<TOption>,
    val soundRows: List<TOption>,
    val subOff: TOption,
    val subAuto: TOption,
    val subTracks: List<TOption>,
    val subStyle: TSubStyle,
    val subBgArgb: Int,
    val subEdge: Int,
    val subItalic: Boolean,
    val subGenActive: Boolean,
    val hasUserSubs: Boolean,
    val autoSitesOn: Boolean,
    val subPosPct: Int,
    val captionSummary: String,
    val speeds: List<TOption>,
    val aspects: List<TOption>,
    val zoomPct: Int,
    val enhance: List<TOption>,
    val statsOn: Boolean,
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
    val openSubStyle: () -> Unit,
    val genSub: () -> Unit,
    val removeSubs: () -> Unit,
    val toggleAutoSites: () -> Unit,
    val subSyncMinus: () -> Unit,
    val subSyncPlus: () -> Unit,
    val subSyncReset: () -> Unit,
    val subSizeMinus: () -> Unit,
    val subSizePlus: () -> Unit,
    val subPosMinus: () -> Unit,
    val subPosPlus: () -> Unit,
    val subColor: (Int) -> Unit,
    val subBold: () -> Unit,
    val subItalicToggle: () -> Unit,
    val subBgCycle: () -> Unit,
    val subEdgeCycle: () -> Unit,
    val selectSpeed: (Int) -> Unit,
    val selectAspect: (Int) -> Unit,
    val zoomMinus: () -> Unit,
    val zoomPlus: () -> Unit,
    val selectEnhance: (Int) -> Unit,
    val pip: () -> Unit,
    val rotate: () -> Unit,
    val toggleCaptions: () -> Unit,
    val toggleStats: () -> Unit,
    val openDecoder: () -> Unit,
)

private val TSheetBg = Color(0xF2141418)
private val TRowBg = Color(0xFF232328)
private val TMuted = Color(0xFFA7A7B0)
private val TPillBg = Color(0xCC1E1E24)
/** Soft drop shadow so free-floating header/seek text reads on bright video. */
private val TShadow = Shadow(color = Color(0xCC000000), offset = Offset(0f, 2f), blurRadius = 6f)

/** Where the tool rail lives: edge side + vertical fraction, remembered. */
private const val RAIL_PREFS = "hikari_rail"
private fun loadRailRight(ctx: Context): Boolean =
    runCatching {
        ctx.getSharedPreferences(RAIL_PREFS, Context.MODE_PRIVATE).getBoolean("right", true)
    }.getOrDefault(true)
private fun loadRailFrac(ctx: Context): Float =
    runCatching {
        ctx.getSharedPreferences(RAIL_PREFS, Context.MODE_PRIVATE).getFloat("y", 0.5f)
    }.getOrDefault(0.5f).coerceIn(0f, 1f)
private fun saveRail(ctx: Context, right: Boolean, frac: Float) {
    runCatching {
        ctx.getSharedPreferences(RAIL_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("right", right).putFloat("y", frac.coerceIn(0f, 1f)).apply()
    }
}

/** The player's live accent: the app accent while "Match app & player theme"
 *  is on, otherwise the player's own accent (Settings → Appearance & Theme).
 *  Teal in the mockups is just one pick — every user gets their own colour
 *  on the play disc, the seek bar, the badges, the radios and the tabs. */
@Composable
private fun tAccent(): Color = AccentStore.player(LocalContext.current).mid

/** Ink (icon/text) drawn on top of the solid accent. */
@Composable
private fun tOnAccent(): Color = inkOn(tAccent())

/** Wash behind selected rows and cells on the dark sheet. */
@Composable
private fun tSelBg(): Color = tAccent().copy(alpha = 0.16f)

/** Hikari Player overlay: card chrome (header card, accent disc, ends-at
 *  seek row, side tool rail) plus the icon-rail option panel holding every
 *  player option. [get] is polled twice a second so time, rows and marks
 *  stay fresh. */
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
    // Tool-rail dock: edge side + vertical fraction, remembered across plays.
    val railCtx = LocalContext.current
    var railRight by remember { mutableStateOf(loadRailRight(railCtx)) }
    var railFrac by remember { mutableFloatStateOf(loadRailFrac(railCtx)) }
    var railWpx by remember { mutableIntStateOf(0) }
    var railHpx by remember { mutableIntStateOf(0) }
    var railDragging by remember { mutableStateOf(false) }
    var railDx by remember { mutableFloatStateOf(0f) }
    var railDy by remember { mutableFloatStateOf(0f) }
    val railHaptic = LocalHapticFeedback.current
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
        // Header: title + server · quality badge · lock, floating free on
        // the video (no bar box) with drop shadows for bright scenes.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 10.dp)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = act.back),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = act.collapse, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Collapse", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                Text(
                    s.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = TShadow),
                )
                Text(
                    s.serverLine,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = TShadow),
                )
            }
            if (s.badge != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, tAccent(), RoundedCornerShape(9.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text(s.badge, color = tAccent(), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = act.toggleLock, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Lock",
                    tint = if (s.locked) tAccent() else Color.White,
                )
            }
        }
        // Centre play cluster: dark side chips around a solid accent disc.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = act.back10),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(tAccent())
                    .clickable(onClick = act.togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = tOnAccent(),
                    modifier = Modifier.size(44.dp),
                )
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = act.fwd10),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        // Seek row: position · ends-at · duration, then the accent bar.
        // The side the rail is docked on stays clear of the bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = if (railRight) 18.dp else 86.dp,
                    end = if (railRight) 86.dp else 18.dp,
                    bottom = 14.dp,
                ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmtT(s.posMs),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    style = TextStyle(shadow = TShadow),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Ends ${s.endsAt}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        style = TextStyle(shadow = TShadow),
                    )
                }
                Text(
                    fmtT(s.durMs),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    style = TextStyle(shadow = TShadow),
                )
            }
            val frac = if (s.durMs > 0) (s.posMs.toFloat() / s.durMs.toFloat()).coerceIn(0f, 1f) else 0f
            Slider(
                value = frac,
                onValueChange = { f -> act.seekTo((f * s.durMs).toLong()) },
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = tAccent(),
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
        }
        // Right-side tool rail (replaces the old bottom pill). Hold it for
        // half a second and it lifts: drag it anywhere, and on release it
        // docks to whichever edge it leans past and stays at that height.
        // The dock (side + height) is remembered across plays.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val marginPx = with(density) { 10.dp.toPx() }
            val clearTopPx = with(density) { 120.dp.toPx() }
            val clearBotPx = with(density) { 120.dp.toPx() }
            val travelPx = (maxHpx - clearTopPx - clearBotPx - railHpx).coerceAtLeast(0f)
            val dockedTopPx = clearTopPx + railFrac * travelPx
            val dockedLeftPx = if (railRight) maxWpx - railWpx - marginPx else marginPx
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (dockedLeftPx + railDx).roundToInt(),
                            (dockedTopPx + railDy).roundToInt(),
                        )
                    }
                    .onGloballyPositioned { railWpx = it.size.width; railHpx = it.size.height }
                    .graphicsLayer {
                        val z = if (railDragging) 1.08f else 1f
                        scaleX = z; scaleY = z
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (railDragging) Color(0xE61E1E24) else TPillBg)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                railHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                railDragging = true; railDx = 0f; railDy = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume(); railDx += amount.x; railDy += amount.y
                            },
                            onDragEnd = {
                                railDragging = false
                                val dropCx = dockedLeftPx + railDx + railWpx / 2f
                                val dropTop = dockedTopPx + railDy
                                railRight = dropCx >= maxWpx / 2f
                                railFrac = if (travelPx > 0f) {
                                    ((dropTop - clearTopPx) / travelPx).coerceIn(0f, 1f)
                                } else 0.5f
                                railDx = 0f; railDy = 0f
                                saveRail(railCtx, railRight, railFrac)
                            },
                            onDragCancel = { railDragging = false; railDx = 0f; railDy = 0f },
                        )
                    }
                    .padding(horizontal = 5.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TPillCc(on = s.captionsOn, onClick = act.toggleCaptions)
                TPillIcon(Icons.Filled.GraphicEq, "Audio", s.audioOthers.isNotEmpty(), onClick = { tab = TTab.AUDIO })
                TPillIcon(Icons.Filled.Tune, "More", false, onClick = { tab = TTab.MORE })
                TPillIcon(Icons.Filled.Smartphone, "Rotate", false, onClick = act.rotate)
                TPillCc(on = false, text = "PiP", onClick = act.pip)
            }
        }
        // Option panel: a card holding a vertical icon rail plus the tab's
        // content. Landscape keeps it on the right; portrait pins it to the
        // bottom. Tapping the dimmed video area closes it.
        if (tab != null) {
            val t = tab!!
            val cfg = LocalConfiguration.current
            val isPortrait = cfg.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val tabTitle = when (t) {
                TTab.SOURCES -> "Sources"
                TTab.QUALITY -> "Quality"
                TTab.AUDIO -> "Audio"
                TTab.SUBTITLES -> "Subtitles"
                TTab.SPEED -> "Speed"
                TTab.MORE -> "More"
            }
            val close = { tab = null; subAppearance = false }
            if (isPortrait) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { close() },
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.68f)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(TSheetBg)
                            .padding(vertical = 12.dp),
                    ) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            TRail(t, onPick = { tab = it; subAppearance = false })
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Text(
                                    tabTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                                )
                                TTabContent(t, s, act, subAppearance, onAppearance = { subAppearance = it })
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { close() },
                    )
                    Column(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxSize()
                            .background(TSheetBg)
                            .padding(vertical = 12.dp),
                    ) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            TRail(t, onPick = { tab = it; subAppearance = false })
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Text(
                                    tabTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                                )
                                TTabContent(t, s, act, subAppearance, onAppearance = { subAppearance = it })
                            }
                        }
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
                .background(if (on) tAccent() else Color.Transparent)
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
                .background(if (dot) tAccent() else Color.Transparent)
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

private fun tabIcon(tt: TTab): androidx.compose.ui.graphics.vector.ImageVector = when (tt) {
    TTab.SOURCES -> Icons.Filled.Layers
    TTab.QUALITY -> Icons.Filled.HighQuality
    TTab.AUDIO -> Icons.Filled.GraphicEq
    TTab.SUBTITLES -> Icons.Filled.ClosedCaption
    TTab.SPEED -> Icons.Filled.Speed
    TTab.MORE -> Icons.Filled.MoreVert
}

/** The panel's vertical icon rail: one accent disc per tab. */
@Composable
private fun TRail(sel: TTab, onPick: (TTab) -> Unit) {
    Column(
        Modifier.width(58.dp).padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tt in TTab.entries) {
            val on = tt == sel
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (on) tAccent() else Color.Transparent)
                    .clickable { onPick(tt) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tabIcon(tt),
                    contentDescription = tt.name,
                    tint = if (on) tOnAccent() else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** The rail-selected tab's list. */
@Composable
private fun TTabContent(
    t: TTab,
    s: TState,
    act: TActions,
    appearance: Boolean,
    onAppearance: (Boolean) -> Unit,
) {
    when (t) {
        TTab.SOURCES -> TSourceList(s, act)
        TTab.QUALITY -> TQualityList(s, act)
        TTab.AUDIO -> TAudioList(s, act)
        TTab.SUBTITLES -> TSubList(s, act, appearance, onAppearance = onAppearance)
        TTab.SPEED -> TSpeedList(s, act)
        TTab.MORE -> TMoreList(s, act)
    }
}

@Composable
private fun TRow(opt: TOption, onClick: (Int) -> Unit, chevron: Boolean = false) {
    val sel = opt.selected
    val ac = tAccent()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (sel) tSelBg() else TRowBg)
            .then(if (sel) Modifier.border(1.dp, ac, RoundedCornerShape(12.dp)) else Modifier)
            .clickable { onClick(opt.key) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Radio: hollow when off, accent dot when on (navigation rows keep
        // the chevron instead).
        if (!chevron) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (sel) ac else TMuted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (sel) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ac),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(opt.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (opt.sub != null) {
                Text(opt.sub, color = TMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (opt.badge != null) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, ac, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(opt.badge, color = ac, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TMuted, modifier = Modifier.size(18.dp).padding(start = 4.dp))
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
                    .background(if (v.selected) tSelBg() else TRowBg)
                    .then(if (v.selected) Modifier.border(1.dp, tAccent(), RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { act.selectServer(v.index) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = tAccent(), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(22.dp))
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
        if (s.audioTracks.isNotEmpty()) {
            item { TSection("THIS STREAM") }
            items(s.audioTracks.size, key = { "t${s.audioTracks[it].key}" }) { i ->
                TRow(s.audioTracks[i], onClick = act.selectAudio)
            }
        } else if (s.audioCurrent != null) {
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
                        .background(if (sel) tSelBg() else Color.Transparent)
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
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Load from internet", color = Color.White, fontSize = 15.sp)
                            Text("Search every subtitle site and addon", color = TMuted, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tAccent())
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TRowBg)
                            .clickable { onAppearance(true) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Subtitle sync & position", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tAccent())
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
                item {
                    TRow(
                        TOption(
                            label = if (s.subGenActive) "Stop writing subtitle" else "Make subtitle from voice",
                            sub = "Listen to the video's sound and write the subtitle",
                            badge = null,
                            selected = s.subGenActive,
                            key = -20,
                        ),
                        onClick = { act.genSub() },
                    )
                }
                if (s.hasUserSubs) {
                    item {
                        TRow(
                            TOption(
                                label = "Remove added subtitles",
                                sub = "Delete files added from this device",
                                badge = null,
                                selected = false,
                                key = -21,
                            ),
                            onClick = { act.removeSubs() },
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TRowBg)
                            .clickable(onClick = act.openSubStyle)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Caption style", color = Color.White, fontSize = 15.sp)
                            Text(s.captionSummary, color = TMuted, fontSize = 12.sp, maxLines = 1)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tAccent())
                    }
                }
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
                            .background(if (s.subStyle.bold) tSelBg() else TRowBg)
                            .clickable(onClick = act.subBold)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bold", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (s.subStyle.bold) Icon(Icons.Filled.Check, contentDescription = null, tint = tAccent(), modifier = Modifier.size(18.dp))
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (s.subItalic) tSelBg() else TRowBg)
                            .clickable(onClick = act.subItalicToggle)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Italic", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (s.subItalic) Icon(Icons.Filled.Check, contentDescription = null, tint = tAccent(), modifier = Modifier.size(18.dp))
                    }
                }
                item {
                    TRow(
                        TOption(
                            label = "Background",
                            sub = if (s.subBgArgb == 0) "Transparent" else "Dark",
                            badge = null,
                            selected = false,
                            key = -23,
                        ),
                        onClick = { act.subBgCycle() },
                        chevron = true,
                    )
                }
                item {
                    TRow(
                        TOption(
                            label = "Edge",
                            sub = when (s.subEdge) {
                                2 -> "Shadow"
                                1 -> "Outline"
                                else -> "Plain"
                            },
                            badge = null,
                            selected = false,
                            key = -24,
                        ),
                        onClick = { act.subEdgeCycle() },
                        chevron = true,
                    )
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
                        Text("Reset", color = tAccent(), fontSize = 14.sp, modifier = Modifier.clickable(onClick = act.subSyncReset))
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Position", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TCircleBtn(Icons.Filled.Remove, act.subPosMinus)
                        Text("${s.subPosPct}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        TCircleBtn(Icons.Filled.Add, act.subPosPlus)
                    }
                }
                item {
                    TRow(
                        TOption(
                            label = "Find subtitles automatically",
                            sub = if (s.autoSitesOn) "On — searches subtitle sites when playback starts" else "Off",
                            badge = if (s.autoSitesOn) "On" else "Off",
                            selected = s.autoSitesOn,
                            key = -22,
                        ),
                        onClick = { act.toggleAutoSites() },
                    )
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
                            .background(if (o.selected) tSelBg() else Color.Transparent)
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
                            .background(if (o.selected) tSelBg() else Color.Transparent)
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
        item { TSection("PLAYBACK") }
        item {
            TRow(
                TOption(
                    label = "Captions",
                    sub = if (s.captionsOn) "On" else "Off",
                    badge = if (s.captionsOn) "On" else "Off",
                    selected = s.captionsOn,
                    key = -30,
                ),
                onClick = { act.toggleCaptions() },
            )
        }
        item {
            TRow(
                TOption(
                    label = "Picture in picture",
                    sub = "Pop the video out over other apps",
                    badge = null,
                    selected = false,
                    key = -31,
                ),
                onClick = { act.pip() },
                chevron = true,
            )
        }
        item {
            TRow(
                TOption(
                    label = "Rotate",
                    sub = "Cycle screen orientation",
                    badge = null,
                    selected = false,
                    key = -32,
                ),
                onClick = { act.rotate() },
                chevron = true,
            )
        }
        item {
            TRow(
                TOption(
                    label = "Stats",
                    sub = if (s.statsOn) "On — codec readout on the picture" else "Codec and buffer readout",
                    badge = if (s.statsOn) "On" else "Off",
                    selected = s.statsOn,
                    key = -33,
                ),
                onClick = { act.toggleStats() },
            )
        }
        item {
            TRow(
                TOption(
                    label = "Decoder",
                    sub = "Software / hardware decoder options",
                    badge = null,
                    selected = false,
                    key = -34,
                ),
                onClick = { act.openDecoder() },
                chevron = true,
            )
        }
    }
}
