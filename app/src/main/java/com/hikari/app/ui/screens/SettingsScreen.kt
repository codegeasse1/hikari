package com.hikari.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.data.Userscript
import com.hikari.app.download.DownloadService
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadsRepository
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.NetTuning
import com.hikari.app.net.Updater
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.web.UserscriptManager
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

private enum class SettingsFolder { ADBLOCKING, WEBVIEW }

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    var themeKey by remember { mutableStateOf(HikariThemeMode.DARK.key) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SettingsFolder?>(null) }

    val currentTheme = remember(themeKey) { HikariThemeMode.fromKey(themeKey) }
    val hideContinueFlow = remember { app.store.hideContinueFlow() }
    val hideContinue by hideContinueFlow.collectAsState(initial = false)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        when (openFolder) {
            SettingsFolder.ADBLOCKING -> {
                item {
                    FolderHeader(
                        title = "Ad Blocking",
                        subtitle = "Ads, trackers & blocklists",
                        onBack = { openFolder = null }
                    )
                }
                item {
                    AdBlockingCard(app)
                }
            }
            SettingsFolder.WEBVIEW -> {
                item {
                    FolderHeader(
                        title = "WebView",
                        subtitle = "Safety, redirects & user agent",
                        onBack = { openFolder = null }
                    )
                }
                item {
                    WebViewSafetyCard(app)
                }
                item {
                    WebViewUserAgentCard(app)
                }
            }
            null -> {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("Version") },
                        supportingContent = { Text(BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")") }
                    )
                    SettingsDivider()
                    Box {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    if (currentTheme == HikariThemeMode.LIGHT) Icons.Filled.LightMode
                                    else Icons.Filled.DarkMode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("Theme") },
                            supportingContent = { Text(currentTheme.label) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { themeMenuOpen = true }
                        )
                        DropdownMenu(
                            expanded = themeMenuOpen,
                            onDismissRequest = { themeMenuOpen = false }
                        ) {
                            HikariThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        themeKey = mode.key
                                        themeMenuOpen = false
                                        scope.launch { app.store.setTheme(mode.key) }
                                    },
                                    leadingIcon = {
                                        if (themeKey == mode.key) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("Check for updates") },
                        supportingContent = {
                            if (checkingUpdates) {
                                Text("Checking GitHub…")
                            } else {
                                Text("Version " + Updater.currentVersion())
                            }
                        },
                        trailingContent = {
                            if (checkingUpdates) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            if (!checkingUpdates) {
                                checkingUpdates = true
                                scope.launch {
                                    updateStatus = runCatching { Updater.checkForUpdate() }.getOrNull()
                                    checkingUpdates = false
                                    showUpdateDialog = true
                                }
                            }
                        }
                    )
                    SettingsDivider()
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("GitHub") },
                        supportingContent = { Text("github.com/codegeasse1/hikari — releases & source") },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/codegeasse1/hikari")
                                )
                            )
                        }
                    )
                    SettingsDivider()
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("Telegram") },
                        supportingContent = { Text("t.me/CodegeasseHikari — help, bugs & feature requests") },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://t.me/CodegeasseHikari")
                                )
                            )
                        }
                    )
                }
            }
        }

        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                UiScaleCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                SlowConnectionCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                PlaybackStartCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                LoadingBannerCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                UniversalExtractionCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Continue Watching",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Show the Continue Watching shelf on Home. It collects " +
                                    "progress from every extension you've watched, so an " +
                                    "episode started on one extension still shows up after " +
                                    "you switch to another.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = !hideContinue,
                            onCheckedChange = { show ->
                                scope.launch { app.store.setHideContinue(!show) }
                            }
                        )
                    }
                }
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                DownloadSettingsCard(app)
            }
        }
        item {
            SettingsFolderRow(
                icon = Icons.Filled.Block,
                title = "Ad Blocking",
                subtitle = "Ads, trackers & blocklists",
                onClick = { openFolder = SettingsFolder.ADBLOCKING }
            )
        }
        item {
            SettingsFolderRow(
                icon = Icons.Filled.Public,
                title = "WebView",
                subtitle = "Redirect block, popups, user agent",
                onClick = { openFolder = SettingsFolder.WEBVIEW }
            )
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                UserscriptsCard(app)
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Roadmap",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "✓ Stremio addons\n" +
                            "✓ Universal scrapers\n" +
                            "✓ HLS/DASH player with headers + subtitles\n" +
                            "✓ CloudStream .cs3 plugin loader\n" +
                            "✓ Torrent engine for infoHash streams\n" +
                            "✓ Watch history + Continue Watching (all extensions)\n" +
                            "✓ Downloads — offline copies, export to phone storage, concurrent limit\n" +
                            "• SkyStream extensions, scriptable scrapers (planned)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "About",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Hikari (光) — a universal streaming app built from scratch. " +
                            "One player, every extension ecosystem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            TextButton(
                onClick = {
                    scope.launch { app.store.clearAll() }
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Clear all data", color = MaterialTheme.colorScheme.error)
            }
        }
            }
        }
    }

    if (showUpdateDialog) {
        UpdateDialog(
            context = context,
            onDismiss = { showUpdateDialog = false },
            initialStatus = updateStatus,
        )
    }
}

