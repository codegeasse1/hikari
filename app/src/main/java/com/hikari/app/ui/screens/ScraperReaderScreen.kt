package com.hikari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ScraperImageStore {
    class Pack(val title: String, val images: List<String>, val referer: String?)

    private val packs = ConcurrentHashMap<String, Pack>()

    fun put(title: String, images: List<String>, referer: String?): String {
        val key = UUID.randomUUID().toString()
        packs[key] = Pack(title, images, referer)
        if (packs.size > 20) {
            val drop = packs.keys.firstOrNull()
            if (drop != null && packs.size > 20) packs.remove(drop)
        }
        return key
    }

    fun get(key: String): Pack? = packs[key]
}

@Composable
fun ScraperReaderScreen(nav: NavHostController, key: String, title: String) {
    val pack = remember(key) { ScraperImageStore.get(key) }
    val images = pack?.images.orEmpty()
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pack?.title?.ifBlank { title }?.ifBlank { "Reader" } ?: "Reader",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (images.isEmpty()) "No pages found" else "${images.size} pages · webtoon mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (images.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No images on this page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(images, key = { index, url -> "$index|$url" }) { _, url ->
                var loading by remember(url) { mutableStateOf(true) }
                Box(Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                        onLoading = { loading = true },
                        onSuccess = { loading = false },
                        onError = { loading = false },
                    )
                    if (loading) {
                        Box(
                            Modifier.fillMaxWidth().height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
