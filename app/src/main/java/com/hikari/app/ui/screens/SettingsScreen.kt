package com.hikari.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.Updater
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val currentTheme = remember(themeKey) { HikariThemeMode.fromKey(themeKey) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text("Version") },
                supportingContent = { Text("${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") }
            )
        }
        item {
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
        }
        item {
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
                        Text(Updater.currentSha().take(7).let { "Build $it" })
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
        }
        item {
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
        }
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AdBlockingCard(app)
            }
        }
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                WebViewSafetyCard(app)
            }
        }
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                            "• Downloads, continue-watching (next)\n" +
                            "• SkyStream extensions, scriptable scrapers (planned)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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

    if (showUpdateDialog) {
        UpdateDialog(
            context = context,
            onDismiss = { showUpdateDialog = false },
            initialStatus = updateStatus,
        )
    }
}

@Composable
private fun WebViewSafetyCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var redirectProtection by remember { mutableStateOf(true) }
    var popupProtection by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        redirectProtection = app.store.webviewRedirect()
        popupProtection = app.store.webviewPopup()
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
