package com.hikari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.data.AnimeReleaseCalendarRepository
import com.hikari.app.data.MediaType
import com.hikari.app.ui.navigation.Routes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun AnimeCalendarScreen(nav: NavHostController) {
    var releases by remember { mutableStateOf<List<AnimeReleaseCalendarRepository.Release>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        releases = AnimeReleaseCalendarRepository.load()
        loading = false
    }

    val now = System.currentTimeMillis()
    val upcoming = releases.filter { it.airAt >= now }.take(80)
    val recent = releases.filter { it.airAt < now }.sortedByDescending { it.airAt }.take(40)
    val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM · hh:mm a").withZone(ZoneId.systemDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 120.dp),
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
