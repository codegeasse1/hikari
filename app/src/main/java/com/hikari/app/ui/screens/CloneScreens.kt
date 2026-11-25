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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import com.hikari.app.HikariApp
import com.hikari.app.data.CloneHosts
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.i18n.tr
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Clone site" entry: every installed extension with a search bar. Clones
 *  themselves are excluded — a clone of a clone gets confusing. */
@Composable
fun ClonePickScreen(
    providers: List<ContentProvider>,
    onPick: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(providers, query) {
        val base = providers.filter { !CloneHosts.isClone(it.config) }
        val matches = if (query.isBlank()) base
        else base.filter {
            it.config.name.contains(query, ignoreCase = true) ||
                it.config.id.contains(query, ignoreCase = true)
        }
        matches.sortedBy { it.config.name.lowercase() }
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
                    tr("Pick the extension whose domain moved"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(tr("Search installed extensions…")) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    tr("No matches"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(rows, key = { it.config.id }) { p ->
                Surface(
                    onClick = { onPick(p.config) },
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
                    }
                }
            }
        }
    }
}

/** Edit a clone: new name + per-domain overrides. Every row defaults to
 *  "keep as it is" (new == old); only changed rows are stored. */
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
    var mainNew by remember(orig.id) { mutableStateOf("") }
    var rows by remember(orig.id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var extraOld by remember(orig.id) { mutableStateOf("") }
    var extraNew by remember(orig.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(orig.id) {
        loading = true
        val (main, rest) = withContext(Dispatchers.IO) { detectCloneDomains(orig) }
        mainOld = main
        mainNew = main.orEmpty()
        rows = rest.map { it to it }
        loading = false
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
                    Text(
                        tr("Domains — leave unchanged to keep as is"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (mainOld != null) {
                    item {
                        DomainRow(
                            oldHost = mainOld.orEmpty(),
                            newHost = mainNew,
                            onNewChange = { mainNew = CloneHosts.sanitizeDomain(it) },
                            main = true,
                            onRemove = null,
                        )
                    }
                }
                items(rows, key = { "ex-" + it.first }) { (old, new) ->
                    DomainRow(
                        oldHost = old,
                        newHost = new,
                        onNewChange = { nv ->
                            rows = rows.map { r -> if (r.first == old) r.first to CloneHosts.sanitizeDomain(nv) else r }
                        },
                        main = false,
                        onRemove = { rows = rows.filterNot { r -> r.first == old } },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = extraOld,
                            onValueChange = { extraOld = CloneHosts.sanitizeDomain(it) },
                            singleLine = true,
                            label = { Text(tr("Old domain")) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = extraNew,
                            onValueChange = { extraNew = CloneHosts.sanitizeDomain(it) },
                            singleLine = true,
                            label = { Text(tr("New domain")) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val o = CloneHosts.sanitizeDomain(extraOld)
                                val n = CloneHosts.sanitizeDomain(extraNew)
                                if (o.isNotBlank() && n.isNotBlank() && rows.none { it.first == o }) {
                                    rows = rows + (o to n)
                                    extraOld = ""
                                    extraNew = ""
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = tr("Add domain"))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(tr("Cancel"))
            }
            Button(
                onClick = {
                    if (saving || name.isBlank()) return@Button
                    scope.launch {
                        saving = true
                        try {
                            saveClone(orig, name.trim(), mainOld, mainNew, rows)
                            onSaved()
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = name.isNotBlank() && !saving && !loading,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (saving) tr("Saving…") else tr("Save clone"))
            }
        }
    }
}

@Composable
private fun DomainRow(
    oldHost: String,
    newHost: String,
    onNewChange: (String) -> Unit,
    main: Boolean,
    onRemove: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            (if (main) tr("Main site: ") else tr("Host: ")) + oldHost,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newHost,
                onValueChange = onNewChange,
                singleLine = true,
                label = { Text(if (main) tr("New main domain") else tr("New domain")) },
                modifier = Modifier.weight(1f),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = tr("Remove"))
                }
            }
        }
        if (newHost.isBlank() || newHost == oldHost) {
            Text(
                tr("Keep as it is"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Main site host + extra hosts found in an installed extension. IO-bound. */
private suspend fun detectCloneDomains(orig: ProviderConfig): Pair<String?, List<String>> {
    return withContext(Dispatchers.IO) {
        val helperMain = runCatching {
            val url = when (orig.type) {
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
                else -> null
            }
            CloneHosts.hostOf(url)
        }.getOrNull()
        val scanned = runCatching {
            val f = java.io.File(orig.url)
            if (!f.isFile) return@runCatching emptyList()
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
            if (total <= 0) emptyList()
            else CloneHosts.extractHosts(String(buf, 0, total, Charsets.UTF_8))
        }.getOrDefault(emptyList())
        val main = helperMain ?: scanned.firstOrNull()
        val rest = scanned.filter { it != main }.distinct()
        main to rest
    }
}

private suspend fun saveClone(
    orig: ProviderConfig,
    name: String,
    mainOld: String?,
    mainNewRaw: String,
    rows: List<Pair<String, String>>,
) {
    val app = HikariApp.instance
    val mainNewHost = CloneHosts.hostOf(mainNewRaw)
    val mainOldHost = CloneHosts.hostOf(mainOld)
    val hosts = LinkedHashMap<String, String>()
    if (!mainOldHost.isNullOrBlank() && !mainNewHost.isNullOrBlank() && mainOldHost != mainNewHost) {
        hosts[mainOldHost] = mainNewHost
    }
    for ((o0, n0) in rows) {
        val o = CloneHosts.hostOf(o0) ?: o0.trim().lowercase()
        val n = CloneHosts.hostOf(n0) ?: n0.trim().lowercase()
        if (o.isNotBlank() && n.isNotBlank() && o != n) hosts[o] = n
    }
    val mainFull = mainNewHost?.takeIf { it.isNotBlank() }?.let { "https://$it/" }
    val extra = CloneHosts.makeExtra(orig.id, mainFull, hosts, orig.extra)
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
