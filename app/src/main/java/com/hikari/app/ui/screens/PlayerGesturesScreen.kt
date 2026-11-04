package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.tv.tvToggle
import kotlinx.coroutines.launch

/**
 * Settings → Customize → Player gestures & decoder.
 *
 * The player behaviours that are not buttons: the surface gestures
 * (brightness/volume swipes, slide-to-seek, pinch-to-zoom) and the video
 * decoder choice. Moved here so Settings → Player holds only playback cards
 * and Customize holds everything about the player experience.
 */
@Composable
fun PlayerGesturesPage(app: HikariApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipesFlow = remember { app.store.playerSwipesFlow() }
    val swipesOn by swipesFlow.collectAsState(initial = true)
    val slideSeekFlow = remember { app.store.playerSlideSeekFlow() }
    val slideSeekOn by slideSeekFlow.collectAsState(initial = true)
    val pinchZoomFlow = remember { app.store.playerPinchZoomFlow() }
    val pinchZoomOn by pinchZoomFlow.collectAsState(initial = false)
    val decoderModeFlow = remember { app.store.playerDecoderModeFlow() }
    val decoderMode by decoderModeFlow.collectAsState(initial = "auto")
    // Page position held across visits (see ScrollKeeper).
    val listState = com.hikari.app.ui.components.keptListState("playerGestures")

    BackHandler { onBack() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("Player gestures & decoder"),
                subtitle = tr("Swipes, zoom and the video decoder"),
                onBack = onBack,
            )
        }

        item(key = "gestures") {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        tr("Gestures"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    GestureSwitchRow(
                        title = tr("Brightness & volume swipes"),
                        desc = tr("Drag up or down on the video: the left half changes the brightness, the right half the volume. Off means the surface only plays."),
                        checked = swipesOn,
                        onChange = { on ->
                            scope.launch {
                                runCatching { app.store.setPlayerSwipes(on) }
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    GestureSwitchRow(
                        title = tr("Slide to seek"),
                        desc = tr("Drag left or right on the video to scrub the timeline, with the landing time previewed. Off means sideways drags do nothing."),
                        checked = slideSeekOn,
                        onChange = { on ->
                            scope.launch {
                                runCatching { app.store.setPlayerSlideSeek(on) }
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    GestureSwitchRow(
                        title = tr("Pinch to zoom"),
                        desc = tr("Pinch with two fingers on the video to zoom the picture, up to 4x. Off means pinches do nothing. The zoom resets when the server changes."),
                        checked = pinchZoomOn,
                        onChange = { on ->
                            scope.launch {
                                runCatching { app.store.setPlayerPinchZoom(on) }
                            }
                        },
                    )
                }
            }
        }

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

@Composable
private fun GestureSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!LocalHideHelp.current) {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            // See [Modifier.tvToggle].
            modifier = Modifier.tvToggle(checked) { onChange(it) },
        )
    }
}
