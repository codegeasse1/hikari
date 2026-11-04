package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.player.PlayerControl
import com.hikari.app.player.PlayerControlSlot
import com.hikari.app.player.PlayerControlsConfig
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.launch

/**
 * Settings → Player → Player controls: choose where each player button sits
 * (the reference streaming apps call this a "control layout").
 *
 * Just the placement lists (top bar, bottom left/right, hidden): the live
 * preview, the look of every button and the gestures live in
 * Customize → Player UI style, so this page no longer duplicates them.
 *
 * The back button, the centre play/pause cluster and the title/badges are fixed
 * and are not offered here: with them gone there is no way to leave, pause or
 * identify what is playing.
 */
@Composable
fun PlayerControlsPage(app: HikariApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val jsonFlow = remember { app.store.playerControlsFlow() }
    val json by jsonFlow.collectAsState(initial = "")
    var layout by remember { mutableStateOf(PlayerControlsConfig.defaults()) }
    var moveMenuFor by remember { mutableStateOf<PlayerControl?>(null) }
    // Page position held across visits (see ScrollKeeper).
    val listState = com.hikari.app.ui.components.keptListState("playerControls")

    // Video decoder choice (AppStore.playerDecoderModeFlow, Best by default).
    val decoderModeFlow = remember { app.store.playerDecoderModeFlow() }
    val decoderMode by decoderModeFlow.collectAsState(initial = "auto")

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
        // Clear of the floating taskbar (0 when there is no bar): this page
        // replaces the Settings list, so it has to keep the last control out
        // from under the bar itself.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            // The same header as every settings folder page — one line, one
            // title size (see SettingsPageHeader) — because this page is opened
            // from one, and Reset has to share that line with the name.
            SettingsPageHeader(
                title = tr("Player controls"),
                subtitle = tr("Move or hide the player buttons"),
                onBack = onBack,
                trailing = {
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
                },
            )
        }

        // Video decoder choice (kept here; gestures moved to Customize → Player UI style).
        item(key = "video") {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        tr("Video"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column {
                        Text(
                            tr("Video decoder"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (!LocalHideHelp.current) {
                        Text(
                            tr("Best uses hardware first and fixes itself when the picture stutters. Hardware only forbids software decoding; Software only prefers it. If a forced choice lags, the player offers to shift back to Best."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                        Spacer(Modifier.height(6.dp))
                        for (mode in listOf("auto", "hw", "sw")) {
                            val label = when (mode) {
                                "hw" -> tr("Hardware only")
                                "sw" -> tr("Software only")
                                else -> tr("Best (auto)")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            runCatching { app.store.setPlayerDecoderMode(mode) }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    if (decoderMode == mode) "✓ " else "○ ",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    }
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
                                    .clip(GlassShape)
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
                                        .clip(GlassShape)
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
                tr("Changes apply the next time the player opens."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

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
    PlayerControl.EXTERNAL -> R.drawable.ic_external
}

