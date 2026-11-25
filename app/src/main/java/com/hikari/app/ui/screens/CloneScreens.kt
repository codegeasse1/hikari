package com.hikari.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import com.hikari.app.HikariApp
import com.hikari.app.data.CloneHosts
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.i18n.tr
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Clone site" entry: two tabs — the user's clones (with delete) and every
 *  installed extension with a search bar. Clones themselves are excluded from
 *  the All tab — a clone of a clone gets confusing. */
@Composable
fun ClonePickScreen(
    providers: List<ContentProvider>,
    onPick: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
    onDelete: (ProviderConfig) -> Unit = {},
) {
    var tab by remember(providers) {
        mutableStateOf(if (providers.any { CloneHosts.isClone(it.config) }) 0 else 1)
    }
    var query by remember { mutableStateOf("") }
    val clones = remember(providers) {
        providers.filter { CloneHosts.isClone(it.config) }.sortedBy { it.config.name.lowercase() }
    }
    val all = remember(providers) {
        providers.filter { !CloneHosts.isClone(it.config) }.sortedBy { it.config.name.lowercase() }
    }
    val base = if (tab == 0) clones else all
    val rows = remember(base, query) {
        if (query.isBlank()) base
        else base.filter {
            it.config.name.contains(query, ignoreCase = true) ||
                it.config.id.contains(query, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Clone site"), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (tab == 0) tr("Your clones — tap to delete one")
                    else tr("Pick the extension whose domain moved"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = {
                    Text(
                        tr("Cloned sites") + if (clones.isNotEmpty()) " (${clones.size})" else "",
                        maxLines = 1,
                    )
                },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(tr("All extensions"), maxLines = 1) },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = {
                Text(
                    if (tab == 0) tr("Search your clones…")
                    else tr("Search installed extensions…")
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) tr("No clones yet — open All extensions to clone one")
                    else tr("No matches"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 0.dp, end = 0.dp, top = 0.dp,
                bottom = LocalTaskbarInset.current + 24.dp,
            ),
        ) {
            items(rows, key = { it.config.id }) { p ->
                Surface(
                    onClick = { if (tab == 1) onPick(p.config) },
                    enabled = tab == 1,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.config.name.ifBlank { p.config.id },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                p.config.type.groupLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (tab == 0) {
                            IconButton(onClick = { onDelete(p.config) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = tr("Delete clone"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Clone edit: new name + ONE new-domain field. The old domain is detected
 *  automatically and never asked — the user cannot know it. */
@Composable
fun CloneEditScreen(
    orig: ProviderConfig,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(orig.id) { mutableStateOf(orig.name + " Clone") }
    var loading by remember(orig.id) { mutableStateOf(true) }
    var mainOld by remember(orig.id) { mutableStateOf<String?>(null) }
    var newDomain by remember(orig.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(orig.id) {
        loading = true
        mainOld = withContext(Dispatchers.IO) { detectCloneMain(orig) }
        loading = false
    }

    val newHost = CloneHosts.hostOf(newDomain)
    val oldHost = CloneHosts.hostOf(mainOld)
    val canSave = name.isNotBlank() && !saving && !loading &&
        !newHost.isNullOrBlank() && !oldHost.isNullOrBlank() && newHost != oldHost

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Clone site"), style = MaterialTheme.typography.titleMedium)
                Text(
                    orig.name.ifBlank { orig.id },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("New name for this clone")) },
                    placeholder = { Text(tr("e.g. newanime4i")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("The original stays untouched. Works best with CloudStream extensions."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    if (oldHost.isNullOrBlank()) {
                        Text(
                            tr("Could not detect this extension's current domain — try opening it once, then come back."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            tr("Current domain: ") + oldHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = CloneHosts.sanitizeDomain(it) },
                        singleLine = true,
                        label = { Text(tr("New domain")) },
                        placeholder = { Text(tr("e.g. 123av.com")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!oldHost.isNullOrBlank() && !newHost.isNullOrBlank() && newHost != oldHost) {
                        Text(
                            tr("This clone will load from ") + newHost + tr(" instead of ") + oldHost,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            tr("Enter the site's new domain — everything (list, details, play, web view) moves over automatically."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Action row sits above the floating taskbar + system nav so Save is
        // always visible and tappable (was hidden behind the bar).
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp + LocalTaskbarInset.current,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(tr("Cancel"))
            }
            Button(
                onClick = {
                    if (!canSave) return@Button
                    scope.launch {
                        saving = true
                        try {
                            saveClone(orig, name.trim(), mainOld, newDomain)
                            onSaved()
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (saving) tr("Saving…") else tr("Save clone"))
            }
        }
    }
}

/** Current main-site host of an installed extension. IO-bound — never call
 *  on the main thread (CS3 plugin load asserts a background thread). */
private suspend fun detectCloneMain(orig: ProviderConfig): String? {
    return withContext(Dispatchers.IO) {
        val helper = runCatching {
            val url: String? = when (orig.type) {
                ProviderType.ANYMEX, ProviderType.ANYMEX_MANGA ->
                    com.hikari.app.anymex.AnymexPluginManager.siteUrlOf(orig)
                ProviderType.ANIYOMI ->
                    com.hikari.app.aniyomi.AniyomiExtensionManager.siteUrlOf(orig)
                ProviderType.SORA ->
                    com.hikari.app.sora.SoraPluginManager.siteUrlOf(orig)
                ProviderType.SKYSTREAM ->
                    com.hikari.app.skystream.SkyStreamPluginManager.siteUrlOf(orig)
                ProviderType.ZANGETSU ->
                    com.hikari.app.zangetsu.ZangetsuPluginManager.siteUrlOf(orig)
                ProviderType.MANGA ->
                    com.hikari.app.manga.MangaExtensionManager.siteUrlOf(orig)
                ProviderType.HIKARI ->
                    runCatching { com.hikari.app.hiki.HikariRuntime.providerFor(orig)?.mainUrl }.getOrNull()
                ProviderType.CS3 -> {
                    val file = java.io.File(orig.url)
                    if (!file.exists()) null
                    else {
                        val idx = orig.id.substringAfterLast("|").toIntOrNull() ?: 0
                        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(
                            com.hikari.app.HikariApp.instance, file,
                        )
                        apis.getOrNull(idx)?.mainUrl
                    }
                }
                else -> null
            }
            CloneHosts.hostOf(url)
        }.getOrNull()
        if (!helper.isNullOrBlank()) return@withContext helper
        runCatching {
            val f = java.io.File(orig.url)
            if (!f.isFile) return@runCatching null
            val cap = 262144
            val buf = ByteArray(cap)
            var total = 0
            f.inputStream().use { s ->
                while (total < cap) {
                    val n = s.read(buf, total, cap - total)
                    if (n <= 0) break
                    total += n
                }
            }
            if (total <= 0) null
            else CloneHosts.extractHosts(String(buf, 0, total, Charsets.UTF_8)).firstOrNull()
        }.getOrNull()
    }
}

private suspend fun saveClone(
    orig: ProviderConfig,
    name: String,
    mainOld: String?,
    newDomainRaw: String,
) {
    val app = HikariApp.instance
    val newHost = CloneHosts.hostOf(newDomainRaw)
    val oldHost = CloneHosts.hostOf(mainOld)
    if (newHost.isNullOrBlank() || oldHost.isNullOrBlank() || newHost == oldHost) return
    val mainFull = "https://$newHost/"
    val extra = CloneHosts.makeExtra(orig.id, oldHost, mainFull, emptyMap(), orig.extra)
    val id = CloneHosts.makeId(orig.id, name)
    val clean = name.trim().ifBlank { orig.name + " Clone" }
    withContext(Dispatchers.IO) {
        app.store.addProvider(
            ProviderConfig(
                id = id,
                name = clean,
                type = orig.type,
                url = orig.url,
                iconUrl = orig.iconUrl,
                enabled = true,
                extra = extra,
                nsfw = orig.nsfw,
                lang = orig.lang,
            ),
        )
        CloneHosts.refreshGlobal(app.store.providers())
        runCatching { app.providers.refresh() }
    }
}
