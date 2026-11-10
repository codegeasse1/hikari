package com.hikari.app.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.hikari.app.data.Site
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.launch

/**
 * Saved websites as their own page: a searchable grid of site cards with
 * one-tap open in the ad-free web view. Reached from the Extensions tab's
 * "Webview sites" entry card.
 */
@Composable
fun WebSitesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ExtensionsViewModel = viewModel()
    val sites by vm.sites.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var siteName by remember { mutableStateOf("") }
    var siteUrl by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Site?>(null) }

    fun openSite(site: Site) {
        val opened = runCatching {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).apply {
                    putExtra("url", site.url)
                    putExtra("title", site.name)
                }
            )
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, I18n.t("Cannot open web view"), Toast.LENGTH_SHORT).show()
        }
    }

    val shown = remember(sites, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) sites
        else sites.filter {
            it.name.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Websites"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (sites.isEmpty()) tr("No sites yet")
                    else I18n.t(if (sites.size == 1) "%s site" else "%s sites")
                        .replace("%s", sites.size.toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = tr("Add website"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(tr("Search websites…")) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (sites.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("No websites yet"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("Add any movie/streaming website and it opens in an ad-free web view — ads, trackers and popups blocked, with one-tap video playback in the player."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showAdd = true }) { Text(tr("Add website")) }
            }
        } else if (shown.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tr("No websites match that search."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = LocalTaskbarInset.current + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.url }) { site ->
                    WebSiteCard(
                        site = site,
                        onOpen = { openSite(site) },
                        onRemove = { removeTarget = site },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!adding) showAdd = false },
            title = { Text(tr("Add website")) },
            text = {
                Column {
                    Text(
                        tr("Opens in the ad-free web view."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        placeholder = { Text(tr("Name (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteUrl,
                        onValueChange = { siteUrl = it },
                        placeholder = { Text(tr("https://example.com")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (adding) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Adding…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !adding && (siteUrl.isNotBlank() || siteName.isNotBlank()),
                    onClick = {
                        val clean = siteUrl.trim()
                        val withScheme = if (clean.startsWith("http://") || clean.startsWith("https://")) clean
                        else "https://$clean"
                        if (withScheme.isBlank() || withScheme == "https://") {
                            Toast.makeText(context, I18n.t("Enter a valid URL"), Toast.LENGTH_SHORT).show()
                        } else {
                            adding = true
                            scope.launch {
                                runCatching { vm.addSite(siteName.trim(), withScheme) }
                                adding = false
                                showAdd = false
                                siteUrl = ""
                                siteName = ""
                            }
                        }
                    },
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!adding) showAdd = false }) { Text(tr("Cancel")) }
            },
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(I18n.t("Remove %s?").replace("%s", target.name)) },
            text = { Text(tr("It will no longer open from here."), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { runCatching { vm.removeSite(target.url) } }
                    removeTarget = null
                }) {
                    Text(tr("Remove"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** One website card: letter avatar over the host colour, name + host below,
 *  tap opens, trash deletes. */
@Composable
private fun WebSiteCard(
    site: Site,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    GlassCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column {
            val host = remember(site.url) {
                runCatching { java.net.URI(site.url).host ?: site.url }.getOrDefault(site.url)
            }
            val tint = remember(site.url) {
                Color.hsl(((site.url.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.42f)
            }
            val letter = remember(site.name) {
                site.name.trim().firstOrNull()?.uppercase() ?: "•"
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = tr("Remove website"),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    site.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
