package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hikari.app.tv.tvTextFieldKeys

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
    // 0 is the "no chat open" sentinel, NOT -1: a channel or group id is
    // NEGATIVE (Saved Messages is the only chat here with a positive id), so a
    // negative sentinel and a real channel id are indistinguishable — tapping a
    // channel set `openChat` to its own negative id, `openChat > 0` was false,
    // and the page simply stayed on the list. That is the reported "clicking a
    // channel does nothing; only Saved Messages opens".
    var openChat by rememberSaveable { mutableStateOf(0L) }
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

    if (openChat != 0L) {
        TelegramChatVideos(
            chatId = openChat,
            title = openChatTitle.ifBlank { "Chat" },
            onBack = { openChat = 0L },
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
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(typed),
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
                    if (!LocalHideHelp.current) {
                    Text(
                        tr("Only public channels can be read without signing in to Telegram."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
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
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
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
    // What is wrong with the pair as typed, checked before anything is stored or
    // sent (see [Td.keyProblem]) — so "not a valid pair" is answered at the
    // field the user got wrong, not a round trip later.
    var keyMsg by remember { mutableStateOf<String?>(null) }

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

    // A pair that is ALREADY stored is shown back in the fields, so a user whose
    // keys were rejected can see what the app is actually using and correct it
    // in place — an empty field over a saved pair is what made "Start over" look
    // like it did nothing.
    LaunchedEffect(auth, storedId, storedHash) {
        if (auth is Td.Auth.Idle || auth is Td.Auth.Closed) {
            if (apiId.isBlank() && storedId > 0) apiId = storedId.toString()
            if (apiHash.isBlank() && storedHash.isNotBlank()) apiHash = storedHash
        }
    }

    // Back to the credentials page from any login step: clears the stored pair
    // and the client that is running on it (see [Td.signInAgain]).
    fun changeKeys() {
        keyMsg = null
        scope.launch {
            runCatching {
                app.store.setTelegramApiId(0)
                app.store.setTelegramApiHash("")
            }
            Td.signInAgain()
        }
    }

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
                    onValueChange = {
                        apiId = it.filter { c -> c.isDigit() }
                        keyMsg = null
                    },
                    label = { Text(idLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(apiId),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = {
                        apiHash = it
                        keyMsg = null
                    },
                    label = { Text(hashLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(apiHash),
                )
                keyMsg?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                        enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
                        onClick = {
                            val problem = Td.keyProblem(apiId, apiHash)
                            if (problem != null) {
                                keyMsg = problem
                                return@TextButton
                            }
                            val id = Td.apiIdOf(apiId) ?: return@TextButton
                            // The SANITISED pair is what is stored and sent: the
                            // hash lowercased to its 32 hex characters is what
                            // Telegram expects (see [Td.apiHashChars]).
                            val hash = Td.apiHashOf(apiHash) ?: return@TextButton
                            apiId = id.toString()
                            apiHash = hash
                            keyMsg = null
                            scope.launch {
                                runCatching {
                                    app.store.setTelegramApiId(id)
                                    app.store.setTelegramApiHash(hash)
                                }
                                Td.setCredentials(id, hash)
                            }
                        },
                    ) { Text(saveKey) }
                    if (storedId > 0 && storedHash.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        // Both values are shown back (the hash shortened), so it
                        // can be compared with the page they came from without
                        // printing a secret in full.
                        Text(
                            tr("Saved: api_id ") + storedId + " · " + storedHash.take(6) + "…" +
                                storedHash.takeLast(4),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(phone),
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
                    // A login that went nowhere (a wrong phone number, a flood
                    // wait) is restarted from here — and a pair of keys that
                    // Telegram refused goes back to the FIELDS, which is what
                    // this button exists for (see [changeKeys]).
                    TextButton(
                        enabled = !busy,
                        onClick = { changeKeys() },
                    ) { Text(tr("Change API keys")) }
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(code),
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(password),
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
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(first),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text(tr("Last name")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(last),
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
    val app = LocalContext.current.applicationContext as HikariApp
    var videos by remember(chatId) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var loadingMore by remember(chatId) { mutableStateOf(false) }
    var reachedEnd by remember(chatId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId) {
        videos = withContext(Dispatchers.IO) { Td.chatVideos(chatId, 0, 60) }
    }

    // ---- search inside this chat (see the search button below) -------------
    // A search that lives HERE, in the chat, and does not hand the user over to
    // the app's Search tab: that tab searches every engine for a title, while
    // the question here is "where in this chat is the video I posted under the
    // tag abc". Different question, different page.
    var searchOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var typed by rememberSaveable(chatId) { mutableStateOf("") }
    var query by rememberSaveable(chatId) { mutableStateOf("") }
    var searchInKey by rememberSaveable(chatId) { mutableStateOf(Td.SearchIn.CHAT.key) }
    val searchIn = Td.SearchIn.fromKey(searchInKey)
    // How the videos are drawn — list, tiles or posters. Remembered across
    // visits (it is a preference, not a per-chat whim); see TgView.
    var viewKey by rememberSaveable { mutableStateOf(TgView.LIST.key) }
    val view = TgView.fromKey(viewKey)
    LaunchedEffect(Unit) { viewKey = TgView.fromKey(app.store.telegramView()).key }
    // Debounced, like the in-engine search on a manga catalog page: typing
    // three letters must be one search, not three.
    LaunchedEffect(typed) {
        delay(350)
        query = typed.trim()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
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
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    typed = ""
                    query = ""
                }
            }) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) tr("Close search") else tr("Search this chat"),
                )
            }
        }

        // ---- how the videos are drawn --------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                tr("View"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TgView.entries.forEach { v ->
                TgPill(tr(v.label), view == v) {
                    viewKey = v.key
                    scope.launch { runCatching { app.store.setTelegramView(v.key) } }
                }
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(tr("Search in this chat")) },
                singleLine = true,
                trailingIcon = {
                    if (typed.isNotEmpty()) {
                        IconButton(onClick = {
                            typed = ""
                            query = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = tr("Clear"))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth().tvTextFieldKeys(typed)
                    .padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("Look in"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Td.SearchIn.entries.forEach { m ->
                    TgPill(tr(searchInLabel(m)), searchIn == m) { searchInKey = m.key }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val loaded = videos

        // A search with something to look for takes the page over. Its results
        // are NOT a filter over what is loaded — a chat's videos may be 400
        // posts deep and the tag being looked for near the bottom — so they come
        // from the chat's own history (see Td.searchChatVideos). It stays on
        // this page: nothing navigates to the app's Search tab.
        if (searchOpen && query.isNotBlank()) {
            ChatVideoSearchResults(
                chatId = chatId,
                query = query,
                searchIn = searchIn,
                view = view,
                onPlay = { playTdVideo(context, it) },
            )
            return@Column
        }

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
        ChatVideoCollection(
            videos = loaded,
            view = view,
            onPlay = { playTdVideo(context, it) },
        ) {
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

/**
 * How the videos of a chat are laid out. Three answers, because a channel with
 * 400 clips and a chat with three are not the same page:
 *
 *  * [LIST] — one row per video, thumbnail beside the title. Best when the
 *    titles are what the user is reading.
 *  * [TILE] — a grid of wide cards, thumbnail on top. Best when the thumbnails
 *    are what they are scanning.
 *  * [POSTER] — a grid of tall, poster-shaped cards. The shape a viewer is used
 *    to from every streaming app's "all episodes" wall, and the fastest to scan
 *    by sight.
 *
 * The choice is stored (Settings-free, per install — see AppStore.telegramView)
 * because it is a preference about how the user reads a chat, not a per-chat
 * setting.
 */
private enum class TgView(val key: String, val label: String) {
    LIST("list", "List"),
    TILE("tile", "Tiles"),
    POSTER("poster", "Posters");

    companion object {
        fun fromKey(key: String?): TgView = entries.firstOrNull { it.key == key } ?: LIST
    }
}

/** The label for one of the search scopes — see [Td.SearchIn]. */
private fun searchInLabel(m: Td.SearchIn): String = when (m) {
    // Not "Chat text": the search finds the POST whose text (or caption) holds
    // the word, and shows the videos posted with it — see Td.videosOfPost.
    Td.SearchIn.CHAT -> "Post text"
    Td.SearchIn.VIDEO -> "Video names"
    Td.SearchIn.BOTH -> "Both"
}

/**
 * The videos of one chat, in whichever of the three shapes is selected, with an
 * optional trailing block (the "load older" paging line, or a search's own
 * footer).
 *
 * ONE lazy container rather than three: [LazyVerticalGrid] with a single fixed
 * column IS the list view, so all three shapes share the same paging, the same
 * keys and the same footer, and only the cell changes. (A [LazyColumn] plus two
 * grids would have meant three copies of the footer and three ways for them to
 * drift apart.)
 */
@Composable
private fun ChatVideoCollection(
    videos: List<Td.ChatVideo>,
    view: TgView,
    onPlay: (Td.ChatVideo) -> Unit,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = when (view) {
            TgView.LIST -> GridCells.Fixed(1)
            TgView.TILE -> GridCells.Adaptive(168.dp)
            TgView.POSTER -> GridCells.Adaptive(112.dp)
        },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 4.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(videos, key = { it.chatId.toString() + "/" + it.messageId }) { video ->
            when (view) {
                TgView.LIST -> TdVideoRow(video) { onPlay(video) }
                TgView.TILE -> TdVideoTile(video) { onPlay(video) }
                TgView.POSTER -> TdVideoPoster(video) { onPlay(video) }
            }
        }
        footer?.invoke(this)
    }
}

/**
 * A video's picture: the real thumbnail TDLib keeps for it, falling back to the
 * post's tiny inline minithumbnail, and then to a play glyph.
 *
 * This is the difference between a usable list and a wall of black rectangles.
 * The list used to draw only the inline *minithumbnail* — a 20-pixel preimage
 * that plenty of posts simply do not carry (and which the app's poster loader
 * would not have known how to turn into an image even when they did, because a
 * `data:` URI never went through [PosterLoader.model]) — so most rows showed the
 * glyph on a dark box. TDLib's own thumbnail (`Video.thumbnail.file`) is a real
 * JPEG it can fetch in a moment, and [Td.thumbPath] asks for it and hands back
 * its path for Coil to draw. The minithumbnail is still the instant placeholder,
 * so a row is never empty while the real one arrives.
 */
@Composable
private fun TdVideoThumb(
    video: Td.ChatVideo,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var path by remember(video.thumbFileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(video.thumbFileId) {
        if (video.thumbFileId == 0) return@LaunchedEffect
        path = withContext(Dispatchers.IO) { Td.thumbPath(video.thumbFileId) }
    }
    // The inline preimage goes through the poster loader, which is what knows how
    // to turn a `data:` URI into bytes on disk that Coil can draw.
    val pre = remember(video.thumb) { PosterLoader.model(video.thumb) }
    val model: Any? = remember(path, pre) { path?.let { File(it) } ?: pre }
    Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (model != null) {
            PosterImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        TdVideoThumb(
            video = video,
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VideoMeta(video)
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** One video as a wide card: thumbnail on top, title under it (see [TgView.TILE]). */
@Composable
private fun TdVideoTile(video: Td.ChatVideo, onPlay: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onPlay)
            .padding(6.dp),
    ) {
        TdVideoThumb(
            video = video,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp)),
        )
        Text(
            video.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val meta = listOfNotNull(duration(video.duration), sizeLabel(video.size)).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** One video as a poster (see [TgView.POSTER]): tall card, thumbnail cropped to
 *  fill it, play glyph over the picture, title and duration under. */
@Composable
private fun TdVideoPoster(video: Td.ChatVideo, onPlay: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            TdVideoThumb(
                video = video,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // A play badge, because a poster is a still: without it the card
            // reads as a photo, not as something that plays.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            duration(video.duration)?.let { d ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(d, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            video.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** "when · how long · how big", the line under a row's title. */
@Composable
private fun VideoMeta(video: Td.ChatVideo, withStamp: Boolean = true) {
    val meta = listOfNotNull(
        if (withStamp) stamp(video.date) else null,
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

/** One of a row of selectable pills — the same shape as [CatalogTab], which is
 *  what every other "pick one of these" control in the app looks like. */
@Composable
private fun TgPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The results of a search inside one chat (see the search button in
 * [TelegramChatVideos]).
 *
 * They come from the chat's own history, not from the loaded list. A chat-text
 * search is answered by Telegram's own index (each hit resolved to the videos
 * that belong to that post — see [Td.videosOfPost]), and a file-name search
 * cannot be indexed at all, so the history is walked a page at a time.
 *
 * Either way the whole chat is walked HERE, without the user having to ask: the
 * search used to stop after one page and hide the rest behind a "Search further
 * back" button, which is the reported "my tag has 200+ videos and it only shows
 * some — in Telegram I see them all". The footer still says honestly how far it
 * got and how many videos it found, and the button is kept only for the case
 * where the walk hit its page ceiling.
 */
@Composable
private fun ChatVideoSearchResults(
    chatId: Long,
    query: String,
    searchIn: Td.SearchIn,
    view: TgView,
    onPlay: (Td.ChatVideo) -> Unit,
) {
    var results by remember(chatId, query, searchIn) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var cursor by remember(chatId, query, searchIn) { mutableStateOf(0L) }
    var scanned by remember(chatId, query, searchIn) { mutableIntStateOf(0) }
    var done by remember(chatId, query, searchIn) { mutableStateOf(false) }
    var busy by remember(chatId, query, searchIn) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The search pages the WHOLE chat by itself. It used to stop after one page
    // and put the rest behind a "Search further back" button — so a tag with
    // 200+ videos stopped at the first page's worth and only grew if the user
    // found and tapped a button under the list. Reported as "it only shows some
    // — in Telegram I see them all". Each page is a local TDLib read, so the
    // loop is cheap; SEARCH_MAX_PAGES is only there so a chat that never ends
    // cannot spin forever (and if it IS hit, the footer still offers the button).
    LaunchedEffect(chatId, query, searchIn) {
        results = null
        scanned = 0
        done = false
        cursor = 0L
        val acc = ArrayList<Td.ChatVideo>()
        val seen = HashSet<Long>()
        var cur = 0L
        var pages = 0
        while (pages < SEARCH_MAX_PAGES) {
            pages++
            val page = withContext(Dispatchers.IO) {
                Td.searchChatVideos(chatId, query, searchIn, cur, SEARCH_PAGE)
            }
            val add = page.videos.filter { seen.add(it.messageId) }
            if (add.isNotEmpty()) acc.addAll(add)
            scanned += page.scanned
            cursor = page.nextCursor
            // Publish as it grows, so a big tag fills the list page by page
            // instead of appearing all at once at the end.
            if (acc.isNotEmpty()) results = acc.toList()
            if (page.exhausted || page.nextCursor == 0L) {
                done = true
                break
            }
            // A page with nothing in it means there is nothing left to walk.
            if (page.videos.isEmpty() && page.scanned == 0) {
                done = true
                break
            }
            cur = page.nextCursor
        }
        results = acc.toList()
        // If the ceiling was reached with nothing found at all, settle on the
        // empty state rather than leaving the spinner up forever; a search with
        // results keeps `done` false so the footer can offer to go further.
        if (acc.isEmpty()) done = true
    }

    val found = results
    // Still loading the first page, or paging a chat that has not produced a
    // first video yet: showing "Nothing found" over a search that is still
    // walking is the same lie the footer used to tell.
    if (found == null || (found.isEmpty() && !done)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    if (scanned > 0) {
                        tr("Looking through this chat… %s post(s) so far").replace("%s", scanned.toString())
                    } else {
                        tr("Searching…")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    if (found.isEmpty()) {
        EmptyState(
            title = tr("Nothing found"),
            subtitle = when (searchIn) {
                Td.SearchIn.CHAT -> tr(
                    "No post in this chat has that in its text — and a post is what a video " +
                        "tag is written on, not the video file itself."
                )
                Td.SearchIn.VIDEO -> tr("No video in this chat has that in its file name.")
                Td.SearchIn.BOTH -> tr("No video in this chat matches that, in its text or its file name.")
            },
        )
        return
    }
    ChatVideoCollection(videos = found, view = view, onPlay = onPlay) {
        item(key = "telegram-td-search-footer") {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    tr("Looked at %s post(s) here.").replace("%s", scanned.toString()) +
                        "  ·  " + tr("%s video(s) found.").replace("%s", found.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("Still looking…"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (cursor != 0L) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val page = withContext(Dispatchers.IO) {
                                            Td.searchChatVideos(chatId, query, searchIn, cursor, SEARCH_PAGE)
                                        }
                                        busy = false
                                        val have = results.orEmpty()
                                        results = have + page.videos.filterNot { v -> have.any { it.messageId == v.messageId } }
                                        cursor = page.nextCursor
                                        scanned += page.scanned
                                        done = page.exhausted
                                    }
                                },
                            ) { Text(if (busy) tr("Searching…") else tr("Search further back")) }
                        }
                    }
                } else {
                    Text(
                        tr("That is the whole chat."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** How many videos one page of a chat search asks for. A floor on the number of
 *  matching POSTS consumed, not a cap on what one of them carries: a tag post
 *  with 200 videos under it yields all 200 in its own page (see
 *  [Td.searchChatVideos]). */
private const val SEARCH_PAGE = 60

/** The most pages the search will page through on its own before leaving the
 *  rest behind the "Search further back" button. Each page is a local TDLib
 *  read, so this is generous; it exists only so a chat that never ends cannot
 *  spin forever. */
private const val SEARCH_MAX_PAGES = 60

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
    val app = LocalContext.current.applicationContext as HikariApp
    var videos by remember(channel) { mutableStateOf<List<TelegramVideo>?>(null) }
    var failed by remember(channel) { mutableStateOf(false) }
    var loadingMore by remember(channel) { mutableStateOf(false) }
    var reachedEnd by remember(channel) { mutableStateOf(false) }
    // How many of the channel's posts carry a video Telegram does NOT publish to
    // a browser (see TelegramPage.unpublished) — the difference between "this
    // channel has no videos" and "this channel's videos are not available
    // anonymously", which used to be reported as the former.
    var unpublished by remember(channel) { mutableStateOf(0) }
    // The account path: this channel read through the user's own Telegram login.
    // Used exactly when the public page has video posts whose files it will not
    // hand over — Telegram withholds them for large uploads and for restricted
    // channels, and the user's own account plays them (the same files the
    // Telegram app the user is looking at is playing).
    var tdVideos by remember(channel) { mutableStateOf<List<Td.ChatVideo>?>(null) }
    var tdChatId by remember(channel) { mutableStateOf<Long?>(null) }
    var tdEnded by remember(channel) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // How the videos are drawn — the same stored preference the account's own
    // chat pages use (see TgView), so the fallback list looks like every other
    // list of Telegram videos in the app.
    var viewKey by rememberSaveable { mutableStateOf(TgView.LIST.key) }
    val view = TgView.fromKey(viewKey)
    LaunchedEffect(Unit) { viewKey = TgView.fromKey(app.store.telegramView()).key }

    // Hoisted strings: the loading ones run outside composition.
    val errText = tr("Telegram did not answer. Check your connection and try again.")
    val emptyText = tr("This channel has no videos on its public page.")
    // The honest version of the empty state: the posts ARE there, the FILES are
    // not published to anonymous visitors.
    val withheldText = tr(
        "Telegram's public page for this channel lists its posts but not the video " +
            "files — it shows them as \"view in Telegram\" only, which is what it " +
            "does for large uploads and for channels with saving restricted. Sign " +
            "in to Telegram above and they play here with your own account."
    )

    /**
     * One page of the channel: the anonymous public preview, and — only when it
     * turns out to have videos it cannot publish — the same channel through the
     * account.
     */
    suspend fun loadFirstPage() {
        val page = withContext(Dispatchers.IO) { TelegramWeb.loadPage(channel) }
        if (page == null) {
            failed = true
            return
        }
        videos = page.videos
        unpublished = page.unpublished
        if (page.videos.isNotEmpty() || page.unpublished == 0) return
        val ctx = context.applicationContext
        Td.init(ctx)
        if (!Td.isSignedIn()) return
        val id = withContext(Dispatchers.IO) { Td.publicChatId(channel) } ?: return
        tdChatId = id
        tdVideos = withContext(Dispatchers.IO) { Td.chatVideos(id, 0, 60) }
    }

    LaunchedEffect(channel) { loadFirstPage() }

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
                    tdVideos = null
                    scope.launch { loadFirstPage() }
                },
            )

            // The account path took over: the public page has these posts but
            // will not publish their files (see loadFirstPage), so this is the
            // channel read through the user's own Telegram login.
            tdVideos != null -> {
                val list = tdVideos.orEmpty()
                if (list.isEmpty()) {
                    EmptyState(title = tr("No videos here"), subtitle = withheldText)
                } else {
                    ChatVideoCollection(
                        videos = list,
                        view = view,
                        onPlay = { playTdVideo(context, it) },
                    ) {
                        item(key = "telegram-channel-td-more") {
                            if (tdEnded) {
                                Text(
                                    tr("That is the oldest post in this channel."),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            } else {
                                TextButton(
                                    enabled = !loadingMore,
                                    onClick = {
                                        val last = list.lastOrNull() ?: return@TextButton
                                        val id = tdChatId ?: return@TextButton
                                        loadingMore = true
                                        scope.launch {
                                            val older = withContext(Dispatchers.IO) {
                                                Td.chatVideos(id, last.messageId, 60)
                                            }.filterNot { v -> list.any { it.messageId == v.messageId } }
                                            loadingMore = false
                                            if (older.isEmpty()) tdEnded = true
                                            else tdVideos = list + older
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

            videos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            videos.orEmpty().isEmpty() -> EmptyState(
                title = tr("No videos here"),
                // Two different empty channels: one has no videos at all, and
                // one has videos Telegram will not publish (see withheldText).
                subtitle = if (unpublished > 0) withheldText else emptyText,
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
    // Start pulling the video NOW, before the player activity exists: resolving
    // the file reference and fetching the first part out of Telegram is the whole
    // cold-start cost of a Telegram video, so getting it going while the player
    // is still being built is what makes playback start on the first frame
    // instead of on the first download.
    Td.prewarm(video.fileId)
    val sources = JSONArray().put(
        JSONObject()
            .put("name", "Telegram")
            // The post it came from rides along so the data source can refresh an
            // expired file reference — TDLib takes the fresh one out of the
            // message it is handed (see TdFileDataSource.repair). Telegram's
            // references are short-lived, and this list can be minutes or days
            // old, which is exactly when a download would silently never start.
            .put("url", TdFileDataSource.uriFor(video.fileId, video.chatId, video.messageId))
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