@Composable
private fun FolderHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsFolderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadSettingsCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by app.store.downloadConcurrencyFlow().collectAsState(initial = 3)
    var value by remember { mutableStateOf(stored.toFloat()) }

    LaunchedEffect(stored) {
        value = stored.toFloat()
    }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Simultaneous downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "How many videos may save at the same time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                value.roundToInt().toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                val n = value.roundToInt().coerceIn(1, 10)
                value = n.toFloat()
                scope.launch {
                    app.store.setDownloadConcurrency(n)
                    DownloadsRepository.setMaxConcurrent(n)
                    // Wake the queue so raising the limit immediately starts
                    // the extra downloads, instead of waiting for the next
                    // enqueue/resume to restart the service.
                    if (DownloadsRepository.snapshot().any { it.status == DownloadStatus.QUEUED }) {
                        DownloadService.start(context)
                    }
                }
            },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Slide to 3 to run three downloads at once, 4 for four, and so on (max 10). " +
                "Videos beyond the limit stay queued and start automatically as slots free up.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UiScaleCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val enabledFlow = remember { app.store.uiScaleEnabledFlow() }
    val enabled by enabledFlow.collectAsState(initial = false)
    val scaleFlow = remember { app.store.uiScaleFlow() }
    val scale by scaleFlow.collectAsState(initial = 1f)
    var slider by remember { mutableStateOf(scale) }

    LaunchedEffect(scale) { slider = scale }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "In-app UI scale",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Force one interface size on every phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    scope.launch { runCatching { app.store.setUiScaleEnabled(on) } }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Turning OFF applies your phone's Font size and Display size settings " +
                "to the app. Turning ON ignores those two phone settings and follows " +
                "the in-app UI scale size below instead, so the app looks the same on " +
                "every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "UI scale size",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    (slider * 100).roundToInt().toString() + "%",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = {
                    val pct = (slider * 100).roundToInt().coerceIn(70, 130)
                    slider = pct / 100f
                    scope.launch { runCatching { app.store.setUiScale(pct) } }
                },
                valueRange = 0.7f..1.3f,
                steps = 5,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Smaller",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Default",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Bigger",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SlowConnectionCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var tipEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.slowConnection()
        tipEnabled = app.store.slowTipEnabled()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Mobile data / slow internet",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Gives every source search much more time and retries extensions " +
                "that time out, so a weak connection doesn't end in " +
                "\"No playable sources found\". Only turn it on if you need it — " +
                "fast connections stay quick with it off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Slow connection mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Enable this if your internet is slow.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    NetTuning.setSlowConnection(it)
                    scope.launch { runCatching { app.store.setSlowConnection(it) } }
                }
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Slow internet suggestion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "When a video looks slow to start, the player offers to switch " +
                        "Slow connection mode on. Turn this off if it keeps guessing " +
                        "wrong on a connection that is actually fine.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = tipEnabled,
                onCheckedChange = {
                    tipEnabled = it
                    scope.launch { runCatching { app.store.setSlowTipEnabled(it) } }
                }
            )
        }
    }
}

