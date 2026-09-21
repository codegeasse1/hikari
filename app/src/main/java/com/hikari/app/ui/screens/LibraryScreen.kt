package com.hikari.app.ui.screens
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.LibraryCategory
import com.hikari.app.data.MediaItem
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.components.CategoryManagerSheet
import com.hikari.app.ui.components.CategoryPickerSheet
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.shape
import kotlinx.coroutines.launch

/**
 * The Library: every title saved with the player's heart, filed into the user's
 * own categories.
 *
 * The category chips above the grid are a FILTER, not folders — a title can be
 * both a Series and an Action, so it appears under both chips. "Move to" on a
 * card (and the Library button on a detail page) opens the same picker, and the
 * picker can invent a new category on the spot.
 */
@Composable
fun LibraryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    // Remembered Flow instances — an inline `store.favoritesFlow()` is a NEW Flow
    // on every recomposition, so collectAsState would re-subscribe from scratch
    // and reset to `initial` (empty) each time.
    val favoritesFlow = remember { app.store.favoritesFlow() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    // The store appends a newly-saved title at the END of the list, so the
    // newest is first here (and legacy duplicates collapse to one card).
    val saved = remember(favorites) { favorites.asReversed().distinctBy { it.uniqueId } }

    val categoriesFlow = remember { app.store.libraryCategoriesFlow() }
    val categories by categoriesFlow.collectAsState(initial = LibraryCategory.DEFAULTS)
    val filedFlow = remember { app.store.favoriteCategoriesFlow() }
    val filed by filedFlow.collectAsState(initial = emptyMap())

    var activeCategory by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<MediaItem?>(null) }
    var managing by remember { mutableStateOf(false) }
    val style = rememberPosterStyle()

    // A category that was just deleted must not keep filtering the grid.
    LaunchedEffect(categories) {
        val id = activeCategory
        if (id != null && categories.none { it.id == id }) activeCategory = null
    }

    val shown = remember(saved, activeCategory, filed) {
        val id = activeCategory
        if (id == null) saved else saved.filter { filed[it.uniqueId].orEmpty().contains(id) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(104)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            // Clear of the floating taskbar (LocalTaskbarInset is 0 on a page
            // with no bar).
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Library"),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (saved.isEmpty()) I18n.t("Titles you save from the player show up here.")
                            else I18n.t(if (saved.size == 1) "%s title saved" else "%s titles saved").replace("%s", saved.size.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { managing = true }) {
                        Icon(
                            Icons.Filled.Category,
                            contentDescription = tr("Library categories"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item(key = "library-chips", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "chip-all") {
                        LibraryChip(
                            label = tr("All"),
                            count = saved.size,
                            selected = activeCategory == null,
                        ) { activeCategory = null }
                    }
                    items(categories, key = { "chip-${it.id}" }) { c ->
                        LibraryChip(
                            label = c.name,
                            count = saved.count { filed[it.uniqueId].orEmpty().contains(c.id) },
                            selected = activeCategory == c.id,
                        ) { activeCategory = if (activeCategory == c.id) null else c.id }
                    }
                }
            }
        }
        if (saved.isEmpty()) {
            item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = tr("Your library is empty"),
                    subtitle = tr("Tap the heart in the player while watching a movie or series " + "and it will be saved here for later."),
                    actionLabel = tr("Browse"),
                    action = { Routes.navigateTab(nav, Routes.HOME) },
                )
            }
        } else if (shown.isEmpty()) {
            item(key = "library-none", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr("Nothing filed under this category yet."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Saved titles can repeat (the same film filed twice, or stored
            // twice by an older build): a duplicated Lazy key is a crash, so the
            // grid is built from the distinct set.
            items(shown.distinctBy { it.uniqueId }, key = { it.uniqueId }) { item ->
                LibraryCard(
                    item = item,
                    style = style,
                    categories = categories.filter { it.id in filed[item.uniqueId].orEmpty() },
                    onClick = {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                providerId = item.providerId,
                                type = item.type,
                                mediaId = item.id,
                                title = item.title,
                                posterUrl = item.posterUrl,
                                rawType = item.rawType,
                            )
                        )
                    },
                    onMove = { moving = item },
                    onRemove = { scope.launch { app.store.removeFavorite(item.uniqueId) } },
                )
            }
        }
    }

    val movingItem = moving
    if (movingItem != null) {
        CategoryPickerSheet(
            title = tr("Move to"),
            subtitle = movingItem.title,
            categories = categories,
            selected = filed[movingItem.uniqueId].orEmpty(),
            confirmLabel = tr("Done"),
            onConfirm = { picked ->
                scope.launch { app.store.setFavoriteCategories(movingItem.uniqueId, picked) }
                moving = null
            },
            onCreateCategory = { name ->
                val c = app.store.addLibraryCategory(name)
                app.store.addFavoriteCategories(movingItem.uniqueId, setOf(c.id))
                c
            },
            onDismiss = { moving = null },
        )
    }

    if (managing) {
        CategoryManagerSheet(
            categories = categories,
            onRename = { id, name -> scope.launch { app.store.renameLibraryCategory(id, name) } },
            onDelete = { id -> scope.launch { app.store.removeLibraryCategory(id) } },
            onCreate = { name -> scope.launch { app.store.addLibraryCategory(name) } },
            onDismiss = { managing = false },
        )
    }
}

/** One filter pill above the grid. */
@Composable
private fun LibraryChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: MediaItem,
    style: PosterStyle,
    categories: List<LibraryCategory>,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
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
            // The top-right corner is this card's own remove button and the
            // bottom-right its "Move to", so the score takes the free corner.
            ratingAlignment = Alignment.BottomStart,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = tr("Remove from library"),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            // "Move to": the same picker the detail page uses, pre-ticked with
            // where the title is filed right now.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onMove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.LibraryAdd,
                    contentDescription = tr("Move to"),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (style.showTitles) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
            )
            if (categories.isNotEmpty()) {
                Text(
                    categories.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
                )
            }
        }
    }
}
