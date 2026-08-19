package com.hikari.app.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.MediaItem
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val repo = ContentRepository(manager)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Selected provider ids to search in. EMPTY = search ALL providers. */
    private val _selectedProviders = MutableStateFlow<Set<String>>(emptySet())
    val selectedProviders: StateFlow<Set<String>> = _selectedProviders.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    init {
        viewModelScope.launch {
            combine(_query.debounce(400).distinctUntilChanged(), _selectedProviders) { q, _ -> q }
                .collectLatest { q ->
                    if (q.isBlank()) {
                        _results.value = emptyList()
                        _searching.value = false
                        return@collectLatest
                    }
                    _searching.value = true
                    _results.value = repo.searchAll(q, providerIds = _selectedProviders.value)
                    _searching.value = false
                }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** "All sources" — empty set means every provider. */
    fun selectAll() {
        _selectedProviders.value = emptySet()
    }

    /** Toggle one provider in/out of the multi-select. Refuses to empty the
     *  selection (which would silently become "All"); use selectAll() for that. */
    fun toggleProvider(id: String) {
        val cur = _selectedProviders.value
        val next = if (id in cur) cur - id else cur + id
        if (next.isNotEmpty()) _selectedProviders.value = next
    }
}

@Composable
fun SearchScreen(nav: NavHostController) {
    val vm: SearchViewModel = viewModel()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val selected by vm.selectedProviders.collectAsState()
    val providers by vm.providers.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = {
                Text(
                    if (selected.isEmpty()) "Search across all providers…"
                    else "Search in ${selected.size} selected provider${if (selected.size == 1) "" else "s"}…"
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (providers.isNotEmpty()) {
            Column {
                var providerFilter by remember { mutableStateOf("") }
                val visibleProviders = remember(providers, providerFilter) {
                    val f = providerFilter.trim()
                    if (f.isEmpty()) providers
                    else providers.filter { it.config.name.contains(f, ignoreCase = true) }
                }
                if (providers.size > 5) {
                    // With many extensions installed the chip row is unusable —
                    // a mini search box narrows it to the ones you mean.
                    OutlinedTextField(
                        value = providerFilter,
                        onValueChange = { providerFilter = it },
                        placeholder = { Text("Filter providers…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (providerFilter.isNotEmpty()) {
                                IconButton(onClick = { providerFilter = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selected.isEmpty(),
                            onClick = { vm.selectAll() },
                            label = { Text("All") }
                        )
                    }
                    items(visibleProviders, key = { it.config.id }) { p ->
                        FilterChip(
                            selected = p.config.id in selected,
                            onClick = { vm.toggleProvider(p.config.id) },
                            label = { Text(p.config.name) }
                        )
                    }
                }
                Text(
                    if (selected.isEmpty()) "Searching every source"
                    else "${selected.size} source${if (selected.size == 1) "" else "s"} selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }
        if (searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (query.isBlank() && results.isEmpty()) {
            EmptyState(
                title = "Search",
                subtitle = "Type something to search across every provider.",
                actionLabel = null,
                action = null
            )
        } else {
            val namesById = providers.associateBy({ it.config.id }, { it.config.name })
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.uniqueId }) { item ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                nav.navigate(
                                    Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                )
                            }
                    ) {
                        AsyncImage(
                            model = PosterLoader.model(item.posterUrl),
                            contentDescription = item.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        namesById[item.providerId]?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
