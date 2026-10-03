package com.hikari.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.data.AnimeReleaseCalendarRepository
import com.hikari.app.data.MediaType
import com.hikari.app.data.ReleaseCalendarRepository
import com.hikari.app.ui.navigation.Routes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.launch

private const val TAB_ANIME = 0
private const val TAB_MOVIES = 1
private const val TAB_SERIES = 2

@Composable
fun AnimeCalendarScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf(TAB_ANIME) }
    val pager = rememberPagerState(initialPage = tab, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab) {
        if (pager.currentPage != tab) runCatching { pager.animateScrollToPage(tab) }
    }
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != tab) tab = pager.settledPage
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (com.hikari.app.tv.TvMode.current()) {
            com.hikari.app.tv.TvScreenHeader(
                title = "Release Calendar",
                subtitle = when (tab) {
                    TAB_MOVIES -> "Upcoming movies"
                    TAB_SERIES -> "Airing series"
                    else -> "Anime episodes"
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(
                start = if (com.hikari.app.tv.TvMode.current()) 20.dp else 20.dp,
                end = 20.dp,
                top = if (com.hikari.app.tv.TvMode.current()) 4.dp else 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalTab("Anime", Icons.Filled.CalendarMonth, tab == TAB_ANIME, Modifier.weight(1f)) {
                tab = TAB_ANIME
                scope.launch { runCatching { pager.animateScrollToPage(TAB_ANIME) } }
            }
            CalTab("Movies", Icons.Filled.Movie, tab == TAB_MOVIES, Modifier.weight(1f)) {
                tab = TAB_MOVIES
                scope.launch { runCatching { pager.animateScrollToPage(TAB_MOVIES) } }
            }
            CalTab("Series", Icons.Filled.Tv, tab == TAB_SERIES, Modifier.weight(1f)) {
                tab = TAB_SERIES
                scope.launch { runCatching { pager.animateScrollToPage(TAB_SERIES) } }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                TAB_MOVIES -> TmdbCalendarPage(nav, ReleaseCalendarRepository.Kind.MOVIE)
                TAB_SERIES -> TmdbCalendarPage(nav, ReleaseCalendarRepository.Kind.TV)
                else -> AnimePage(nav)
            }
        }
    }
}

@Composable
private fun CalTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        tonalElevation = if (selected) 0.dp else 2.dp,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .com.hikari.app.tv.tvPress(previewPass = true, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AnimePage(nav: NavHostController) {
    var releases by remember { mutableStateOf<List<AnimeReleaseCalendarRepository.Release>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        loading = true
        releases = AnimeReleaseCalendarRepository.load()
        loading = false
    }

    val now = System.currentTimeMillis()
    val q = query.trim().lowercase()
    val upcoming = releases.filter { it.airAt >= now }.filter { q.isBlank() || it.title.lowercase().contains(q) }.take(80)
    val recent = releases.filter { it.airAt < now }.filter { q.isBlank() || it.title.lowercase().contains(q) }.sortedByDescending { it.airAt }.take(40)
    val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM · hh:mm a").withZone(ZoneId.systemDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Anime Calendar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Upcoming episodes and recently aired", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search anime") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (loading) {
            item { Text("Loading release schedule…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (releases.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(24.dp)) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No release schedule available", fontWeight = FontWeight.Bold)
                        Text(
                            "Connect a Simkl app in Trackers to load the current public anime calendar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item { Text("Upcoming", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(upcoming, key = { it.simklId.toString() + ":" + it.airAt + ":" + it.episode }) { release ->
                ReleaseCard(release, formatter) {
                    nav.navigate(Routes.detail("simkl", MediaType.SERIES, release.simklId.toString(), release.title, release.poster, "anime"))
                }
            }
            item { Text("Recently aired", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(recent, key = { "recent:" + it.simklId + ":" + it.airAt + ":" + it.episode }) { release ->
                ReleaseCard(release, formatter) {
                    nav.navigate(Routes.detail("simkl", MediaType.SERIES, release.simklId.toString(), release.title, release.poster, "anime"))
                }
            }
        }
    }
}

@Composable
private fun TmdbCalendarPage(nav: NavHostController, defaultKind: ReleaseCalendarRepository.Kind) {
    var kindKey by rememberSaveable { mutableStateOf(defaultKind.key) }
    val kind = runCatching { ReleaseCalendarRepository.Kind.valueOf(kindKey.uppercase()) }
        .getOrDefault(defaultKind)
    var region by rememberSaveable { mutableStateOf("US") }
    var countryOpen by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<ReleaseCalendarRepository.DayGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(kindKey, region, refresh) {
        loading = true
        groups = ReleaseCalendarRepository.load(kind, region)
        loading = false
    }

    val q = query.trim().lowercase()
    val shown = remember(groups, q) {
        if (q.isBlank()) groups
        else groups.mapNotNull { g ->
            val hits = g.entries.filter { it.title.lowercase().contains(q) }
            if (hits.isEmpty()) null else g.copy(entries = hits)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = kind == ReleaseCalendarRepository.Kind.MOVIE,
                    onClick = { kindKey = ReleaseCalendarRepository.Kind.MOVIE.key },
                    label = { Text("Movie") },
                )
                FilterChip(
                    selected = kind == ReleaseCalendarRepository.Kind.TV,
                    onClick = { kindKey = ReleaseCalendarRepository.Kind.TV.key },
                    label = { Text("TV") },
                )
                FilterChip(
                    selected = kind == ReleaseCalendarRepository.Kind.EPISODE,
                    onClick = { kindKey = ReleaseCalendarRepository.Kind.EPISODE.key },
                    label = { Text("TV Episode") },
                )
                Box {
                    TextButton(onClick = { countryOpen = true }) {
                        Text(ReleaseCalendarRepository.countryName(region), fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = countryOpen, onDismissRequest = { countryOpen = false }) {
                        ReleaseCalendarRepository.COUNTRIES.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(if (code == region) "$name ✓" else name) },
                                onClick = { region = code; countryOpen = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (kind == ReleaseCalendarRepository.Kind.MOVIE) "Search movies" else "Search series") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (loading) {
            item { Text("Loading release schedule…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (shown.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(24.dp)) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Nothing scheduled", fontWeight = FontWeight.Bold)
                        Text(
                            "No upcoming releases found for " + ReleaseCalendarRepository.countryName(region) + ".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            shown.forEach { group ->
                item(key = "day:" + kindKey + ":" + group.sortKey) {
                    Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(group.entries, key = { "e:" + kindKey + ":" + it.tmdbId }) { entry ->
                    TmdbReleaseRow(entry) {
                        nav.navigate(
                            Routes.detail(
                                "tmdb",
                                if (entry.kind == ReleaseCalendarRepository.Kind.MOVIE) MediaType.MOVIE else MediaType.SERIES,
                                entry.tmdbId.toString(),
                                entry.title,
                                entry.poster,
                                "",
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TmdbReleaseRow(entry: ReleaseCalendarRepository.Entry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .com.hikari.app.tv.tvPress(previewPass = true, onClick = onOpen)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.poster,
            contentDescription = entry.title,
            modifier = Modifier.size(width = 54.dp, height = 80.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                entry.title + (entry.year?.let { " ($it)" } ?: ""),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.genres.isNotEmpty()) {
                Text(
                    entry.genres.joinToString(" · "),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entry.blurb?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.Filled.Add, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ReleaseCard(
    release: AnimeReleaseCalendarRepository.Release,
    formatter: DateTimeFormatter,
    onClick: () -> Unit,
) {
    val delta = release.airAt - System.currentTimeMillis()
    val countdown = if (delta >= 0) {
        val mins = max(0L, delta / 60_000L)
        val days = mins / 1440
        val hours = (mins % 1440) / 60
        val minutes = mins % 60
        if (days > 0) days.toString() + "d " + hours + "h " + minutes + "m"
        else hours.toString() + "h " + minutes + "m"
    } else {
        val mins = max(0L, -delta / 60_000L)
        val days = mins / 1440
        if (days > 0) days.toString() + "d ago" else (mins / 60).toString() + "h ago"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = release.poster,
            contentDescription = release.title,
            modifier = Modifier.size(width = 72.dp, height = 104.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(release.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                release.episode?.let { "Episode " + it } ?: "Release",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            release.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatter.format(Instant.ofEpochMilli(release.airAt)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (delta >= 0) "In " + countdown else countdown,
                color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
