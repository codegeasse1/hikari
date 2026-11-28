package com.hikari.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.desktop.engine.EngineKind
import com.hikari.desktop.engine.EngineProvider

data class EngineRepo(val url: String, val name: String, val description: String)

data class EngineRepoEntry(val name: String, val url: String)

internal fun guessEngineKind(url: String): EngineKind {
    val u = url.trim().lowercase()
    if (u.count { it == '|' } == 2) return EngineKind.IPTV
    if (u.endsWith(".m3u") || u.endsWith(".m3u8") || u.endsWith(".pls")) return EngineKind.IPTV
    if (u.endsWith(".js")) return EngineKind.NUVIO
    if (u.endsWith("manifest.json") || u.endsWith("/manifest.json")) return EngineKind.STREMIO
    return EngineKind.STREMIO
}

@Composable
internal fun ExtensionsScreen(
    providers: List<EngineProvider>,
    tmdbKey: String,
    onTmdbKeyChange: (String) -> Unit,
    onSaveTmdbKey: () -> Unit,
    addUrl: String,
    onAddUrlChange: (String) -> Unit,
    addKind: EngineKind,
    onAddKindChange: (EngineKind) -> Unit,
    adding: Boolean,
    addError: String?,
    onAddProvider: () -> Unit,
    repoKind: EngineKind,
    onRepoKindChange: (EngineKind) -> Unit,
    repoOptions: List<EngineRepo>,
    repoEntries: List<EngineRepoEntry>,
    repoName: String,
    reposLoading: Boolean,
    repoError: String?,
    onLoadRepo: (String, String) -> Unit,
    onInstallRepoEntry: (EngineRepoEntry) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onValidate: (String) -> Unit,
) {
    var kindMenuOpen by remember { mutableStateOf(false) }
    var repoKindMenuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TMDB key (optional)", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Extension catalogs and details use TMDB. Hikari ships with built-in keys; paste your own if they ever run dry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = tmdbKey,
                        onValueChange = onTmdbKeyChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("TMDB API key") },
                        placeholder = { Text("Leave blank to use the built-in keys") },
                        singleLine = true,
                    )
                    Button(onClick = onSaveTmdbKey) { Text("Save") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add an extension", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Stremio manifest, provider .js file, M3U playlist, or Xtream portal as host|user|pass.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = onAddUrlChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Extension URL or file path") },
                        placeholder = { Text("https://…/manifest.json, ….js, ….m3u, host|user|pass") },
                        singleLine = true,
                        enabled = !adding,
                    )
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { kindMenuOpen = true }, enabled = !adding) {
                            Text(addKind.title)
                        }
                        DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                            EngineKind.entries.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind.title) },
                                    onClick = {
                                        onAddKindChange(kind)
                                        kindMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Button(onClick = onAddProvider, enabled = !adding && addUrl.isNotBlank()) {
                        if (adding) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Add")
                    }
                }
                if (addError != null) Text(addError, color = MaterialTheme.colorScheme.error)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Extension repositories", style = MaterialTheme.typography.titleLarge)
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { repoKindMenuOpen = true }) { Text(repoKind.title) }
                        DropdownMenu(expanded = repoKindMenuOpen, onDismissRequest = { repoKindMenuOpen = false }) {
                            listOf(EngineKind.NUVIO, EngineKind.ANYMEX).forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind.title) },
                                    onClick = {
                                        onRepoKindChange(kind)
                                        repoKindMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (repoName.isNotBlank()) Text(repoName, style = MaterialTheme.typography.titleMedium)
                repoOptions.forEach { repo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(repo.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                repo.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onLoadRepo(repo.url, repo.name) }) { Text("Browse") }
                    }
                }
                if (repoEntries.isNotEmpty()) Text("Files", style = MaterialTheme.typography.titleSmall)
                if (reposLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text("Loading repository…")
                    }
                }
                if (repoError != null) Text(repoError, color = MaterialTheme.colorScheme.error)
                repoEntries.take(200).forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onInstallRepoEntry(entry) }) { Text("Install") }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Installed extensions (${providers.size})", style = MaterialTheme.typography.titleLarge)
        }

        if (providers.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text(
                    "Nothing installed yet. Add a Stremio add-on, a provider .js file, or an IPTV list above.",
                    modifier = Modifier.padding(22.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            providers.forEach { provider ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text(
                                            provider.kind.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                                Text(
                                    provider.source,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = provider.enabled,
                                onCheckedChange = { onToggleEnabled(provider.id, it) },
                            )
                        }
                        provider.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onValidate(provider.id) }) { Text("Check") }
                            TextButton(onClick = { onRemove(provider.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}
