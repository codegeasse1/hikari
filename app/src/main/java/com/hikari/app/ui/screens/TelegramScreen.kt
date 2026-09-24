package com.hikari.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.net.Http
import com.hikari.app.telegram.TelegramChannels
import com.hikari.app.telegram.TelegramVideo
import com.hikari.app.telegram.TelegramWeb
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Telegram tab — the videos on Telegram channels, played in Hikari.
 *
 * What it is: the channels the user adds (a `@name` or a `t.me` link) are read
 * through the public web preview Telegram serves for them, and each video on it
 * plays in Hikari's own player — the file is served straight from Telegram's CDN
 * to the same ExoPlayer every other source uses, so seeking, subtitles, audio
 * tracks and the player UI are all the existing ones.
 *
 * What it deliberately is NOT: a Telegram client. Reading a channel this way is
 * exactly what an anonymous visitor to t.me sees, so it covers PUBLIC channels
 * and nothing else — no Saved Messages, no private channels, no login. Those
 * need an authorised MTProto session (TDLib), which is a native library and a
 * whole login flow of its own; the tab says so where the user would look for it
 * rather than pretending the entry is missing.
 *
 * The tab is OFF until it is switched on (Settings → Taskbar buttons), like IPTV
 * and Stats: an install with no channels should not carry a ninth button.
 */
