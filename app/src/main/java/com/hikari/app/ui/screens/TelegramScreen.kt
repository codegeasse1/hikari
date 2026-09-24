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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tag
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.net.Http
import com.hikari.app.telegram.Td
import com.hikari.app.telegram.TdFileDataSource
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Telegram tab.
 *
 * Two halves, and they answer different questions:
 *
 *  * **Your Telegram** — a real client, on TDLib, signed in to the user's own
 *    account (see `com.hikari.app.telegram.Td`). This is what reads Saved
 *    Messages, private channels and groups, chats with people, and any channel
 *    the account is in, public or not. Videos play straight out of TDLib, over
 *    the same player everything else uses.
 *  * **Public channels** — the no-account half ([TelegramWeb]): a channel's
 *    `t.me/s/<name>` preview carries its video files' own CDN URLs, so a public
 *    channel can be watched without signing in to anything at all.
 *
 * The tab is OFF until it is switched on (Settings → Taskbar buttons):
 * an install that never opens Telegram should not carry a ninth button.
 */
@Composable
fun TelegramScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val rawFlow = remember { app.store.telegramChannelsFlow() }
    val raw by rawFlow.collectAsState(initial = "")
    val channels = remember(raw) { TelegramChannels.decode(raw) }
    val scope = rememberCoroutineScope()

    // Start TDLib (or find that credentials are still missing) once per visit.
    LaunchedEffect(Unit) { Td.init(context) }

    // Which channel / which chat is open, if any. Kept in saved state so a
    // rotation does not dump the user back to the list.
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var openChat by rememberSaveable { mutableStateOf(-1L) }
    var openChatTitle by rememberSaveable { mutableStateOf("") }
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
            "or a t.me link and its videos appear in Hikari. Sign in above to see your " +
            "own chats, private channels and Saved Messages."
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

    val openChannel = channels.firstOrNull { it.first == openName }

    if (openChat > 0) {
        TelegramChatVideos(
            chatId = openChat,
            title = openChatTitle.ifBlank { "Chat" },
            onBack = { openChat = -1L },
        )
    } else if (openChannel != null) {
        TelegramChannelVideos(
            channel = openChannel.first,
            title = openChannel.second.ifBlank { openChannel.first },
            onBack = { openName = null },
        )
    } else {
        TelegramHome(
            channels = channels,
            onOpenChannel = { openName = it },
            onOpenChat = { id, title ->
                openChat = id
                openChatTitle = title
            },
            onAddChannel = { typed = ""; addError = ""; addOpen = true },
            onRemoveChannel = { name ->
                scope.launch { TelegramChannels.remove(app.store, channels, name) }
            },
            hint = hint,
        )
    }

    if (addOpen) {
        AlertDialog(
            onDismissRequest = { if (!adding) addOpen = false },
            title = { Text(tr("Add a public channel")) },
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

/** The tab's landing page: your Telegram account, then the public channels. */
@Composable
private fun TelegramHome(
    channels: List<Pair<String, String>>,
    onOpenChannel: (String) -> Unit,
    onOpenChat: (Long, String) -> Unit,
    onAddChannel: () -> Unit,
    onRemoveChannel: (String) -> Unit,
    hint: String,
) {
    val app = LocalContext.current.applicationContext as HikariApp
    val auth by Td.auth.collectAsState()
    val chatList by Td.chats.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    // The account's chats, filtered by what the user typed. Saved Messages is
    // always in the list (it is a chat like any other, it just has no one on
    // the other end).
    val visibleChats = remember(chatList, query) {
        if (query.isBlank()) chatList
        else chatList.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
    }

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
                        tr("Your chats and public channels"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onAddChannel) {
                    Icon(Icons.Filled.Add, contentDescription = tr("Add a public channel"))
                }
            }
        }

        // ---- Your Telegram (TDLib) ----
        item(key = "telegram-account") { TelegramAccountCard(app) }

        when (val state = auth) {
            is Td.Auth.Ready -> {
                if (chatList.isNotEmpty()) {
                    item(key = "telegram-chats-header") {
                        Text(
                            tr("Your chats"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    item(key = "telegram-chats-filter") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(tr("Search your chats")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (visibleChats.isEmpty()) {
                        item(key = "telegram-chats-none") {
                            Text(
                                tr("No chat matches that name."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visibleChats, key = { "chat-" + it.id }) { chat ->
                        TelegramChatRow(
                            title = chat.title,
                            kind = chat.kind,
                            unread = chat.unread,
                            onOpen = { onOpenChat(chat.id, chat.title) },
                        )
                    }
                } else {
                    item(key = "telegram-chats-loading") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tr("Loading your chats…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> Unit
        }

        // ---- Public channels (no account needed) ----
        item(key = "telegram-public-header") {
            Text(
                tr("Public channels"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (channels.isEmpty()) {
            item(key = "telegram-empty") {
                EmptyState(
                    title = tr("No public channels yet"),
                    subtitle = tr(
                        "Add a public channel and its videos are playable here, " +
                            "in Hikari's own player."
                    ),
                    actionLabel = tr("Add a channel"),
                    action = onAddChannel,
                )
            }
        } else {
            items(channels, key = { "channel-" + it.first }) { (name, title) ->
                TelegramChannelRow(
                    title = title.ifBlank { name },
                    handle = name,
                    onOpen = { onOpenChannel(name) },
                    onRemove = { onRemoveChannel(name) },
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

/**
 * The TDLib half's front door: whatever the account state is right now, this
 * card is the one place the user acts on it — set up the API credentials, type
 * the phone number, the code, the password, or read who they are signed in as.
 *
 * The credentials are asked for rather than bundled: an api_id/api_hash belongs
 * to the application that registered it (my.telegram.org → API development
 * tools), and shipping someone else's is both against Telegram's terms and
 * something Telegram revokes.
 */
@Composable
private fun TelegramAccountCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Td.auth.collectAsState()
    val me by Td.me.collectAsState()
    val online by Td.online.collectAsState()
    val problem by Td.problem.collectAsState()
    val busy by Td.busy.collectAsState()
    val sentTo by Td.sentTo.collectAsState()

    val storedIdFlow = remember { app.store.telegramApiIdFlow() }
    val storedId by storedIdFlow.collectAsState(initial = 0)
    val storedHashFlow = remember { app.store.telegramApiHashFlow() }
    val storedHash by storedHashFlow.collectAsState(initial = "")

    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }

    // Hoisted strings (tr is composable; the click lambdas are not).
    val idLabel = tr("api_id")
    val hashLabel = tr("api_hash")
    val phoneLabel = tr("Phone number (with country code)")
    val codeLabel = tr("Login code")
    val passwordLabel = tr("Two-step password")
    val saveKey = tr("Save and continue")
    val sendCode = tr("Send code")
    val signIn = tr("Sign in")
    val signOutLabel = tr("Sign out")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Your Telegram"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (val state = auth) {
                        is Td.Auth.Ready ->
                            tr("Signed in as ") + me + if (online) "" else " · " + tr("connecting…")
                        is Td.Auth.Starting -> tr("Starting Telegram…")
                        is Td.Auth.WaitPhone -> tr("Enter your phone number")
                        is Td.Auth.WaitCode -> tr("Enter the code Telegram sent you")
                        is Td.Auth.WaitPassword -> tr("Enter your two-step password")
                        is Td.Auth.WaitRegistration -> tr("Finish creating your account")
                        is Td.Auth.WaitOtherDevice -> tr("Confirm the login on your other device")
                        is Td.Auth.Unavailable -> tr("Telegram support is not available in this build")
                        is Td.Auth.Closed -> tr("Signed out")
                        else -> tr("Sign in to see your own chats — private channels, groups and Saved Messages")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (auth is Td.Auth.Ready) {
                TextButton(onClick = { Td.signOut() }) { Text(signOutLabel) }
            }
        }

        // Whatever Telegram refused, in a sentence, right where the user is
        // looking. Every send in the login flow reports its own result now, so
        // this line is the difference between "it does nothing" and "that code
        // is not right".
        problem?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (val state = auth) {
            is Td.Auth.Unavailable -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "This build could not load the Telegram library on this device. " +
                            "Public channels below still work."
                    ) + (Td.loadError?.let { "\n" + it } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is Td.Auth.Ready, is Td.Auth.Starting -> Unit

            is Td.Auth.Idle, is Td.Auth.Closed -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiId,
                    onValueChange = { apiId = it.filter { c -> c.isDigit() } },
                    label = { Text(idLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it.trim() },
                    label = { Text(hashLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "Get an api_id and api_hash for your own account at my.telegram.org " +
                            "(API development tools). They are kept on this device and are used " +
                            "only to log in."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = apiId.isNotBlank() && apiHash.length >= 8,
                        onClick = {
                            val id = apiId.toIntOrNull() ?: 0
                            if (id <= 0) return@TextButton
                            // Stored first: Td reads them from the store when the
                            // tab is next opened, so a restart signs in by itself.
                            scope.launch {
                                runCatching {
                                    app.store.setTelegramApiId(id)
                                    app.store.setTelegramApiHash(apiHash)
                                }
                                Td.setCredentials(id, apiHash)
                            }
                        },
                    ) { Text(saveKey) }
                    if (storedId > 0 && storedHash.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Saved: api_id ") + storedId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        // Wipe the session TDLib keeps and start on the stored
                        // pair from scratch — the honest way out of a login that
                        // is stuck (a variant this build cannot finish, a session
                        // Telegram has revoked).
                        TextButton(onClick = { Td.restart() }) { Text(tr("Start over")) }
                    }
                }
            }

            is Td.Auth.WaitPhone -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(phoneLabel) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !busy && phone.count { it.isDigit() } >= 7,
                        onClick = { Td.submitPhone(phone) },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Sending…"))
                        } else {
                            Text(sendCode)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // A login that went nowhere (a flood wait, an api_id that
                    // was corrected, a number typed wrongly) is restarted from
                    // here instead of leaving the user to reinstall the app.
                    TextButton(
                        enabled = !busy,
                        onClick = { Td.restart() },
                    ) { Text(tr("Start over")) }
                }
            }

            is Td.Auth.WaitCode -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (sentTo.isBlank()) tr("Enter the code Telegram sent you")
                    else tr("Telegram sent a code to ") + sentTo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text(codeLabel) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !busy && code.isNotBlank(),
                        onClick = { Td.submitCode(code) },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Checking…"))
                        } else {
                            Text(signIn)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // The usual reason to be here with the wrong number typed:
                    // this closes the client and asks for the number again.
                    TextButton(
                        enabled = !busy,
                        onClick = { Td.restart() },
                    ) { Text(tr("Use a different number")) }
                }
            }

            is Td.Auth.WaitPassword -> {
                Spacer(Modifier.height(10.dp))
                if (state.hint.isNotBlank()) {
                    Text(
                        tr("Hint: ") + state.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(passwordLabel) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !busy && password.isNotBlank(),
                        onClick = { Td.submitPassword(password) },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Checking…"))
                        } else {
                            Text(signIn)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = !busy, onClick = { Td.restart() }) {
                        Text(tr("Start over"))
                    }
                }
            }

            is Td.Auth.WaitRegistration -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(tr("First name")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text(tr("Last name")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !busy && first.isNotBlank(),
                        onClick = { Td.submitRegistration(first, last) },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Creating…"))
                        } else {
                            Text(tr("Create account"))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = !busy, onClick = { Td.restart() }) {
                        Text(tr("Start over"))
                    }
                }
            }

            is Td.Auth.WaitOtherDevice -> {
                // The QR flow this build does not offer: Telegram is waiting for
                // a confirmation on another signed-in device, and there is
                // nothing to type here. Saying so beats an empty card.
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "Telegram is waiting for you to confirm this login on another " +
                            "device that is already signed in."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { Td.restart() }) { Text(tr("Start over")) }
            }
        }
    }
}

/** One chat in the list: what it is, its name, and how much is unread. */
@Composable
private fun TelegramChatRow(
    title: String,
    kind: Td.Chat.Kind,
    unread: Int,
    onOpen: () -> Unit,
) {
    val icon = when (kind) {
        Td.Chat.Kind.SAVED -> Icons.Filled.Bookmark
        Td.Chat.Kind.CHANNEL -> Icons.Filled.Tag
        else -> Icons.Filled.Send
    }
    val kindLabel = when (kind) {
        Td.Chat.Kind.SAVED -> tr("Saved Messages")
        Td.Chat.Kind.CHANNEL -> tr("Channel")
        Td.Chat.Kind.GROUP -> tr("Group")
        Td.Chat.Kind.PRIVATE -> tr("Chat")
        Td.Chat.Kind.SECRET -> tr("Secret chat")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (unread > 0) {
            Text(
                unread.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One chat's videos, newest first, with the older ones behind one button.
 *
 * Paging is TDLib's own (`getChatHistory` walks backwards from a message id), so
 * a channel with ten thousand posts costs one page at a time and nothing is
 * fetched twice.
 */
@Composable
private fun TelegramChatVideos(
    chatId: Long,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var videos by remember(chatId) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var loadingMore by remember(chatId) { mutableStateOf(false) }
    var reachedEnd by remember(chatId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId) {
        videos = withContext(Dispatchers.IO) { Td.chatVideos(chatId, 0, 60) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
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
                    tr("Telegram"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val loaded = videos
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (loaded.isEmpty()) {
            EmptyState(
                title = tr("No videos in this chat"),
                subtitle = tr("Posts here carry no video file Telegram can hand to the player."),
            )
            return@Column
        }
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
            items(loaded, key = { it.chatId.toString() + "/" + it.messageId }) { video ->
                TdVideoRow(video) { playTdVideo(context, video) }
            }
            item(key = "telegram-td-more") {
                if (reachedEnd) {
                    Text(
                        tr("That is the oldest post in this chat."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    TextButton(
                        enabled = !loadingMore,
                        onClick = {
                            val last = loaded.lastOrNull() ?: return@TextButton
                            loadingMore = true
                            scope.launch {
                                val older = withContext(Dispatchers.IO) {
                                    Td.chatVideos(chatId, last.messageId, 60)
                                }.filterNot { v -> loaded.any { it.messageId == v.messageId } }
                                loadingMore = false
                                if (older.isEmpty()) reachedEnd = true else videos = loaded + older
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

/** One video from a chat: its thumbnail, its title, when it was posted. */
@Composable
private fun TdVideoRow(video: Td.ChatVideo, onPlay: () -> Unit) {
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
            if (!video.thumb.isNullOrBlank()) {
                PosterImage(
                    model = video.thumb,
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
                stamp(video.date),
                duration(video.duration),
                sizeLabel(video.size),
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

private fun stamp(seconds: Int): String? {
    if (seconds <= 0) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1000L))
    }.getOrNull()
}

private fun duration(seconds: Int): String? {
    if (seconds <= 0) return null
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun sizeLabel(bytes: Long): String? {
    if (bytes <= 0) return null
    val mb = bytes / 1048576.0
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
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
 * One public channel's videos, newest first, with the older pages behind one
 * button.
 *
 * Paging is Telegram's own web-preview paging: `?before=<message id>` returns
 * the posts older than that id, so the list walks a channel's history in the
 * same chunks the preview shows it in — nothing is crawled up front.
 */
@Composable
private fun TelegramChannelVideos(
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

/** One public channel video: its still, its title, when it was posted. */
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
 * Play a video that lives in the user's Telegram account.
 *
 * The URL is Hikari's own `hikari-td://` scheme, which the player's data-source
 * chain answers with [TdFileDataSource]: TDLib is asked for the bytes at the
 * offset ExoPlayer wants, and the file TDLib writes is what gets read. Nothing
 * is downloaded up front — seeking into the middle of a film starts downloading
 * at the middle — and the player's own UI (quality, subtitles, speed, audio
 * tracks) is untouched.
 */
private fun playTdVideo(context: android.content.Context, video: Td.ChatVideo) {
    val sources = JSONArray().put(
        JSONObject()
            .put("name", "Telegram")
            .put("url", TdFileDataSource.uriFor(video.fileId))
            // Marks the source as "already local" so the player goes straight to
            // ExoPlayer: there is no URL to probe and no CDN to fail over from.
            .put("local", true)
            .put("isM3u8", false)
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

/**
 * Play a public channel's video: Telegram's own CDN URL, straight into the same
 * player, with the headers its CDN expects.
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
