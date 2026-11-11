package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.tv.TvUi
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.Dp
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
import com.hikari.app.data.CollectionViewModes
import com.hikari.app.data.CollectionsRepository
import com.hikari.app.data.CoverKinds
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.NuvioCatalogImport
import com.hikari.app.data.NuvioCollectionsImport
import com.hikari.app.data.NuvioCollectionsExport
import com.hikari.app.data.RepoProvenance
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
import com.hikari.app.ui.FlagIcon
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.ProviderFlags
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassCornerRadius
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.shape
import com.hikari.app.ui.rememberVisibleItems
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import com.hikari.app.tv.tvToggle
import com.hikari.app.tv.tvAdjust
import com.hikari.app.tv.tvTextFieldKeys

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
    var importOpen by remember { mutableStateOf(false) }

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
                actions = {
                    IconButton(onClick = { importOpen = true }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = tr("Import collections (JSON)"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        enabled = collections.isNotEmpty(),
                        onClick = {
                            val json = NuvioCollectionsExport.encode(collections)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            if (cm != null) {
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "Hikari collections", json,
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    // I18n.t, not tr(): this runs from a click
                                    // callback, outside composition.
                                    I18n.t("Collections JSON copied"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = tr("Copy collections (JSON)"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            LazyColumn(
                // Collections index position held across visits (see
                // ScrollKeeper).
                state = com.hikari.app.ui.components.keptListState("collections"),
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
                                if (!LocalHideHelp.current) {
                                Text(
                                    tr("Name it, then add a folder for each kind of content you want."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                }
                            }
                        }
                    }
                }
                item {
                    Surface(
                        onClick = { importOpen = true },
                        shape = GlassShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("Import collections (JSON)"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!LocalHideHelp.current) {
                                Text(
                                    tr(
                                        "Paste a Nuvio/SkyStream collections export, or open a .json " +
                                            "file. Its folders and catalogs are created for you."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                }
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
                itemsIndexed(collections, key = { _, c -> c.id }) { index, c ->
                    CollectionBlock(
                        collection = c,
                        onOpen = { Routes.safeNavigate(nav, Routes.collectionView(c.id)) },
                        onEdit = { openEditor(c) },
                        onDelete = { confirmDelete = c },
                        canMoveUp = index > 0,
                        canMoveDown = index < collections.lastIndex,
                        onMoveUp = {
                            scope.launch {
                                app.store.saveCollections(
                                    collections.moveItem(index, index - 1)
                                )
                            }
                        },
                        onMoveDown = {
                            scope.launch {
                                app.store.saveCollections(
                                    collections.moveItem(index, index + 1)
                                )
                            }
                        },
                        onOpenFolder = { f ->
                            Routes.safeNavigate(nav, Routes.collectionView(c.id, f.id))
                        },
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

    if (importOpen) {
        ImportCollectionsSheet(
            onImported = { summary ->
                importOpen = false
                android.widget.Toast.makeText(context, summary, android.widget.Toast.LENGTH_LONG).show()
            },
            onDismiss = { importOpen = false },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (collection.pinToTop) {
                        // A pinned catalog is drawn on Home without being picked,
                        // so the list says so where the user will look for it.
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = tr("Pinned to Home"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        collection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    FolderSummary(collection) + if (collection.pinToTop) {
                        " · " + tr("Pinned")
                    } else {
                        ""
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
@Composable
private fun FolderSummary(c: Collection): String {
    val folders = c.folders.size
    val sources = c.folders.sumOf { it.sources.size }
    val f = if (folders == 1) tr("folder") else tr("folders")
    val s = if (sources == 1) tr("catalog") else tr("catalogs")
    return "$folders $f · $sources $s"
}

@Composable
private fun CollectionBlock(
    collection: Collection,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: (CollectionFolder) -> Unit,
    /** The reorder pair the collection row carries (see [CollectionListRow]):
     *  a collection's position in the store IS its position on Home. */
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        CollectionListRow(
            collection = collection,
            onOpen = onOpen,
            onEdit = onEdit,
            onDelete = onDelete,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )
        if (collection.folders.isEmpty()) return@Column
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(collection.folders, key = { it.id }) { f ->
                // The shape the tile will actually wear: its own when it has a
                // cover, otherwise the collection's (see FolderTile).
                val ownCover = CoverKinds.normalize(f.coverKind) != CoverKinds.NONE &&
                    f.coverValue.isNotBlank()
                FolderTile(
                    folder = f,
                    inheritedKind = collection.coverKind,
                    inheritedValue = collection.coverValue,
                    inheritedShape = collection.tileShape,
                    width = folderTileWidth(if (ownCover) f.tileShape else collection.tileShape),
                    onClick = { onOpenFolder(f) },
                )
            }
        }
    }
}

fun folderTileWidth(shape: String): Dp = when (TileShapes.normalize(shape)) {
    TileShapes.WIDE -> 150.dp
    TileShapes.SQUARE -> 112.dp
    else -> 102.dp
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
            // Collection editor position held per collection (see ScrollKeeper).
            state = com.hikari.app.ui.components.keptListState("colEdit:${collection.id}"),
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
                        .fillMaxWidth().tvTextFieldKeys(collection.name)
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
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("Pin to top of Home"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!LocalHideHelp.current) {
                                Text(
                                    tr(
                                        "Show this catalog's folders on Home even when nothing " +
                                            "is picked from the source list."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = collection.pinToTop,
                                onCheckedChange = { onChange(collection.copy(pinToTop = it)) },
                                // A remote cannot land on a bare Switch at the end of a
                                // row — see [Modifier.tvToggle].
                                modifier = Modifier.tvToggle(
                                    collection.pinToTop,
                                    onValueChange = { onChange(collection.copy(pinToTop = it)) },
                                ),
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        Text(
                            tr("Folders shown as"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!LocalHideHelp.current) {
                        Text(
                            tr(
                                "Rows stacks one shelf per folder. Tabs puts a tab strip at " +
                                    "the top and shows one folder at a time."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            ChoiceChip(
                                label = tr("Rows"),
                                selected = collection.viewMode != CollectionViewModes.TABS,
                                onClick = {
                                    onChange(collection.copy(viewMode = CollectionViewModes.ROWS))
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            ChoiceChip(
                                label = tr("Tabs"),
                                selected = collection.viewMode == CollectionViewModes.TABS,
                                onClick = {
                                    onChange(collection.copy(viewMode = CollectionViewModes.TABS))
                                },
                            )
                        }
                        if (collection.viewMode == CollectionViewModes.TABS) {
                            Row(
                                Modifier.padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tr("Show an \"All\" tab"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (!LocalHideHelp.current) {
                                    Text(
                                        tr(
                                            "An extra tab holding every folder's catalogs, " +
                                                "for browsing the whole catalog at once."
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = collection.showAllTab,
                                    onCheckedChange = {
                                        onChange(collection.copy(showAllTab = it))
                                    },
                                    // See [Modifier.tvToggle].
                                    modifier = Modifier.tvToggle(
                                        collection.showAllTab,
                                        onValueChange = {
                                            onChange(collection.copy(showAllTab = it))
                                        },
                                    ),
                                )
                            }
                        }
                        OutlinedTextField(
                            shape = GlassShape,
                            value = collection.backdropUrl,
                            onValueChange = { onChange(collection.copy(backdropUrl = it)) },
                            singleLine = true,
                            label = { Text(tr("Wide backdrop URL (optional)")) },
                            supportingText = {
                                Text(
                                    tr(
                                        "A wide image for this catalog's own page header. " +
                                            "Blank uses the cover above."
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth().tvTextFieldKeys(collection.backdropUrl)
                                .padding(top = 10.dp),
                        )
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditorPage(
    folder: CollectionFolder,
    onBack: () -> Unit,
    onSave: (CollectionFolder) -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    var sources by remember { mutableStateOf(folder.sources.distinctBy { it.key }) }
    var coverKind by remember { mutableStateOf(folder.coverKind) }
    var coverValue by remember { mutableStateOf(folder.coverValue) }
    var tileShape by remember { mutableStateOf(folder.tileShape) }
    var hideTitle by remember { mutableStateOf(folder.hideTitle) }
    var gifAlways by remember { mutableStateOf(folder.gifAlways) }
    var heroBackdrop by remember { mutableStateOf(folder.heroBackdropUrl) }
    var titleLogo by remember { mutableStateOf(folder.titleLogoUrl) }

    var tmdbSheet by remember { mutableStateOf(false) }
    var providerSheet by remember { mutableStateOf(false) }
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
    // Editor position held per folder (see ScrollKeeper).
    val listState = com.hikari.app.ui.components.keptListState("folderEdit:${folder.id}")
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val rowCentres = remember { HashMap<String, Float>() }
    val dragY = remember { floatArrayOf(0f) }
    val lastMoveAt = remember { longArrayOf(0L) }
    var collectScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
                        .fillMaxWidth().tvTextFieldKeys(name)
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
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("Hide the folder name"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (!LocalHideHelp.current) {
                                Text(
                                    tr(
                                        "Draw the tile as cover art only — the name still shows " +
                                            "in here and on the folder's own page."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = hideTitle,
                                onCheckedChange = { hideTitle = it },
                                // See [Modifier.tvToggle].
                                modifier = Modifier.tvToggle(hideTitle, onValueChange = { hideTitle = it }),
                            )
                        }
                        if (CoverKinds.normalize(coverKind) == CoverKinds.GIF) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tr("Always animate"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        tr(
                                            "An animated cover plays only while its tile is " +
                                                "focused unless this is on — a screen of " +
                                                "animating tiles costs battery."
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = gifAlways,
                                    onCheckedChange = { gifAlways = it },
                                    // See [Modifier.tvToggle].
                                    modifier = Modifier.tvToggle(gifAlways, onValueChange = { gifAlways = it }),
                                )
                            }
                        }
                        OutlinedTextField(
                            shape = GlassShape,
                            value = heroBackdrop,
                            onValueChange = { heroBackdrop = it },
                            singleLine = true,
                            label = { Text(tr("Hero backdrop URL (optional)")) },
                            supportingText = {
                                Text(
                                    tr(
                                        "A wide image behind this folder's page header. Blank " +
                                            "uses the cover."
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth().tvTextFieldKeys(heroBackdrop)
                                .padding(top = 10.dp),
                        )
                        OutlinedTextField(
                            shape = GlassShape,
                            value = titleLogo,
                            onValueChange = { titleLogo = it },
                            singleLine = true,
                            label = { Text(tr("Title logo URL (optional)")) },
                            supportingText = {
                                Text(
                                    tr(
                                        "A transparent logo image (a Netflix-style wordmark) " +
                                            "drawn on this folder's header instead of its name."
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth().tvTextFieldKeys(titleLogo)
                                .padding(top = 10.dp),
                        )
                    }
                }
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
            // (The "import a list (JSON)" card sat here, inside the folder
            // editor. It could only ever fill the folder the user had already
            // made, while the exports people actually bring — Nuvio's and
            // SkyStream's — already contain their OWN folders and catalogs, so
            // the file's structure had to be rebuilt by hand first. Importing
            // moved up to the collections list, where its folders become
            // collections; see ImportCollectionsSheet.)
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
                                    lastMoveAt[0] = 0L
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragY[0] += amount.y
                                    val from = sources.indexOfFirst { it.key == s.key }
                                    if (from >= 0) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastMoveAt[0] >= 140L) {
                                            val to = dragTarget(sources, rowCentres, from, dragY[0])
                                            if (to != from && to in sources.indices) {
                                                lastMoveAt[0] = now
                                                sources = sources.moveItem(from, to)
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove
                                                )
                                            }
                                        }
                                    }
                                    val step = autoScrollStep(
                                        dragY[0],
                                        listBounds[0],
                                        listBounds[1],
                                        autoScrollEdge,
                                    )
                                    collectScrollJob?.cancel()
                                    collectScrollJob = if (step != 0f) {
                                        dragScope.launch { listState.scrollBy(step) }
                                    } else null
                                },
                                onDragEnd = {
                                    liftedKey = null
                                    collectScrollJob?.cancel()
                                    collectScrollJob = null
                                },
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
                                hideTitle = hideTitle,
                                gifAlways = gifAlways,
                                heroBackdropUrl = heroBackdrop.trim(),
                                titleLogoUrl = titleLogo.trim(),
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
                if (!LocalHideHelp.current) {
                Text(
                    tr("Tap every catalog this folder should show — tap one to add it, tap it again to take it out."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                }
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

private fun autoScrollStep(pointerY: Float, top: Float, bottom: Float, edge: Float): Float =
    when {
        bottom <= top -> 0f
        pointerY < top + edge -> -6f
        pointerY > bottom - edge -> 6f
        else -> 0f
    }


private val TMDB_ADV_FILTERS: List<Triple<String, String, String>> = listOf(
    Triple("genresText", "Genres", "TMDB genre ids — 28,12 = both, 28|12 = either"),
    Triple("genresExclude", "Exclude genres", "TMDB genre ids, comma separated"),
    Triple("dateFrom", "Released from", "YYYY-MM-DD"),
    Triple("dateTo", "Released to", "YYYY-MM-DD"),
    Triple("ratingMin", "Lowest rating", "0-10, e.g. 7"),
    Triple("ratingMax", "Highest rating", "0-10, e.g. 9"),
    Triple("votesMin", "Fewest votes", "e.g. 200"),
    Triple("language", "Original language", "en, ja, ko…"),
    Triple("country", "Origin country", "US, GB, JP…"),
    Triple("keywords", "Keywords", "TMDB keyword ids — 9715,9882"),
    Triple("keywordsExclude", "Exclude keywords", "TMDB keyword ids"),
    Triple("companies", "Companies", "TMDB company ids — 420 for Marvel"),
    Triple("companiesExclude", "Exclude companies", "TMDB company ids"),
    Triple("networks", "Networks", "TMDB network ids — 213 for Netflix"),
    Triple("providers", "On these services", "TMDB provider ids — 8 for Netflix"),
    Triple("providersExclude", "Not on these services", "TMDB provider ids"),
    Triple("region", "Watch region", "US, GB, IN… — needed for the service filters"),
)

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
    // The advanced discover filters (see [TMDB_ADV_FILTERS]): one string per
    // TMDB parameter, empty when unset.
    var showAdvanced by remember { mutableStateOf(false) }
    val adv = remember { mutableStateMapOf<String, String>() }
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
        adv.clear()
        showAdvanced = false
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
            // Only a Custom source carries these; every other kind is an id or a
            // name and would ignore them anyway.
            genresText = if (type == TmdbSourceType.DISCOVER) adv["genresText"].orEmpty() else "",
            genresExclude = if (type == TmdbSourceType.DISCOVER) {
                adv["genresExclude"].orEmpty()
            } else "",
            dateFrom = if (type == TmdbSourceType.DISCOVER) adv["dateFrom"].orEmpty() else "",
            dateTo = if (type == TmdbSourceType.DISCOVER) adv["dateTo"].orEmpty() else "",
            ratingMin = if (type == TmdbSourceType.DISCOVER) adv["ratingMin"].orEmpty() else "",
            ratingMax = if (type == TmdbSourceType.DISCOVER) adv["ratingMax"].orEmpty() else "",
            votesMin = if (type == TmdbSourceType.DISCOVER) adv["votesMin"].orEmpty() else "",
            language = if (type == TmdbSourceType.DISCOVER) adv["language"].orEmpty() else "",
            country = if (type == TmdbSourceType.DISCOVER) adv["country"].orEmpty() else "",
            keywords = if (type == TmdbSourceType.DISCOVER) adv["keywords"].orEmpty() else "",
            keywordsExclude = if (type == TmdbSourceType.DISCOVER) {
                adv["keywordsExclude"].orEmpty()
            } else "",
            companies = if (type == TmdbSourceType.DISCOVER) adv["companies"].orEmpty() else "",
            companiesExclude = if (type == TmdbSourceType.DISCOVER) {
                adv["companiesExclude"].orEmpty()
            } else "",
            networks = if (type == TmdbSourceType.DISCOVER) adv["networks"].orEmpty() else "",
            providers = if (type == TmdbSourceType.DISCOVER) adv["providers"].orEmpty() else "",
            providersExclude = if (type == TmdbSourceType.DISCOVER) {
                adv["providersExclude"].orEmpty()
            } else "",
            region = if (type == TmdbSourceType.DISCOVER) adv["region"].orEmpty() else "",
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
            if (!LocalHideHelp.current) {
            Text(
                tr("Tap a ready-made source to add it — tap several in a row — or build one from a TMDB id, a name or a link."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            }
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
                            .fillMaxWidth().tvTextFieldKeys(text)
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
                                .fillMaxWidth().tvTextFieldKeys(year)
                                .padding(top = 12.dp),
                        )
                    }
                    // The rest of the reference app's discover filter set. It is
                    // folded away because it is seventeen fields and most rows
                    // use the chips above; anything typed here is sent to TMDB
                    // verbatim, and anything left blank sends no parameter at
                    // all — so an untouched section changes nothing.
                    item {
                        Surface(
                            onClick = { showAdvanced = !showAdvanced },
                            shape = GlassShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tr("Advanced filters"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    if (showAdvanced) Icons.Filled.KeyboardArrowUp
                                    else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    if (showAdvanced) {
                        items(TMDB_ADV_FILTERS, key = { it.first }) { (key, label, hint) ->
                            OutlinedTextField(
                                shape = GlassShape,
                                value = adv[key].orEmpty(),
                                onValueChange = { adv[key] = it },
                                singleLine = true,
                                label = { Text(tr(label)) },
                                supportingText = { Text(tr(hint)) },
                                modifier = Modifier
                                    .fillMaxWidth().tvTextFieldKeys(adv[key].orEmpty())
                                    .padding(top = 10.dp),
                            )
                        }
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
                            .fillMaxWidth().tvTextFieldKeys(displayTitle)
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
                    .fillMaxWidth().tvTextFieldKeys(query)
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
                items(hits.distinctBy { it.id + "|" + it.media }, key = { it.id + "|" + it.media }) { hit ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCollectionsSheet(
    onImported: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The file's own text, and what the field shows: a file can be megabytes,
    // and putting that in a TextField would stall the sheet.
    var body by remember { mutableStateOf("") }
    var fieldLabel by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<NuvioCollectionsImport.Plan?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun read(source: String) {
        val parsed = NuvioCollectionsImport.parse(source)
        plan = parsed
        val titles = parsed.titleLists.count { it.items.isNotEmpty() }
        // I18n.t, not tr(): this runs from a click/file-picker callback, which
        // is outside composition, where the composable tr() cannot be called.
        message = when {
            parsed.collections.isNotEmpty() -> ""
            titles > 0 -> ""
            else -> I18n.t(
                "No collections found. Paste the JSON exactly as you got it — an " +
                    "array of collections, each with its folders inside."
            )
        }
    }

    LaunchedEffect(body) {
        if (body.isBlank() || plan != null) return@LaunchedEffect
        delay(280)
        val parsed = withContext(Dispatchers.Default) {
            runCatching { NuvioCollectionsImport.parse(body) }.getOrNull()
        } ?: return@LaunchedEffect
        plan = parsed
        message = ""
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

    val parsed = plan
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("Import collections (JSON)"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (!LocalHideHelp.current) {
            Text(
                tr(
                    "Paste your collections JSON below. Every collection's folders and " +
                        "catalogs are created for you — nothing to rebuild by hand."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            }
            OutlinedTextField(
                shape = GlassShape,
                value = fieldLabel.ifBlank { body },
                onValueChange = {
                    fieldLabel = ""
                    body = it
                    plan = null
                    message = ""
                },
                label = { Text(tr("JSON")) },
                minLines = 3,
                maxLines = 7,
                modifier = Modifier
                    .fillMaxWidth().tvTextFieldKeys(fieldLabel.ifBlank { body })
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

            if (parsed != null && parsed.collections.isNotEmpty()) {
                Text(
                    tr("Found in this file"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                // A scrollable preview, because a real export has a dozen
                // collections and the button has to stay reachable.
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    itemsIndexed(parsed.collections, key = { i, c -> "$i|" + c.title }) { _, c ->
                        Surface(
                            shape = GlassShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.List,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        c.folders.size.toString() + " " + tr("folders") + " · " +
                                            c.folders.sumOf { it.sources.size } + " " +
                                            tr("catalogs"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (c.folders.isNotEmpty() &&
                                        c.folders.sumOf { it.sources.size } == 0
                                    ) {
                                        Text(
                                            tr(
                                                "No catalogs here — every source names an " +
                                                    "addon you don't have installed, or a " +
                                                    "Trakt list."
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val titleLists = parsed?.titleLists.orEmpty().filter { it.items.isNotEmpty() }
            if (parsed != null && parsed.collections.isEmpty() && titleLists.isNotEmpty()) {
                Text(
                    tr("Found in this file"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                titleLists.forEach { group ->
                    Text(
                        group.name.ifBlank { tr("Imported list") } + " · " +
                            group.items.size + " " + tr("titles"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            val ready = parsed != null && (
                parsed.collections.isNotEmpty() ||
                    parsed.titleLists.any { it.items.isNotEmpty() }
                )
            Surface(
                onClick = {
                    val p = plan ?: return@Surface
                    busy = true
                    scope.launch {
                        val summary = withContext(Dispatchers.IO) {
                            runCatching {
                                withTimeoutOrNull(90_000L) { importCollections(app, p) }
                            }
                        }
                        busy = false
                        val failure = summary.exceptionOrNull()
                        if (failure != null) {
                            com.hikari.app.data.Logs.log(
                                "Collections",
                                "import failed: ${failure.javaClass.name}: ${failure.message}"
                            )
                        }
                        onImported(
                            summary.getOrNull()
                                ?: I18n.t("The import took too long — try again in a moment.")
                        )
                    }
                },
                shape = GlassShape,
                color = MaterialTheme.colorScheme.primary,
                enabled = ready && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 28.dp),
            ) {
                Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Import"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

private suspend fun importCollections(
    app: HikariApp,
    plan: NuvioCollectionsImport.Plan,
): String {
    val catalogCache = HashMap<String, List<CatalogRef>>()
    val matchDeadline = System.currentTimeMillis() + CATALOG_MATCH_BUDGET_MS
    // Shared by every source in the file — see MAX_GUESSED_ADDONS.
    val guessedAddons = HashSet<String>()
    val result = NuvioCollectionsImport.materialise(
        plan,
        app.store::newId,
    ) { addonId, type, catalogId ->
        matchInstalledCatalog(
            app, addonId, type, catalogId, catalogCache, matchDeadline, guessedAddons,
        )
    }
    var collections = 0
    var folders = 0
    for (c in result.collections) {
        runCatching { app.store.upsertCollection(c) }
        collections++
        folders += c.folders.size
    }
    if (collections == 0) {
        val lists = plan.titleLists.filter { it.items.isNotEmpty() }
        if (lists.isNotEmpty()) {
            val built = lists.mapNotNull { group ->
                val json = NuvioCatalogImport.encode(group.items)
                if (json.isEmpty()) return@mapNotNull null
                val cover = group.items
                    .firstOrNull { !it.posterUrl.isNullOrBlank() }
                    ?.posterUrl
                CollectionFolder(
                    id = app.store.newId("fld"),
                    name = group.name.ifBlank { I18n.t("Imported list") },
                    sources = listOf(
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
                    ),
                    coverKind = if (cover != null) CoverKinds.URL else CoverKinds.NONE,
                    coverValue = cover.orEmpty(),
                )
            }
            if (built.isNotEmpty()) {
                runCatching {
                    app.store.upsertCollection(
                        Collection(
                            id = app.store.newId("col"),
                            name = lists.first().name.ifBlank { I18n.t("Imported list") },
                            folders = built,
                            // Same reasoning as the folders above: the collection
                            // card takes the first cover it can find, so the
                            // import is visible the moment it is added.
                            coverKind = built.firstOrNull { it.coverKind == CoverKinds.URL }
                                ?.let { CoverKinds.URL } ?: CoverKinds.NONE,
                            coverValue = built.firstOrNull { it.coverKind == CoverKinds.URL }
                                ?.coverValue ?: "",
                        )
                    )
                }
                collections++
                folders += built.size
            }
        }
    }
    val head = I18n.t("Imported %s").replace(
        "%s",
        collections.toString() + " " +
            (if (collections == 1) I18n.t("collection") else I18n.t("collections")) +
            " · " + folders + " " + I18n.t("folders") +
            " · " + result.catalogs + " " + I18n.t("catalogs"),
    )
    val notes = buildList {
        if (result.droppedAddons > 0) {
            add(
                result.droppedAddons.toString() + " " + I18n.t("catalogs") + " " +
                    I18n.t("are from an addon that is not installed")
            )
        }
        if (result.droppedTrakt > 0) {
            add(result.droppedTrakt.toString() + " " + I18n.t("Trakt lists (not supported)"))
        }
    }
    return if (notes.isEmpty()) head else head + " — " + notes.joinToString(", ")
}

/** How long one HINTED extension gets to list its catalogs. Generous (an
 *  extension that has to load a plugin and fetch its index is slow the first
 *  time) but bounded. */
private const val CATALOG_LOOKUP_MS = 8_000L
private const val CATALOG_GUESS_MS = 1_200L
/** The most guessed providers ONE import will ask. */
private const val MAX_GUESSED_PROVIDERS = 6
/** The most DIFFERENT addons one import will guess for. A file whose addon
 *  spellings Hikari does not recognise is worth a few attempts, not one per
 *  source: the rest are dropped and counted, which the summary says outright. */
private const val MAX_GUESSED_ADDONS = 4
/** The whole matching phase of one import: after this the remaining sources are
 *  dropped (and counted), instead of a wedged extension holding the sheet open.
 *  Importing is a local write; nothing about it may depend on a plugin's host
 *  being alive. */
private const val CATALOG_MATCH_BUDGET_MS = 30_000L

private suspend fun matchInstalledCatalog(
    app: HikariApp,
    addonId: String,
    type: String,
    catalogId: String,
    /** provider id → the catalogs it exposes. Filled on demand and reused for
     *  the rest of the import (see the call site). */
    cache: MutableMap<String, List<CatalogRef>>,
    /** Wall clock after which this import stops matching (see
     *  [CATALOG_MATCH_BUDGET_MS]). */
    deadline: Long,
    /** The addon ids this import has already guessed for (see
     *  [MAX_GUESSED_ADDONS]); shared across the whole import. */
    guessedAddons: MutableSet<String>,
): CatalogSource? = withContext(Dispatchers.IO) {
    val wanted = catalogId.trim()
    if (wanted.isBlank()) return@withContext null
    if (System.currentTimeMillis() > deadline) return@withContext null
    val wantType = when (type.trim().lowercase()) {
        "movie", "movies", "film" -> MediaType.MOVIE
        "series", "tv", "show", "shows", "anime" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }
    val hint = addonId.substringAfterLast('.').trim().lowercase()
    val installed = app.providers.providers.value.filter { it.config.enabled }
    val hinted = if (hint.isBlank()) {
        emptyList()
    } else {
        installed.filter { p ->
            p.config.name.contains(hint, ignoreCase = true) ||
                p.config.url.contains(hint, ignoreCase = true)
        }
    }
    if (hinted.isEmpty()) {
        val key = addonId.lowercase()
        if (key !in guessedAddons) {
            if (guessedAddons.size >= MAX_GUESSED_ADDONS) return@withContext null
            guessedAddons += key
        }
        for (p in installed.take(MAX_GUESSED_PROVIDERS)) {
            if (System.currentTimeMillis() > deadline) return@withContext null
            val hit = pickCatalog(catalogsOf(p, cache, CATALOG_GUESS_MS), wanted, wantType)
                ?: continue
            return@withContext providerSource(p, hit)
        }
        return@withContext null
    }
    for (p in hinted) {
        if (System.currentTimeMillis() > deadline) return@withContext null
        val hit = pickCatalog(catalogsOf(p, cache, CATALOG_LOOKUP_MS), wanted, wantType)
            ?: continue
        return@withContext providerSource(p, hit)
    }
    null
}

/** The catalog called [wanted], preferring the one whose kind matches the
 *  imported source's own type. */
private fun pickCatalog(refs: List<CatalogRef>, wanted: String, wantType: MediaType): CatalogRef? =
    refs.firstOrNull { it.id == wanted && wantType != MediaType.UNKNOWN && it.type == wantType }
        ?: refs.firstOrNull { it.id == wanted }

private suspend fun catalogsOf(
    p: ContentProvider,
    cache: MutableMap<String, List<CatalogRef>>,
    budgetMs: Long,
): List<CatalogRef> = cache.getOrPut(p.config.id) {
    runCatching {
        kotlinx.coroutines.withTimeoutOrNull(budgetMs) { p.catalogs() }
    }.getOrNull().orEmpty()
}

private fun providerSource(p: ContentProvider, hit: CatalogRef): CatalogSource =
    CatalogSource(
        kind = CatalogSourceKind.PROVIDER,
        title = hit.name,
        providerId = p.config.id,
        catalogId = hit.id,
        type = hit.type,
        rawType = hit.rawType,
    )

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
    val repos by remember { app.store.reposFlow() }.collectAsState(initial = emptyList())
    val repoNameByProvider = remember(all, repos) {
        RepoProvenance.nameMap(all.map { it.config }, repos)
    }
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
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
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
                    items(active.distinctBy { it.config.id }, key = { it.config.id }) { p ->
                        val lineSupporting = repoNameByProvider[p.config.id]
                            ?.let { "${p.config.type.groupLabel} · $it" }
                        PickerLine(
                            label = p.config.name,
                            selected = false,
                            supporting = lineSupporting,
                            flagCode = ProviderFlags.codeOf(p.config.name, lineSupporting),
                        ) { onPick(p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerLine(
    label: String,
    selected: Boolean,
    supporting: String? = null,
    /** ISO country code for the real flag image in front of the name. */
    flagCode: String? = null,
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
            if (flagCode != null) {
                FlagIcon(code = flagCode, size = 22.dp)
                Spacer(Modifier.width(10.dp))
            }
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

private object CollectionCovers {

    private fun dir(context: Context): File =
        File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

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
    /** False to draw an animated cover as its FIRST FRAME. Only a
     *  [CoverKinds.GIF] cover is affected, and only when its folder (or this
     *  device) asked for it — see [PosterLoader.stillModel]. */
    animateGif: Boolean = true,
    /** Round the artwork to THIS radius instead of the poster style's own. A
     *  caller that draws its own panel (see [FolderTile]) passes the panel's
     *  radius minus its padding, so the two corners are CONCENTRIC. Null keeps
     *  the poster style's setting, which is what a poster in a normal grid gets. */
    cornerRadius: Dp? = null,
    /** False to draw NO surface of its own — no fill and no glass hairline — so
     *  the artwork sits ON the caller's panel instead of inside a second box.
     *  True (the default) is the standalone card: a poster in a grid, a list-row
     *  thumb. See [FolderTile] for the one-surface rule. */
    ownGlass: Boolean = true,
) {
    val tokens = rememberGlassTokens()
    val k = CoverKinds.normalize(kind)
    val posterStyle = rememberPosterStyle()
    // What THIS artwork is drawn with. A caller that already draws the tile's
    // glass panel (see [FolderTile]) asks for no surface of its own and for the
    // panel's concentric corner, so the tile is one box rather than two; every
    // other caller gets the user's poster style untouched. `.copy` keeps the
    // rest of the style — the badges, the halo, the effects — in place, and the
    // corner is never allowed ABOVE the user's own setting, so a user who asked
    // for square posters still gets square covers inside a tile.
    val coverStyle = if (ownGlass && cornerRadius == null) {
        posterStyle
    } else {
        posterStyle.copy(
            corner = cornerRadius?.value?.roundToInt()?.coerceIn(0, posterStyle.corner)
                ?: posterStyle.corner,
            glass = ownGlass,
        )
    }
    val sized = if (shaped) Modifier.aspectRatio(TileShapes.aspect(shape)) else Modifier
    if ((k == CoverKinds.URL || k == CoverKinds.GIF) && value.isNotBlank()) {
        PosterArt(
            model = if (k == CoverKinds.GIF && !animateGif) {
                PosterLoader.stillModel(value)
            } else {
                value
            },
            contentDescription = name.ifBlank { null },
            style = coverStyle,
            modifier = modifier.then(sized),
            imageAlignment = Alignment.TopCenter,
        )
        return
    }
    val glyphShape = cornerRadius?.let { RoundedCornerShape(it) } ?: GlassShape
    Box(
        modifier
            .then(sized)
            .then(
                if (ownGlass) {
                    Modifier
                        .clip(glyphShape)
                        .background(tokens.fillTop)
                        .border(1.dp, tokens.border, glyphShape)
                } else {
                    Modifier
                },
            ),
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
                if (!LocalHideHelp.current) {
                Text(
                    tr("Drag to move, pinch or use the slider to zoom. The tile shows exactly this."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
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
                            .padding(start = 10.dp)
                            // The remote steps the zoom, with the same clamping the
                            // drag's own handler does. See [Modifier.tvAdjust].
                            .tvAdjust { delta ->
                                val bmp = bitmap
                                if (bmp != null) {
                                    val next = (zoom + delta * 0.25f).coerceIn(1f, 6f)
                                    val (mx, my) = limits(viewport, bmp, next)
                                    offX = offX.coerceIn(-mx, mx)
                                    offY = offY.coerceIn(-my, my)
                                    zoom = next
                                }
                            },
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value),
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value),
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
                    if (!LocalHideHelp.current) {
                    Text(
                        tr("A GIF plays while its tile is on screen; otherwise the first frame is shown."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
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
                CollectionCovers.deleteCopy(context, value)
                // Cropping a GIF keeps its picture, not its animation, so the
                // kind follows the value: a GIF cover becomes a still one.
                if (k == CoverKinds.GIF) onKind(CoverKinds.URL)
                onValue(cropped)
            },
        )
    }
}

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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(text),
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
        if (c.viewMode == CollectionViewModes.TABS && c.folders.isNotEmpty()) {
            CollectionTabsContent(nav = nav, collection = c)
        } else {
            CollectionFoldersPage(nav = nav, collection = c)
        }
    } else {
        CollectionFolderContent(nav = nav, collection = c, folder = folder)
    }
}

@Composable
private fun CollectionTabsContent(nav: NavHostController, collection: Collection) {
    val app = LocalContext.current.applicationContext as HikariApp
    val showAll = collection.showAllTab && collection.folders.size > 1
    // The "All" tab is the slot after the last folder.
    val allSlot = collection.folders.size
    var index by remember(collection.id) { mutableStateOf(0) }
    var rows by remember(collection.id, index) { mutableStateOf<List<CatalogRow>?>(null) }

    LaunchedEffect(collection.id, index) {
        rows = null
        val repo = CollectionsRepository(app.providers)
        rows = withContext(Dispatchers.IO) {
            val loaded = if (showAll && index == allSlot) {
                runCatching { repo.allRowsOnce(collection) }.getOrDefault(emptyList())
            } else {
                val folder = collection.folders.getOrNull(index)
                if (folder == null) {
                    emptyList()
                } else {
                    runCatching { repo.folderRowsOnce(collection, folder) }
                        .getOrDefault(emptyList())
                }
            }
            // The same poster-token collapse every other catalog grid does, so a
            // folder full of base64 items stays inside a stock heap.
            loaded.map { row -> row.copy(items = row.items.map { it.tokenized() }) }
        }
    }

    val current = collection.folders.getOrNull(index)
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = collection.name,
            subtitle = if (showAll && index == allSlot) {
                FolderSummary(collection)
            } else {
                collection.name + " · " + (current?.name.orEmpty())
            },
            onBack = { nav.popBackStack() },
            heroBackdrop = collection.backdropUrl,
            actions = {
                IconButton(
                    onClick = {
                        Routes.safeNavigate(nav, Routes.searchInCollection(collection.id))
                    }
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = tr("Search inside this catalog"),
                    )
                }
            },
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(collection.folders, key = { it.id }) { f ->
                val i = collection.folders.indexOf(f)
                ChoiceChip(
                    label = f.name,
                    selected = i == index,
                    onClick = { if (i != index) index = i },
                )
            }
            if (showAll) {
                item(key = "all-tab") {
                    ChoiceChip(
                        label = tr("All"),
                        selected = index == allSlot,
                        onClick = { index = allSlot },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
        FolderRowList(
            nav = nav,
            sources = if (showAll && index == allSlot) {
                collection.allSources
            } else {
                current?.sources.orEmpty()
            },
            loaded = rows,
            keeperKey = "colTabs:${collection.id}:$index",
            // Only the "All" tab mixes folders, so only it needs each row to say
            // which folder it came from (see FolderRowList.showProviderName).
            showProviderName = showAll && index == allSlot,
            emptyTitle = tr("Nothing here right now"),
            emptySubtitle = tr(
                "This folder's catalogs returned no content. Check the extension's " +
                    "site, or edit the folder to pick another catalog."
            ),
        )
    }
}

/** The folder tiles of one collection. */
@Composable
private fun CollectionFoldersPage(nav: NavHostController, collection: Collection) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = collection.name,
            subtitle = FolderSummary(collection),
            onBack = { nav.popBackStack() },
            // The collection's own wide backdrop, when it has one — the hero the
            // reference app opens a catalog with.
            heroBackdrop = collection.backdropUrl,
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
            // Three tiles on a phone, not two: the same "make it small" the
            // folder rows got (a folder tile is a bookmark, not a poster).
            columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(100)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(collection.folders, key = { it.id }) { f ->
                FolderTile(
                    folder = f,
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

/** A folder tile's own inset: the panel's padding, and the amount the cover's
 *  corner radius is reduced by so the two arcs stay concentric. See
 *  [FolderTile]. */
private val TILE_PADDING = 7.dp

private const val TILE_CORNER_FRACTION = 0.16f

/** One folder tile: its cover (or the collection's, when the folder has none of
 *  its own), name and catalog count. Public because Home draws the same tile
 *  for a personal catalog's folders (see
 *  [com.hikari.app.ui.screens.CollectionFoldersOnHome] in HomeScreen.kt). */
@Composable
fun FolderTile(
    folder: CollectionFolder,
    inheritedKind: String = CoverKinds.NONE,
    inheritedValue: String = "",
    inheritedShape: String = folder.tileShape,
    /** A fixed width for a tile that sits in a horizontal row (see
     *  [CollectionBlock]); null lets the tile fill the grid cell it is in. */
    width: Dp? = null,
    onClick: () -> Unit,
) {
    val ownCover = CoverKinds.normalize(folder.coverKind) != CoverKinds.NONE &&
        folder.coverValue.isNotBlank()
    val tokens = rememberGlassTokens()
    val app = LocalContext.current.applicationContext as HikariApp
    val gifAnimFlow = remember { app.store.gifAnimFlow() }
    val animateGif by gifAnimFlow.collectAsState(initial = true)
    val tvQuiet = com.hikari.app.data.PerfMode.tvDevice && com.hikari.app.data.PerfMode.tvOn
    BoxWithConstraints(
        modifier = Modifier.then(
            if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(),
        ),
    ) {
        val panelRadius = minOf(GlassCornerRadius, maxWidth * TILE_CORNER_FRACTION)
        val panelShape = RoundedCornerShape(panelRadius)
        val coverRadius = (panelRadius - TILE_PADDING).coerceAtLeast(0.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .background(tokens.fillTop, panelShape)
                .border(1.dp, tokens.border, panelShape)
                .clickable(onClick = onClick)
                .padding(TILE_PADDING),
        ) {
            CoverArt(
                kind = if (ownCover) folder.coverKind else inheritedKind,
                value = if (ownCover) folder.coverValue else inheritedValue,
                shape = if (ownCover) folder.tileShape else inheritedShape,
                name = folder.name,
                animateGif = folder.gifAlways || (animateGif && !tvQuiet),
                cornerRadius = coverRadius,
                ownGlass = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            if (!folder.hideTitle) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
            }
            if (folder.sources.isEmpty()) {
                Text(
                    tr("Empty folder"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
            }
        }
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
            // The folder's own hero, when the file (or the editor) gave it one:
            // a wide backdrop above the header and, above all, a wordmark in
            // place of the title text — the way the reference app opens a
            // catalog folder.
            //
            // With no hero set, the folder's own COVER opens the page: that is
            // the artwork the user picked for this folder and the one they tapped
            // to get here, so leaving the space above the title empty made the
            // page look like it had lost its header ("the image header in the
            // catalog — make it show"). Only a URL/GIF cover can be a backdrop;
            // an emoji or a plain colour stays on the tile.
            heroBackdrop = folder.heroBackdropUrl.ifBlank { folderCoverUrl(folder) },
            titleLogo = folder.titleLogoUrl,
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
        FolderRowList(
            nav = nav,
            sources = folder.sources,
            loaded = loaded,
            keeperKey = "colFolder:${collection.id}:${folder.id}",
            // One folder on screen: its own header says which one, so the rows
            // do not repeat it (see FolderRowList.showProviderName).
            showProviderName = false,
            emptyTitle = tr("Nothing here right now"),
            emptySubtitle = tr(
                "This folder's catalogs returned no content. Check the extension's " +
                    "site, or edit the folder to pick another catalog."
            ),
        )
    }
}

@Composable
private fun FolderRowList(
    nav: NavHostController,
    sources: List<CatalogSource>,
    loaded: List<CatalogRow>?,
    emptyTitle: String,
    emptySubtitle: String,
    showProviderName: Boolean = true,
    /** ScrollKeeper slot for this row list (see ScrollKeeper): Back from a
     *  title lands where the rows were. Blank keeps a plain state. */
    keeperKey: String = "",
) {
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (loaded.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(title = emptyTitle, subtitle = emptySubtitle)
        }
        return
    }
    // Row-list position held per page (see ScrollKeeper). Keys re-find the
    // exact row when sources finish in a different order.
    val rowKeys = remember(loaded) { loaded.distinctBy { it.key }.map { it.key } }
    val rowListState = if (keeperKey.isBlank()) androidx.compose.foundation.lazy.rememberLazyListState()
    else com.hikari.app.ui.components.keptListState(keeperKey, itemKeys = rowKeys)
    LazyColumn(
        state = rowListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(loaded.distinctBy { it.key }, key = { it.key }) { row ->
            // The source this row came from — needed for "Show all".
            val src = sources.firstOrNull { it.key == row.catalogId }
            MediaRow(
                title = row.title,
                providerName = if (showProviderName) row.providerName else "",
                items = row.items,
                keeperKey = if (keeperKey.isBlank()) "" else "colRow:$keeperKey:${row.key}",
                onClick = { item ->
                    Routes.safeNavigate(
                        nav,
                        // Tracker manga opens in the manga section, searched
                        // across the installed manga engines — never as video.
                        if (item.rawType == "manga") {
                            Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                        } else {
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        }
                    )
                },
                // Hold-menu play matches Home: video arrives wanting playback
                // (see DetailScreen autoplay); manga opens in the manga section.
                onQuickPlay = { item ->
                    Routes.safeNavigate(
                        nav,
                        if (item.rawType == "manga") {
                            Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                        } else {
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType,
                                autoplay = true,
                            )
                        }
                    )
                },
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
                                    (src?.providerId ?: row.providerId).ifBlank { row.providerId },
                                    (src?.catalogId ?: row.catalogId).ifBlank { row.catalogId },
                                    row.title,
                                    row.providerName,
                                    src?.type ?: row.type,
                                    (src?.rawType ?: row.rawType).ifBlank { row.rawType },
                                )
                            )
                        }
                    }
                }
            )
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

/** The folder's cover as a URL, when it has one that can be drawn as a header. */
private fun folderCoverUrl(folder: CollectionFolder): String {
    val kind = CoverKinds.normalize(folder.coverKind)
    if (kind != CoverKinds.URL && kind != CoverKinds.GIF) return ""
    return folder.coverValue.trim()
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
    /** A wide image drawn ABOVE the title row — a folder's or collection's own
     *  hero, the way the reference app opens a catalog with its artwork. Null or
     *  blank draws the header exactly as it was. */
    heroBackdrop: String? = null,
    /** A transparent wordmark drawn in place of the title TEXT (a
     *  Netflix-style logo). Blank keeps the text title. */
    titleLogo: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        var heroFailed by remember(heroBackdrop) { mutableStateOf(false) }
        var logoFailed by remember(titleLogo) { mutableStateOf(false) }
        if (!heroBackdrop.isNullOrBlank() && !heroFailed) {
            AsyncImage(
                model = PosterLoader.model(heroBackdrop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { heroFailed = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(GlassShape),
            )
        }
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
                if (!titleLogo.isNullOrBlank() && !logoFailed) {
                    AsyncImage(
                        model = PosterLoader.model(titleLogo),
                        contentDescription = tr(title),
                        contentScale = ContentScale.Fit,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .height(38.dp)
                            .fillMaxWidth(0.62f),
                    )
                } else {
                    Text(
                        // Preset/catalog names are translated; a user-typed
                        // collection name isn't in the i18n files, so tr() hands
                        // it back unchanged.
                        tr(title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/** The kind chips under a TMDB catalog's own search box — All first, so the
 *  default is "everything this catalog holds" and one tap narrows it (see
 *  [TmdbGridScreen]). "Movies & series" is the pair TMDB's own genre pages
 *  answer with, so it is the one everything non-anime falls into. */
private const val KIND_ALL = "all"
private const val KIND_MOVIE = "movie"
private const val KIND_SERIES = "series"
private const val KIND_ANIME = "anime"
private const val KIND_MOVIE_SERIES = "movie_series"

private val KIND_CHIPS: List<Pair<String, String>> = listOf(
    KIND_ALL to "All",
    KIND_MOVIE to "Movies",
    KIND_SERIES to "Series",
    KIND_ANIME to "Anime",
    KIND_MOVIE_SERIES to "Movies & series",
)

@Composable
fun TmdbGridScreen(
    nav: NavHostController,
    presetKey: String,
    title: String,
    specJson: String = "",
    /** Active genre this grid was opened for (see Routes.tmdbGridSpec): shown
     *  as a removable chip row, blank on every non-genre grid. */
    genreChip: String = "",
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
    val gridItems = rememberVisibleItems(items)

    var filterText by rememberSaveable(specJson, presetKey) { mutableStateOf("") }
    val needle = filterText.trim()
    val matched = remember(gridItems, needle) {
        if (needle.isBlank()) gridItems
        else gridItems.filter {
            it.title.contains(needle, ignoreCase = true) ||
                (it.originalTitle?.contains(needle, ignoreCase = true) == true)
        }
    }
    var kindKey by rememberSaveable(specJson, presetKey) { mutableStateOf(KIND_ALL) }
    val shown = remember(matched, kindKey) {
        when (kindKey) {
            KIND_MOVIE -> matched.filter { it.type == MediaType.MOVIE && !it.looksAnime() }
            KIND_SERIES -> matched.filter { it.type == MediaType.SERIES && !it.looksAnime() }
            KIND_ANIME -> matched.filter { it.looksAnime() }
            KIND_MOVIE_SERIES -> matched.filter {
                (it.type == MediaType.MOVIE || it.type == MediaType.SERIES) && !it.looksAnime()
            }
            else -> matched
        }
    }
    var deepened by remember { mutableIntStateOf(0) }
    LaunchedEffect(needle, kindKey) { deepened = 0 }
    // "The reader has narrowed this catalog" — by a name, by a kind, or both.
    // Only then is an empty list worth walking the catalog for; a catalog that
    // is simply still loading its first page is not.
    val narrowed = needle.isNotBlank() || kindKey != KIND_ALL
    LaunchedEffect(needle, kindKey, shown.isEmpty(), loading, done) {
        if (narrowed && shown.isEmpty() && !loading && !done && deepened < 8) {
            deepened++
            vm.loadNext()
        }
    }

    val tmdbKeys = remember(shown) { shown.map { it.uniqueId } }
    val gridState = com.hikari.app.ui.components.keptGridState(
        "tmdbGrid:$presetKey|$specJson|$needle|$kindKey",
        itemKeys = tmdbKeys,
        onNeedMore = { if (!done && !loading) vm.loadNext() },
    )
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
        GlassSearchField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = if (genreChip.isNotBlank()) tr("Search this genre") else tr("Search this catalog"),
            height = 46.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
        if (genreChip.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = true,
                    onClick = { nav.popBackStack() },
                    label = { Text(genreChip) },
                    trailingIcon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.Close,
                            contentDescription = tr("Clear"),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        labelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    tr("Filtered") + " · " + (spec?.let { TmdbSources.detail(it) } ?: preset?.detail ?: "TMDB"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { nav.popBackStack() }) { Text(tr("Clear")) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((key, label) in KIND_CHIPS) {
                FilterChip(
                    selected = kindKey == key,
                    onClick = { kindKey = key },
                    label = { Text(tr(label)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            if (shown.isNotEmpty()) {
                Text(
                    tr("%s shown").replace("%s", shown.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (shown.isEmpty() && narrowed && !done) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr("Looking further into this catalog…"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (shown.isEmpty() && narrowed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("Nothing matches this filter"),
                    subtitle = tr("This catalog has no titles of that kind — try All."),
                )
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = tr("Nothing here right now"),
                    subtitle = tr("TMDB returned no titles for this catalog."),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(84)),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(shown, key = { _, it -> it.uniqueId }) { index, item ->
                    com.hikari.app.ui.components.StaggerIn(index = index) {
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
    val cinema = style.isCinema(com.hikari.app.tv.TvMode.current())
    val hero = if (cinema) Artwork.heroModel(item) else null
    Column(
        Modifier
            .clickable(onClick = onClick)
    ) {
        PosterArt(
            model = if (cinema) hero?.first else Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
            imdb = badge,
            item = item,
            contentScale = ContentScale.Crop,
            imageAlignment = if (cinema) com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT else Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (cinema) 16f / 9f else 2f / 3f),
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


@Composable
fun CollectionGridScreen(nav: NavHostController, collectionId: String) {
    val app = LocalContext.current.applicationContext as HikariApp
    val style = rememberPosterStyle()
    val repo = remember { CollectionsRepository(app.providers) }
    var collection by remember(collectionId) { mutableStateOf<Collection?>(null) }
    var rows by remember(collectionId) { mutableStateOf<List<CatalogRow>?>(null) }
    /** True when this page has nothing of its own to draw and hands over to the
     *  collection's folder tiles (see the LaunchedEffect below). */
    var folderHandover by remember(collectionId) { mutableStateOf(false) }
    /** True when the id names no collection at all. */
    var missing by remember(collectionId) { mutableStateOf(false) }

    suspend fun decorate(list: List<CatalogRow>): List<CatalogRow> = withContext(Dispatchers.IO) {
        list.map { row -> row.copy(items = row.items.map { it.tokenized() }) }
    }

    LaunchedEffect(collectionId) {
        val c = withContext(Dispatchers.IO) { app.store.collection(collectionId) }
        collection = c
        if (c == null) {
            missing = true
            rows = emptyList()
            return@LaunchedEffect
        }
        var landed = false
        runCatching {
            repo.allRows(c).collect { r ->
                if (r.isNotEmpty()) {
                    landed = true
                    rows = decorate(r)
                }
            }
        }
        if (landed) return@LaunchedEffect
        val picked = runCatching { repo.pickRows(c).lastOrNull() }.getOrNull().orEmpty()
        if (picked.isNotEmpty()) {
            rows = decorate(picked)
            return@LaunchedEffect
        }
        rows = emptyList()
        folderHandover = true
    }

    // A page with nothing to show IS the folder page — it carries the
    // collection's own header and summary, so there is no second header here.
    val handoverTo = collection
    if (folderHandover && handoverTo != null) {
        CollectionFoldersPage(nav = nav, collection = handoverTo)
        return
    }

    val loaded = rows
    Column(Modifier.fillMaxSize()) {
        val titleCount = loaded?.sumOf { it.items.size } ?: 0
        val catalogCount = loaded?.size ?: 0
        PageHeader(
            title = when {
                missing -> tr("Collection not found")
                else -> collection?.name ?: tr("Loading…")
            },
            subtitle = if (missing || loaded == null) "" else
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
        if (missing) return@Column
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
        val colKeys = remember(loaded) {
            buildList {
                loaded.forEachIndexed { rowIndex, row ->
                    add("shelf|$rowIndex|" + row.key)
                    row.items.distinctBy { it.uniqueId }.forEach { item ->
                        add("$rowIndex|" + row.key + "|" + item.uniqueId)
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(84)),
            state = com.hikari.app.ui.components.keptGridState("colGrid:$collectionId", itemKeys = colKeys),
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
