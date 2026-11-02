package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.player.ElementStyle
import com.hikari.app.player.PlayerButtonShape
import com.hikari.app.player.PlayerControl
import com.hikari.app.player.PlayerControlSlot
import com.hikari.app.player.PlayerControlsConfig
import com.hikari.app.player.PlayerElementInfo
import com.hikari.app.player.PlayerElements
import com.hikari.app.player.PlayerUiPresets
import com.hikari.app.player.PlayerUiStyle
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings → Customize → Player UI style.
 *
 * One page to restyle everything in the player: every button, the title, the
 * time texts and the seek bar. Each element opens its own editor with icon /
 * button / text size, shape (circle, rounded square, square), colours, opacity,
 * a nudge in x and y, and — for the sixteen movable controls — where it sits
 * (top bar, bottom left, bottom right or hidden). The first entry, "All
 * buttons", sets a look for every button at once; an element's own values win
 * over it.
 *
 * Like the customise screens of a game HUD, a live preview stays pinned at the
 * top and follows every change; tapping a button IN the preview jumps to its
 * editor. "Quick looks" are one-tap starting points.
 *
 * The values are saved as one JSON string (see [PlayerUiStyle]) and read by
 * PlayerActivity when the player opens.
 */

private const val GAP_OFF = -1f

/** Cards in the list BEFORE the element editors (presets, gap, hint). */
private const val CARDS_BEFORE_ELEMENTS = 3

private fun gapToSlider(v: Int): Float = if (v < 0) GAP_OFF else v.toFloat()

private fun sliderToGap(f: Float): Int {
    val i = f.roundToInt()
    return if (i < PlayerUiStyle.GAP_MIN) -1 else i.coerceAtMost(PlayerUiStyle.GAP_MAX)
}

/** Icon / text colours offered (opaque ARGB). */
private val ICON_COLORS: List<Long> = listOf(
    0xFFFFFFFF, 0xFFFFD600, 0xFFFF9800, 0xFFF44336, 0xFFE91E63,
    0xFF9C27B0, 0xFF2196F3, 0xFF00E5FF, 0xFF4CAF50, 0xFF000000,
)

/** Button fill colours offered (translucent, so the picture shows through). */
private val BUTTON_COLORS: List<Long> = listOf(
    0xB3000000, 0xB3FFFFFF, 0xB3FFD600, 0xB3FF9800, 0xB3F44336,
    0xB3E91E63, 0xB39C27B0, 0xB32196F3, 0xB300BCD4, 0xB34CAF50,
)

private fun shapeFor(shape: PlayerButtonShape): Shape = when (shape) {
    PlayerButtonShape.DEFAULT -> RoundedCornerShape(50)
    PlayerButtonShape.CIRCLE -> CircleShape
    PlayerButtonShape.ROUNDED -> RoundedCornerShape(12.dp)
    PlayerButtonShape.SQUARE -> RoundedCornerShape(3.dp)
}

