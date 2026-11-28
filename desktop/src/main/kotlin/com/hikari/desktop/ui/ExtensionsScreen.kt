package com.hikari.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.desktop.engine.EngineKind
import com.hikari.desktop.engine.EngineProvider

internal data class SourceTab(val label: String, val kind: EngineKind, val hint: String, val placeholder: String)

internal fun sourceTabsFor(kind: EngineKind): List<SourceTab> = listOf(
    SourceTab("Hikari repo", EngineKind.NUVIO, "Official .hiki jars are Android dex archives — desktop runs the jar build when published. Paste the repo.json link anyway; any portable .js inside installs.", "Hikari repo URL (repo.json)"),
    SourceTab("CloudStream repo", EngineKind.SORA, "CloudStream .cs3 files are Android dex and can't execute on the JVM. Paste a CS repo link — portable Sora mirrors install here.", "CloudStream repo URL"),
    SourceTab("SkyStream repo", EngineKind.SKYSTREAM, "SkyStream .sky files are zips with plugin.js + plugin.json and install natively.", "SkyStream repo URL"),
    SourceTab("Nuvio repo", EngineKind.NUVIO, "Nuvio manifest.json with scrapers installs natively.", "Nuvio repo URL (manifest.json)"),
    SourceTab("AniYomi repo", EngineKind.ANYMEX, "AniYomi .apk extensions are Android dex and can't run on desktop. Anymex .js mirrors install instead.", "AniYomi repo URL (index.json)"),
    SourceTab("IPTV playlist", EngineKind.IPTV, "Paste an .m3u/.m3u8 URL or Xtream portal as host|user|pass — it becomes an IPTV provider.", "https://…/playlist.m3u8 or host|user|pass"),
    SourceTab("Stremio addon", EngineKind.STREMIO, "Paste any Stremio manifest.json URL to add its catalogs.", "Stremio addon URL (manifest.json)"),
    SourceTab("Universal scraper", EngineKind.ZANGETSU, "Zangetsu / Vega style JS providers install natively.", "Provider .js URL"),
    SourceTab("File / URL", EngineKind.STREMIO, "Repos, addons, scrapers and single files — paste anything and Hikari guesses the type.", "https://…"),
)

data class EngineRepo(
    val url: String,
    val name: String,
    val description: String,
    val pluginCount: Int? = null,
    val badge: String? = null,
    val iconText: String? = null,
)

data class EngineRepoEntry(
    val name: String,
    val url: String,
    val version: String? = null,
    val sizeLabel: String? = null,
    val language: String? = null,
    val description: String? = null,
)

internal fun guessEngineKind(url: String): EngineKind {
    val u = url.trim().lowercase()
    if (u.count { it == '|' } == 2) return EngineKind.IPTV
    if (u.endsWith(".m3u") || u.endsWith(".m3u8") || u.endsWith(".pls")) return EngineKind.IPTV
    if (u.endsWith(".sky")) return EngineKind.SKYSTREAM
    if (u.endsWith(".js")) return EngineKind.NUVIO
    if (u.endsWith(".apk")) return EngineKind.ANYMEX
    if (u.endsWith(".cs3")) return EngineKind.SORA
    if (u.endsWith("manifest.json") || u.endsWith("/manifest.json")) return EngineKind.STREMIO
    return EngineKind.STREMIO
}

