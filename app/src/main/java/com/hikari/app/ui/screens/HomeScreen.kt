package com.hikari.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.ContentRepository
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
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
            manager.providers.collect { ps ->
                val sel = _selectedProvider.value
                if (sel != null && ps.none { it.config.enabled && it.config.id == sel }) {
                    _selectedProvider.value = null
                }
                loadInternal()
            }
        }
    }

    fun selectProvider(id: String?) {
        if (_selectedProvider.value == id) return
        _selectedProvider.value = id
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

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel()
    val rows by vm.rows.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selectedProvider.collectAsState()
    val providers by vm.providers.collectAsState()
    val activeProviders = providers.filter { it.config.enabled }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                "Hikari",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                "Every stream, one place.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (activeProviders.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selected == null,
                            onClick = { vm.selectProvider(null) },
                            label = { Text("All") }
                        )
                    }
                    items(activeProviders, key = { it.config.id }) { p ->
                        FilterChip(
                            selected = selected == p.config.id,
                            onClick = { vm.selectProvider(p.config.id) },
                            label = { Text(p.config.name) }
                        )
                    }
                }
            }
        }
        if (loading) {
            items(4) { ShimmerRow() }
        }
        rows.forEach { row ->
            item(key = "${row.providerName}|${row.title}") {
                MediaRow(
                    title = row.title,
                    providerName = row.providerName,
                    items = row.items
                ) { item ->
                    nav.navigate(Routes.detail(item.providerId, item.type, item.id, item.title))
                }
            }
        }
        if (rows.isEmpty() && !loading) {
            item {
                if (selected != null) {
                    val name = providers.firstOrNull { it.config.id == selected }?.config?.name
                    val reason =
                        com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[selected]
                    EmptyState(
                        title = "Couldn't load ${name ?: "this extension"}",
                        subtitle = reason
                            ?: "It returned no content right now. The site may be temporarily down or blocking the app — retry, or browse another extension.",
                        actionLabel = "Retry",
                        action = { vm.refresh() }
                    )
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
}
