package com.hikari.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hikari.desktop.ShelfState
import com.hikari.desktop.SourceState
import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.StreamKind
import java.net.URI

internal enum class DesktopPage {
    HOME,
    SEARCH,
    SOURCES,
    DETAIL,
}

@Composable
internal fun DesktopSidebar(
    selectedPage: DesktopPage,
    onNavigate: (DesktopPage) -> Unit,
) {
    Surface(
        modifier = Modifier.width(224.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "HIKARI",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                text = "Windows desktop",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 24.dp),
            )
            NavigationItem("Beranda", selectedPage == DesktopPage.HOME) { onNavigate(DesktopPage.HOME) }
            NavigationItem("Cari", selectedPage == DesktopPage.SEARCH) { onNavigate(DesktopPage.SEARCH) }
            NavigationItem("Sumber", selectedPage == DesktopPage.SOURCES) { onNavigate(DesktopPage.SOURCES) }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text(
                text = "Katalog dari add-on Stremio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun NavigationItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(label, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            shape = shape,
        ) {
            Text(label, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun HomeScreen(
    sources: List<SourceState>,
    selectedSourceUrl: String?,
    shelves: List<ShelfState>,
    onSelectSource: (String) -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onOpenSources: () -> Unit,
    onRetry: () -> Unit,
) {
    if (sources.isEmpty()) {
        EmptyState(
            title = "Belum ada sumber katalog",
            message = "Tambahkan URL add-on Stremio untuk mulai memuat katalog.",
            actionLabel = "Kelola sumber",
            onAction = onOpenSources,
        )
        return
    }

    val selected = sources.firstOrNull { it.url == selectedSourceUrl } ?: sources.first()
    var sourceMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sumber katalog", style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { sourceMenuOpen = true }) {
                        Text(
                            selected.manifest?.name ?: sourceHost(selected.url),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismissRequest = { sourceMenuOpen = false },
                    ) {
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = {
                                    Text(source.manifest?.name ?: sourceHost(source.url), maxLines = 1)
                                },
                                onClick = {
                                    onSelectSource(source.url)
                                    sourceMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onOpenSources) { Text("Kelola sumber") }
        }

        when {
            selected.loading -> ProgressMessage("Memeriksa sumber katalog…")
            selected.error != null -> MessagePanel(
                title = "Sumber tidak dapat dimuat",
                message = selected.error,
                actionLabel = "Coba lagi",
                onAction = onRetry,
                isError = true,
            )
            selected.manifest == null -> MessagePanel(
                title = "Manifest belum tersedia",
                message = "Periksa koneksi atau buka pengelolaan sumber untuk memperbaiki URL.",
                actionLabel = "Coba lagi",
                onAction = onRetry,
                isError = true,
            )
            shelves.isEmpty() -> EmptyState(
                title = "Belum ada katalog",
                message = "Add-on ini belum menyediakan katalog yang bisa ditampilkan.",
                actionLabel = "Kelola sumber",
                onAction = onOpenSources,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Katalog", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Judul yang disediakan oleh ${selected.manifest.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = shelves,
                    key = { "${selected.url}:${it.catalog.type}:${it.catalog.id}" },
                ) { shelf ->
                    CatalogShelf(shelf, onOpenTitle, onRetry)
                }
            }
        }
    }
}

@Composable
private fun CatalogShelf(
    shelf: ShelfState,
    onOpenTitle: (AddonTitle) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(shelf.catalog.name, style = MaterialTheme.typography.titleLarge)
            if (shelf.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
        }
        when {
            shelf.loading -> Text(
                "Memuat judul…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shelf.error != null -> MessagePanel(
                title = "Katalog ini gagal dimuat",
                message = shelf.error,
                actionLabel = "Muat ulang sumber",
                onAction = onRetry,
                isError = true,
            )
            shelf.titles.isEmpty() -> Text(
                "Belum ada judul di katalog ini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(
                    items = shelf.titles,
                    key = { "${it.addonUrl}:${it.type}:${it.id}" },
                ) { title ->
                    TitleCard(
                        title = title,
                        modifier = Modifier.width(164.dp),
                        onClick = { onOpenTitle(title) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    results: List<AddonTitle>,
    hasSources: Boolean,
    onSearch: () -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onOpenSources: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("Nama film atau serial") },
                singleLine = true,
                enabled = !loading && hasSources,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!loading && hasSources) onSearch() }),
            )
            Button(onClick = onSearch, enabled = !loading && hasSources && query.isNotBlank()) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Cari")
            }
        }

        if (!hasSources) {
            EmptyState(
                title = "Tambahkan sumber sebelum mencari",
                message = "Pencarian memakai katalog add-on Stremio yang Anda pasang.",
                actionLabel = "Kelola sumber",
                onAction = onOpenSources,
            )
        } else if (loading) {
            ProgressMessage("Mencari di katalog…")
        } else {
            if (error != null) MessagePanel(
                title = "Pencarian belum lengkap",
                message = error,
                isError = true,
            )
            if (results.isNotEmpty()) {
                Text("Hasil pencarian", style = MaterialTheme.typography.titleLarge)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                ) {
                    items(
                        items = results,
                        key = { "${it.addonUrl}:${it.type}:${it.id}" },
                    ) { title ->
                        TitleCard(
                            title = title,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenTitle(title) },
                        )
                    }
                }
            } else if (error == null && query.isNotBlank()) {
                EmptyState(
                    title = "Tidak ada judul yang cocok",
                    message = "Coba kata kunci lain atau periksa apakah sumber mendukung pencarian.",
                )
            } else if (query.isBlank()) {
                Text(
                    "Masukkan judul, lalu tekan Cari atau Enter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SourceManagerScreen(
    sources: List<SourceState>,
    url: String,
    onUrlChange: (String) -> Unit,
    adding: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onRemove: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Tambahkan add-on", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Tempel URL manifest atau alamat add-on Stremio. Hikari memeriksa manifest sebelum menyimpannya.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("URL sumber") },
                        placeholder = { Text("https://contoh.addon/manifest.json") },
                        singleLine = true,
                        enabled = !adding,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!adding) onAdd() }),
                    )
                    Button(onClick = onAdd, enabled = !adding && url.isNotBlank()) {
                        if (adding) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Tambah")
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sumber terpasang", style = MaterialTheme.typography.titleLarge)
            if (sources.any { it.error != null }) TextButton(onClick = onRetry) { Text("Periksa ulang") }
        }

        if (sources.isEmpty()) {
            EmptyState(
                title = "Daftar sumber masih kosong",
                message = "Sumber yang berhasil ditambahkan akan muncul di sini.",
            )
        } else {
            sources.forEach { source ->
                SourceCard(
                    source = source,
                    onRetry = onRetry,
                    onRemove = { onRemove(source.url) },
                    onUse = { onUse(source.url) },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: SourceState,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onUse: () -> Unit,
) {
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
                    Text(
                        source.manifest?.name ?: sourceHost(source.url),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    source.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    source.error != null -> Text("Perlu diperiksa", color = MaterialTheme.colorScheme.error)
                    source.manifest != null -> Text("Siap", color = MaterialTheme.colorScheme.primary)
                }
            }
            source.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.manifest != null) OutlinedButton(onClick = onUse) { Text("Gunakan") }
                if (source.error != null) OutlinedButton(onClick = onRetry) { Text("Coba lagi") }
                TextButton(onClick = onRemove) { Text("Hapus") }
            }
        }
    }
}

