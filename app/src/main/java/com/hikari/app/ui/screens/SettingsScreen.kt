package com.hikari.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GitHub
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.net.Updater
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.launch

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
                leading = {
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
                    leading = {
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
                leading = {
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
                leading = {
                    Icon(
                        Icons.Filled.GitHub,
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
