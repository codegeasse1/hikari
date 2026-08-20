package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.ProviderType
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val store = (app as HikariApp).store
    private val repo = ContentRepository(manager)

    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedProvider = MutableStateFlow<String?>(null)
    val selectedProvider: StateFlow<String?> = _selectedProvider.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // Restore the user's last pick ("All" when never picked).
            _selectedProvider.value = store.homeProvider().ifBlank { null }
            loadInternal()
        }
        viewModelScope.launch {
            manager.providers.collect { ps ->
                val sel = _selectedProvider.value
                if (sel != null && ps.none { it.config.enabled && it.config.id == sel }) {
                    _selectedProvider.value = null
                    store.setHomeProvider("")
                }
                loadInternal()
            }
        }
    }

    fun selectProvider(id: String?) {
        if (_selectedProvider.value == id) return
        _selectedProvider.value = id
        viewModelScope.launch { store.setHomeProvider(id ?: "") }
        viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _rows.value = emptyList()
            _rows.value = repo.homeRows(_selectedProvider.value)
            _loading.value = false
        }
        loadJob?.join()
    }

    fun refresh() {
        viewModelScope.launch { loadInternal() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel()
    val rows by vm.rows.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selectedProvider.collectAsState()
    val providers by vm.providers.collectAsState()
    // Stream-only Stremio addons (Torrentio, NovaStream…) have no catalog to
    // browse, so like in Stremio they don't appear here at all — only addons
    // that can fill the home screen do. CS3 plugins / universal scrapers are
    // always shown (their catalogs are dynamic).
    val activeProviders = providers.filter {
        it.config.enabled && (it.config.type != com.hikari.app.data.ProviderType.STREMIO ||
            com.hikari.app.providers.StremioAddon.streamOnlyAddons[it.config.id] != true)
    }
    val selectedName = providers.firstOrNull { it.config.id == selected }?.config?.name
    var showCrash by remember { mutableStateOf(HikariApp.lastCrash != null) }
    var showPicker by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Cloudflare verification: when the selected extension's site is blocked
    // by a WAF check, this globe button opens the site in the WebView so the
    // user can verify once; the WebView auto-closes once the challenge passes
    // and the catalog reloads (the extension's cookie jar is now cleared).
    val context = LocalContext.current
    // Resolved off the main thread: for CS3 plugins this loads the plugin dex
    // to read its mainUrl, which must never block the UI thread (it can take
    // seconds and previously froze/ANR'd the app when Home recomposed during
    // heavy loads or right after an extension install).
    var webUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected, providers) {
        webUrl = withContext(Dispatchers.IO) {
            providers.firstOrNull { it.config.id == selected }?.let { webUrlFor(it) }
        }
    }
    val verifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Hikari",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Text(
                            "Every stream, one place.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            nav.navigate(Routes.SEARCH) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Translate: per-extension toggle — turns this extension's
                    // titles/text into English inside the app. Shown whenever a
                    // provider is selected (content exists regardless of webUrl).
                    selected?.let { pid ->
                        val translateOn = com.hikari.app.data.Translator.isOn(pid)
                        IconButton(
                            onClick = { showTranslate = true }
                        ) {
                            Text(
                                "A\u3042",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (translateOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    if (webUrl != null) {
                        IconButton(
                            onClick = {
                                verifyLauncher.launch(
                                    Intent(context, WebViewActivity::class.java).apply {
                                        putExtra("url", webUrl)
                                        putExtra("title", "Verify: ${selectedName ?: "site"}")
                                        putExtra("providerId", selected)
                                        putExtra("autoCloseWhenCloudflarePassed", true)
                                    }
                                )
                            }
                        ) {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = "Open site in web view (Cloudflare verification)",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (showCrash && HikariApp.lastCrash != null) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "The app crashed on a previous launch:\n${HikariApp.lastCrash}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            showCrash = false
                            HikariApp.instance.clearCrash()
                        }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            if (loading) {
                items(4) { ShimmerRow() }
            }
            rows.forEach { row ->
                item(key = row.key.ifBlank { "${row.providerName}|${row.title}" }) {
                    MediaRow(
                        title = row.title,
                        providerName = row.providerName,
                        items = row.items,
                        onClick = { item ->
                            Routes.safeNavigate(
                                nav,
                                Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                            )
                        },
                        onShowAll = {
                            Routes.safeNavigate(
                                nav,
                                Routes.catalog(
                                    row.providerId, row.catalogId, row.title,
                                    row.providerName, row.type, row.rawType
                                )
                            )
                        }
                    )
                }
            }
            if (rows.isEmpty() && !loading) {
                item {
                    if (selected != null) {
                        val reason =
                            com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[selected]
                                ?: com.hikari.app.providers.StremioAddon.catalogErrors[selected]
                        val streamOnly =
                            com.hikari.app.providers.StremioAddon.streamOnlyAddons[selected] == true
                        if (streamOnly) {
                            EmptyState(
                                title = "No catalog from ${selectedName ?: "this addon"}",
                                subtitle = "This addon doesn't provide a catalog to browse — it only " +
                                    "adds playback sources to titles opened from other addons. " +
                                    "Pick any movie or series and its streams will show up.",
                                actionLabel = "Browse all",
                                action = { vm.selectProvider(null) }
                            )
                        } else {
                            EmptyState(
                                title = "Couldn't load ${selectedName ?: "this extension"}",
                                subtitle = reason
                                    ?: "It returned no content right now. If the site is stuck behind " +
                                        "a Cloudflare check, tap the globe button at the top to verify — " +
                                        "the catalog reloads by itself when you're done. Otherwise the " +
                                        "site may be down — retry or browse another extension.",
                                actionLabel = "Retry",
                                action = { vm.refresh() }
                            )
                        }
                    } else {
                        EmptyState(
                            title = "No content yet",
                            subtitle = "Add a Stremio addon or a universal scraper to start watching.",
                            actionLabel = "Add extensions",
                            action = { nav.navigate(Routes.EXTENSIONS) }
                        )
                    }
                }
            }
        }

        // Floating source pill (Anikoto-style): shows the current provider and
        // opens the picker sheet. Sits above the bottom nav bar.
        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  ${selectedName ?: "All providers"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showPicker) {
        ProviderPickerSheet(
            providers = activeProviders,
            selectedId = selected,
            onPick = { id ->
                showPicker = false
                vm.selectProvider(id)
            },
            onDismiss = { showPicker = false },
        )
    }

    val selId = selected
    if (showTranslate && selId != null) {
        val pid = selId
        val pname = selectedName ?: "this extension"
        val isOn = com.hikari.app.data.Translator.isOn(pid)
        AlertDialog(
            onDismissRequest = { showTranslate = false },
            title = { Text(if (isOn) "Turn off translation?" else "Translate to English?") },
            text = {
                Text(
                    if (isOn) {
                        "Translation is ON for $pname — its titles and text are shown in English."
                    } else {
                        "$pname shows content in its original language. Turn it into English? " +
                            "Only this extension is affected — every other extension stays as it is."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTranslate = false
                    scope.launch {
                        com.hikari.app.data.Translator.enable(pid, !isOn)
                        vm.refresh()
                    }
                }) {
                    Text(if (isOn) "Turn off" else "Always translate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTranslate = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(
    providers: List<ContentProvider>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Alphabetical (by extension name), so the picker isn't "install order".
    val filtered = remember(query) {
        val sorted = providers.sortedBy { it.config.name.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.config.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Choose an extension",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Only the selected extension's catalog is shown on Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search extensions…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                item {
                    PickerRow("All providers", isSelected = selectedId == null) {
                        onPick(null)
                    }
                }
                if (filtered.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                }
                items(filtered, key = { it.config.id }) { p ->
                    PickerRow(p.config.name, isSelected = selectedId == p.config.id) {
                        onPick(p.config.id)
                    }
                }
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            "No extension matches \"$query\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The website URL a provider's content actually lives on (for the Cloudflare
 *  verification WebView button). HIKARI providers expose it through their SDK
 *  mainUrl; Stremio/universal use the configured URL; CS3 plugins load theirs
 *  from the plugin dex. Null when unknown — the button is hidden then. */
private fun webUrlFor(p: ContentProvider): String? = when (p.config.type) {
    ProviderType.STREMIO, ProviderType.UNIVERSAL ->
        p.config.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    ProviderType.HIKARI ->
        com.hikari.app.hiki.HikariRuntime.providerFor(p.config)?.mainUrl
    ProviderType.CS3 -> runCatching {
        val file = java.io.File(p.config.url)
        if (!file.exists()) return@runCatching null
        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(com.hikari.app.HikariApp.instance, file)
        apis.getOrNull(p.config.id.substringAfterLast("|").toIntOrNull() ?: 0)?.mainUrl
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()
    else -> null
}
