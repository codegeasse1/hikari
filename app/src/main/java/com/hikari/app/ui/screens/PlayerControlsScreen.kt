package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.player.PlayerControl
import com.hikari.app.player.PlayerControlSlot
import com.hikari.app.player.PlayerControlsConfig
import com.hikari.app.ui.components.GlassCard
import kotlinx.coroutines.launch

/**
 * Settings → Player → Player controls: choose where each player button sits
 * (the reference streaming apps call this a "control layout").
 *
 * The page is deliberately a full screen rather than one more settings card:
 * fifteen controls each need a four-way placement choice, which cannot be
 * expressed as a switch. It is drawn as a schematic of the player overlay —
 * top bar, bottom row (left/right ends) and a Hidden bucket — so the result is
 * visible at a glance instead of being a list of raw setting values.
 *
 * The back button, the centre play/pause cluster and the title/badges are fixed
 * and are not offered here: with them gone there is no way to leave, pause or
 * identify what is playing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerControlsPage(app: HikariApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val jsonFlow = remember { app.store.playerControlsFlow() }
    val json by jsonFlow.collectAsState(initial = "")
    var layout by remember { mutableStateOf(PlayerControlsConfig.defaults()) }
    var moveMenuFor by remember { mutableStateOf<PlayerControl?>(null) }
    // Preview style: the player shows buttons as ICONS (most of them have no
    // text), so the schematic can be read either as the button names or — the
    // truer picture — as the actual glyphs the user will see.
    var iconsPreview by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(json) { layout = PlayerControlsConfig.decode(json) }

    BackHandler { onBack() }

    fun move(control: PlayerControl, slot: PlayerControlSlot) {
        val next = layout.toMutableMap()
        next[control] = slot
        layout = next
        scope.launch {
            runCatching {
                app.store.setPlayerControls(PlayerControlsConfig.encode(next))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("Back to settings"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Player controls"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            tr("Move or hide the player buttons"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            val next = PlayerControlsConfig.defaults()
                            layout = next
                            scope.launch {
                                runCatching {
                                    app.store.setPlayerControls(
                                        PlayerControlsConfig.encode(next)
                                    )
                                }
                            }
                        }
                    ) { Text(tr("Reset")) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Pick where each button lives in the player. Buttons you never " + "use can be hidden completely — the layout below shows the ") +
                        I18n.t("result. The back button, the play/pause circle and the ") +
                        I18n.t("title are always shown."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("Preview"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        PreviewStyleToggle(icons = iconsPreview) { iconsPreview = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    OverlaySketch(layout, icons = iconsPreview)
                }
            }
        }

        for (slot in listOf(
            PlayerControlSlot.TOP_BAR,
            PlayerControlSlot.BOTTOM_LEFT,
            PlayerControlSlot.BOTTOM_RIGHT,
            PlayerControlSlot.HIDDEN,
        )) {
            val controls = PlayerControl.entries.filter { (layout[it] ?: it.defaultSlot) == slot }
            item(key = "hdr_${slot.key}") {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
                    Text(
                        slot.label + " (" + controls.size + ")",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        slot.blurb,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (controls.isEmpty()) {
                item(key = "empty_${slot.key}") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            tr("Nothing here"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                items(controls, key = { it.key }) { control ->
                    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(controlIcon(control)),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    control.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    control.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box {
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        )
                                        .clickable { moveMenuFor = control }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        tr("Move"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        (layout[control] ?: control.defaultSlot).label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                DropdownMenu(
                                    expanded = moveMenuFor == control,
                                    onDismissRequest = { moveMenuFor = null },
                                ) {
                                    PlayerControlSlot.entries.forEach { target ->
                                        DropdownMenuItem(
                                            text = { Text(target.label) },
                                            onClick = {
                                                move(control, target)
                                                moveMenuFor = null
                                            },
                                            leadingIcon = {
                                                if ((layout[control] ?: control.defaultSlot) ==
                                                    target
                                                ) {
                                                    Icon(
                                                        Icons.Filled.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "footer") {
            Text(
                tr("Changes apply the next time the player opens. Anything hidden " + "can be brought back here at any time."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

/**
 * A schematic of the player overlay: the fixed furniture (back arrow, title,
 * play circle) plus the buttons the user has placed, so the layout can be read
 * without leaving the settings page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlaySketch(layout: Map<PlayerControl, PlayerControlSlot>, icons: Boolean) {
    val accent = MaterialTheme.colorScheme.primary

    fun inSlot(slot: PlayerControlSlot): List<PlayerControl> =
        PlayerControl.entries.filter { (layout[it] ?: it.defaultSlot) == slot }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0E1A))
            .padding(10.dp),
    ) {
        // Top bar: back arrow, title, then the user's top-bar buttons.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SketchBox("←", accent, filled = false)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF2A3352))
            )
            Spacer(Modifier.width(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                inSlot(PlayerControlSlot.TOP_BAR).forEach { SketchChip(it.label, accent, if (icons) controlIcon(it) else null) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Centre: the fixed play/pause circle.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Bottom row: progress bar, then left and right button groups.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.35f))
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    inSlot(PlayerControlSlot.BOTTOM_LEFT).forEach { SketchChip(it.label, accent, if (icons) controlIcon(it) else null) }
                }
            }
            Spacer(Modifier.width(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                inSlot(PlayerControlSlot.BOTTOM_RIGHT).forEach { SketchChip(it.label, accent, if (icons) controlIcon(it) else null) }
            }
        }

        val hidden = inSlot(PlayerControlSlot.HIDDEN)
        if (hidden.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Hidden: ") + hidden.joinToString(", ") { it.label },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8A90A8),
            )
        }
    }
}

@Composable
private fun SketchChip(label: String, accent: Color, iconRes: Int? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(
                horizontal = if (iconRes != null) 4.dp else 5.dp,
                vertical = if (iconRes != null) 4.dp else 3.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            Icon(
                painterResource(iconRes),
                contentDescription = label,
                tint = Color(0xFFEDF0F9),
                modifier = Modifier.size(12.dp),
            )
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFEDF0F9),
            )
        }
    }
}

/** The real glyph the player draws for [control], so the preview (and the rows)
 *  match what a user sees in the overlay. */
