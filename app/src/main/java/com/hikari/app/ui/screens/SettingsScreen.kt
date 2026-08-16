package com.hikari.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

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
                headlineContent = { Text("Version") },
                supportingContent = { Text("0.1.0 — Stage 1") }
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = { Text("Hikari Dark") }
            )
        }
        item {
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("github.com/codegeasse1/hikari") },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/codegeasse1/hikari"))
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
                            "• .cs3 plugin loader (Stage 2)\n" +
                            "• Torrent engine for infoHash streams (Stage 2)\n" +
                            "• Downloads, favorites, continue-watching (Stage 2)\n" +
                            "• SkyStream extensions, scriptable scrapers (Stage 3)",
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
            androidx.compose.material3.TextButton(
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
