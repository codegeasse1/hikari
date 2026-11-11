package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaType
import com.hikari.app.data.NetworkStream
import com.hikari.app.data.Profiles
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.providers.IptvProvider
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds one torrent or one network stream: the standalone replacement for the
 *  old Playlists page's add dialog. Torrents and streams live in the provider
 *  store (never in the Live TV engine), and play through the same destinations
 *  the old page used. */
@Composable
fun TorrentStreamAddDialog(
    nav: NavHostController,
    startAsTorrent: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HikariApp
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var addLink by remember(startAsTorrent) { mutableStateOf("") }
    var addName by remember(startAsTorrent) { mutableStateOf("") }
    var addTorrent by remember(startAsTorrent) { mutableStateOf(startAsTorrent) }
    var adding by remember { mutableStateOf(false) }

    fun addNow(playAfter: Boolean = false) {
        val link = addLink.trim()
        val name = addName
        val wantTorrent = addTorrent
        adding = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                addNetworkStream(app, link, name)
            }
            adding = false
            result.fold(
                onSuccess = {
                    val msg = if (wantTorrent) I18n.t("Added torrent stream")
                    else I18n.t("Added network stream")
                    Toast.makeText(uiContext, msg, Toast.LENGTH_LONG).show()
                    addLink = ""
                    addName = ""
                    if (playAfter) {
                        val saved = app.store.providers().firstOrNull { p ->
                            p.type == ProviderType.IPTV &&
                                (p.url == link || p.extra == link)
                        }
                        if (saved != null) openTorrentStream(nav, saved)
                    }
                    onDone()
                },
                onFailure = { t ->
                    Toast.makeText(
                        uiContext,
                        t.message ?: I18n.t("Could not add that link"),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text(if (addTorrent) tr("Add torrent") else tr("Add network stream")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddModePill(
                        label = tr("Network stream"),
                        selected = !addTorrent,
                        onClick = { addTorrent = false },
                    )
                    Spacer(Modifier.width(8.dp))
                    AddModePill(
                        label = tr("Torrent"),
                        selected = addTorrent,
                        onClick = { addTorrent = true },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (addTorrent) {
                        tr(
                            "Paste a magnet link, a .torrent URL, or a bare info hash. Add saves it for later; " +
                                "Play starts it now through the torrent engine."
                        )
                    } else {
                        tr(
                            "Paste a link to a stream: an m3u8 or mp4, a Terabox/Telebox " +
                                "share, an MDisk link, or a download page — anything an " +
                                "installed extension can open. It is resolved when you " +
                                "press play."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addLink,
                    onValueChange = { addLink = it },
                    placeholder = {
                        Text(
                            if (addTorrent) tr("magnet:?…, .torrent URL or info hash")
                            else tr("https://…/m3u8, terabox, mdisk…")
                        )
                    },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(addLink),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addName,
                    onValueChange = { addName = it },
                    label = { Text(tr("Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(addName),
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
            Row {
                if (addLink.isNotBlank()) {
                    TextButton(
                        enabled = !adding,
                        onClick = { addNow(playAfter = true) },
                    ) { Text(tr("Play")) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    enabled = !adding && addLink.isNotBlank(),
                    onClick = { addNow(playAfter = false) },
                ) { Text(tr("Add")) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                addLink = ""
                addName = ""
                onDismiss()
            }) { Text(tr("Cancel")) }
        },
    )
}

/** One torrent/stream row: icon thumb, name + subtitle; tap plays, trash
 *  removes (home passes no onRemove — removal lives in settings). */
@Composable
fun TorrentStreamRow(
    name: String,
    subtitle: String,
    torrent: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (torrent) Icons.Filled.Download else Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = I18n.t("Remove %s").replace("%s", name),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Opens a saved torrent or stream exactly where the old Playlists page did:
 *  a torrent lands on its channel catalog, a stream straight on Play. */
fun openTorrentStream(nav: NavHostController, p: ProviderConfig) {
    if (NetworkStream.isTorrentLink(p.url)) {
        Routes.safeNavigate(
            nav,
            Routes.catalog(
                providerId = p.id,
                catalogId = IptvProvider.CATALOG_ALL,
                title = p.name,
                providerName = p.name,
                type = MediaType.MOVIE,
                rawType = "torrent",
            ),
        )
    } else {
        Routes.safeNavigate(
            nav,
            Routes.detail(
                providerId = p.id,
                type = MediaType.MOVIE,
                mediaId = p.url,
                title = p.name,
                posterUrl = null,
                rawType = "stream",
            ),
        )
    }
}

/** Removes a torrent/stream provider, its cached state, and its stored file
 *  when nothing else references it. */
suspend fun removeTorrentStreamProvider(app: HikariApp, providerId: String) =
    withContext(Dispatchers.IO) {
        val providers = runCatching { app.store.providers() }.getOrDefault(emptyList())
        val target = providers.firstOrNull { it.id == providerId } ?: return@withContext
        runCatching { app.store.removeProvider(providerId) }
        runCatching { app.providers.refresh() }
        IptvProvider.forget(providerId)
        val path = target.url
        val base = app.filesDir.absolutePath + "/iptv/"
        if (path.startsWith(base) &&
            runCatching { app.store.providers() }.getOrDefault(emptyList()).none { it.url == path } &&
            !Profiles.otherProfilesReference(app, path)
        ) {
            runCatching { java.io.File(path).delete() }
        }
    }

private suspend fun addNetworkStream(
    app: HikariApp,
    link: String,
    name: String,
): Result<Int> = withContext(Dispatchers.IO) {
    val trimmed = link.trim()
    // A magnet link is complete as-is: prefixing "https://" (as before) mangles
    // it into an unplayable https URL that then lists and plays like an IPTV
    // channel instead of going to the torrent engine. A bare info hash is a
    // magnet with no scheme yet (see [NetworkStream.magnetize]).
    val url = if (trimmed.startsWith("magnet:", ignoreCase = true)) {
        trimmed
    } else if (NetworkStream.isBareInfoHash(trimmed)) {
        NetworkStream.magnetize(trimmed)
    } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else if (trimmed.isBlank()) {
        ""
    } else {
        "https://$trimmed"
    }
    if (url.isBlank()) {
        return@withContext Result.failure(Exception(I18n.t("Paste a link to the stream")))
    }
    if (!NetworkStream.isStreamLink(url)) {
        return@withContext Result.failure(
            Exception(I18n.t("That is not a link — paste a full http(s) URL or magnet link")),
        )
    }
    // A stream's name cannot come from the link (a share key is not a name), so
    // the host stands in when the user did not type one.
    val display = name.trim().ifBlank {
        when {
            url.startsWith("magnet:", ignoreCase = true) -> torrentDisplayName(url)
            NetworkStream.isTorrentFile(url) ->
                url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                    .substringBeforeLast('.').ifBlank { "Torrent" }
            else -> NetworkStream.hostOf(url).removePrefix("www.").substringBefore('.').ifBlank { "Stream" }
        }
    }
    app.store.addProvider(
        ProviderConfig(
            id = "iptv|" + url.hashCode(),
            name = display,
            type = ProviderType.IPTV,
            url = url,
            extra = NetworkStream.MARKER,
        )
    )
    app.providers.refresh()
    Result.success(1)
}

/** Display name for a pasted torrent: the magnet's own dn= name, the
 *  .torrent file's name, or Torrent + short hash — never a URL shard like
 *  "magnet:". */
private fun torrentDisplayName(link: String): String {
    if (NetworkStream.isBareInfoHash(link)) {
        return "Torrent · " + link.take(8).uppercase()
    }
    Regex("[?&]dn=([^&]+)").find(link)?.let { m ->
        runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    if (!link.startsWith("magnet:", ignoreCase = true)) {
        link.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .substringBeforeLast('.').ifBlank { null }?.let { return it }
    }
    val hash = Regex("btih:([a-zA-Z0-9]+)").find(link)?.groupValues?.getOrNull(1)
    return if (hash.isNullOrBlank()) "Torrent" else "Torrent · " + hash.take(8).uppercase()
}

/** One of the add dialog's two modes: a single Network stream, or a Torrent. */
@Composable
private fun AddModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    // Television pills sit black with a hairline edge (desktop language).
    val tvPill = com.hikari.app.tv.TvMode.current()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else if (tvPill) com.hikari.app.tv.TvDesktop.SurfaceCard
        else MaterialTheme.colorScheme.surfaceVariant,
        border = if (!selected && tvPill) androidx.compose.foundation.BorderStroke(
            1.dp,
            com.hikari.app.tv.TvDesktop.GlassEdge,
        ) else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else if (tvPill) com.hikari.app.tv.TvDesktop.TextMuted
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
