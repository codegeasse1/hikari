package com.hikari.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.ContentRepository
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.navigation.Routes
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

    init {
        viewModelScope.launch {
            manager.providers.collect { loadInternal() }
        }
    }

    private suspend fun loadInternal() {
        _loading.value = true
        _rows.value = repo.homeRows()
        _loading.value = false
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
        if (loading && rows.isEmpty()) {
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
