package com.hikari.app.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel(
    app: Application,
    private val providerId: String,
    private val catalogId: String,
    private val catalogName: String,
    private val type: MediaType,
    private val rawType: String,
) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var page = 1
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        loadNext()
    }

    /** Loads the next page. Returns true when more pages may exist. */
    fun loadNext() {
        if (_loading.value || _done.value) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            val provider: ContentProvider? = manager.byId(providerId)
            val ref = CatalogRef(providerId, type, catalogId, catalogName, rawType)
            val fresh = try {
                (provider?.getCatalog(ref, page) ?: emptyList()).map { it.trimInlinePoster() }
            } catch (t: Throwable) {
                emptyList()
            }
            val translated = if (providerId in com.hikari.app.data.Translator.enabledIds()) {
                com.hikari.app.data.Translator.translateAll(fresh.map { it.title })
                    .mapIndexed { i, t -> if (t != fresh[i].title) fresh[i].copy(title = t) else fresh[i] }
            } else {
                fresh
            }
            if (translated.isEmpty()) {
                _done.value = true
            } else {
                val seen = _items.value.map { it.uniqueId }.toMutableSet()
                val merged = _items.value + translated.filter { seen.add(it.uniqueId) }
                _items.value = merged
                page++
            }
            _loading.value = false
        }
    }

    fun refresh() {
        page = 1
        _items.value = emptyList()
        _done.value = false
        loadNext()
    }
}

/** 51CG/MRDS posters arrive as huge inline data: URIs (hundreds of KB of
 * base64 each). A big catalog holding thousands of those blows the heap the moment
 * the user scrolls — the detail page re-fetches the poster via /meta anyway,
 * so drop oversized inline posters to let a catalog of ANY size be browsed
 * without an OutOfMemoryError. */
private const val MAX_INLINE_POSTER = 24_000

private fun MediaItem.trimInlinePoster(): MediaItem {
    val p = posterUrl?.takeIf { it.length <= MAX_INLINE_POSTER }
    val b = backdropUrl?.takeIf { it.length <= MAX_INLINE_POSTER }
    return if (p == posterUrl && b == backdropUrl) this
    else copy(posterUrl = p, backdropUrl = b)
}

    nav: NavHostController,
    providerId: String,
    catalogId: String,
    catalogName: String,
    providerName: String,
    type: MediaType,
    rawType: String,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: CatalogViewModel = viewModel(
        key = "$providerId|$catalogId|$type",
        factory = viewModelFactory {
            initializer {
                CatalogViewModel(
                    app,
                    providerId, catalogId, catalogName, type, rawType
                )
            }
        }
    )
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val done by vm.done.collectAsState()

    val gridState = rememberLazyGridState()
    // Infinite scroll: fetch the next page when the user scrolls close to the
    // bottom. (A LaunchedEffect keyed on gridState alone never re-fires on
    // scroll — gridState is a stable object — so this watches the scroll
    // position via snapshotFlow instead.)
    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (last >= total - 3 && !done && !loading) {
                vm.loadNext()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    catalogName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here right now — the site may be blocking or down.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(items, key = { it.uniqueId }) { item ->
                    CatalogCard(item) {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        )
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.width(28.dp))
                        else if (done && items.isNotEmpty()) {
                            Text(
                                "That's everything",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = PosterLoader.model(item.posterUrl),
            contentDescription = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}