@OptIn(ExperimentalLayoutApi::class)
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
    openedRepoUrl: String? = null,
    onCloseRepo: () -> Unit = {},
    onCopyRepo: (String) -> Unit = {},
    onRefreshRepo: (String) -> Unit = {},
    onDeleteRepo: (String) -> Unit = {},
    installedEntryNames: Set<String> = providers.map { it.name }.toSet(),
    multiFeedEnabled: Boolean = false,
    onAddToFeed: (String) -> Unit = {},
) {
    var tabIndex by remember(repoKind) {
        mutableStateOf(
            when (repoKind) {
                EngineKind.SKYSTREAM -> 2
                EngineKind.NUVIO -> 3
                EngineKind.IPTV -> 5
                EngineKind.STREMIO -> 6
                EngineKind.ZANGETSU, EngineKind.VEGA -> 7
                else -> 8
            },
        )
    }
    val tabs = remember { sourceTabsFor(repoKind) }
    val activeTab = tabs[tabIndex.coerceIn(tabs.indices)]
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
                Text("Add a source", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Repos, addons, scrapers and single files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        if (index == tabIndex) {
                            Button(onClick = {}) { Text(tab.label) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    tabIndex = index
                                    onAddKindChange(tab.kind)
                                    onRepoKindChange(
                                        when (tab.kind) {
                                            EngineKind.STREMIO, EngineKind.IPTV -> tab.kind
                                            else -> tab.kind
                                        },
                                    )
                                },
                            ) { Text(tab.label) }
                        }
                    }
                }
                Text(
                    activeTab.hint,
                    style = MaterialTheme.typography.bodySmall,
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
                        label = { Text(activeTab.label) },
                        placeholder = { Text(activeTab.placeholder) },
                        singleLine = true,
                        enabled = !adding,
                    )
                    Button(onClick = {
                        onAddKindChange(activeTab.kind)
                        onAddProvider()
                    }, enabled = !adding && addUrl.isNotBlank()) {
                        if (adding) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Add repo")
                    }
                }
                if (addError != null) Text(addError, color = MaterialTheme.colorScheme.error)
            }
        }

        Text("Repos", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${repoOptions.size} repositories",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) {
            Text(
                "Showing ${repoEntries.size} plugins from ${repoOptions.size} repositories",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (repoName.isNotBlank()) Text(repoName, style = MaterialTheme.typography.titleMedium)
        repoOptions.chunked(2).forEach { rowRepos ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowRepos.forEach { repo ->
                    RepoCard(
                        repo = repo,
                        modifier = Modifier.weight(1f),
                        onOpen = { onLoadRepo(repo.url, repo.name) },
                        onCopy = { onCopyRepo(repo.url) },
                        onRefresh = { onRefreshRepo(repo.url) },
                        onDelete = { onDeleteRepo(repo.url) },
                    )
                }
                if (rowRepos.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (reposLoading) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading repository…")
            }
        }
        if (repoError != null) Text(repoError, color = MaterialTheme.colorScheme.error)
        if (openedRepoUrl != null) {
            OpenedRepoHeader(
                name = repoName.ifBlank { openedRepoUrl },
                onReload = { onRefreshRepo(openedRepoUrl) },
                onRemove = { onDeleteRepo(openedRepoUrl) },
                onClose = onCloseRepo,
            )
            repoEntries.take(200).chunked(2).forEach { rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowEntries.forEach { entry ->
                        val installed = installedEntryNames.contains(entry.name)
                        ExtensionCard(
                            entry = entry,
                            installed = installed,
                            showFeedPlus = multiFeedEnabled && installed,
                            modifier = Modifier.weight(1f),
                            onInstall = { onInstallRepoEntry(entry) },
                            onAddToFeed = { onAddToFeed(entry.name) },
                        )
                    }
                    if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (repoEntries.isEmpty() && !reposLoading && repoError == null) {
                Text(
                    "No installable files in this repository.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (repoEntries.isNotEmpty()) {
            Text("Browse a repo above to install its extensions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            providers.chunked(2).forEach { rowProviders ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowProviders.forEach { provider ->
                        InstalledExtensionCard(
                            provider = provider,
                            modifier = Modifier.weight(1f),
                            showFeedPlus = multiFeedEnabled,
                            onToggleEnabled = { onToggleEnabled(provider.id, it) },
                            onValidate = { onValidate(provider.id) },
                            onRemove = { onRemove(provider.id) },
                            onAddToFeed = { onAddToFeed(provider.name) },
                        )
                    }
                    if (rowProviders.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun RepoCard(
    repo: EngineRepo,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RepoAvatar(text = repo.iconText ?: repo.name)
                Column(modifier = Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        repo.description.ifBlank { repo.url },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repo.pluginCount?.let {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
                        Text("$it plugins", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                repo.badge?.let {
                    Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small) {
                        Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Open")
                }
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RepoAvatar(text: String) {
    val letter = text.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(40.dp),
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OpenedRepoHeader(name: String, onReload: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose) { Text("All repos") }
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onReload) { Text("Reload") }
        TextButton(onClick = onRemove) { Text("Remove repo") }
    }
}

@Composable
private fun ExtensionCard(
    entry: EngineRepoEntry,
    installed: Boolean,
    showFeedPlus: Boolean,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onAddToFeed: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RepoAvatar(text = entry.name)
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        entry.language?.let {
                            Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
                                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(
                        listOfNotNull(entry.version, entry.sizeLabel).joinToString(" · ").ifBlank { entry.url.substringAfter("://").take(48) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showFeedPlus) {
                    OutlinedButton(onClick = onAddToFeed, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                        Text("+")
                    }
                }
            }
            entry.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
                        Text("Installed", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text("signed", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (installed) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)),
                    ) { Text("Uninstall") }
                } else {
                    Button(onClick = onInstall) { Text("Install") }
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    provider: EngineProvider,
    modifier: Modifier = Modifier,
    showFeedPlus: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit,
    onAddToFeed: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RepoAvatar(text = provider.name)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                            Text(provider.kind.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
                if (showFeedPlus) {
                    OutlinedButton(onClick = onAddToFeed, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                        Text("+")
                    }
                } else {
                    Switch(checked = provider.enabled, onCheckedChange = onToggleEnabled)
                }
            }
            provider.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onValidate) { Text("Check") }
                TextButton(onClick = onRemove) { Text("Delete") }
            }
        }
    }
}