@Composable
fun PlayerStylePage(app: HikariApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    val styleFlow = remember { app.store.playerUiStyleFlow() }
    val json by styleFlow.collectAsState(initial = "")
    var style by remember { mutableStateOf(PlayerUiStyle()) }
    var gapSlider by remember { mutableStateOf(GAP_OFF) }
    LaunchedEffect(json) {
        val s = PlayerUiStyle.decode(json)
        style = s
        gapSlider = gapToSlider(s.gapDp)
    }

    // Where each movable control sits (the same setting the layout editor uses).
    val slotFlow = remember { app.store.playerControlsFlow() }
    val slotJson by slotFlow.collectAsState(initial = "")
    var layout by remember { mutableStateOf(PlayerControlsConfig.defaults()) }
    LaunchedEffect(slotJson) { layout = PlayerControlsConfig.decode(slotJson) }

    var openKey by remember { mutableStateOf<String?>(null) }

    // The preview stays pinned above the list when the screen is tall enough;
    // on a short (landscape) screen it scrolls with the list instead.
    val tall = LocalConfiguration.current.screenHeightDp >= 600
    var pinPreview by remember { mutableStateOf(tall) }
    val listState = rememberLazyListState()

    BackHandler { onBack() }

    fun save(next: PlayerUiStyle) {
        style = next
        scope.launch {
            runCatching { app.store.setPlayerUiStyle(PlayerUiStyle.encode(next)) }
        }
    }

    fun saveSlot(control: PlayerControl, slot: PlayerControlSlot) {
        val next = layout.toMutableMap()
        next[control] = slot
        layout = next
        scope.launch {
            runCatching { app.store.setPlayerControls(PlayerControlsConfig.encode(next)) }
        }
    }

    // Tapping a button in the preview opens its editor and scrolls to it.
    fun pick(key: String) {
        openKey = key
        val idx = PlayerElements.list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val start = CARDS_BEFORE_ELEMENTS + (if (pinPreview) 0 else 1)
            scope.launch { listState.animateScrollToItem(start + idx) }
        }
    }

    val shownStyle = style.copy(gapDp = sliderToGap(gapSlider))

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            SettingsPageHeader(
                title = tr("Player UI style"),
                subtitle = tr("Move, resize and recolour every player button"),
                onBack = onBack,
                trailing = {
                    TextButton(
                        onClick = {
                            gapSlider = GAP_OFF
                            save(PlayerUiStyle())
                        }
                    ) { Text(tr("Reset all")) }
                },
            )
            if (pinPreview) {
                PreviewCard(
                    style = shownStyle,
                    selected = openKey,
                    pinned = true,
                    onTogglePin = { pinPreview = false },
                    onPick = { pick(it) },
                    mockHeight = 190.dp,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = LocalTaskbarInset.current + 16.dp,
            ),
        ) {
            if (!pinPreview) {
                item(key = "preview") {
                    PreviewCard(
                        style = shownStyle,
                        selected = openKey,
                        pinned = false,
                        onTogglePin = { pinPreview = true },
                        onPick = { pick(it) },
                        mockHeight = 270.dp,
                    )
                }
            }

            item(key = "presets") {
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            tr("Quick looks"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (p in PlayerUiPresets.list) {
                                PresetChip(
                                    label = tr(p.label),
                                    selected = style == p.style,
                                    onClick = {
                                        gapSlider = gapToSlider(p.style.gapDp)
                                        save(p.style)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "gap") {
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        StyleSlider(
                            label = tr("Gap between buttons"),
                            value = gapSlider,
                            valueText = if (sliderToGap(gapSlider) < 0) tr("Default")
                            else "${sliderToGap(gapSlider)} dp",
                            valueRange = GAP_OFF..PlayerUiStyle.GAP_MAX.toFloat(),
                            steps = PlayerUiStyle.GAP_MAX,
                            onValueChange = { gapSlider = it },
                            onValueChangeFinished = {
                                val v = sliderToGap(gapSlider)
                                gapSlider = gapToSlider(v)
                                save(style.copy(gapDp = v))
                            },
                        )
                    }
                }
            }

            item(key = "hint") {
                Text(
                    tr("Tap a button in the preview, or an element below, to edit it. \"All buttons\" changes every button at once; an element's own settings win over it. Changes apply the next time the player opens."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
                )
            }

            items(PlayerElements.list, key = { it.key }) { info ->
                ElementCard(
                    info = info,
                    st = style.get(info.key),
                    expanded = openKey == info.key,
                    slot = info.control?.let { layout[it] ?: it.defaultSlot },
                    onToggle = { openKey = if (openKey == info.key) null else info.key },
                    onChange = { next -> save(style.withElement(info.key, next)) },
                    onSlot = { slot -> info.control?.let { saveSlot(it, slot) } },
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    style: PlayerUiStyle,
    selected: String?,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onPick: (String) -> Unit,
    mockHeight: Dp,
) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Preview"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onTogglePin) {
                    Text(if (pinned) tr("Unpin") else tr("Pin to top"))
                }
            }
            Box(Modifier.padding(end = 8.dp)) {
                OverlayMock(style, MockCtx(selected, onPick), mockHeight)
            }
        }
    }
}

