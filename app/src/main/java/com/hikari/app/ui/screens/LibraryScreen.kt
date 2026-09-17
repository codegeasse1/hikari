package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.launch

/**
 * The Library: every title saved with the player's heart. Backed by the same
 * favourites store the detail/player screens write to (`store.favoritesFlow`),
 * shown as a poster grid so a large library is browsable, with a heart badge on
 * each card that removes just that title.
 */
@Composable
fun LibraryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    // Remembered Flow instance — an inline `store.favoritesFlow()` is a NEW Flow
    // on every recomposition, so collectAsState would re-subscribe from scratch
    // and reset to `initial` (empty) each time.
    val favoritesFlow = remember { app.store.favoritesFlow() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    // The store appends a newly-saved title at the END of the list, so the
    // newest is first here (and legacy duplicates collapse to one card).
    val saved = remember(favorites) { favorites.asReversed().distinctBy { it.uniqueId } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 2.dp)) {
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
        } else {
            items(saved, key = { it.uniqueId }) { item ->
                LibraryCard(
                    item = item,
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
                    onRemove = { scope.launch { app.store.removeFavorite(item.uniqueId) } },
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(item: MediaItem, onClick: () -> Unit, onRemove: () -> Unit) {
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
            // Behind the artwork: a cell whose poster 403s/404s still reads as a
            // poster slot instead of a blank rectangle.
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(28.dp),
            )
            AsyncImage(
                model = Artwork.model(item),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
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
        }
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