@Composable
private fun PlaybackStartCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var waitServers by remember { mutableStateOf(false) }
    var minServers by remember { mutableStateOf(2f) }
    var askServer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        waitServers = app.store.playWaitServers()
        minServers = app.store.playMinServers().toFloat()
        askServer = app.store.askServerOnPlay()
    }

    fun persist(wait: Boolean) {
        waitServers = wait
        scope.launch { runCatching { app.store.setPlayWaitServers(wait) } }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Playback start",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose when the player starts after you tap Play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !waitServers, onClick = { persist(false) })
            Column(Modifier.weight(1f)) {
                Text(
                    "Play as soon as the first server is found",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Instant playback — the fastest option. If that server turns out " +
                        "to be dead, the player moves to the next one automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = waitServers, onClick = { persist(true) })
            Column(Modifier.weight(1f)) {
                Text(
                    "Wait for more servers first",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Playback starts once the number chosen below has been found — or " +
                        "when every installed extension has finished searching, " +
                        "whichever happens first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (waitServers) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Servers to wait for",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    minServers.roundToInt().toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = minServers,
                onValueChange = { minServers = it },
                onValueChangeFinished = {
                    val n = minServers.roundToInt().coerceIn(1, 5)
                    minServers = n.toFloat()
                    scope.launch { runCatching { app.store.setPlayMinServers(n) } }
                },
                valueRange = 1f..5f,
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Slide to 3 to start once three servers are ready. If the whole " +
                    "search finds fewer than that (say only 2), playback starts with " +
                    "everything that was found the moment every extension has " +
                    "finished — it never waits forever for a server that doesn't exist.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Don't play directly — show all servers to choose",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (askServer) {
                        "On — tapping Play stops at the server list instead of " +
                            "starting a server by itself. Every server found is " +
                            "divided into sections by the engine it came from " +
                            "(CloudStream, Hikari, Nuvio, Stremio) so you can pick " +
                            "one deliberately."
                    } else {
                        "Off — the player starts on the first server it finds and " +
                            "only moves to another one if that server turns out to " +
                            "be dead."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = askServer,
                onCheckedChange = {
                    askServer = it
                    scope.launch { runCatching { app.store.setAskServerOnPlay(it) } }
                }
            )
        }
    }
}

@Composable
private fun LoadingBannerCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.showLoadingBanner()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Loading screen",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "What covers the player while it finds a server and buffers the first " +
                "frame of video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Show banner until servers load",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (enabled) {
                        "On — the title's artwork and name (breathing in and out) stay " +
                            "on screen until the first frame of video is ready. Tapping " +
                            "the banner does nothing."
                    } else {
                        "Off — the player opens straight away with just a round loading " +
                            "icon, no artwork or name."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { runCatching { app.store.setShowLoadingBanner(it) } }
                }
            )
        }
    }
}

@Composable
private fun UniversalExtractionCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.ytdlpEnabled()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Universal extraction (yt-dlp)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "When a provider's own extractors find no playable source, the " +
                "built-in yt-dlp engine takes over and tries to pull a direct " +
                "stream from the page. Adds ~60 MB to the APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Fall back to yt-dlp when no sources found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Only kicks in on pages the built-in extractors can't resolve.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { runCatching { app.store.setYtdlpEnabled(it) } }
                }
            )
        }
    }
}

