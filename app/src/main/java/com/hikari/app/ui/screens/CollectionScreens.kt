package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.CatalogSource
import com.hikari.app.data.CatalogSourceKind
import com.hikari.app.data.Collection
import com.hikari.app.data.CollectionFolder
import com.hikari.app.data.CollectionsRepository
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TmdbPreset
import com.hikari.app.data.TmdbPresets
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Collections manager: the "New Collection" / "New Folder" screens of the
 * reference client, folded into one destination with three stages (list →
 * collection → folder) so a whole collection can be built without the back
 * stack growing per keystroke.
 *
 * A collection is a NAME plus one or more folders, and a folder is a name plus
 * a list of catalog sources. A source is either a TMDB preset (Marvel Studios,
 * HBO, Prime Video…) — which needs no extension installed — or one catalog of
 * an installed extension. Saving writes the whole collection to [AppStore];
 * Home's extension picker then offers it next to the extensions.
 */
@Composable
fun CollectionsScreen(nav: NavHostController, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.collectionsFlow() }
    val collections by flow.collectAsState(initial = emptyList())

    // The collection being edited (a working copy — nothing is written until
    // Save) and the folder being edited inside it. Both null = the list view.
    var draft by remember { mutableStateOf<Collection?>(null) }
    var folderDraft by remember { mutableStateOf<CollectionFolder?>(null) }
    var askNewCollection by remember { mutableStateOf(false) }
    var askNewFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Collection?>(null) }

    BackHandler(enabled = folderDraft != null || draft != null) {
        if (folderDraft != null) folderDraft = null else draft = null
    }

    val openEditor: (Collection) -> Unit = { c ->
        draft = c
        folderDraft = null
    }

    val editing = draft
    if (editing != null) {
        val folder = folderDraft
        if (folder != null) {
            FolderEditorPage(
                folder = folder,
                onBack = { folderDraft = null },
                onSave = { updated ->
                    draft = editing.copy(
                        folders = editing.folders.map { if (it.id == updated.id) updated else it }
                    )
                    folderDraft = null
                },
            )
        } else {
            CollectionEditorPage(
                collection = editing,
                onBack = { draft = null },
                onChange = { draft = it },
                onAddFolder = { askNewFolder = true },
                onEditFolder = { folderDraft = it },
                onDeleteFolder = { f ->
                    draft = editing.copy(folders = editing.folders.filter { it.id != f.id })
                },
                onSave = {
                    scope.launch {
                        app.store.upsertCollection(editing)
                        draft = null
                    }
                },
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                title = tr("Collections"),
                subtitle = tr("Your own folders of catalogs, shown on Home."),
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                item {
                    Surface(
                        onClick = { askNewCollection = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("New collection"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    tr("Name it, then add a folder for each kind of content you want."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (collections.isEmpty()) {
                    item {
                        Column(Modifier.padding(top = 28.dp)) {
                            EmptyState(
                                title = tr("No collections yet"),
                                subtitle = tr(
                                    "A collection groups the catalogs you actually watch into one " +
                                        "place — pick it on Home and only those catalogs load."
                                ),
                            )
                        }
                    }
                }
                items(collections, key = { it.id }) { c ->
                    CollectionListRow(
                        collection = c,
                        onOpen = { Routes.safeNavigate(nav, Routes.collectionView(c.id)) },
                        onEdit = { openEditor(c) },
                        onDelete = { confirmDelete = c },
                    )
                }
            }
        }
    }

    if (askNewCollection) {
        NameDialog(
            title = tr("New collection"),
            label = tr("Collection name"),
            initial = "",
            onDismiss = { askNewCollection = false },
            onConfirm = { name ->
                askNewCollection = false
                openEditor(
                    Collection(
                        id = app.store.newId("col"),
                        name = name,
                        folders = emptyList(),
                    )
                )
            },
        )
    }

    val editingForFolder = draft
    if (askNewFolder && editingForFolder != null) {
        NameDialog(
            title = tr("New folder"),
            label = tr("Folder name"),
            initial = "",
            onDismiss = { askNewFolder = false },
            onConfirm = { name ->
                askNewFolder = false
                val f = CollectionFolder(id = app.store.newId("fld"), name = name)
                draft = editingForFolder.copy(folders = editingForFolder.folders + f)
                folderDraft = f
            },
        )
    }

    val toDelete = confirmDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete collection?")) },
            text = {
                Text(
                    tr("Delete") + " \"" + toDelete.name + "\"? " +
                        tr("Only the collection is removed — your extensions and their catalogs stay.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { app.store.removeCollection(toDelete.id) }
                }) {
                    Text(tr("Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** One collection in the manager list: name, size, and open/edit/delete. */
@Composable
private fun CollectionListRow(
    collection: Collection,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderSummary(collection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = tr("Edit"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Delete"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "3 folders · 5 catalogs" — what the collection actually holds. */
private fun folderSummary(c: Collection): String {
    val folders = c.folders.size
    val sources = c.folders.sumOf { it.sources.size }
    val f = if (folders == 1) "folder" else "folders"
    val s = if (sources == 1) "catalog" else "catalogs"
    return "$folders $f · $sources $s"
}

/** The collection editor: name, its folders, and Save. */
@Composable
private fun CollectionEditorPage(
    collection: Collection,
    onBack: () -> Unit,
    onChange: (Collection) -> Unit,
    onAddFolder: () -> Unit,
    onEditFolder: (CollectionFolder) -> Unit,
    onDeleteFolder: (CollectionFolder) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = tr("Collection"),
            subtitle = tr("A collection holds folders; each folder holds catalogs."),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                OutlinedTextField(
                    value = collection.name,
                    onValueChange = { onChange(collection.copy(name = it)) },
                    singleLine = true,
                    label = { Text(tr("Collection name")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            item {
                Surface(
                    onClick = onAddFolder,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr("Add folder"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (collection.folders.isEmpty()) {
                item {
                    Text(
                        tr("No folders yet — add one, name it, and pick the catalogs it should show."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }
            items(collection.folders, key = { it.id }) { f ->
                FolderEditorRow(
                    folder = f,
                    onOpen = { onEditFolder(f) },
                    onDelete = { onDeleteFolder(f) },
                )
            }
            item {
                Surface(
                    onClick = onSave,
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    enabled = collection.name.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                ) {
                    Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (collection.id.isBlank()) tr("Create collection") else tr("Save"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** One folder inside the collection editor. */
@Composable
private fun FolderEditorRow(
    folder: CollectionFolder,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (folder.sources.isEmpty()) tr("No catalogs yet")
                    else folder.sources.joinToString(" · ") { it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Delete"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The folder editor: its name and the catalogs it shows. "+ TMDB" opens the
 * ready-made presets (free — no extension needed); "+ Add catalog" lists the
 * installed extensions and then their catalogs, so a folder can mix both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditorPage(
    folder: CollectionFolder,
    onBack: () -> Unit,
    onSave: (CollectionFolder) -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    var sources by remember { mutableStateOf(folder.sources) }

    var tmdbSheet by remember { mutableStateOf(false) }
    var providerSheet by remember { mutableStateOf(false) }
    var pickProvider by remember { mutableStateOf<ContentProvider?>(null) }
    var catalogs by remember { mutableStateOf<List<CatalogRef>?>(null) }

    BackHandler { onBack() }

    val picker = pickProvider
    LaunchedEffect(picker) {
        catalogs = null
        if (picker != null) {
            catalogs = withContext(Dispatchers.IO) {
                runCatching { picker.catalogs() }.getOrDefault(emptyList())
            }.distinctBy { it.type to it.id }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = tr("Folder"),
            subtitle = tr("What this folder shows."),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("Folder name")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            item {
                Row(Modifier.padding(top = 16.dp)) {
                    Surface(
                        onClick = { tmdbSheet = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("TMDB"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = { providerSheet = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Extension catalog"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    tr("Catalog sources"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp, bottom = 2.dp),
                )
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        tr("No catalogs yet — add a TMDB preset or a catalog from an installed extension."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sources, key = { it.key }) { s ->
                SourceRow(source = s, onDelete = { sources = sources.filter { it.key != s.key } })
            }
            item {
                Surface(
                    onClick = { onSave(folder.copy(name = name.trim(), sources = sources)) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    enabled = name.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                ) {
                    Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Text(
                            tr("Save"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }

    if (tmdbSheet) {
        TmdbPresetSheet(
            selected = sources.filter { it.kind == CatalogSourceKind.TMDB }
                .map { it.tmdbPreset }
                .toSet(),
            onToggle = { preset ->
                val existing = sources.firstOrNull {
                    it.kind == CatalogSourceKind.TMDB && it.tmdbPreset == preset.key
                }
                sources = if (existing != null) {
                    sources.filter { it != existing }
                } else {
                    sources + CatalogSource(
                        kind = CatalogSourceKind.TMDB,
                        title = preset.name,
                        type = preset.kind,
                        tmdbPreset = preset.key,
                    )
                }
            },
            onDismiss = { tmdbSheet = false },
        )
    }

    if (providerSheet) {
        ExtensionPickerSheet(
            onPick = { p ->
                providerSheet = false
                pickProvider = p
            },
            onDismiss = { providerSheet = false },
        )
    }

    val provider = pickProvider
    if (provider != null) {
        val loaded = catalogs
        ModalBottomSheet(onDismissRequest = { pickProvider = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    provider.config.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("Pick the catalog this folder should show."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                if (loaded == null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                } else if (loaded.isEmpty()) {
                    Text(
                        tr("This extension didn't return any browsable catalog."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                } else {
                    LazyColumn(Modifier.padding(bottom = 24.dp)) {
                        items(loaded, key = { "${it.type}|${it.id}" }) { ref ->
                            PickerLine(label = ref.name, selected = false) {
                                sources = sources + CatalogSource(
                                    kind = CatalogSourceKind.PROVIDER,
                                    title = ref.name,
                                    providerId = provider.config.id,
                                    catalogId = ref.id,
                                    type = ref.type,
                                    rawType = ref.rawType,
                                )
                                pickProvider = null
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One catalog source line inside the folder editor. */
@Composable
private fun SourceRow(source: CatalogSource, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (source.kind == CatalogSourceKind.TMDB) Icons.Filled.Movie
                else Icons.Filled.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (source.kind == CatalogSourceKind.TMDB) tr("TMDB preset")
                    else tr("From an installed extension"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = tr("Remove"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The TMDB preset picker — "Pick a ready-made source" in the reference app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TmdbPresetSheet(
    selected: Set<String>,
    onToggle: (TmdbPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("TMDB sources"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr("Pick a ready-made source. You can remove it again any time."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { SheetHeading(tr("Movie studios")) }
                items(TmdbPresets.MOVIES, key = { it.key }) { p ->
                    PickerLine(
                        label = p.name,
                        supporting = p.detail,
                        selected = p.key in selected,
                    ) { onToggle(p) }
                }
                item { SheetHeading(tr("TV networks")) }
                items(TmdbPresets.SERIES, key = { it.key }) { p ->
                    PickerLine(
                        label = p.name,
                        supporting = p.detail,
                        selected = p.key in selected,
                    ) { onToggle(p) }
                }
            }
        }
    }
}

/** Installed-extension picker, shared by the folder editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionPickerSheet(
    onPick: (ContentProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HikariApp
    val all by app.providers.providers.collectAsState()
    var query by remember { mutableStateOf("") }
    val active = remember(all, query) {
        val installed = all.filter { it.config.enabled }
        val sorted = installed.sortedBy { it.config.name.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.config.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("Choose an extension"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr("Pick the extension whose catalog you want in this folder."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(tr("Search extensions…")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (active.isEmpty()) {
                Text(
                    tr("No extensions installed yet."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                LazyColumn(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                    items(active, key = { it.config.id }) { p ->
                        PickerLine(label = p.config.name, selected = false) { onPick(p) }
                    }
                }
            }
        }
    }
}

/** One line in a picker sheet: a label, an optional caption, a check. */
@Composable
private fun PickerLine(
    label: String,
    selected: Boolean,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** A name prompt — used for a new collection and a new folder. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) {
                Text(tr("Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/**
 * A collection's own page: its folder tiles, or — once a folder is picked — the
 * catalogs inside that folder as rows, exactly like a Home shelf.
 *
 * Both shapes live in one destination ([Routes.collectionView]) because they
 * are two zoom levels of the same thing: `fid` blank shows the tiles, `fid` set
 * shows that folder. Every item opens the normal detail page, so playback is
 * the app's usual player with every installed extension searching for sources.
 */
@Composable
fun CollectionViewScreen(nav: NavHostController, collectionId: String, folderId: String) {
    val app = LocalContext.current.applicationContext as HikariApp
    var collection by remember(collectionId) { mutableStateOf<Collection?>(null) }
    var missing by remember(collectionId) { mutableStateOf(false) }

    LaunchedEffect(collectionId) {
        val c = withContext(Dispatchers.IO) { app.store.collection(collectionId) }
        collection = c
        missing = c == null
    }

    val c = collection
    if (c == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                title = if (missing) tr("Collection not found") else tr("Loading…"),
                subtitle = "",
                onBack = { nav.popBackStack() },
            )
            if (!missing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    val folder = c.folders.firstOrNull { it.id == folderId }
    if (folder == null) {
        CollectionFoldersPage(nav = nav, collection = c)
    } else {
        CollectionFolderContent(nav = nav, collection = c, folder = folder)
    }
}

/** The folder tiles of one collection. */
@Composable
private fun CollectionFoldersPage(nav: NavHostController, collection: Collection) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = collection.name,
            subtitle = folderSummary(collection),
            onBack = { nav.popBackStack() },
        )
        if (collection.folders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("This collection has no folders"),
                    subtitle = tr("Add a folder in Settings → Appearance → Collections."),
                )
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(collection.folders, key = { it.id }) { f ->
                FolderTile(folder = f) {
                    Routes.safeNavigate(
                        nav,
                        Routes.collectionView(collection.id, f.id),
                    )
                }
            }
        }
    }
}

/** One folder tile: its name and how many catalogs are in it. */
@Composable
private fun FolderTile(folder: CollectionFolder, onClick: () -> Unit) {
    val tokens = rememberGlassTokens()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tokens.fillTop)
            .border(1.dp, tokens.border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            folder.sources.firstOrNull()?.title?.let { first ->
                if (folder.sources.size > 1) "$first +${folder.sources.size - 1}"
                else first
            } ?: tr("Empty folder"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One folder's catalogs, one row each — tap an item to play it normally. */
@Composable
private fun CollectionFolderContent(
    nav: NavHostController,
    collection: Collection,
    folder: CollectionFolder,
) {
    val app = LocalContext.current.applicationContext as HikariApp
    var rows by remember(collection.id, folder.id) { mutableStateOf<List<CatalogRow>?>(null) }

    LaunchedEffect(collection.id, folder.id) {
        val repo = CollectionsRepository(app.providers)
        rows = withContext(Dispatchers.IO) {
            val loaded = runCatching { repo.folderRowsOnce(collection, folder) }.getOrDefault(emptyList())
            loaded.map { row -> row.copy(items = row.items.map { it.tokenized() }) }
        }
    }

    val breadcrumb = "${collection.name} · ${folder.name}"
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = folder.name,
            subtitle = breadcrumb,
            onBack = { nav.popBackStack() },
        )
        val loaded = rows
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (loaded.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("Nothing here right now"),
                    subtitle = tr(
                        "This folder's catalogs returned no content. Check the extension's " +
                            "site, or edit the folder to pick another catalog."
                    ),
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(loaded, key = { it.key }) { row ->
                MediaRow(
                    title = row.title,
                    providerName = row.providerName,
                    items = row.items,
                    onClick = { item ->
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        )
                    },
                    onShowAll = {
                        val source = folder.sources.firstOrNull { it.key == row.catalogId }
                        if (source?.kind == CatalogSourceKind.TMDB) {
                            Routes.safeNavigate(
                                nav,
                                Routes.tmdbGrid(source.tmdbPreset, row.title)
                            )
                        } else {
                            Routes.safeNavigate(
                                nav,
                                Routes.catalog(
                                    row.providerId, row.catalogId, row.title,
                                    row.providerName, row.type, row.rawType
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

/** Collapses a huge base64 `data:` poster into a small disk-cache token, so a
 *  folder full of such items stays inside a stock heap. See [PosterLoader]. */
private fun MediaItem.tokenized(): MediaItem {
    val p = PosterLoader.tokenize(posterUrl)
    val b = PosterLoader.tokenize(backdropUrl)
    return if (p == posterUrl && b == backdropUrl) this
    else copy(posterUrl = p, backdropUrl = b)
}

/** A plain page header with a back button — the folder pages' own title bar. */
@Composable
private fun PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
}

/**
 * One TMDB preset as a full, paged grid — the "Show All" of a preset row.
 *
 * Paging is TMDB's own (20 items a page), and the grid is deliberately the same
 * component the extension catalogs use, so a preset and an extension catalog
 * look and behave identically.
 */
class TmdbGridViewModel(
    app: Application,
    private val presetKey: String,
) : AndroidViewModel(app) {
    private val preset = TmdbPresets.byKey(presetKey)

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var page = 1

    init {
        loadNext()
    }

    fun loadNext() {
        if (_loading.value || _done.value || preset == null) return
        val work = com.hikari.app.work.BackgroundWork.begin("Loading " + preset.name)
        viewModelScope.launch {
            _loading.value = true
            val fresh = runCatching { TmdbPresets.page(preset, page) }.getOrDefault(emptyList())
            if (fresh.isEmpty()) {
                _done.value = true
            } else {
                val seen = _items.value.map { it.uniqueId }.toMutableSet()
                _items.value = _items.value + fresh.filter { seen.add(it.uniqueId) }
                page++
            }
            _loading.value = false
        }.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
    }

    fun refresh() {
        page = 1
        _items.value = emptyList()
        _done.value = false
        loadNext()
    }
}

@Composable
fun TmdbGridScreen(nav: NavHostController, presetKey: String, title: String) {
    val app = LocalContext.current.applicationContext as Application
    val vm: TmdbGridViewModel = viewModel(
        key = "tmdb-grid|$presetKey",
        factory = viewModelFactory {
            initializer { TmdbGridViewModel(app, presetKey) }
        }
    )
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val done by vm.done.collectAsState()
    val preset = remember(presetKey) { TmdbPresets.byKey(presetKey) }
    val name = title.ifBlank { preset?.name ?: presetKey }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (last >= total - 3 && !done && !loading) vm.loadNext()
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = name,
            subtitle = preset?.detail?.let { "TMDB · $it" } ?: "TMDB",
            onBack = { nav.popBackStack() },
        )
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("Nothing here right now"),
                    subtitle = tr("TMDB returned no titles for this catalog."),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.uniqueId }) { item ->
                    TmdbGridCard(item) {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
                        else if (done && items.isNotEmpty()) {
                            Text(
                                tr("That's everything"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TmdbGridCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(26.dp),
            )
            AsyncImage(
                model = Artwork.model(item),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
