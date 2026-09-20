package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.hikari.app.data.CoverKinds
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.NuvioCatalogImport
import com.hikari.app.data.TileShapes
import com.hikari.app.data.TmdbGenre
import com.hikari.app.data.TmdbGenres
import com.hikari.app.data.TmdbHit
import com.hikari.app.data.TmdbPreset
import com.hikari.app.data.TmdbPresets
import com.hikari.app.data.TmdbSort
import com.hikari.app.data.TmdbSorts
import com.hikari.app.data.TmdbSourceType
import com.hikari.app.data.TmdbSources
import com.hikari.app.data.TmdbSpec
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.shape
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

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
                        shape = GlassShape,
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
        NameCoverDialog(
            title = tr("New collection"),
            nameLabel = tr("Collection name"),
            onDismiss = { askNewCollection = false },
            onConfirm = { name, kind, value, shape ->
                askNewCollection = false
                openEditor(
                    Collection(
                        id = app.store.newId("col"),
                        name = name,
                        folders = emptyList(),
                        coverKind = kind,
                        coverValue = value,
                        tileShape = shape,
                    )
                )
            },
        )
    }

    val editingForFolder = draft
    if (askNewFolder && editingForFolder != null) {
        NameCoverDialog(
            title = tr("New folder"),
            nameLabel = tr("Folder name"),
            onDismiss = { askNewFolder = false },
            onConfirm = { name, kind, value, shape ->
                askNewFolder = false
                val f = CollectionFolder(
                    id = app.store.newId("fld"),
                    name = name,
                    coverKind = kind,
                    coverValue = value,
                    tileShape = shape,
                )
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
                    scope.launch {
                        // The cover copies this app made for the collection are
                        // not the user's files — drop them with it.
                        CollectionCovers.deleteCopy(context, toDelete.coverValue)
                        toDelete.folders.forEach {
                            CollectionCovers.deleteCopy(context, it.coverValue)
                        }
                        app.store.removeCollection(toDelete.id)
                    }
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
            CoverArt(
                kind = collection.coverKind,
                value = collection.coverValue,
                shape = collection.tileShape,
                name = collection.name,
                modifier = Modifier.size(48.dp),
                shaped = false,
                emojiSize = 22.sp,
            )
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
                    shape = GlassShape,
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
                CoverSection(
                    kind = collection.coverKind,
                    value = collection.coverValue,
                    shape = collection.tileShape,
                    onKind = { onChange(collection.copy(coverKind = it)) },
                    onValue = { onChange(collection.copy(coverValue = it)) },
                    onShape = { onChange(collection.copy(tileShape = it)) },
                    name = collection.name,
                )
            }
            item {
                Surface(
                    onClick = onAddFolder,
                    shape = GlassShape,
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
            // Folders are listed — and saved — in the order they appear on Home,
            // so their rows carry the same up/down pair the catalogs inside a
            // folder do (see [MoveButtons]).
            itemsIndexed(collection.folders, key = { _, f -> f.id }) { index, f ->
                FolderEditorRow(
                    folder = f,
                    canMoveUp = index > 0,
                    canMoveDown = index < collection.folders.lastIndex,
                    onMoveUp = {
                        onChange(collection.copy(folders = collection.folders.moveItem(index, index - 1)))
                    },
                    onMoveDown = {
                        onChange(collection.copy(folders = collection.folders.moveItem(index, index + 1)))
                    },
                    onOpen = { onEditFolder(f) },
                    onDelete = { onDeleteFolder(f) },
                )
            }
            item {
                Surface(
                    onClick = onSave,
                    shape = GlassShape,
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
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
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
            MoveButtons(
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
            )
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
    // The list this page works on. A folder can hold the same catalog twice
    // (added twice, or restored from an older save) and two identical Lazy keys
    // are a crash in Compose, so the repeats are dropped once here — from then on
    // the order of this list IS the order the user sees and the order that saves.
    var sources by remember { mutableStateOf(folder.sources.distinctBy { it.key }) }
    var coverKind by remember { mutableStateOf(folder.coverKind) }
    var coverValue by remember { mutableStateOf(folder.coverValue) }
    var tileShape by remember { mutableStateOf(folder.tileShape) }

    var tmdbSheet by remember { mutableStateOf(false) }
    var providerSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    var titleSheet by remember { mutableStateOf(false) }
    var pickProvider by remember { mutableStateOf<ContentProvider?>(null) }
    var catalogs by remember { mutableStateOf<List<CatalogRef>?>(null) }
    val app = LocalContext.current.applicationContext as HikariApp

    // ---- Long-press to pick a catalog up, drag to put it where you want ----
    // The chevrons stay (they are exact, and right for a one-step nudge), but
    // walking one catalog from the bottom of a thirty- or fifty-catalog folder
    // to the top is thirty taps. Holding a row instead lifts it — the phone
    // ticks and the row wobbles, so it is unmistakable that it is in your hand
    // — and dragging carries it past its neighbours, which move out of the way
    // as it goes; letting go drops it there.
    //
    // Long-press rather than plain drag: a plain drag on a scrolling list IS
    // the scroll gesture.
    var liftedKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Plain holders, not state: these are only ever read inside the drag
    // gesture (never during composition), and writing them from
    // onGloballyPositioned would recompose the whole page on every scroll.
    val rowCentres = remember { HashMap<String, Float>() }
    val dragY = remember { floatArrayOf(0f) }
    val listBounds = remember { floatArrayOf(0f, 0f) }
    val autoScrollEdge = with(LocalDensity.current) { 72.dp.toPx() }

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
            // The folder's search, up in the header where Home keeps its own:
            // finding ONE film or show should not mean describing a whole
            // catalog first (see TitleSearchSheet).
            actions = {
                IconButton(onClick = { titleSheet = true }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = tr("Search movies and series"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                OutlinedTextField(
                    shape = GlassShape,
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
                CoverSection(
                    kind = coverKind,
                    value = coverValue,
                    shape = tileShape,
                    onKind = { coverKind = it },
                    onValue = { coverValue = it },
                    onShape = { tileShape = it },
                    name = name,
                )
            }
            item {
                Row(Modifier.padding(top = 16.dp)) {
                    Surface(
                        onClick = { tmdbSheet = true },
                        shape = GlassShape,
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
                        shape = GlassShape,
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
            // Importing a list of titles: the third way to fill a folder, next
            // to TMDB presets and extension catalogs. See ImportListSheet.
            item {
                Surface(
                    onClick = { importSheet = true },
                    shape = GlassShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("Import a list (JSON)"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                tr("Paste a Nuvio or Stremio list, or open a .json file"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // The key carries the row's position as well as the catalog's own
            // key, so a list that somehow held the same catalog twice can never
            // crash the page (`Key "prov|cs3|…" was already used` — the crash a
            // user reported while picking catalogs in the personal catalog
            // creator). The list is deduped on load and on save, so the index is
            // only ever the second half of a key that was already unique.
            itemsIndexed(sources, key = { index, s -> "$index|" + s.key }) { index, s ->
                SourceRow(
                    source = s,
                    lifted = liftedKey == s.key,
                    canMoveUp = index > 0,
                    canMoveDown = index < sources.lastIndex,
                    onMoveUp = { sources = sources.moveItem(index, index - 1) },
                    onMoveDown = { sources = sources.moveItem(index, index + 1) },
                    onDelete = { sources = sources.filter { it.key != s.key } },
                    modifier = Modifier
                        .onGloballyPositioned { c -> rowCentres[s.key] = c.boundsInRoot().center.y }
                        .pointerInput(s.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    liftedKey = s.key
                                    dragY[0] = rowCentres[s.key] ?: 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragY[0] += amount.y
                                    val from = sources.indexOfFirst { it.key == s.key }
                                    if (from < 0) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    val to = dragTarget(sources, rowCentres, from, dragY[0])
                                    if (to != from && to in sources.indices) {
                                        sources = sources.moveItem(from, to)
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove
                                        )
                                    }
                                    val step = autoScrollStep(
                                        dragY[0],
                                        listBounds[0],
                                        listBounds[1],
                                        autoScrollEdge,
                                    )
                                    if (step != 0f) dragScope.launch { listState.scrollBy(step) }
                                },
                                onDragEnd = { liftedKey = null },
                                onDragCancel = { liftedKey = null },
                            )
                        },
                )
            }
            item {
                Surface(
                    onClick = {
                        onSave(
                            folder.copy(
                                name = name.trim(),
                                sources = sources.distinctBy { it.key },
                                coverKind = coverKind,
                                coverValue = coverValue,
                                tileShape = tileShape,
                            )
                        )
                    },
                    shape = GlassShape,
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
        TmdbSourceSheet(
            addedKeys = sources.mapTo(HashSet<String>()) { it.key },
            onPreset = { source -> sources = sources.toggleSource(source) },
            onAdd = { source ->
                if (sources.none { it.key == source.key }) sources = sources + source
                tmdbSheet = false
            },
            onDismiss = { tmdbSheet = false },
        )
    }

    if (titleSheet) {
        TitleSearchSheet(
            addedKeys = sources.mapTo(HashSet<String>()) { it.key },
            onAdd = { source -> sources = sources.toggleSource(source) },
            onDismiss = { titleSheet = false },
        )
    }

    if (importSheet) {
        ImportListSheet(
            onImport = { lists ->
                // Imported titles live in the collection itself (there is no
                // extension or server behind them), so each chosen list becomes
                // one ITEMS source with its own stable id. A list whose titles
                // are ALREADY in this folder is dropped rather than added twice.
                val existing = sources.map { it.itemsJson }.toHashSet()
                val added = lists.mapNotNull { group ->
                    val json = NuvioCatalogImport.encode(group.items)
                    if (json.isEmpty() || existing.contains(json)) return@mapNotNull null
                    CatalogSource(
                        kind = CatalogSourceKind.ITEMS,
                        title = group.name.ifBlank { I18n.t("Imported list") },
                        itemsJson = json,
                        type = if (group.items.any { it.type == MediaType.SERIES }) {
                            MediaType.SERIES
                        } else {
                            MediaType.MOVIE
                        },
                        rawType = "import",
                        uid = app.store.newId("items"),
                    )
                }
                sources = sources + added
                importSheet = false
            },
            onDismiss = { importSheet = false },
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
        val catalogSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { pickProvider = null },
            sheetState = catalogSheetState,
        ) {
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
                    tr("Tap every catalog this folder should show — tap one to add it, tap it again to take it out."),
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
                        items(loaded.distinctBy { "${it.type}|${it.id}" }, key = { "${it.type}|${it.id}" }) { ref ->
                            val source = CatalogSource(
                                kind = CatalogSourceKind.PROVIDER,
                                title = ref.name,
                                providerId = provider.config.id,
                                catalogId = ref.id,
                                type = ref.type,
                                rawType = ref.rawType,
                            )
                            PickerLine(label = ref.name, selected = sources.any { it.key == source.key }) {
                                sources = sources.toggleSource(source)
                            }
                        }
                        item { SheetDoneRow { pickProvider = null } }
                    }
                }
            }
        }
    }
}

/**
 * The up/down pair that reorders one row of a list.
 *
 * Two small chevrons rather than a drag handle: a drag has to be aimed, is
 * awkward while the page itself scrolls, and needs a long-press on a row that is
 * already a button — where a chevron is one tap, behaves the same for a
 * two-source folder as for a twenty-source one, and says by fading out at the
 * ends that there is no further to go.
 */
@Composable
private fun MoveButtons(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MoveButton(Icons.Filled.KeyboardArrowUp, tr("Move up"), canMoveUp, onMoveUp)
        MoveButton(Icons.Filled.KeyboardArrowDown, tr("Move down"), canMoveDown, onMoveDown)
    }
}

/** One chevron of [MoveButtons]; a no-op and faded when it cannot move. */
@Composable
private fun MoveButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(28.dp)
            .clip(GlassShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                else Color.Transparent
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** [this] with the item at [from] moved to [to] — what [MoveButtons] calls. */
private fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** [this] with [source] added to the end, or removed when it is already there. */
private fun List<CatalogSource>.toggleSource(source: CatalogSource): List<CatalogSource> =
    if (any { it.key == source.key }) filterNot { it.key == source.key } else this + source

/**
 * One catalog source line inside the folder editor.
 *
 * The up/down pair reorders this folder's catalogs, and a folder's row on Home
 * shows its sources in exactly this order (see CollectionsRepository), so moving
 * HBO above Netflix here is what puts it first there. A newly added source lands
 * at the bottom, which is why the pair is how a source ends up where the user
 * wants it rather than only where it happened to land.
 */
@Composable
private fun SourceRow(
    source: CatalogSource,
    onDelete: () -> Unit,
    lifted: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A short wobble the moment the row is picked up — with the haptic tick and
    // the lift below it, that is what says "this catalog is in your hand now",
    // instead of leaving the user to guess whether the long press took.
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(lifted) {
        wobble.snapTo(0f)
        if (!lifted) return@LaunchedEffect
        wobble.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 300
                -3f at 40
                3f at 120
                -2f at 190
                1f at 245
            },
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .offset { IntOffset(wobble.value.roundToInt(), 0) }
            .then(
                if (lifted) Modifier.graphicsLayer {
                    scaleX = 1.03f
                    scaleY = 1.03f
                } else Modifier
            ),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (source.kind) {
                        CatalogSourceKind.TMDB -> Icons.Filled.Movie
                        CatalogSourceKind.ITEMS -> Icons.Filled.List
                        CatalogSourceKind.PROVIDER -> Icons.Filled.Tv
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.title.ifBlank { tr("Imported list") },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (source.kind) {
                            CatalogSourceKind.TMDB ->
                                source.spec?.let { "TMDB · " + TmdbSources.detail(it) }
                                    ?: tr("TMDB preset")
                            CatalogSourceKind.ITEMS ->
                                tr("Imported list") + " · " + source.itemCount + " " + tr("titles")
                            CatalogSourceKind.PROVIDER -> tr("From an installed extension")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoveButtons(
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
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
        // The row in your hand, ringed in the accent colour. Drawn over the card
        // (not passed to GlassCard) because a border in the card's own modifier
        // chain is painted UNDER its fill and would never be seen.
        if (lifted) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(GlassShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, GlassShape),
            )
        }
    }
}

/**
 * Where the row in hand should move to on this frame: one step up or down the
 * moment the finger passes the neighbouring row's centre, otherwise nowhere.
 *
 * Stepping (rather than computing a final slot from the drag distance) is what
 * makes the rows swap under the finger, and it only ever asks about the two
 * neighbours — which are on screen beside the row in hand — so it cannot be
 * confused by the rows that scrolled out of view. Each row's own centre is the
 * measure, not a fixed row height: a TMDB preset's row is taller than one whose
 * title is one short line.
 */
private fun dragTarget(
    sources: List<CatalogSource>,
    centres: Map<String, Float>,
    from: Int,
    pointerY: Float,
): Int {
    sources.getOrNull(from + 1)?.let { next ->
        centres[next.key]?.let { if (pointerY > it) return from + 1 }
    }
    sources.getOrNull(from - 1)?.let { prev ->
        centres[prev.key]?.let { if (pointerY < it) return from - 1 }
    }
    return from
}

/**
 * How far the list should scroll while the finger is held near its top or
 * bottom edge — `0` when it is not.
 *
 * Dragging the last catalog of a fifty-row folder up to the first slot means
 * going further than the screen is tall, so the list has to follow the finger.
 */
private fun autoScrollStep(pointerY: Float, top: Float, bottom: Float, edge: Float): Float =
    when {
        bottom <= top -> 0f
        pointerY < top + edge -> -14f
        pointerY > bottom - edge -> 14f
        else -> 0f
    }

/**
 * The "TMDB sources" editor — the reference client's source-type chips
 * (Presets, Public list, Production, Network, Collection, Person, Director,
 * Custom) plus the small form that turns an id, a name or a themoviedb.org URL
 * into a saved [CatalogSource].
 *
 * What a saved source carries is always a plain numeric TMDB id (plus its kind,
 * media and order), so a row keeps working even if TMDB renames a studio — and
 * so the language the app is set to is the only thing that decides which
 * language the row's own items are titled in.
 *
 * A preset is one tap, and the sheet stays open so several can be added in a
 * row: a preset already in the folder is ticked, and tapping it again takes it
 * back out. The hand-built form is still one source and then the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TmdbSourceSheet(
    addedKeys: Set<String>,
    onPreset: (CatalogSource) -> Unit,
    onAdd: (CatalogSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(TmdbSourceType.PRESET) }
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<TmdbHit?>(null) }
    var displayTitle by remember { mutableStateOf("") }
    var media by remember { mutableStateOf("movie") }
    var sort by remember { mutableStateOf("popularity.desc") }
    var genre by remember { mutableStateOf(0) }
    var year by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TmdbHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun sourcesFor(name: String, spec: TmdbSpec): CatalogSource = CatalogSource(
        kind = CatalogSourceKind.TMDB,
        title = name,
        type = spec.kind,
        tmdbSpec = spec.encode(),
    )

    fun presetSource(p: TmdbPreset): CatalogSource = CatalogSource(
        kind = CatalogSourceKind.TMDB,
        title = p.name,
        type = p.kind,
        tmdbPreset = p.key,
    )

    fun resetFor(t: TmdbSourceType) {
        type = t
        picked = null
        hits = emptyList()
        searched = false
        genre = 0
        year = ""
        sort = "popularity.desc"
        media = when {
            t.forcesTv -> "tv"
            t == TmdbSourceType.COLLECTION -> "movie"
            // A one-title source takes its shape from the title the user
            // picks, not from a chip.
            t == TmdbSourceType.TITLE -> "all"
            t.isPerson || t == TmdbSourceType.LIST -> "all"
            else -> "movie"
        }
    }

    val typedId = TmdbSources.numericId(text)
    val canSearch = type != TmdbSourceType.DISCOVER && type != TmdbSourceType.PRESET
    val canAdd = type == TmdbSourceType.PRESET ||
        type == TmdbSourceType.DISCOVER || picked != null || typedId != null

    fun runSearch() {
        if (text.isBlank() || searching) return
        searching = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { TmdbSources.search(type, text) }
            hits = found
            searched = true
            searching = false
            if (found.size == 1) picked = found.first()
        }
    }

    fun add() {
        if (saving) return
        val choice = picked
        val spec = TmdbSpec(
            type = type,
            id = choice?.id ?: typedId.orEmpty(),
            // The picked title knows whether it is a film or a show — that is
            // what the media chip cannot say for a one-title source.
            media = if (type == TmdbSourceType.TITLE && !choice?.media.isNullOrBlank()) {
                choice.media
            } else {
                media
            },
            sort = if (type == TmdbSourceType.DISCOVER || type == TmdbSourceType.COMPANY ||
                type == TmdbSourceType.NETWORK
            ) sort else "popularity.desc",
            genre = if (type == TmdbSourceType.DISCOVER) genre else 0,
            year = if (type == TmdbSourceType.DISCOVER) year.toIntOrNull() ?: 0 else 0,
            title = displayTitle.trim(),
        )
        val given = displayTitle.trim()
        val known = choice?.name?.takeIf { it.isNotBlank() }
        val name = given.ifBlank { known.orEmpty() }
        if (name.isNotBlank()) {
            onAdd(sourcesFor(name, spec))
            return
        }
        // Nothing to title the row with, so ask TMDB for the entity's own name.
        saving = true
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { TmdbSources.displayName(spec) }
            saving = false
            onAdd(sourcesFor(resolved, spec))
        }
    }

    val hint = when (type) {
        TmdbSourceType.TITLE -> tr("A film or show name, or a TMDB id.")
        TmdbSourceType.LIST -> tr("A list id, or a themoviedb.org/list link.")
        TmdbSourceType.COMPANY -> tr("Marvel Studios, 420, or a company link.")
        TmdbSourceType.NETWORK -> tr("213 for Netflix, 49 for HBO, 2739 for Disney+.")
        TmdbSourceType.COLLECTION -> tr("10 for Star Wars Collection, or a collection link.")
        TmdbSourceType.PERSON -> tr("31 for Tom Hanks, or a person link.")
        TmdbSourceType.DIRECTOR -> tr("525 for Christopher Nolan, or a person link.")
        TmdbSourceType.DISCOVER -> tr("No id needed — narrow it down with genre, year and order.")
        TmdbSourceType.PRESET -> ""
    }
    val fieldLabel = when (type) {
        TmdbSourceType.TITLE -> tr("Film or show name")
        TmdbSourceType.LIST -> tr("List id or link")
        TmdbSourceType.COMPANY -> tr("Studio name or id")
        TmdbSourceType.NETWORK -> tr("Network name or id")
        TmdbSourceType.COLLECTION -> tr("Collection name or id")
        TmdbSourceType.PERSON -> tr("Actor name or id")
        TmdbSourceType.DIRECTOR -> tr("Director name or id")
        else -> tr("Name or id")
    }
    val mediaOptions: List<Pair<String, String>> = when {
        type.forcesTv -> listOf("tv" to tr("Series"))
        type == TmdbSourceType.COLLECTION -> listOf("movie" to tr("Movies"))
        // A one-title source is filtered by the title itself, so there is
        // nothing to choose here — the search result decides.
        type == TmdbSourceType.TITLE -> emptyList()
        type.isPerson || type == TmdbSourceType.LIST ->
            listOf("all" to tr("Both"), "movie" to tr("Movies"), "tv" to tr("Series"))
        else -> listOf("movie" to tr("Movies"), "tv" to tr("Series"))
    }
    val sortable = type == TmdbSourceType.DISCOVER || type == TmdbSourceType.COMPANY ||
        type == TmdbSourceType.NETWORK

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
                tr("Tap a ready-made source to add it — tap several in a row — or build one from a TMDB id, a name or a link."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            ChipRow(Modifier.padding(top = 12.dp)) {
                TmdbSourceType.entries.forEach { t ->
                    ChoiceChip(
                        label = tr(t.label),
                        selected = t == type,
                        onClick = { if (t != type) resetFor(t) },
                    )
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            ) {
                if (type == TmdbSourceType.PRESET) {
                    item { SheetHeading(tr("Movie studios")) }
                    items(TmdbPresets.MOVIES, key = { it.key }) { p ->
                        val source = presetSource(p)
                        PickerLine(
                            label = p.name,
                            supporting = p.detail,
                            selected = addedKeys.contains(source.key),
                        ) { onPreset(source) }
                    }
                    item { SheetHeading(tr("TV networks")) }
                    items(TmdbPresets.SERIES, key = { it.key }) { p ->
                        val source = presetSource(p)
                        PickerLine(
                            label = p.name,
                            supporting = p.detail,
                            selected = addedKeys.contains(source.key),
                        ) { onPreset(source) }
                    }
                    item { SheetDoneRow(onDismiss) }
                    return@LazyColumn
                }

                item {
                    OutlinedTextField(
                        shape = GlassShape,
                        value = text,
                        onValueChange = {
                            text = it
                            picked = null
                            hits = emptyList()
                            searched = false
                        },
                        singleLine = true,
                        label = { Text(fieldLabel) },
                        placeholder = { Text(hint) },
                        trailingIcon = if (canSearch) {
                            {
                                IconButton(onClick = { runSearch() }) {
                                    if (searching) {
                                        CircularProgressIndicator(Modifier.size(18.dp))
                                    } else {
                                        Icon(Icons.Filled.Search, contentDescription = tr("Search"))
                                    }
                                }
                            }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
                if (canSearch && searched && hits.isEmpty() && !searching) {
                    item {
                        Text(
                            tr("Nothing found. Try the numeric TMDB id (or paste the link)."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(hits.distinctBy { it.id + "|" + it.media }, key = { it.id + "|" + it.media }) { hit ->
                    PickerLine(
                        label = hit.name,
                        supporting = hit.subtitle.ifBlank { "ID ${hit.id}" },
                        selected = picked?.id == hit.id && picked?.media == hit.media,
                    ) { picked = hit }
                }

                if (mediaOptions.isNotEmpty()) {
                    item {
                        SheetHeading(
                            if (mediaOptions.size == 1) tr("Media") else tr("Movies or series?")
                        )
                    }
                    item {
                        ChipRow {
                            mediaOptions.forEach { (key, label) ->
                                ChoiceChip(label = label, selected = media == key) { media = key }
                            }
                        }
                    }
                }

                if (sortable) {
                    item { SheetHeading(tr("Order")) }
                    item {
                        ChipRow {
                            TmdbSorts.forMedia(media).forEach { s ->
                                ChoiceChip(label = tr(s.label), selected = sort == s.key) {
                                    sort = s.key
                                }
                            }
                        }
                    }
                }

                if (type == TmdbSourceType.DISCOVER) {
                    item { SheetHeading(tr("Genre")) }
                    item {
                        ChipRow {
                            ChoiceChip(label = tr("Any"), selected = genre == 0) { genre = 0 }
                            TmdbGenres.forMedia(media).forEach { g ->
                                ChoiceChip(label = tr(g.name), selected = genre == g.id) {
                                    genre = g.id
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            shape = GlassShape,
                            value = year,
                            onValueChange = { v -> year = v.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            label = { Text(tr("Year")) },
                            placeholder = { Text(tr("Any year")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        shape = GlassShape,
                        value = displayTitle,
                        onValueChange = { displayTitle = it },
                        singleLine = true,
                        label = { Text(tr("Display title")) },
                        supportingText = {
                            Text(tr("Shown as the row name. Leave blank and Hikari names it from TMDB."))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }

                item {
                    val enabled = canAdd && !saving
                    Surface(
                        onClick = { add() },
                        shape = GlassShape,
                        color = MaterialTheme.colorScheme.primary,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                    ) {
                        Box(
                            Modifier.padding(vertical = 15.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(
                                    tr("Add source"),
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
    }
}

/**
 * "Find a title and add it" — the search the folder editor's header carries.
 *
 * Building a personal catalog used to mean describing a CATALOG (a studio, a
 * network, a list, a director) and only then hunting for the one film you
 * actually wanted inside it: there was a search, but it lived three taps deep
 * inside the TMDB source form, under a type chip. This is the same idea the Home
 * tab's header already offers — a search button, up front — except that what it
 * finds is a single title, and picking one adds exactly that title to the folder
 * as a one-title source (see [TmdbSourceType.TITLE]).
 *
 * Several can be added in a row: the sheet stays open, a title already in the
 * folder is ticked, and tapping it again takes it back out — the same rules the
 * preset list follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleSearchSheet(
    addedKeys: Set<String>,
    onAdd: (CatalogSource) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TmdbHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var lastAsked by remember { mutableStateOf("") }

    /** The one-title source a search hit becomes. [TmdbSpec.identity] ignores the
     *  display title, so the key is stable and a title cannot be added twice. */
    fun sourceFor(hit: TmdbHit): CatalogSource = CatalogSource(
        kind = CatalogSourceKind.TMDB,
        title = hit.name,
        type = if (hit.media == "tv") MediaType.SERIES else MediaType.MOVIE,
        tmdbSpec = TmdbSpec(
            type = TmdbSourceType.TITLE,
            id = hit.id,
            media = hit.media.ifBlank { "movie" },
        ).encode(),
    )

    // Typing searches: a title search is cheap (one TMDB request) and the whole
    // point of the sheet is that the answer appears as you narrow the name. The
    // delay keeps a burst of keystrokes to one request.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            hits = emptyList()
            searched = false
            lastAsked = ""
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        searching = true
        val found = withContext(Dispatchers.IO) {
            runCatching { TmdbSources.search(TmdbSourceType.TITLE, q) }.getOrDefault(emptyList())
        }
        hits = found
        lastAsked = q
        searched = true
        searching = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("Add a movie or series"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr("Search for a title and add just that one to this folder."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedTextField(
                shape = GlassShape,
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(tr("Film or show name")) },
                placeholder = { Text(tr("Avengers: Endgame")) },
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = tr("Search"))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
            ) {
                if (hits.isEmpty()) {
                    item {
                        Text(
                            when {
                                searching -> tr("Searching TMDB…")
                                searched && lastAsked.isNotBlank() ->
                                    I18n.t("Nothing on TMDB matched \"$lastAsked\".")
                                else -> tr("Type at least two letters to search.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(hits, key = { it.id + "|" + it.media }) { hit ->
                    val source = sourceFor(hit)
                    val isAdded = addedKeys.contains(source.key)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onAdd(source) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(66.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hit.posterUrl.isNotBlank()) {
                                AsyncImage(
                                    model = PosterLoader.model(hit.posterUrl),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    if (hit.media == "tv") Icons.Filled.Tv else Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                hit.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    hit.subtitle.takeIf { it.isNotBlank() },
                                    if (isAdded) tr("In this folder") else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAdded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Icon(
                            if (isAdded) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (isAdded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                item { SheetDoneRow(onDismiss) }
            }
        }
    }
}

/** A horizontally scrolling strip of pills — the form's one-line choice rows. */
@Composable
private fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One pill in a [ChipRow]. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = GlassShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * Imports a list of titles from JSON (a Nuvio/Stremio export, a file someone
 * shared, a catalog response copied out of a browser) as one or more folder
 * sources.
 *
 * The sheet is deliberately two-step: paste (or open a file), then LOOK at what
 * was found before anything is added. JSON exports vary wildly — one file can
 * hold four named catalogs — so the user picks which of the lists inside it to
 * bring in, each shown with its name and how many titles it actually yielded.
 * Nothing is added until they tap the button, and a file that yields nothing is
 * reported in place rather than silently ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportListSheet(
    onImport: (List<NuvioCatalogImport.ImportedList>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The JSON itself, and what the text field shows: a file's contents can be
    // megabytes, and putting that in a TextField would stall the sheet, so a
    // loaded file is summarised in the field and kept here.
    var body by remember { mutableStateOf("") }
    var fieldLabel by remember { mutableStateOf("") }
    var lists by remember { mutableStateOf<List<NuvioCatalogImport.ImportedList>?>(null) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun read(source: String) {
        val found = NuvioCatalogImport.parse(source)
        lists = found
        selected = found.indices.filter { found[it].items.isNotEmpty() }.toSet()
        // I18n.t, not tr(): this runs from a click/file-picker callback, which
        // is outside composition, where the composable tr() cannot be called.
        message = when {
            found.isEmpty() -> I18n.t(
                "No titles found. Paste the JSON exactly as you got it — an array of " +
                    "titles, or an object with items/metas/catalogs inside."
            )
            found.all { it.items.isEmpty() } -> I18n.t(
                "This file describes catalogs but carries no titles of its own, so there " +
                    "is nothing to save."
            )
            else -> ""
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
            busy = false
            if (text.isNullOrBlank()) {
                message = I18n.t("Couldn't read that file.")
                return@launch
            }
            body = text
            fieldLabel = uri.lastPathSegment?.substringAfterLast('/') ?: I18n.t("File loaded")
            read(text)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("Import a list of titles"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr(
                    "Paste a Nuvio or Stremio list, a catalog response, or any JSON array of " +
                        "titles. The names are matched on TMDB, so they get posters, details " +
                        "and sources like every other title."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                shape = GlassShape,
                value = fieldLabel.ifBlank { body },
                onValueChange = {
                    fieldLabel = ""
                    body = it
                    lists = null
                    message = ""
                },
                label = { Text(tr("JSON")) },
                minLines = 3,
                maxLines = 7,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 220.dp),
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val clip = cm?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (clip.isNullOrBlank()) {
                            message = I18n.t("The clipboard is empty.")
                        } else {
                            body = clip
                            fieldLabel = ""
                            read(clip)
                        }
                    },
                    shape = GlassShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Text(
                        tr("Paste"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
                Surface(
                    onClick = {
                        runCatching { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    },
                    shape = GlassShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Open file"), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (busy) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            val found = lists
            if (found != null && found.any { it.items.isNotEmpty() }) {
                Text(
                    tr("Found in this file"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                found.forEachIndexed { i, group ->
                    if (group.items.isEmpty()) return@forEachIndexed
                    Surface(
                        onClick = {
                            selected = if (selected.contains(i)) selected - i else selected + i
                        },
                        shape = GlassShape,
                        color = if (selected.contains(i)) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected.contains(i)) Icons.Filled.Check else Icons.Filled.List,
                                contentDescription = null,
                                tint = if (selected.contains(i)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    group.name.ifBlank { tr("Imported list") },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    group.items.size.toString() + " " + tr("titles"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            val chosen = found?.let { list ->
                list.filterIndexed { i, g -> g.items.isNotEmpty() && selected.contains(i) }
            }.orEmpty()
            Surface(
                onClick = {
                    if (chosen.isNotEmpty()) onImport(chosen)
                },
                shape = GlassShape,
                color = MaterialTheme.colorScheme.primary,
                enabled = chosen.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 28.dp),
            ) {
                Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (chosen.isEmpty()) tr("Add to folder") else
                            tr("Add") + " " + chosen.size + " " + tr("to folder"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                shape = GlassShape,
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

/**
 * One line in a picker sheet: a label, an optional caption, a check. Flat, with
 * a hairline under it — the same minimal list the Home extension picker uses
 * (a card per row turned a long list of near-identical names into a wall).
 */
@Composable
private fun PickerLine(
    label: String,
    selected: Boolean,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(GlassShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
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
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 10.dp),
        )
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

/**
 * The row that closes a multi-select sheet. A picker that keeps itself open has
 * no "tap one and it's gone" moment to tell you it is finished, so it says it
 * outright instead of leaving the user to guess at the scrim.
 */
@Composable
private fun SheetDoneRow(onDone: () -> Unit) {
    Surface(
        onClick = onDone,
        shape = GlassShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 10.dp),
    ) {
        Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                tr("Done"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * Covers for collections and folders.
 *
 * A cover is one of: nothing (the folder glyph), an emoji, an image URL, or an
 * animated GIF URL — the four choices of the reference client's "Edit Folder"
 * screen. The value lives in [com.hikari.app.data.Collection] /
 * [com.hikari.app.data.CollectionFolder].
 */
private object CollectionCovers {

    private fun dir(context: Context): File =
        File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /**
     * Copies a picked gallery image into the app's own storage and returns the
     * `file://` URI to store.
     *
     * The copy is the point: a `content://` URI from the photo picker is a
     * grant that dies with the process, so storing the URI directly is how a
     * cover becomes a blank tile after the next launch.
     */
    fun copyFromUri(context: Context, uri: Uri): String? = runCatching {
        val name = "cover-" + System.currentTimeMillis().toString(36) +
            "-" + (1000 + (Math.random() * 8999).toInt())
        val file = File(dir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (file.length() <= 0L) {
            file.delete()
            return null
        }
        Uri.fromFile(file).toString()
    }.getOrNull()

    /** Deletes a copy [copyFromUri] made, when the cover it belonged to is
     *  replaced or thrown away. Anything that is not one of our own files is
     *  left alone (an https:// URL is the user's, not ours). */
    fun deleteCopy(context: Context, value: String) {
        if (!value.startsWith("file:")) return
        runCatching {
            val path = Uri.parse(value).path ?: return
            val file = File(path)
            if (file.absolutePath.startsWith(dir(context).absolutePath)) file.delete()
        }
    }

    /** Longest side a cover is decoded/cropped at. A cover is drawn at most a
     *  few hundred dp wide, so a full 12 MP camera shot is decoded at a quarter
     *  of its pixels: the crop editor stays smooth and the saved file stays
     *  small, while the tile it ends up in cannot tell the difference. */
    private const val MAX_SIDE = 2048

    /**
     * The image behind a cover value, ready to be cropped: our own `file:` copy
     * read straight off disk, or an `https://` link fetched over the app's own
     * HTTP client (the site a cover points at needs a real user agent, which a
     * bare URL stream does not send).
     *
     * The EXIF orientation is applied too. A phone photo is usually stored
     * sideways with a "rotate me" flag that Coil honours and [BitmapFactory]
     * does not — without this step, cropping a gallery photo produced a cover
     * lying on its side, which is exactly the kind of thing that looks like the
     * app broke.
     */
    suspend fun loadBitmap(context: Context, value: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes: ByteArray = when {
                    value.startsWith("file:") -> {
                        val path = Uri.parse(value).path ?: return@runCatching null
                        File(path).readBytes()
                    }
                    value.startsWith("http") -> Http.getBytes(value) ?: return@runCatching null
                    else -> return@runCatching null
                }
                if (bytes.isEmpty()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_SIDE ||
                    bounds.outHeight / (sample * 2) >= MAX_SIDE
                ) {
                    sample *= 2
                }
                val decoded = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@runCatching null
                applyExifRotation(bytes, decoded)
            }.getOrNull()
        }

    /** [bmp] turned the way the file's EXIF orientation says it should be. */
    private fun applyExifRotation(bytes: ByteArray, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            android.media.ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }.getOrDefault(bmp)
    }

    /**
     * Writes a cropped cover into our own storage and returns its `file://` URI.
     *
     * The crop is a NEW file rather than a rewrite of the picked one: the
     * original copy is deleted by the caller once the new value is in place, and
     * keeping the write separate means a failure here cannot leave the cover
     * pointing at a half-written file.
     */
    fun saveCropped(context: Context, bitmap: Bitmap): String? = runCatching {
        val name = "cover-" + System.currentTimeMillis().toString(36) +
            "-" + (1000 + (Math.random() * 8999).toInt())
        val file = File(dir(context), name)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (file.length() <= 0L) {
            file.delete()
            return null
        }
        Uri.fromFile(file).toString()
    }.getOrNull()
}

/** The cover choices, in the reference design's order. */
private val COVER_CHOICES: List<Pair<String, String>>
    get() = listOf(
        CoverKinds.NONE to "None",
        CoverKinds.EMOJI to "Emoji",
        CoverKinds.URL to "Image URL",
        CoverKinds.GIF to "Animated GIF URL",
    )

/** A handful of one-tap covers for the Emoji choice. */
private val COVER_EMOJIS = listOf("🎬", "📺", "🍿", "🎭", "🔥", "⭐", "🌙", "🎞️")

/**
 * A collection/folder cover, drawn into the tile shape the user picked: a
 * poster (2:3), a square, or a wide 16:9 tile. With no cover configured it
 * falls back to the folder glyph, so a tile always has something in it.
 */
@Composable
private fun CoverArt(
    kind: String,
    value: String,
    shape: String,
    name: String = "",
    modifier: Modifier = Modifier,
    /** False when the caller has already fixed the size (a list-row thumb):
     *  the tile then fills it instead of imposing its own aspect. */ 
    shaped: Boolean = true,
    emojiSize: TextUnit = 34.sp,
) {
    val tokens = rememberGlassTokens()
    val k = CoverKinds.normalize(kind)
    // The user's own poster styling — Settings → App Layout → Poster styling —
    // applied to a personal-catalog cover exactly as it is to a TMDB poster:
    // the corner rounding, the halo, and the chosen effect (aura ring, 3D tilt,
    // sheen…). A cover the user picked is still a poster, and asking for the
    // aura ring while browsing and then getting a plain rectangle inside their
    // own catalog was the report. The effect is drawn by [PosterArt], the one
    // place poster styling lives.
    val posterStyle = rememberPosterStyle()
    val sized = if (shaped) Modifier.aspectRatio(TileShapes.aspect(shape)) else Modifier
    if ((k == CoverKinds.URL || k == CoverKinds.GIF) && value.isNotBlank()) {
        PosterArt(
            model = value,
            contentDescription = name.ifBlank { null },
            style = posterStyle,
            modifier = modifier.then(sized),
            imageAlignment = Alignment.TopCenter,
        )
        return
    }
    Box(
        modifier
            .then(sized)
            .clip(GlassShape)
            .background(tokens.fillTop)
            .border(1.dp, tokens.border, GlassShape),
        contentAlignment = Alignment.Center,
    ) {
        if (k == CoverKinds.EMOJI && value.isNotBlank()) {
            Text(value, fontSize = emojiSize)
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(GlassShape)
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
        }
    }
}

/**
 * Crops the cover image the user is adding.
 *
 * A cover tile is a fixed shape — a 2:3 poster, a square, a 16:9 wide tile —
 * and a photo straight out of the gallery almost never matches it: a portrait
 * shot in a wide tile loses most of itself, and there was nothing the user could
 * do about it (the report: "there is no crop button so if the image is long the
 * user is unable to adjust it"). So the picked image opens here at the exact
 * shape of the tile it is going into, and the user drags and pinches until the
 * part they want fills that shape; what they see is exactly what the tile will
 * show, because the crop is the viewport itself.
 *
 * The maths is deliberately exact rather than approximate: the preview draws the
 * bitmap with one scale and one offset, and the saved crop is derived from the
 * SAME scale and offset, so there is no way for the two to disagree about which
 * pixels were kept. Zooming never lets the frame show empty space (the scale
 * starts at "cover the frame" and only ever grows, and the drag is clamped to
 * the image's edges).
 */
@Composable
private fun CoverCropDialog(
    value: String,
    shape: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aspect = TileShapes.aspect(shape)
    // Resolved up here, not inside the coroutine that saves the crop: `tr` is a
    // @Composable lookup and cannot be called from a coroutine body.
    val croppedToast = tr("Cover cropped")
    val croppedFailedToast = tr("Couldn't save the cropped cover.")
    var bitmap by remember(value) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(value) { mutableStateOf(false) }
    var loading by remember(value) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    LaunchedEffect(value) {
        loading = true
        val bmp = CollectionCovers.loadBitmap(context, value)
        bitmap = bmp
        failed = bmp == null
        loading = false
    }

    // The scale at which the image first exactly covers the frame. Everything
    // else is expressed relative to it, so a rotation/zoom gesture only ever
    // moves the zoom factor in [1, 6].
    fun baseScale(vp: IntSize, bmp: Bitmap): Float {
        if (vp.width == 0 || vp.height == 0) return 1f
        return maxOf(
            vp.width.toFloat() / bmp.width.toFloat(),
            vp.height.toFloat() / bmp.height.toFloat(),
        )
    }

    // How far the image may be dragged before an edge would come into view.
    fun limits(vp: IntSize, bmp: Bitmap, z: Float): Pair<Float, Float> {
        val s = baseScale(vp, bmp) * z
        return maxOf(0f, (bmp.width * s - vp.width) / 2f) to
            maxOf(0f, (bmp.height * s - vp.height) / 2f)
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = GlassShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(18.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    tr("Crop cover"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Drag to move, pinch or use the slider to zoom. The tile shows exactly this."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(GlassShape)
                        .background(Color.Black)
                        .onSizeChanged { viewport = it }
                        .pointerInput(bitmap, viewport) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val bmp = bitmap ?: return@detectTransformGestures
                                if (viewport == IntSize.Zero) return@detectTransformGestures
                                val next = (zoom * gestureZoom).coerceIn(1f, 6f)
                                val (mx, my) = limits(viewport, bmp, next)
                                offX = (offX + pan.x).coerceIn(-mx, mx)
                                offY = (offY + pan.y).coerceIn(-my, my)
                                zoom = next
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val s = baseScale(viewport, bmp) * zoom
                            val cx = viewport.width / 2f + offX
                            val cy = viewport.height / 2f + offY
                            withTransform({
                                translate(
                                    left = cx - bmp.width * s / 2f,
                                    top = cy - bmp.height * s / 2f,
                                )
                                scale(scaleX = s, scaleY = s, pivot = Offset.Zero)
                            }) {
                                image?.let {
                                    drawImage(it, filterQuality = FilterQuality.Medium)
                                }
                            }
                        }
                    }
                    when {
                        loading -> CircularProgressIndicator()
                        failed -> Text(
                            tr("That image couldn't be loaded."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Zoom"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = zoom,
                        onValueChange = { z ->
                            val bmp = bitmap
                            if (bmp != null) {
                                val next = z.coerceIn(1f, 6f)
                                val (mx, my) = limits(viewport, bmp, next)
                                offX = offX.coerceIn(-mx, mx)
                                offY = offY.coerceIn(-my, my)
                                zoom = next
                            }
                        },
                        valueRange = 1f..6f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                    TextButton(
                        onClick = {
                            zoom = 1f
                            offX = 0f
                            offY = 0f
                        },
                    ) {
                        Text(tr("Reset"))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !saving, onClick = onDismiss) { Text(tr("Cancel")) }
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = bitmap != null && !saving,
                        onClick = {
                            val bmp = bitmap
                            if (bmp != null && viewport != IntSize.Zero) {
                            saving = true
                            scope.launch {
                                val s = baseScale(viewport, bmp) * zoom
                                val cx = viewport.width / 2f + offX
                                val cy = viewport.height / 2f + offY
                                // Viewport -> bitmap pixels (the inverse of the
                                // preview's transform, see [Canvas] above).
                                val left = ((0f - cx + bmp.width * s / 2f) / s)
                                    .coerceIn(0f, (bmp.width - 1).toFloat())
                                val top = ((0f - cy + bmp.height * s / 2f) / s)
                                    .coerceIn(0f, (bmp.height - 1).toFloat())
                                val right = ((viewport.width - cx + bmp.width * s / 2f) / s)
                                    .coerceIn(left + 1f, bmp.width.toFloat())
                                val bottom = ((viewport.height - cy + bmp.height * s / 2f) / s)
                                    .coerceIn(top + 1f, bmp.height.toFloat())
                                val w = (right - left).toInt().coerceAtLeast(1)
                                val h = (bottom - top).toInt().coerceAtLeast(1)
                                val cropped = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Bitmap.createBitmap(bmp, left.toInt(), top.toInt(), w, h)
                                    }.getOrNull()
                                }
                                val uri = cropped?.let {
                                    withContext(Dispatchers.IO) {
                                        CollectionCovers.saveCropped(context, it)
                                    }
                                }
                                saving = false
                                if (uri != null) {
                                    Toast.makeText(context, croppedToast, Toast.LENGTH_SHORT).show()
                                    onDone(uri)
                                } else {
                                    Toast.makeText(context, croppedFailedToast, Toast.LENGTH_LONG).show()
                                }
                            }
                            }
                        },
                    ) {
                        Text(tr("Crop & save"))
                    }
                }
            }
        }
    }
}

/**
 * The "Appearance" half of a collection/folder form: the cover choice, its
 * input (a text field, an emoji strip, or a gallery pick), a live preview, and
 * the shape of the tile. Shared by the editor pages and the create dialogs so
 * creating something and editing it later offer exactly the same options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSection(
    kind: String,
    value: String,
    shape: String,
    onKind: (String) -> Unit,
    onValue: (String) -> Unit,
    onShape: (String) -> Unit,
    name: String = "",
) {
    val context = LocalContext.current
    val k = CoverKinds.normalize(kind)
    var cropOpen by remember { mutableStateOf(false) }

    // "Choose from storage": the photo picker (no storage permission needed on
    // any supported Android version), copied into the app before it can go away.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val stored = CollectionCovers.copyFromUri(context, uri)
        if (stored != null) {
            CollectionCovers.deleteCopy(context, value)
            onKind(CoverKinds.URL)
            onValue(stored)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            tr("Cover"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        ChipRow {
            COVER_CHOICES.forEach { (key, label) ->
                ChoiceChip(label = tr(label), selected = k == key) { onKind(key) }
            }
        }
        when (k) {
            CoverKinds.EMOJI -> {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    COVER_EMOJIS.forEach { e ->
                        Surface(
                            onClick = { onValue(e) },
                            shape = GlassShape,
                            color = if (value == e) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                            },
                        ) {
                            Text(
                                e,
                                fontSize = 22.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    shape = GlassShape,
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    label = { Text(tr("Or type/paste an emoji")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CoverKinds.URL, CoverKinds.GIF -> {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    shape = GlassShape,
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    label = {
                        Text(
                            if (k == CoverKinds.GIF) tr("Animated GIF link (https://…)")
                            else tr("Image link (https://…)")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    shape = GlassShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Choose from storage"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (k == CoverKinds.GIF) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("A GIF plays while its tile is on screen; otherwise the first frame is shown."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { cropOpen = true },
                        shape = GlassShape,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Crop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Crop & zoom"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            else -> Unit
        }

        Spacer(Modifier.height(12.dp))
        Text(
            tr("Tile shape"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        ChipRow {
            ChoiceChip(tr("Poster"), TileShapes.normalize(shape) == TileShapes.POSTER) {
                onShape(TileShapes.POSTER)
            }
            ChoiceChip(tr("Square"), TileShapes.normalize(shape) == TileShapes.SQUARE) {
                onShape(TileShapes.SQUARE)
            }
            ChoiceChip(tr("Wide"), TileShapes.normalize(shape) == TileShapes.WIDE) {
                onShape(TileShapes.WIDE)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            tr("Preview"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        CoverArt(
            kind = k,
            value = value,
            shape = shape,
            name = name,
            modifier = Modifier.width(128.dp),
        )
    }

    if (cropOpen) {
        CoverCropDialog(
            value = value,
            shape = shape,
            onDismiss = { cropOpen = false },
            onDone = { cropped ->
                cropOpen = false
                // The old copy (ours, if the cover came from the gallery) is
                // dropped as the new crop takes its place, so cropping three
                // times does not leave three files behind.
                CollectionCovers.deleteCopy(context, value)
                // Cropping a GIF keeps its picture, not its animation, so the
                // kind follows the value: a GIF cover becomes a still one.
                if (k == CoverKinds.GIF) onKind(CoverKinds.URL)
                onValue(cropped)
            },
        )
    }
}

/**
 * The create dialog for a new collection or folder: its name, and the same
 * cover + tile-shape choices the editor pages offer, so the cover is asked for
 * at the moment the thing is made (and can still be changed later).
 */
@Composable
private fun NameCoverDialog(
    title: String,
    nameLabel: String,
    initialName: String = "",
    initialKind: String = CoverKinds.NONE,
    initialValue: String = "",
    initialShape: String = TileShapes.POSTER,
    onDismiss: () -> Unit,
    onConfirm: (name: String, kind: String, value: String, shape: String) -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf(initialKind) }
    var value by remember { mutableStateOf(initialValue) }
    var shape by remember { mutableStateOf(initialShape) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    shape = GlassShape,
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(nameLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CoverSection(
                    kind = kind,
                    value = value,
                    shape = shape,
                    onKind = { kind = it },
                    onValue = { value = it },
                    onShape = { shape = it },
                    name = text,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim(), kind, value, shape) },
            ) {
                Text(tr("Create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/**
 * Shows one collection: either its folder tiles, or (when a folder is selected) the
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
            // Same magnifier as a folder page, for the collection itself — the
            // "abc" header the user marked. Searching here spans every folder
            // and every source of the collection.
            actions = {
                IconButton(onClick = { Routes.safeNavigate(nav, Routes.searchInCollection(collection.id)) }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = tr("Search inside this catalog"),
                    )
                }
            },
        )
        if (collection.folders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("This collection has no folders"),
                    subtitle = tr("Add a folder in Settings → Personal Catalog creator."),
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
                FolderTile(
                    folder = f,
                    // A folder with no cover of its own borrows its
                    // collection's, so an image picked for the collection is
                    // visible here too instead of every folder showing the
                    // drawn fallback.
                    inheritedKind = collection.coverKind,
                    inheritedValue = collection.coverValue,
                    inheritedShape = collection.tileShape,
                ) {
                    Routes.safeNavigate(
                        nav,
                        Routes.collectionView(collection.id, f.id),
                    )
                }
            }
        }
    }
}

/** One folder tile: its cover (or the collection's, when the folder has none of
 *  its own), name and catalog count. */
@Composable
private fun FolderTile(
    folder: CollectionFolder,
    inheritedKind: String = CoverKinds.NONE,
    inheritedValue: String = "",
    inheritedShape: String = folder.tileShape,
    onClick: () -> Unit,
) {
    val ownCover = CoverKinds.normalize(folder.coverKind) != CoverKinds.NONE &&
        folder.coverValue.isNotBlank()
    val tokens = rememberGlassTokens()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GlassShape)
            .background(tokens.fillTop)
            .border(1.dp, tokens.border, GlassShape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        CoverArt(
            kind = if (ownCover) folder.coverKind else inheritedKind,
            value = if (ownCover) folder.coverValue else inheritedValue,
            shape = if (ownCover) folder.tileShape else inheritedShape,
            name = folder.name,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
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
            modifier = Modifier.padding(horizontal = 6.dp),
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
            // Search INSIDE this catalog: the magnifier opens the Search tab
            // scoped to the collection this folder belongs to, so a title can be
            // looked for across exactly the sources the user picked for it
            // (Amazon, HBO, whatever each folder holds). Asked for in these
            // words: "add the search icon above in side of header abc in catalog
            // so I can search any movie or series there to watch from those
            // selected categories". The results are normal Search results, so
            // anything found opens and plays like any other title.
            actions = {
                IconButton(onClick = { Routes.safeNavigate(nav, Routes.searchInCollection(collection.id)) }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = tr("Search inside this catalog"),
                    )
                }
            },
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
            items(loaded.distinctBy { it.key }, key = { it.key }) { row ->
                // The folder source this row came from — needed for "Show all".
                val src = folder.sources.firstOrNull { it.key == row.catalogId }
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
                    // An imported list already puts ALL of its titles in this
                    // row, and there is no upstream catalog to page through, so
                    // it has no "Show all" — offering one would open an empty
                    // screen. Every other kind keeps it.
                    onShowAll = if (src?.kind == CatalogSourceKind.ITEMS) null else {
                        {
                            if (src?.kind == CatalogSourceKind.TMDB) {
                                val spec = src.spec
                                Routes.safeNavigate(
                                    nav,
                                    if (spec != null) {
                                        Routes.tmdbGridSpec(spec.encode(), row.title)
                                    } else {
                                        Routes.tmdbGrid(src.tmdbPreset, row.title)
                                    }
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
private fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    /** Optional trailing buttons (the folder editor's Search, the way Home's
     *  header carries its own). Laid out after the titles, so the titles shrink
     *  around them instead of being pushed off the row. */
    actions: (@Composable () -> Unit)? = null,
) {
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
                    // Preset/catalog names are translated; a user-typed
                    // collection name isn't in the i18n files, so tr() hands it
                    // back unchanged.
                    tr(title),
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
            actions?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
}

/**
 * One TMDB preset as a full, paged grid — the "Show All" of a preset row.
 *
 * Paging is TMDB')s own (20 items a page), and the grid is deliberately the same
 * component the extension catalogs use, so a preset and an extension catalog
 * look and behave identically.
 */
class TmdbGridViewModel(
    app: Application,
    private val presetKey: String,
    /** A hand-built TMDB source ([TmdbSpec] JSON) — what "Show all" passes for a
     *  public list, a studio, a network, a collection, a person or a custom
     *  query. Blank when the row is a built-in preset. */
    private val specJson: String = "",
) : AndroidViewModel(app) {
    private val preset = TmdbPresets.byKey(presetKey)
    private val spec = TmdbSpec.decode(specJson).takeIf { specJson.isNotBlank() }
    private val label = spec?.let { TmdbSources.fallbackName(it) } ?: (preset?.name ?: presetKey)

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
        if (_loading.value || _done.value) return
        if (preset == null && spec == null) return
        var jobHolder: kotlinx.coroutines.Job? = null
        val work = com.hikari.app.work.BackgroundWork.begin("Loading " + label) {
            jobHolder?.cancel()
        }
        jobHolder = viewModelScope.launch {
            _loading.value = true
            val specNow = spec
            val presetNow = preset
            val fresh = runCatching {
                if (specNow != null) TmdbSources.page(specNow, page)
                else if (presetNow != null) TmdbPresets.page(presetNow, page)
                else emptyList()
            }.getOrDefault(emptyList())
            // A page that adds nothing new ends the list — a collection (whose
            // parts all arrive on page 1) and a list TMDB has exhausted must
            // both stop the grid, not leave it scrolling forever.
            val seen = _items.value.map { it.uniqueId }.toMutableSet()
            val added = fresh.filter { seen.add(it.uniqueId) }
            if (added.isEmpty()) {
                _done.value = true
            } else {
                _items.value = _items.value + added
                page++
            }
            _loading.value = false
        }.also { job ->
            // `also` (not a chained `invokeOnCompletion`) — the latter returns a
            // DisposableHandle, not the Job this holder has to keep.
            job.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
        }
    }

    fun refresh() {
        page = 1
        _items.value = emptyList()
        _done.value = false
        loadNext()
    }
}

@Composable
fun TmdbGridScreen(
    nav: NavHostController,
    presetKey: String,
    title: String,
    specJson: String = "",
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: TmdbGridViewModel = viewModel(
        key = "tmdb-grid|$presetKey|$specJson",
        factory = viewModelFactory {
            initializer { TmdbGridViewModel(app, presetKey, specJson) }
        }
    )
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val done by vm.done.collectAsState()
    val spec = remember(specJson) { TmdbSpec.decode(specJson).takeIf { specJson.isNotBlank() } }
    val preset = remember(presetKey) { TmdbPresets.byKey(presetKey) }
    val name = title.ifBlank { spec?.let { TmdbSources.fallbackName(it) } ?: preset?.name ?: presetKey }
    val style = rememberPosterStyle()

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
            subtitle = when {
                spec != null -> "TMDB · " + TmdbSources.detail(spec)
                preset != null -> "TMDB · " + preset.detail
                else -> "TMDB"
            },
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
                items(items.distinctBy { it.uniqueId }, key = { it.uniqueId }) { item ->
                    TmdbGridCard(item, style) {
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
private fun TmdbGridCard(item: MediaItem, style: PosterStyle, onClick: () -> Unit) {
    val badge = rememberPosterScore(item, style)
    Column(
        Modifier
            // Not clipped to the poster rounding: at high corner values the
            // curve cut into the title's first and last letters. The artwork
            // rounds itself.
            .clickable(onClick = onClick)
    ) {
        PosterArt(
            model = Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
            imdb = badge,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        if (style.showTitles) {
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
}


/**
 * The whole collection as ONE grid — the destination of every "Show all" while
 * a collection is being browsed.
 *
 * The rows arrive per catalog ([CollectionsRepository.allRows]) and each one is
 * painted as a full-width shelf heading followed by its own posters, so a
 * single scroll shows everything the collection holds while still saying which
 * folder and which catalog a title came from.
 */
@Composable
fun CollectionGridScreen(nav: NavHostController, collectionId: String) {
    val app = LocalContext.current.applicationContext as HikariApp
    val style = rememberPosterStyle()
    var collection by remember(collectionId) { mutableStateOf<Collection?>(null) }
    var rows by remember(collectionId) { mutableStateOf<List<CatalogRow>?>(null) }

    LaunchedEffect(collectionId) {
        val c = withContext(Dispatchers.IO) { app.store.collection(collectionId) }
        collection = c
        if (c == null) {
            rows = emptyList()
            return@LaunchedEffect
        }
        runCatching {
            CollectionsRepository(app.providers).allRows(c).collect { r ->
                rows = withContext(Dispatchers.IO) {
                    r.map { row -> row.copy(items = row.items.map { it.tokenized() }) }
                }
            }
        }
    }

    val loaded = rows
    Column(Modifier.fillMaxSize()) {
        val titleCount = loaded?.sumOf { it.items.size } ?: 0
        val catalogCount = loaded?.size ?: 0
        PageHeader(
            title = collection?.name ?: tr("Loading…"),
            subtitle = if (loaded == null) "" else
                "$titleCount " + (if (titleCount == 1) "title" else "titles") + " · " +
                    "$catalogCount " + (if (catalogCount == 1) "catalog" else "catalogs"),
            onBack = { nav.popBackStack() },
            actions = {
                collection?.let { c ->
                    IconButton(onClick = { Routes.safeNavigate(nav, Routes.searchInCollection(c.id)) }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = tr("Search inside this catalog"),
                        )
                    }
                }
            },
        )
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
                        "This collection's catalogs returned no content. Check the " +
                            "extension's site, or pick another catalog for its folder."
                    ),
                )
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 84.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The grid's keys carry the row's INDEX as well as the catalog's own
            // key. A row's key is its catalog (`prov|…|row:8:0`), and two rows
            // can only ever share one if the same catalog is somehow on the list
            // twice — which used to crash this whole screen with
            //   IllegalArgumentException: Key "prov|cs3|…" was already used.
            // Sources are deduped on the way in (AppStore, CollectionsRepository)
            // so it should not happen at all; the index makes it impossible for
            // it to crash the grid even if it ever does again.
            loaded.forEachIndexed { rowIndex, row ->
                item(key = "shelf|$rowIndex|" + row.key, span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.providerName.isNotBlank()) {
                            Text(
                                row.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                items(
                    row.items.distinctBy { it.uniqueId },
                    key = { item -> "$rowIndex|" + row.key + "|" + item.uniqueId },
                ) { item ->
                    TmdbGridCard(item, style) {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        )
                    }
                }
            }
        }
    }
}