@Composable
private fun WebViewSafetyCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var redirectProtection by remember { mutableStateOf(true) }
    var popupProtection by remember { mutableStateOf(true) }
    var allowedRedirects by remember { mutableStateOf(listOf<String>()) }
    var newAllowedDomain by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        redirectProtection = app.store.webviewRedirect()
        popupProtection = app.store.webviewPopup()
        allowedRedirects = app.store.webviewRedirectAllow()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "WebView safety",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Stops sites from redirecting or popping you out to ad pages. " +
                "Only pages/popups that belong to the site itself are allowed. " +
                "Turn off if a site's player opens in another tab on a different domain.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Block redirects to other sites",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Same-site pages & subdomains still load normally.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = redirectProtection,
                onCheckedChange = {
                    redirectProtection = it
                    scope.launch { runCatching { app.store.setWebviewRedirect(it) } }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Block popups from other sites",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Only popups opened by the site itself can appear.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = popupProtection,
                onCheckedChange = {
                    popupProtection = it
                    scope.launch { runCatching { app.store.setWebviewPopup(it) } }
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))

        Text(
            "Allowed redirect links",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Redirects to these hosts are never blocked, even though they're a " +
                "different site (e.g. a player or CDN a site must send you to).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newAllowedDomain,
                onValueChange = { newAllowedDomain = it },
                placeholder = { Text("player.example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val d = AdBlocker.normalizeDomain(newAllowedDomain)
                if (d.isNotBlank()) {
                    val next = (allowedRedirects + d).distinct()
                    allowedRedirects = next
                    newAllowedDomain = ""
                    scope.launch { app.store.setWebviewRedirectAllow(next) }
                }
            }) { Text("Add") }
        }
        allowedRedirects.forEach { domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val next = allowedRedirects.filterNot { it == domain }
                    allowedRedirects = next
                    scope.launch { app.store.setWebviewRedirectAllow(next) }
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove $domain",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebViewUserAgentCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var useDefault by remember { mutableStateOf(true) }
    var customUa by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        useDefault = app.store.webviewUseDefaultUa()
        customUa = app.store.webviewCustomUa()
        draft = customUa
    }

    fun persist(u: Boolean, custom: String) {
        useDefault = u
        customUa = custom
        // Keep the runtime UA (used by both WebViews + stream capture) current.
        app.webViewUseDefaultUa = u
        app.webViewCustomUa = custom.ifBlank { null }
        scope.launch { runCatching { app.store.setWebViewUa(u, custom) } }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "WebView user agent",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Some sites (Cloudflare) block the WebView when it advertises a " +
                "desktop browser it doesn't match. The stock Android user agent " +
                "passes verification on most sites; a custom one is for sites " +
                "that need a specific desktop/mobile UA.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Use Android default user agent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Stock Android WebView UA — passes Cloudflare checks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = useDefault,
                onCheckedChange = { on -> persist(on, draft) }
            )
        }
        if (!useDefault) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Mozilla/5.0 …") },
                    singleLine = true,
                    label = { Text("Custom user agent") },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { persist(false, draft) },
                    enabled = draft.trim().isNotEmpty() && draft.trim() != customUa
                ) { Text("Save") }
            }
            Text(
                "Currently used: ${app.effectiveWebViewUa().take(70)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UserscriptsCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<Userscript>>(emptyList()) }
    var editing by remember { mutableStateOf<Userscript?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scripts = runCatching { app.store.userscripts() }.getOrDefault(emptyList())
    }

    fun persist(list: List<Userscript>) {
        scripts = list
        scope.launch { runCatching { app.store.setUserscripts(list) } }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Userscripts",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tampermonkey-style scripts that run ONLY inside the app's WebView " +
                "(@match/@include/@run-at + GM_getValue/setValue). Add as many as you like.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (scripts.isEmpty()) {
            Text(
                "No userscripts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        } else {
            scripts.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            if (s.enabled) "Active in WebView" else "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { on ->
                            persist(scripts.map {
                                if (it.id == s.id) it.copy(enabled = on) else it
                            })
                        }
                    )
                    TextButton(onClick = { draft = s.code; editing = s }) { Text("Edit") }
                    IconButton(onClick = { persist(scripts.filterNot { it.id == s.id }) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        OutlinedButton(onClick = { draft = ""; adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add userscript")
        }
    }

    if (adding || editing != null) {
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) "Edit userscript" else "Add userscript") },
            text = {
                Column {
                    Text(
                        "Paste a userscript with a // ==UserScript== header " +
                            "(name, @match, @run-at…). It runs only in the WebView.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = { Text("// ==UserScript==\n// @name   My Script\n// @match  https://example.com/*\n// @run-at document-start\n// ==/UserScript==\n\nconsole.log('hello');") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = draft.trim()
                    if (code.isNotEmpty()) {
                        val name = UserscriptManager.parse(code).name
                        val editId = editing?.id
                        persist(
                            if (editId != null) {
                                scripts.map {
                                    if (it.id == editId) it.copy(name = name, code = code) else it
                                }
                            } else {
                                scripts + Userscript(
                                    id = "us" + System.currentTimeMillis(),
                                    name = name,
                                    code = code
                                )
                            }
                        )
                    }
                    adding = false
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AdBlockingCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf(listOf<AdBlocker.HostList>()) }
    var blockList by remember { mutableStateOf(listOf<String>()) }
    var whiteList by remember { mutableStateOf(listOf<String>()) }
    var updating by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var showAddListDialog by remember { mutableStateOf(false) }
    var newBlockDomain by remember { mutableStateOf("") }
    var newWhiteDomain by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        enabled = app.store.adEnabled()
        lists = app.store.adLists()
        blockList = app.store.adBlock()
        whiteList = app.store.adWhite()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Ad Blocking",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Block ads & trackers in websites opened in the browser tab",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { app.store.setAdEnabled(it) }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Applies only to WebView sites — the video player is never affected.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                "Blocklists (ad hosts)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            // FlowRow so all three presets stay visible (the third wraps to a
            // second line instead of overflowing off the right edge), with no
            // dead space between the chips and the row below.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdBlocker.PRESETS.forEach { preset ->
                    val isAdded = lists.any { it.url == preset.url }
                    OutlinedButton(
                        onClick = {
                            val next = if (isAdded) {
                                lists.filterNot { it.url == preset.url }
                            } else {
                                lists.filterNot { it.url == preset.url } + preset
                            }
                            lists = next
                            scope.launch(Dispatchers.IO) {
                                runCatching { app.store.setAdLists(next) }
                                runCatching { AdBlocker.download(preset.url, context) }
                            }
                        }
                    ) {
                        Text(if (isAdded) "✓ ${preset.name}" else "+ ${preset.name}")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showAddListDialog = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Add custom list URL")
            }
            lists.forEach { list ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(list.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            list.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        lists = lists.filterNot { it.url == list.url }
                        scope.launch { app.store.setAdLists(lists) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${list.name}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        updating = true
                        updateStatus = null
                        // Downloads are blocking okhttp + retries — must NOT run
                        // on the main thread (it froze the app / ANR-crashed).
                        // One bad list can never abort the rest or crash.
                        scope.launch(Dispatchers.IO) {
                            val total = runCatching { AdBlocker.refreshAll(lists, context) }
                                .getOrDefault(emptySet()).size
                            withContext(Dispatchers.Main) {
                                updating = false
                                updateStatus = if (total > 0) {
                                    "$total blocked domains ready"
                                } else {
                                    "Couldn't update lists — check connection"
                                }
                            }
                        }
                    },
                    enabled = !updating && lists.isNotEmpty()
                ) {
                    if (updating) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Updating…")
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Update lists")
                    }
                }
            }
            if (updateStatus != null) {
                Text(
                    updateStatus!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                "Manual blocklist",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a domain to always block in the browser tab",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newBlockDomain,
                    onValueChange = { newBlockDomain = it },
                    placeholder = { Text("ads.example.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val d = AdBlocker.normalizeDomain(newBlockDomain)
                    if (d.isNotBlank()) {
                        val next = (blockList + d).distinct()
                        blockList = next
                        newBlockDomain = ""
                        scope.launch { app.store.setAdBlock(next) }
                    }
                }) { Text("Add") }
            }
            blockList.forEach { domain ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val next = blockList.filterNot { it == domain }
                        blockList = next
                        scope.launch { app.store.setAdBlock(next) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Unblock $domain",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                "Whitelist",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "If a site or video is wrongly blocked, whitelist its domain",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newWhiteDomain,
                    onValueChange = { newWhiteDomain = it },
                    placeholder = { Text("video-site.example.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val d = AdBlocker.normalizeDomain(newWhiteDomain)
                    if (d.isNotBlank()) {
                        val next = (whiteList + d).distinct()
                        whiteList = next
                        newWhiteDomain = ""
                        scope.launch { app.store.setAdWhite(next) }
                    }
                }) { Text("Add") }
            }
            whiteList.forEach { domain ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val next = whiteList.filterNot { it == domain }
                        whiteList = next
                        scope.launch { app.store.setAdWhite(next) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove $domain from whitelist",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAddListDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddListDialog = false },
            title = { Text("Add blocklist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Hosts file URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = url.trim()
                    if (name.isNotBlank() && u.startsWith("http")) {
                        val list = AdBlocker.HostList(name.trim(), u)
                        val next = lists.filterNot { it.url == u } + list
                        lists = next
                        showAddListDialog = false
                        scope.launch(Dispatchers.IO) {
                            runCatching { app.store.setAdLists(next) }
                            runCatching { AdBlocker.download(u, context) }
                        }
                    } else {
                        showAddListDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddListDialog = false }) { Text("Cancel") }
            }
        )
    }
}
