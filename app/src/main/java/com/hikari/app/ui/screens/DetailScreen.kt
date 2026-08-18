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
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.player.PlayerActivity
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.web.WebViewActivity
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
    private val repo = ContentRepository(manager)

    private val _meta = MutableStateFlow<MediaItem?>(null)
    val meta: StateFlow<MediaItem?> = _meta.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>?>(null)
    val episodes: StateFlow<List<Episode>?> = _episodes.asStateFlow()

    /** True while the origin addon is still listing episodes (so the UI shows
     *  a spinner instead of a misleading "no episodes" for the first seconds). */
    private val _episodesLoading = MutableStateFlow(false)
    val episodesLoading: StateFlow<Boolean> = _episodesLoading.asStateFlow()

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

    /** How many addons were asked for sources on the last lookup. */
    private val _searchedProviders = MutableStateFlow(0)
    val searchedProviders: StateFlow<Int> = _searchedProviders.asStateFlow()

    /** Reason the last lookup came up empty (origin addon's message). */
    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    /** The set of addons asked for sources, Stremio-style: every installed
     *  Stremio addon plus the origin provider itself (so CS3 plugins and
     *  universal scrapers keep their own pipeline). */
    private fun streamTargets(item: MediaItem): List<ContentProvider> =
        manager.providers.value.filter {
            it.config.enabled &&
                (it.config.type == ProviderType.STREMIO || it.config.id == item.providerId)
        }

    private fun recordOutcome(result: List<StreamSource>, item: MediaItem) {
        _searchedProviders.value = streamTargets(item).size
        if (result.isEmpty()) {
            _streamError.value =
                com.hikari.app.providers.StremioAddon.streamErrors[item.providerId]
                    ?: com.hikari.app.cs3.Cs3MainApiProvider.lastStreamsError
        } else {
            _streamError.value = null
        }
    }

    fun load(providerId: String, type: MediaType, mediaId: String, title: String, posterUrl: String?, rawType: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _streamsReady.value = false
            _streamError.value = null
            if (manager.byId(providerId) == null) {
                _error.value = "Provider not found"
                _loading.value = false
                return@launch
            }
            // The catalog row already carries the poster — render the page
            // immediately instead of waiting on the origin's /meta (which may
            // be slow or minimal). rawType keeps the addon's own type string
            // for meta/episode/stream URLs.
            val base = MediaItem(
                providerId, mediaId, title, type,
                posterUrl = posterUrl,
                rawType = rawType,
            )
            _meta.value = base
            _loading.value = false
            withContext(Dispatchers.IO) {
                // Fetch meta FIRST — CS3 plugins can label a series/actor page
                // as a movie on their search results (LeakPorner actors are
                // NSFW→MOVIE), and getMeta corrects the type from the
                // LoadResponse. Episodes are then fetched against the
                // CORRECTED item (loadResponse is cached, so this stays a
                // single origin fetch) — fetching against the raw base would
                // leave the episode grid empty for every mis-typed item.
                val meta = runCatching { repo.metaFor(base) }.getOrDefault(base)
                _meta.value = meta
                _episodesLoading.value = true
                try {
                    _episodes.value = runCatching { repo.episodesFor(meta) }.getOrNull()
                } finally {
                    _episodesLoading.value = false
                }
            }
            prefetchFirstStreams(base)
        }
    }

    /** While the user is still reading the detail page, resolve sources for the
     *  movie or the first episode so the player starts immediately on tap. */
    private suspend fun prefetchFirstStreams(base: MediaItem) {
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
            runCatching { repo.streamsFor(item, ep) }.getOrDefault(emptyList())
        }
        streamCache[key] = result
        recordOutcome(result, item)
        _streamsReady.value = true
    }

    private fun cacheKey(item: MediaItem, ep: Episode?): String =
        item.providerId + "|" + item.id + "|" + (ep?.id ?: "")

    suspend fun getStreams(episode: Episode?): List<StreamSource> {
        val m = _meta.value ?: return emptyList()
        val key = cacheKey(m, episode)
        streamCache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            runCatching { repo.streamsFor(m, episode) }.getOrDefault(emptyList())
        }
        streamCache[key] = result
        recordOutcome(result, m)
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
    posterUrl: String? = null,
    rawType: String = "",
) {
    val vm: DetailViewModel = viewModel()
    val meta by vm.meta.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val episodesLoading by vm.episodesLoading.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val streamsReady by vm.streamsReady.collectAsState()
    val searchedProviders by vm.searchedProviders.collectAsState()
    val streamError by vm.streamError.collectAsState()
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
        vm.load(providerId, type, mediaId, title, posterUrl, rawType)
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
            // Player-able sources: direct URLs AND torrents (the player boots
            // the bundled TorrServer engine). YouTube/external sources open in
            // the web view instead.
            val playable = streams.filter { s ->
                s.ytId == null && !s.externalUrl && (s.url.isNotBlank() || s.isTorrent)
            }
            // With a single playable source we go straight to the player. When
            // there are several (e.g. multiple Torrentio servers for one movie)
            // keep the sheet open so the user picks a source — exactly how
            // Stremio presents them.
            if (playable.size == 1) {
                val payload = playerPayload(playable)
                if (payload != null) {
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
                            if (episodesLoading) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "Loading episodes…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    "No episode list available.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
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
                    if (searchedProviders > 0) {
                        Text(
                            "Searched $searchedProviders addon${if (searchedProviders == 1) "" else "s"} for sources.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    val err = streamError
                    if (err != null) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(streams) { index, s ->
                        val enabled = when {
                            s.ytId != null -> true
                            s.externalUrl -> s.url.isNotBlank()
                            s.isTorrent -> true
                            else -> s.url.isNotBlank()
                        }
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = {
                                Text(
                                    when {
                                        s.isTorrent -> "Torrent — streams from peers"
                                        s.ytId != null -> "YouTube"
                                        s.externalUrl -> "Opens in web view"
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
                                    when {
                                        s.ytId != null -> {
                                            showSheet = false
                                            context.startActivity(
                                                Intent(context, WebViewActivity::class.java).apply {
                                                    putExtra("url", "https://www.youtube.com/watch?v=${s.ytId}")
                                                    putExtra("title", (m?.title ?: title) + " — YouTube")
                                                }
                                            )
                                        }
                                        s.externalUrl -> {
                                            showSheet = false
                                            context.startActivity(
                                                Intent(context, WebViewActivity::class.java).apply {
                                                    putExtra("url", s.url)
                                                    putExtra("title", m?.title ?: title)
                                                }
                                            )
                                        }
                                        else -> {
                                            showSheet = false
                                            context.startActivity(
                                                Intent(context, PlayerActivity::class.java).apply {
                                                    putExtra("title", m?.title ?: title)
                                                    putExtra("sources", playerPayload(listOf(s)).orEmpty())
                                                }
                                            )
                                        }
                                    }
                                }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Builds the PlayerActivity "sources" JSON payload for the given streams,
 *  carrying torrent metadata so the player can spin up TorrServer. */
private fun playerPayload(streams: List<StreamSource>): String? = runCatching {
    JSONArray().apply {
        streams.forEach { s ->
            put(
                JSONObject()
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("headers", JSONObject(s.headers))
                    .put("isM3u8", s.isM3u8)
                    .put("isMpd", s.isMpd)
                    .put("isTorrent", s.isTorrent)
                    .put("infoHash", s.infoHash ?: "")
                    .put("fileIdx", s.fileIdx ?: -1)
                    .put(
                        "trackers",
                        JSONArray().apply { s.trackers.forEach { put(it) } }
                    )
                    .put(
                        "subtitles",
                        JSONArray().apply {
                            s.subtitles.forEach {
                                put(JSONObject().put("lang", it.lang).put("url", it.url))
                            }
                        }
                    )
            )
        }
    }.toString()
}.getOrNull()

@Composable
private fun Hero(meta: MediaItem?, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        val img = PosterLoader.model(meta?.backdropUrl ?: meta?.posterUrl)
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
        val thumb = PosterLoader.model(ep.image)
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