private fun controlIcon(control: PlayerControl): Int = when (control) {
    PlayerControl.FAVORITE -> R.drawable.ic_heart
    PlayerControl.DOWNLOAD -> R.drawable.ic_download
    PlayerControl.PIP -> R.drawable.ic_pip
    PlayerControl.OPTIONS -> R.drawable.ic_settings
    PlayerControl.LOCK -> R.drawable.ic_lock
    PlayerControl.SPEED -> R.drawable.ic_speed
    PlayerControl.EPISODES -> R.drawable.ic_episodes
    PlayerControl.SOURCES -> R.drawable.ic_server
    PlayerControl.QUALITY -> R.drawable.ic_quality
    PlayerControl.AUDIO -> R.drawable.ic_audio
    PlayerControl.SUBS -> R.drawable.ic_subtitles
    PlayerControl.ROTATE -> R.drawable.ic_rotate
    PlayerControl.SKIP -> R.drawable.ic_skip
    PlayerControl.RESIZE -> R.drawable.ic_resize
    PlayerControl.ENHANCE -> R.drawable.ic_enhance
}

/** Words | Icons switch for the preview card. */
@Composable
private fun PreviewStyleToggle(icons: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Words" to false, "Icons" to true).forEach { (label, wantsIcons) ->
            val selected = icons == wantsIcons
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color.Transparent
                    )
                    .clickable { onChange(wantsIcons) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SketchBox(label: String, accent: Color, filled: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (filled) accent.copy(alpha = 0.35f) else Color(0xFF2A3352)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFEDF0F9),
        )
    }
}