@Composable
fun TelegramScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val rawFlow = remember { app.store.telegramChannelsFlow() }
    val raw by rawFlow.collectAsState(initial = "")
    val channels = remember(raw) { TelegramChannels.decode(raw) }
    val scope = rememberCoroutineScope()

    // Which channel is open, if any. Kept in saved state so a rotation does not
    // dump the user back to the list.
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    // Hoisted: `tr` is composable and these are read inside plain functions.
    val errBad = tr("That is not a channel — paste a @name or a t.me link")
    val errDup = tr("That channel is already in the list")
    val errNone = tr("Telegram did not answer for that channel. Check the name and try again.")
    val hint = tr(
        "Public channels play here without signing in: add a channel by its @name " +
            "or a t.me link and its videos appear in Hikari. Private channels and " +
            "your Saved Messages need a Telegram login, which this build does not do."
    )

    fun addChannel() {
        val handle = TelegramWeb.normalize(typed)
        if (handle == null) {
            addError = errBad
            return
        }
        if (channels.any { it.first.equals(handle, ignoreCase = true) }) {
            addError = errDup
            return
        }
        adding = true
        scope.launch {
            // Ask Telegram for the channel's own title while we are at it, so a
            // row reads "Netflix" and not "@netflix", and a name Telegram does
            // not know is refused here rather than stored as a row that can
            // never load.
            val info = withContext(Dispatchers.IO) { TelegramWeb.load(handle) }
            adding = false
            if (info == null) {
                addError = errNone
            } else {
                TelegramChannels.add(app.store, channels, handle, info.first ?: handle)
                typed = ""
                addError = ""
                addOpen = false
            }
        }
    }

    val open = channels.firstOrNull { it.first == openName }
    if (open != null) {
        TelegramChannelVideos(
            nav = nav,
            channel = open.first,
            title = open.second.ifBlank { open.first },
            onBack = { openName = null },
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = LocalTaskbarInset.current + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "telegram-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Telegram"),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (channels.isEmpty()) tr("No channels yet")
                            else channels.size.toString() + " " + tr("channels"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    IconButton(onClick = { typed = ""; addError = ""; addOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = tr("Add a channel"))
                    }
                }
            }
            if (channels.isEmpty()) {
                item(key = "telegram-empty") {
                    EmptyState(
                        title = tr("No Telegram channels yet"),
                        subtitle = tr(
                            "Add a public channel and its videos are playable here, " +
                                "in Hikari's own player."
                        ),
                        actionLabel = tr("Add a channel"),
                        action = { typed = ""; addError = ""; addOpen = true },
                    )
                }
            } else {
                items(channels, key = { it.first }) { (name, title) ->
                    TelegramChannelRow(
                        title = title.ifBlank { name },
                        handle = name,
                        onOpen = { openName = name },
                        onRemove = {
                            scope.launch { TelegramChannels.remove(app.store, channels, name) }
                        },
                    )
                }
            }
            item(key = "telegram-note") {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (addOpen) {
        AlertDialog(
            onDismissRequest = { if (!adding) addOpen = false },
            title = { Text(tr("Add a Telegram channel")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            addError = ""
                        },
                        label = { Text(tr("@channel or t.me link")) },
                        singleLine = true,
                        isError = addError.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (addError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            addError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Only public channels can be read without signing in to Telegram."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !adding && typed.isNotBlank(), onClick = { addChannel() }) {
                    if (adding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Add"))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !adding, onClick = { addOpen = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** One channel in the list: its title, its handle, and a way to drop it. */
@Composable
private fun TelegramChannelRow(
    title: String,
    handle: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Send,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                handle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = tr("Remove channel"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One channel's videos, newest first, with the older pages behind one button.
 *
 * Paging is Telegram's own: `?before=<message id>` returns the posts older than
 * that id, so the list walks the channel's history in the same chunks the web
 * preview shows it in — no crawling of the whole channel up front.
 */
@Composable
private fun TelegramChannelVideos(
    nav: NavHostController,
    channel: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var videos by remember(channel) { mutableStateOf<List<TelegramVideo>?>(null) }
    var failed by remember(channel) { mutableStateOf(false) }
    var loadingMore by remember(channel) { mutableStateOf(false) }
    var reachedEnd by remember(channel) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Hoisted strings: the loading ones run outside composition.
    val errText = tr("Telegram did not answer. Check your connection and try again.")
    val emptyText = tr("This channel has no videos on its public page.")

    LaunchedEffect(channel) {
        val page = withContext(Dispatchers.IO) { TelegramWeb.load(channel) }
        if (page == null) {
            failed = true
        } else {
            videos = page.second
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    channel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            failed -> EmptyState(
                title = tr("Nothing came back"),
                subtitle = errText,
                actionLabel = tr("Try again"),
                action = {
                    failed = false
                    videos = null
                    scope.launch {
                        val page = withContext(Dispatchers.IO) { TelegramWeb.load(channel) }
                        if (page == null) failed = true else videos = page.second
                    }
                },
            )

            videos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            videos.orEmpty().isEmpty() -> EmptyState(
                title = tr("No videos here"),
                subtitle = emptyText,
            )

            else -> {
                val list = videos.orEmpty()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = LocalTaskbarInset.current + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.channel + "/" + it.messageId }) { video ->
                        TelegramVideoRow(video) { playTelegramVideo(context, video) }
                    }
                    item(key = "telegram-more") {
                        if (reachedEnd) {
                            Text(
                                tr("That is the end of this channel's public page."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            TextButton(
                                enabled = !loadingMore,
                                onClick = {
                                    val last = list.lastOrNull() ?: return@TextButton
                                    loadingMore = true
                                    scope.launch {
                                        val page = withContext(Dispatchers.IO) {
                                            TelegramWeb.load(channel, before = last.messageId)
                                        }
                                        loadingMore = false
                                        val older = page?.second.orEmpty().filterNot { v ->
                                            list.any { it.messageId == v.messageId }
                                        }
                                        if (older.isEmpty()) {
                                            reachedEnd = true
                                        } else {
                                            videos = list + older
                                        }
                                    }
                                },
                            ) {
                                Text(if (loadingMore) tr("Loading…") else tr("Load older"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One video: its still, its title, when it was posted, and how long it is. */
@Composable
private fun TelegramVideoRow(video: TelegramVideo, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (!video.posterUrl.isNullOrBlank()) {
                PosterImage(
                    model = video.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                video.dateLabel,
                video.duration?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Hand the video to Hikari's own player, the same way the WebView's "play in
 * Hikari" does: a one-entry `sources` payload with the headers the CDN expects.
 * Everything after this — seeking, subtitles, audio tracks, the player UI — is
 * the existing player, untouched.
 */
private fun playTelegramVideo(context: android.content.Context, video: TelegramVideo) {
    val headers = JSONObject()
        .put("User-Agent", Http.UA)
        // Telegram's CDN answers a bare request; the referer keeps it looking
        // like the web preview the URL was read from.
        .put("Referer", "https://t.me/")
    val sources = JSONArray().put(
        JSONObject()
            .put("name", "Telegram")
            .put("url", video.url)
            .put("headers", headers)
            .put("isM3u8", video.url.contains(".m3u8", true))
            .put("isMpd", false)
            .put("subtitles", JSONArray())
    )
    runCatching {
        context.startActivity(
            Intent(context, com.hikari.app.player.PlayerActivity::class.java).apply {
                putExtra("title", video.title)
                putExtra("sources", sources.toString())
            }
        )
    }
}