@Composable
internal fun DetailScreen(
    title: AddonTitle,
    episodes: List<AddonEpisode>,
    loading: Boolean,
    error: String?,
    playbackMessage: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayEpisode: (AddonEpisode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ArtworkPoster(
            url = title.posterUrl,
            title = title.title,
            modifier = Modifier.width(252.dp).aspectRatio(0.68f),
        )
        LazyColumn(
            modifier = Modifier.fillMaxHeight().weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text("Kembali")
                }
                Text(title.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(typeLabel(title.type))
                        title.releaseInfo?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (title.genres.isNotEmpty()) {
                    Text(
                        title.genres.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Button(onClick = onPlay) {
                    Text(if (title.type.equals("movie", ignoreCase = true)) "Pilih pemutar" else "Putar episode pertama")
                }
                if (playbackMessage != null) {
                    Text(playbackMessage, color = MaterialTheme.colorScheme.primary)
                }
                if (loading) ProgressMessage("Memuat detail judul…")
                if (error != null) MessagePanel(
                    title = "Detail tidak dapat dimuat",
                    message = error,
                    actionLabel = "Kembali ke katalog",
                    onAction = onBack,
                    isError = true,
                )
                title.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!loading && episodes.isEmpty() && title.type.equals("series", ignoreCase = true)) {
                item {
                    Text(
                        "Daftar episode tidak disediakan oleh add-on ini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                item { Text("Episode", style = MaterialTheme.typography.titleLarge) }
                items(items = episodes, key = { it.id }) { episode ->
                    EpisodeRow(episode = episode, onPlay = { onPlayEpisode(episode) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: AddonEpisode, onPlay: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("S${episode.season} · E${episode.number}", color = MaterialTheme.colorScheme.primary)
                Text(episode.title, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onPlay) { Text("Pilih stream") }
        }
    }
}

@Composable
private fun TitleCard(title: AddonTitle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            ArtworkPoster(
                url = title.posterUrl,
                title = title.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(typeLabel(title.type), title.releaseInfo?.takeIf(String::isNotBlank))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StreamPickerDialog(
    title: String,
    streams: List<AddonStream>,
    loading: Boolean,
    error: String?,
    onChoose: (AddonStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 400.dp, max = 620.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Pilih stream", style = MaterialTheme.typography.titleLarge)
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                when {
                    loading -> ProgressMessage("Meminta stream dari add-on…")
                    error != null -> MessagePanel(
                        title = "Stream belum tersedia",
                        message = error,
                        isError = true,
                    )
                    streams.isEmpty() -> Text(
                        "Add-on belum mengembalikan pilihan stream.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = streams, key = { it.url }) { stream ->
                            Button(
                                onClick = { onChoose(stream) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stream.name.ifBlank { "Stream" }, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (stream.kind == StreamKind.DIRECT) {
                                            "Video langsung · VLC/MPV jika tersedia, selain itu browser"
                                        } else {
                                            "Dibuka di browser"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ProgressMessage(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceHost(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank) ?: url

private fun typeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> "Film"
    "series" -> "Serial"
    else -> type.replaceFirstChar { it.uppercase() }
}
