package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.iptv.VodEpisode
import com.hikari.app.iptv.VodItem
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.components.ChoiceDialog
import com.hikari.app.ui.components.ChoiceItem
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On Demand: movies and series from every Xtream login, with categories, sort
 * and search. A title opens its info + episodes; everything plays in the app
 * player.
 */
@Composable
fun LiveTvOnDemandScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var sources by remember { mutableStateOf<List<com.hikari.app.iptv.LiveTvSource>>(emptyList()) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("DEFAULT") }
    var query by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var categories by remember { mutableStateOf<List<com.hikari.app.iptv.LiveTvVod.VodCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<VodItem?>(null) }
    var warmed by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            runCatching {
                runCatching { LiveTvEngine.init(context) }
                val srcs = LiveTvEngine.sources().filter { it.isXtream && it.importVod }
                val sid = sourceId?.takeIf { id -> srcs.any { it.id == id } } ?: srcs.firstOrNull()?.id
                val cats = if (sid == null) emptyList()
                else LiveTvEngine.vod.categoriesOf(sid, series)
                val all = if (sid == null) emptyList() else LiveTvEngine.vod.titles(sid)
                VodData(srcs, sid, cats, all, LiveTvEngine.store.settings().vodDefaultSort)
            }.getOrDefault(VodData(emptyList(), null, emptyList(), emptyList(), "DEFAULT"))
        }
        sources = data.sources
        if (sourceId == null) sourceId = data.sourceId
        categories = data.categories
        titles = data.titles
        if (tick == 0) sort = data.sort
        loading = false
        // Cold start paints the cache, then refreshes stale sources once in
        // the background and repaints (see the guide screen's note).
        if (!warmed) {
            warmed = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { LiveTvEngine.ensureFresh() } }
                tick++
            }
        }
    }

    val shown = remember(titles, category, sort, query) {
        val q = query.trim()
        var list = if (q.length >= 2) {
            titles.filter { it.name.contains(q, ignoreCase = true) }
        } else {
            val all = LiveTvEngine.vod.let { v -> titles }
            if (category == null) all else all.filter { it.categoryId == category }
        }
        list = when (sort.uppercase()) {
            "NAME" -> list.sortedBy { it.name.lowercase() }
            "NEWEST" -> list.sortedByDescending { it.addedSec }
            "RATING" -> list.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
            else -> list
        }
        list.take(600)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("On Demand"),
                subtitle = if (titles.isEmpty()) tr("Movies & series") else tr("%s titles").replace("%s", titles.size.toString()),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading On Demand…")) }
            return@LazyColumn
        }
        if (sources.isEmpty()) {
            item {
                Text(
                    tr("No Xtream login imports On Demand yet — turn it on per login in Live TV settings."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@LazyColumn
        }
        item {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                items(sources, key = { it.id }) { s ->
                    VodPill(s.name, s.id == sourceId) { sourceId = s.id; category = null; tick++ }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                VodPill(tr("Movies"), !series) { series = false; category = null; tick++ }
                VodPill(tr("Series"), series) { series = true; category = null; tick++ }
                Spacer(Modifier.weight(1f))
                Text(
                    tr("Sort: %s").replace("%s", sortName(sort)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        scope.launch {
                            // cycle DEFAULT → NAME → NEWEST → RATING
                            val next = when (sort.uppercase()) {
                                "DEFAULT" -> "NAME"
                                "NAME" -> "NEWEST"
                                "NEWEST" -> "RATING"
                                else -> "DEFAULT"
                            }
                            sort = next
                            withContext(Dispatchers.IO) {
                                val s = LiveTvEngine.store.settings()
                                LiveTvEngine.store.saveSettings(s.copy(vodDefaultSort = next))
                            }
                        }
                    }.padding(8.dp),
                )
            }
        }
        if (categories.isNotEmpty()) {
            item {
                var open by remember { mutableStateOf(false) }
                val current = categories.firstOrNull { it.id == category }?.name ?: tr("All categories")
                Text(
                    current,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
                )
                if (open) {
                    ChoiceDialog(
                        title = tr("Category"),
                        items = listOf(ChoiceItem("", tr("All categories"))) +
                            categories.map { ChoiceItem(it.id, it.name) },
                        selectedKey = category.orEmpty(),
                        onPick = { category = it.ifBlank { null }; open = false },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text(tr("Search titles")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).tvTextFieldKeys(query),
            )
        }
        items(shown.chunked(3), key = { row -> row.firstOrNull()?.uid ?: row.hashCode().toString() }) { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                row.forEach { item ->
                    VodTile(item, Modifier.weight(1f), onOpen = { opened = item })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    opened?.let { item ->
        VodDetailSheet(
            item = item,
            onDismiss = { opened = null },
        )
    }
}

private data class VodData(
    val sources: List<com.hikari.app.iptv.LiveTvSource>,
    val sourceId: String?,
    val categories: List<com.hikari.app.iptv.LiveTvVod.VodCategory>,
    val titles: List<VodItem>,
    val sort: String,
)

private fun sortName(sort: String): String = when (sort.uppercase()) {
    "NAME" -> "Name"
    "NEWEST" -> "Newest"
    "RATING" -> "Rating"
    else -> "Default"
}

@Composable
private fun VodPill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun VodTile(item: VodItem, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(
        modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() },
    ) {
        AsyncImage(
            model = item.icon,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(
            item.year,
            item.rating?.let { "★ $it" },
        ).joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A title's info + episodes, with play buttons. */
@Composable
fun VodDetailSheet(item: VodItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var episodes by remember(item.uid) { mutableStateOf<List<VodEpisode>?>(null) }
    var info by remember(item.uid) { mutableStateOf<String?>(null) }
    var busy by remember(item.uid) { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(item.uid) {
        if (!item.isSeries) return@LaunchedEffect
        busy = true
        episodes = withContext(Dispatchers.IO) { LiveTvEngine.vod.episodesOf(item) }
        busy = false
        info = withContext(Dispatchers.IO) {
            runCatching {
                LiveTvEngine.vod.infoOf(item)?.optJSONObject("info")
                    ?.optString("plot").takeIf { !it.isNullOrBlank() }
            }.getOrNull()
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                val sub = listOfNotNull(
                    item.categoryName.takeIf { it.isNotBlank() },
                    item.year,
                    item.rating?.let { "★ $it" },
                    item.genre,
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                if (!info.isNullOrBlank()) {
                    Text(info.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
                if (!item.isSeries) {
                    Text(
                        tr("Play"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            onDismiss()
                            scope.launch {
                                withContext(Dispatchers.IO) { LiveTvPlayback.playVod(context, item) }
                            }
                        }.padding(vertical = 8.dp),
                    )
                } else {
                    val eps = episodes
                    if (busy || eps == null) {
                        Text(tr("Reading episodes…"), style = MaterialTheme.typography.bodySmall)
                    } else if (eps.isEmpty()) {
                        Text(tr("No episodes listed."), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            tr("%s episodes").replace("%s", eps.size.toString()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(eps, key = { it.season * 100000 + it.episode }) { ep ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        onDismiss()
                                        scope.launch {
                                            withContext(Dispatchers.IO) { LiveTvPlayback.playVod(context, item, ep) }
                                        }
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "S${ep.season} E${ep.episode} · ${ep.title}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (!ep.info.isNullOrBlank()) {
                                            Text(
                                                ep.info.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}
