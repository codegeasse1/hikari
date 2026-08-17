package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.player.PlayerActivity
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers

    private val _meta = MutableStateFlow<MediaItem?>(null)
    val meta: StateFlow<MediaItem?> = _meta.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>?>(null)
    val episodes: StateFlow<List<Episode>?> = _episodes.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Streams resolved ahead of time (first episode / movie) so tapping Play
     *  or the first episode starts instantly instead of waiting 20-30s for
     *  extraction. Keyed by the target id. */
    private val streamCache = ConcurrentHashMap<String, List<StreamSource>>()

    private val _streamsReady = MutableStateFlow(false)
    val streamsReady: StateFlow<Boolean> = _streamsReady.asStateFlow()

    fun load(providerId: String, type: MediaType, mediaId: String, title: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _streamsReady.value = false
            val provider = manager.byId(providerId)
            if (provider == null) {
                _error.value = "Provider not found"
                _loading.value = false
                return@launch
            }
            val base = MediaItem(providerId, mediaId, title, type)
            var metaDone = false
            withContext(Dispatchers.IO) {
                runCatching {
                    _meta.value = provider.getMeta(base)
                    _episodes.value = provider.getEpisodes(base)
                }.onFailure {
                    _error.value = it.message ?: "Failed to load"
                }
                metaDone = true
            }
            _loading.value = false
            if (metaDone) prefetchFirstStreams(provider, base)
        }
    }

    /** While the user is still reading the detail page, resolve sources for the
     *  movie or the first episode so the player starts immediately on tap. */
    private suspend fun prefetchFirstStreams(provider: ContentProvider, base: MediaItem) {
        val target = if (_episodes.value.isNullOrEmpty()) {
            base to null
        } else {
            val first = _episodes.value!!.sortedBy { it.number }.firstOrNull()
            if (first == null) return
            base to first
        }
        val (item, ep) = target
        val key = cacheKey(item, ep)
        if (streamCache.containsKey(key)) {
            _streamsReady.value = true
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { provider.getStreams(item, ep) }.getOrDefault(emptyList())
        }
        streamCache[key] = result
        _streamsReady.value = true
    }

    private fun cacheKey(item: MediaItem, ep: Episode?): String =
        item.providerId + "|" + item.id + "|" + (ep?.id ?: "")

    suspend fun getStreams(episode: Episode?): List<StreamSource> {
        val m = _meta.value ?: return emptyList()
        val key = cacheKey(m, episode)
        streamCache[key]?.let { return it }
        val provider = manager.byId(m.providerId) ?: return emptyList()
        val result = withContext(Dispatchers.IO) {
            runCatching { provider.getStreams(m, episode) }.getOrDefault(emptyList())
        }
        streamCache[key] = result
        return result
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    nav: NavHostController,
    providerId: String,
    type: MediaType,
    mediaId: String,
    title: String,
) {
    val vm: DetailViewModel = viewModel()
    val meta by vm.meta.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val streamsReady by vm.streamsReady.collectAsState()
    val m = meta

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
    var selectedEp by remember { mutableStateOf<Episode?>(null) }
    var streams by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var loadingStreams by remember { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var rangeExpanded by remember { mutableStateOf(false) }

    val sortedEps = remember(episodes) { episodes.orEmpty().sortedBy { it.number } }
    val ranges = remember(sortedEps) {
        if (sortedEps.isEmpty()) emptyList()
        else {
            val lo = sortedEps.minOf { it.number }
            val hi = sortedEps.maxOf { it.number }
            (lo..hi step 30).map { s -> s to minOf(s + 29, hi) }
        }
    }

    LaunchedEffect(providerId, mediaId) {
        vm.load(providerId, type, mediaId, title)
    }

    val openStreams: (Episode?) -> Unit = { ep ->
        // Show the sheet + spinner IMMEDIATELY, then resolve sources in the
        // background. Otherwise a slow provider looks like a dead click.
        selectedEp = ep
        streams = emptyList()
        loadingStreams = true
        showSheet = true
        scope.launch {
            streams = vm.getStreams(ep)
            loadingStreams = false
            val playable = streams.filter { !it.isTorrent && it.url.isNotBlank() }
            if (playable.isNotEmpty()) {
                // Build the intent payload defensively — a single bad header
                // value must never crash the whole app.
                val payload = runCatching {
                    JSONArray().apply {
                        playable.forEach { s ->
                            put(
                                JSONObject()
                                    .put("name", s.name)
                                    .put("url", s.url)
                                    .put("headers", JSONObject(s.headers))
                                    .put("isM3u8", s.isM3u8)
                                    .put("isMpd", s.isMpd)
                                    .put(
                                        "subtitles",
                                        JSONArray().apply {
                                            s.subtitles.forEach {
                                                put(
                                                    JSONObject()
                                                        .put("lang", it.lang)
                                                        .put("url", it.url)
                                                )
                                            }
                                        }
                                    )
                            )
                        }
                    }.toString()
                }.getOrNull()
                if (payload != null && playable.isNotEmpty()) {
                    showSheet = false
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java).apply {
                            putExtra("title", m?.title ?: title)
                            putExtra("sources", payload)
                        }
                    )
                }
            }
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null && meta == null -> Box(Modifier.fillMaxSize()) {
            EmptyState("Something went wrong", error.orEmpty(), "Back", { nav.popBackStack() })
        }
        else -> {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Hero(meta, onBack = { nav.popBackStack() }) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            m?.title ?: title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (m?.year != null) {
                            Text(
                                "${m.year}  ·  ${m.type.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (!m?.genres.isNullOrEmpty()) {
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                m!!.genres.take(4).forEach { g ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            g,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        if (!m?.overview.isNullOrBlank()) {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                m!!.overview!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clickable { expanded = !expanded }
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                // Type reporting varies wildly across .cs3 plugins, so only use
                // it as a hint: show the episode list whenever the item is a
                // series OR the provider actually returned episodes, and always
                // give mislabeled/unknown items a Play button so nothing is
                // ever unplayable.
                val isSeries = m?.type == MediaType.SERIES || (episodes?.isNotEmpty() == true)
                val canPlay = m?.type != MediaType.SERIES || episodes.isNullOrEmpty()
                if (canPlay) {
                    item {
                        Button(
                            onClick = { openStreams(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            if (streamsReady) {
                                Text("Play")
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Preparing…")
                            }
                        }
                    }
                }
                if (isSeries) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Episodes (${sortedEps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (ranges.size > 1) {
                                Box {
                                    OutlinedButton(onClick = { rangeExpanded = true }) {
                                        Text(
                                            rangeStart?.let { s -> "$s - ${s + 29}" }
                                                ?: "All episodes"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = rangeExpanded,
                                        onDismissRequest = { rangeExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("All episodes") },
                                            onClick = { rangeStart = null; rangeExpanded = false }
                                        )
                                        ranges.forEach { (start, end) ->
                                            DropdownMenuItem(
                                                text = { Text("$start - $end") },
                                                onClick = {
                                                    rangeStart = start
                                                    rangeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (sortedEps.isEmpty()) {
                        item {
                            Text(
                                "No episode list available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        val shownEps = if (rangeStart == null) sortedEps
                        else sortedEps.filter {
                            it.number >= rangeStart!! && it.number <= rangeStart!! + 29
                        }
                        // key MUST be unique — plugins (MoviesMod, …) emit
                        // duplicate ids/numbers per quality group, and a
                        // duplicate Compose key crashes the whole screen.
                        shownEps.forEachIndexed { index, ep ->
                            item(key = "ep-$index") {
                                EpisodeRow(ep) { openStreams(ep) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                selectedEp?.let { "Episode ${it.number}" } ?: "Playback sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                m?.title ?: title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            when {
                loadingStreams -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                streams.isEmpty() -> Column(
                    Modifier.padding(24.dp)
                ) {
                    Text(
                        "No playable sources found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val streamErr = com.hikari.app.cs3.Cs3MainApiProvider.lastStreamsError
                    if (streamErr != null) {
                        Text(
                            streamErr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    val tookMs = com.hikari.app.cs3.Cs3MainApiProvider.lastStreamsTimeMs
                    if (tookMs > 0) {
                        Text(
                            "Looked for sources for ${tookMs / 1000}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(streams) { index, s ->
                        val enabled = !s.isTorrent && s.url.isNotBlank()
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = {
                                Text(
                                    when {
                                        s.isTorrent -> "Torrent — engine coming in Stage 2"
                                        s.url.contains(".m3u8", true) -> "HLS"
                                        else -> "Direct"
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (s.isTorrent) Icons.Filled.Warning else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = enabled) {
                                    showSheet = false
                                    context.startActivity(
                                        Intent(context, PlayerActivity::class.java).apply {
                                            putExtra("title", m?.title ?: title)
                                            putExtra(
                                                "sources",
                                                runCatching {
                                                    JSONArray().apply {
                                                        put(
                                                            JSONObject()
                                                                .put("name", s.name)
                                                                .put("url", s.url)
                                                                .put("headers", JSONObject(s.headers))
                                                                .put("isM3u8", s.isM3u8)
                                                                .put("isMpd", s.isMpd)
                                                                .put(
                                                                    "subtitles",
                                                                    JSONArray().apply {
                                                                        s.subtitles.forEach {
                                                                            put(
                                                                                JSONObject()
                                                                                    .put("lang", it.lang)
                                                                                    .put("url", it.url)
                                                                            )
                                                                        }
                                                                    }
                                                                )
                                                        )
                                                    }.toString()
                                                }.getOrNull().orEmpty()
                                            )
                                        }
                                    )
                                }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Hero(meta: MediaItem?, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        val img = (meta?.backdropUrl ?: meta?.posterUrl)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (img != null) {
            AsyncImage(
                model = img,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumb = ep.image?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ep.number.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            ep.name?.ifBlank { "Episode ${ep.number}" } ?: "Episode ${ep.number}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
