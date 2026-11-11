package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hikari.app.data.Genres
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TmdbSourceType
import com.hikari.app.data.TmdbSources
import com.hikari.app.data.TmdbSpec
import com.hikari.app.i18n.tr
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.StaggerIn
import java.util.concurrent.ConcurrentHashMap

/** Backdrop art behind each genre card. Loaded lazily (only cards that scroll
 *  into view fetch), cached for the session; Coil's own disk cache keeps the
 *  bytes across restarts. A genre whose lookup fails simply keeps its dark
 *  gradient — the grid never shows an empty box for it. */
object GenreArt {
    private val cache = ConcurrentHashMap<String, String?>()
    /** Genres whose lookup already came back empty: not an error, just no
     *  art — remembered so a scroll doesn't refetch them every time. Kept
     *  apart from [cache] because that map (like every ConcurrentHashMap)
     *  throws on null values, which is what crashed 0.12.84 on opening. */
    private val dead = ConcurrentHashMap.newKeySet<String>()

    fun cached(name: String): String? =
        if (dead.contains(name)) null else cache[name]

    suspend fun artFor(name: String, genresText: String, keywordsText: String): String? {
        cache[name]?.let { return it }
        if (dead.contains(name)) return null
        val art = runCatching {
            val items: List<MediaItem> = TmdbSources.page(
                TmdbSpec(
                    type = TmdbSourceType.DISCOVER,
                    media = "all",
                    genresText = genresText,
                    keywords = keywordsText,
                    sort = "popularity.desc",
                    title = name,
                ),
                1,
            )
            items.firstOrNull { !it.backdropUrl.isNullOrBlank() }?.backdropUrl
                ?: items.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl
        }.getOrNull()
        if (art != null) cache[name] = art else dead.add(name)
        return art
    }
}

/** Full-screen genre browser: every Hikari genre as an image card in a
 *  two-column grid, with a search field on top. A tap reports the genre
 *  through [onPick] (the caller applies the same everything-vs-extension
 *  choice the home strip uses, then opens the grid). */
@Composable
fun GenresOverlay(
    onClose: () -> Unit,
    onPick: (name: String, genresText: String, keywordsText: String) -> Unit,
) {
    BackHandler(onBack = onClose)
    val all = remember {
        Genres.ALL.map { name ->
            val ids = Genres.tmdbGenreFilter(name)
            HomeGenre(
                name = name,
                genresText = ids,
                keywordsText = if (ids.isNotEmpty()) ""
                else Genres.keywordCandidates(name).joinToString("|"),
            )
        }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all
        else all.filter { it.name.lowercase().contains(q) }
    }
    val tv = com.hikari.app.tv.TvMode.current()
    Column(
        Modifier
            .fillMaxSize()
            .background(
                if (tv) Color.Black
                else MaterialTheme.colorScheme.background
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = if (tv) com.hikari.app.tv.TvDesktop.TextPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                tr("Genres"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (tv) com.hikari.app.tv.TvDesktop.TextPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        GlassSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("Search genres"),
            height = 46.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    tr("No genre matches") + " \u201c${query.trim()}\u201d",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tv) com.hikari.app.tv.TvDesktop.TextMuted
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(shown, key = { _, g -> g.name }) { index, g ->
                    StaggerIn(index = index) {
                        GenreCard(name = g.name, onClick = { onPick(g.name, g.genresText, g.keywordsText) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreCard(name: String, onClick: () -> Unit) {
    var art by remember(name) { mutableStateOf(GenreArt.cached(name)) }
    LaunchedEffect(name) {
        if (art == null) {
            val ids = Genres.tmdbGenreFilter(name)
            val kw = if (ids.isNotEmpty()) "" else Genres.keywordCandidates(name).joinToString("|")
            art = GenreArt.artFor(name, ids, kw)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.8f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                ),
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