@Composable
private fun ElementCard(
    info: PlayerElementInfo,
    st: ElementStyle,
    expanded: Boolean,
    slot: PlayerControlSlot?,
    onToggle: () -> Unit,
    onChange: (ElementStyle) -> Unit,
    onSlot: (PlayerControlSlot) -> Unit,
) {
    val kind = info.kind
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(info.label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!st.isDefault) {
                    Text(
                        tr("Edited"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    // ---- Size ----
                    if (PlayerElements.hasSize(kind)) {
                        val min = PlayerElements.sizeMin(kind)
                        val max = PlayerElements.sizeMax(kind)
                        val off = (min - 1).toFloat()
                        var sizeSlider by remember(info.key, st.sizeDp) {
                            mutableStateOf(if (st.sizeDp <= 0) off else st.sizeDp.toFloat())
                        }
                        val shown = sizeSlider.roundToInt()
                        StyleSlider(
                            label = tr(PlayerElements.sizeLabel(kind)),
                            value = sizeSlider,
                            valueText = if (shown < min) tr("Default") else "$shown",
                            valueRange = off..max.toFloat(),
                            steps = max - min,
                            onValueChange = { sizeSlider = it },
                            onValueChangeFinished = {
                                val v = sizeSlider.roundToInt()
                                val size = if (v < min) 0 else v.coerceAtMost(max)
                                sizeSlider = if (size == 0) off else size.toFloat()
                                onChange(st.copy(sizeDp = size))
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // ---- Shape ----
                    if (PlayerElements.hasShape(kind)) {
                        Text(tr("Shape"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (shape in PlayerButtonShape.entries) {
                                ShapeChoice(
                                    shape = shape,
                                    selected = st.shape == shape,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onChange(st.copy(shape = shape)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- Colours ----
                    Text(tr(PlayerElements.colorLabel(kind)), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    SwatchRow(
                        colors = ICON_COLORS,
                        selected = st.color,
                        onPick = { onChange(st.copy(color = it)) },
                    )
                    if (PlayerElements.hasShape(kind)) {
                        Spacer(Modifier.height(12.dp))
                        Text(tr("Button colour"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        SwatchRow(
                            colors = BUTTON_COLORS,
                            selected = st.bgColor,
                            onPick = { onChange(st.copy(bgColor = it)) },
                        )
                    }

                    // ---- Opacity ----
                    Spacer(Modifier.height(12.dp))
                    val aMin = PlayerElements.ALPHA_MIN
                    val aMax = PlayerElements.ALPHA_MAX
                    val aOff = (aMin - 1).toFloat()
                    var alphaSlider by remember(info.key, st.alphaPct) {
                        mutableStateOf(if (st.alphaPct <= 0) aOff else st.alphaPct.toFloat())
                    }
                    val aShown = alphaSlider.roundToInt()
                    StyleSlider(
                        label = tr("Opacity"),
                        value = alphaSlider,
                        valueText = if (aShown < aMin) tr("Default") else "$aShown%",
                        valueRange = aOff..aMax.toFloat(),
                        steps = aMax - aMin,
                        onValueChange = { alphaSlider = it },
                        onValueChangeFinished = {
                            val v = alphaSlider.roundToInt()
                            val pct = if (v < aMin) 0 else v.coerceAtMost(aMax)
                            alphaSlider = if (pct == 0) aOff else pct.toFloat()
                            onChange(st.copy(alphaPct = pct))
                        },
                    )

                    // ---- Position ----
                    if (slot != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(tr("Where it sits"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (option in PlayerControlSlot.entries) {
                                SlotChoice(
                                    label = option.label,
                                    selected = slot == option,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSlot(option) },
                                )
                            }
                        }
                    }
                    if (info.key != PlayerElements.ALL) {
                        Spacer(Modifier.height(12.dp))
                        var xSlider by remember(info.key, st.dx) { mutableStateOf(st.dx.toFloat()) }
                        var ySlider by remember(info.key, st.dy) { mutableStateOf(st.dy.toFloat()) }
                        val nudgeMax = PlayerElements.NUDGE_MAX
                        StyleSlider(
                            label = tr("Move left / right"),
                            value = xSlider,
                            valueText = "${xSlider.roundToInt()} dp",
                            valueRange = -nudgeMax.toFloat()..nudgeMax.toFloat(),
                            steps = nudgeMax * 2 - 1,
                            onValueChange = { xSlider = it },
                            onValueChangeFinished = {
                                val v = xSlider.roundToInt().coerceIn(-nudgeMax, nudgeMax)
                                xSlider = v.toFloat()
                                onChange(st.copy(dx = v))
                            },
                        )
                        StyleSlider(
                            label = tr("Move up / down"),
                            value = ySlider,
                            valueText = "${ySlider.roundToInt()} dp",
                            valueRange = -nudgeMax.toFloat()..nudgeMax.toFloat(),
                            steps = nudgeMax * 2 - 1,
                            onValueChange = { ySlider = it },
                            onValueChangeFinished = {
                                val v = ySlider.roundToInt().coerceIn(-nudgeMax, nudgeMax)
                                ySlider = v.toFloat()
                                onChange(st.copy(dy = v))
                            },
                        )
                    }

                    if (!st.isDefault) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { onChange(ElementStyle()) }) {
                            Text(tr("Reset this element"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ShapeChoice(
    shape: PlayerButtonShape,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val edge = if (selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, edge), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(shapeFor(shape))
                .background(if (selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr(
                when (shape) {
                    PlayerButtonShape.DEFAULT -> "Default"
                    PlayerButtonShape.CIRCLE -> "Circle"
                    PlayerButtonShape.ROUNDED -> "Rounded"
                    PlayerButtonShape.SQUARE -> "Square"
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlotChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val edge = if (selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, edge), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tr(label),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val edge = if (selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, edge), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwatchRow(
    colors: List<Long>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Auto": nothing customised.
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .border(
                    BorderStroke(if (selected == 0) 2.dp else 1.dp, if (selected == 0) primary else faint),
                    CircleShape,
                )
                .clickable { onPick(0) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr("Auto"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (c in colors) {
            val argb = c.toInt()
            val isSel = selected == argb
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        BorderStroke(if (isSel) 3.dp else 1.dp, if (isSel) primary else Color(0x55FFFFFF)),
                        CircleShape,
                    )
                    .clickable { onPick(argb) }
            )
        }
    }
}

// ---- Preview ---------------------------------------------------------------

/** Which element is open in the editor, and what a tap in the preview does. */
private class MockCtx(
    val selected: String?,
    val onPick: (String) -> Unit,
)

private fun tintOf(st: ElementStyle): Color =
    if (st.color != 0) Color(st.color) else Color.White

private fun fillOf(st: ElementStyle, default: Color = Color(0x66000000)): Color =
    if (st.bgColor != 0) Color(st.bgColor) else default

private fun alphaOf(st: ElementStyle): Float =
    if (st.alphaPct > 0) st.alphaPct / 100f else 1f

/** Tap target + a ring around the element that is open in the editor. */
@Composable
private fun pickMod(ctx: MockCtx, key: String, shape: Shape): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    val ring = if (ctx.selected == key) Modifier.border(2.dp, primary, shape) else Modifier
    return ring.clickable { ctx.onPick(key) }
}

/** A mock of the player overlay that follows [style] as it changes. */
@Composable
private fun OverlayMock(style: PlayerUiStyle, ctx: MockCtx, height: Dp) {
    val gap = if (style.gapDp >= 0) style.gapDp else 4

    fun pill(c: PlayerControl): ElementStyle = style.resolved(PlayerElements.pillKey(c))

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B2335), Color(0xFF070A14)),
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: back, title, action buttons.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MockIconButton(
                    PlayerElements.BACK, Icons.AutoMirrored.Filled.ArrowBack,
                    style.resolved(PlayerElements.BACK), 30, ctx,
                )
                Spacer(Modifier.width(8.dp))
                MockText(
                    PlayerElements.TITLE,
                    "Mirzapur: The Movie",
                    style.get(PlayerElements.TITLE),
                    13,
                    ctx,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                    MockPill(PlayerElements.pillKey(PlayerControl.FAVORITE), Icons.Filled.Favorite, pill(PlayerControl.FAVORITE), ctx)
                    MockPill(PlayerElements.pillKey(PlayerControl.DOWNLOAD), Icons.Filled.Download, pill(PlayerControl.DOWNLOAD), ctx)
                    MockPill(PlayerElements.pillKey(PlayerControl.OPTIONS), Icons.Filled.Settings, pill(PlayerControl.OPTIONS), ctx)
                    MockPill(PlayerElements.pillKey(PlayerControl.LOCK), Icons.Filled.Lock, pill(PlayerControl.LOCK), ctx)
                }
            }

            // Centre: previous, rewind, play, forward, next.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MockIconButton(
                    PlayerElements.PREV, Icons.Filled.SkipPrevious,
                    style.resolved(PlayerElements.PREV), 30, ctx,
                )
                Spacer(Modifier.width(6.dp))
                MockSeekButton(PlayerElements.REWIND, "10", style.resolved(PlayerElements.REWIND), ctx)
                Spacer(Modifier.width(6.dp))
                MockIconButton(
                    PlayerElements.PLAY, Icons.Filled.PlayArrow,
                    style.resolved(PlayerElements.PLAY), 52, ctx,
                    accentFill = true,
                )
                Spacer(Modifier.width(6.dp))
                MockSeekButton(PlayerElements.FORWARD, "10", style.resolved(PlayerElements.FORWARD), ctx)
                Spacer(Modifier.width(6.dp))
                MockIconButton(
                    PlayerElements.NEXT, Icons.Filled.SkipNext,
                    style.resolved(PlayerElements.NEXT), 30, ctx,
                )
            }

            // Bottom: times + seek bar, then the pills.
            Column {
                val bar = style.get(PlayerElements.PROGRESS)
                val played = if (bar.color != 0) Color(bar.color) else MaterialTheme.colorScheme.primary
                val barShape = RoundedCornerShape(2.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MockText(PlayerElements.POSITION, "00:14", style.get(PlayerElements.POSITION), 11, ctx)
                    // A taller, invisible touch area around the thin bar.
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .offset(bar.dx.dp, bar.dy.dp)
                            .alpha(alphaOf(bar))
                            .height(20.dp)
                            .then(pickMod(ctx, PlayerElements.PROGRESS, RoundedCornerShape(6.dp))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(barShape)
                                .background(Color(0x55FFFFFF))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.3f)
                                    .height(4.dp)
                                    .background(played)
                            )
                        }
                    }
                    MockText(PlayerElements.DURATION, "1:20:00", style.get(PlayerElements.DURATION), 11, ctx)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MockPill(PlayerElements.pillKey(PlayerControl.SPEED), Icons.Filled.Speed, pill(PlayerControl.SPEED), ctx)
                    MockPill(PlayerElements.pillKey(PlayerControl.EPISODES), Icons.Filled.VideoLibrary, pill(PlayerControl.EPISODES), ctx)
                    MockPill(PlayerElements.pillKey(PlayerControl.SUBS), Icons.Filled.Subtitles, pill(PlayerControl.SUBS), ctx)
                }
            }
        }
    }
}

@Composable
private fun MockText(
    key: String,
    text: String,
    st: ElementStyle,
    defaultDp: Int,
    ctx: MockCtx,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    // The player sizes its text in dp (not sp), so the preview does too.
    val density = LocalDensity.current
    val size = with(density) { (if (st.sizeDp > 0) st.sizeDp else defaultDp).dp.toSp() }
    Text(
        text,
        color = tintOf(st),
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .offset(st.dx.dp, st.dy.dp)
            .alpha(alphaOf(st))
            .then(pickMod(ctx, key, RoundedCornerShape(4.dp))),
    )
}

@Composable
private fun MockPill(key: String, icon: ImageVector, st: ElementStyle, ctx: MockCtx) {
    val iconDp = if (st.sizeDp > 0) st.sizeDp else 24
    val shape = shapeFor(st.shape)
    Box(
        Modifier
            .offset(st.dx.dp, st.dy.dp)
            .alpha(alphaOf(st))
            .clip(shape)
            .background(fillOf(st))
            .then(pickMod(ctx, key, shape))
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tintOf(st),
            modifier = Modifier.size(iconDp.dp),
        )
    }
}

@Composable
private fun MockIconButton(
    key: String,
    icon: ImageVector,
    st: ElementStyle,
    defaultDp: Int,
    ctx: MockCtx,
    accentFill: Boolean = false,
) {
    val side = if (st.sizeDp > 0) st.sizeDp else defaultDp
    val shape = shapeFor(st.shape)
    val defaultFill = if (accentFill) MaterialTheme.colorScheme.primary else Color(0x66000000)
    Box(
        Modifier
            .offset(st.dx.dp, st.dy.dp)
            .alpha(alphaOf(st))
            .size(side.dp)
            .clip(shape)
            .background(fillOf(st, defaultFill))
            .then(pickMod(ctx, key, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tintOf(st),
            modifier = Modifier.size((side * 0.55f).dp),
        )
    }
}

@Composable
private fun MockSeekButton(key: String, label: String, st: ElementStyle, ctx: MockCtx) {
    val side = if (st.sizeDp > 0) st.sizeDp else 34
    val shape = shapeFor(st.shape)
    val density = LocalDensity.current
    val textSize = with(density) { (side * 0.35f).dp.toSp() }
    Box(
        Modifier
            .offset(st.dx.dp, st.dy.dp)
            .alpha(alphaOf(st))
            .size(side.dp)
            .clip(shape)
            .background(fillOf(st))
            .then(pickMod(ctx, key, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = tintOf(st),
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
        )
    }
}
