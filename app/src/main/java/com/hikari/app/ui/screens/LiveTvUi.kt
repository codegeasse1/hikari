package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.EpgProgram
import com.hikari.app.iptv.LiveChannel
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvImport
import com.hikari.app.iptv.LiveTvParental
import com.hikari.app.tv.tvTextFieldKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the engine once per process and refreshes stale sources quietly. */
@Composable
fun LiveTvInit() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            runCatching {
                LiveTvImport.importExistingProviders(context.applicationContext as HikariApp)
            }
            runCatching { LiveTvEngine.ensureFresh() }
        }
    }
}

fun liveTvTime(ms: Long, use24h: Boolean): String {
    val fmt = if (use24h) SimpleDateFormat("HH:mm", Locale.getDefault())
    else SimpleDateFormat("h:mm a", Locale.getDefault())
    return runCatching { fmt.format(Date(ms)) }.getOrDefault("--:--")
}

fun liveTvDay(ms: Long): String {
    val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    return runCatching { fmt.format(Date(ms)) }.getOrDefault("")
}

fun liveTvClock(ms: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return runCatching { fmt.format(Date(ms)) }.getOrDefault("")
}

/** One channel tile: logo, name/number, now/next, play + favorite. */
@Composable
fun LiveTvChannelRow(
    channel: LiveChannel,
    now: EpgProgram?,
    next: EpgProgram?,
    use24h: Boolean,
    showLogos: Boolean,
    showNumbers: Boolean,
    isFav: Boolean,
    locked: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onLocked: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable { if (locked) onLocked() else onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogos && !channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showNumbers && channel.number > 0) {
                    Text(
                        channel.number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            val line = when {
                now != null -> "▸ ${now.title}" +
                    (if (next != null) "  ·  ${liveTvTime(next.startMs, use24h)} ${next.title}" else "")
                next != null -> "${liveTvTime(next.startMs, use24h)} ${next.title}"
                else -> channel.group
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { now.progress(System.currentTimeMillis()) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        } else {
            IconButton(onClick = onToggleFav) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (isFav) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Wraps locked content behind the parental PIN dialog. */
@Composable
fun LiveTvPinGate(
    groupName: String,
    onUnlocked: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    content()
    // The caller shows this gate only when locked; tapping it asks the PIN.
    if (askPin) {
        AlertDialog(
            onDismissRequest = { askPin = false; pin = "" },
            title = { Text(tr("Locked: %s").replace("%s", groupName)) },
            text = {
                Column {
                    Text(
                        tr("Enter the parental PIN to open this group until the app closes."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(tr("PIN")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(pin),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LiveTvParental.unlock("__gate__", pin)
                            }
                            if (ok) {
                                askPin = false
                                pin = ""
                                onUnlocked()
                            } else {
                                Toast.makeText(context, tr("Wrong PIN"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text(tr("Unlock")) }
            },
            dismissButton = {
                TextButton(onClick = { askPin = false; pin = "" }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * Asks the PIN for a locked group id, then runs [onOk]. Returns true from
 * [LiveTvLockedTap] handling so callers can gate a tap in one line.
 */
@Composable
fun LiveTvLockedTap(
    groupId: String,
    groupName: String,
    onOk: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    if (askPin) {
        AlertDialog(
            onDismissRequest = { askPin = false; pin = "" },
            title = { Text(tr("Locked: %s").replace("%s", groupName)) },
            text = {
                Column {
                    Text(
                        tr("Enter the parental PIN to open this group until the app closes."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(tr("PIN")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(pin),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LiveTvParental.unlock(groupId, pin)
                            }
                            if (ok) {
                                askPin = false
                                pin = ""
                                onOk()
                            } else {
                                Toast.makeText(context, tr("Wrong PIN"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text(tr("Unlock")) }
            },
            dismissButton = {
                TextButton(onClick = { askPin = false; pin = "" }) { Text(tr("Cancel")) }
            },
        )
    }
    return { askPin = true }
}

/** Small loading row used by every Live TV screen. */
@Composable
fun LiveTvLoading(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The shortcut strip pinned above the IPTV tab's sections: guide, On Demand,
 * recordings, Sports, calendar and the settings gear. Also fires the one-time
 * import of the tab's own playlists into the engine.
 */
@Composable
fun LiveTvShortcutBar(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showXtream by remember { mutableStateOf(false) }
    var showEpg by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            runCatching {
                LiveTvImport.importExistingProviders(context.applicationContext as HikariApp)
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.CalendarMonth,
                label = tr("Guide"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_GUIDE,
                )
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.VideoLibrary,
                label = tr("On Demand"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_ONDEMAND,
                )
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.FiberManualRecord,
                label = tr("Record"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_RECORDINGS,
                )
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.Star,
                label = tr("Sports"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_SPORTS,
                )
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.DateRange,
                label = tr("Calendar"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_CALENDAR,
                )
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.Add,
                label = tr("Xtream"),
            ) { showXtream = true }
        }
        item {
            if (updating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                LiveTvShortcut(
                    icon = androidx.compose.material.icons.Icons.Filled.Refresh,
                    label = tr("Update"),
                ) {
                    updating = true
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching {
                                LiveTvEngine.sources().filter { it.enabled }
                                    .forEach { LiveTvEngine.refreshSource(it.id) }
                                LiveTvEngine.allChannels().size
                            }.getOrDefault(0)
                        }
                        updating = false
                        toast(
                            if (n > 0) tr("%s channels").replace("%s", n.toString())
                            else tr("Nothing to update yet")
                        )
                    }
                }
            }
        }
        item {
            LiveTvShortcut(
                icon = androidx.compose.material.icons.Icons.Filled.Settings,
                label = tr("Settings"),
            ) {
                com.hikari.app.ui.navigation.Routes.safeNavigate(
                    nav,
                    com.hikari.app.ui.navigation.Routes.LIVETV_SETTINGS,
                )
            }
        }
    }

    if (showXtream) {
        AddXtreamDialog(onDone = { showXtream = false }, onDismiss = { showXtream = false })
    }
    if (showEpg) {
        AddEpgDialog(onDone = { showEpg = false }, onDismiss = { showEpg = false })
    }
}

@Composable
private fun LiveTvShortcut(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
