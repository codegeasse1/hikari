package com.hikari.app.telegram

import android.content.Context
import android.os.Build
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object Td {

    /** The states the tab renders. */
    sealed class Auth {
        /** No api_id/api_hash stored yet (or the tab has never started it). */
        object Idle : Auth()

        /** The native library could not be loaded — see [loadError]. */
        object Unavailable : Auth()
        object Starting : Auth()
        object WaitPhone : Auth()
        object WaitCode : Auth()
        data class WaitPassword(val hint: String) : Auth()
        data class WaitRegistration(val terms: String) : Auth()
        /** Waiting for a QR login to be confirmed on another device — see [link]. */
        data class WaitOtherDevice(val link: String) : Auth()
        object Ready : Auth()
        object Closed : Auth()
    }

    /** One chat as the list prints it. */
    data class Chat(
        val id: Long,
        val title: String,
        val kind: Kind,
        val order: Long,
        val unread: Int,
        val archived: Boolean = false,
    ) {
        enum class Kind { SAVED, PRIVATE, GROUP, CHANNEL, SECRET }
    }

    /** One playable video found in a chat. */
    data class ChatVideo(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val title: String,
        val duration: Int,
        val date: Int,
        val size: Long,
        /** `data:` URL of the post's inline minithumbnail, when it has one. */
        val thumb: String?,
        val caption: String = "",
        /** The video's original file name, as the sender wrote it. */
        val fileName: String = "",
        val thumbFileId: Int = 0,
    )

    /** What a search inside a chat looks at — see [ChatVideo.caption].
     *  [fileName]. A caption is message text, so Telegram indexes it; a file
     *  name is not, so that half has its own walker (see [searchChatVideos]). */
    enum class SearchIn(val key: String) {
        CHAT("chat"),
        VIDEO("video"),
        BOTH("both");

        companion object {
            fun fromKey(key: String?): SearchIn = entries.firstOrNull { it.key == key } ?: CHAT
        }
    }

    /** One page of [searchChatVideos]. */
    data class ChatVideoPage(
        val videos: List<ChatVideo>,
        /** Hand back as the next call's `cursor`; 0 means there is nothing more. */
        val nextCursor: Long,
        /** How many posts this call actually looked at — printed by the UI, so a
         *  partial scan never pretends to be a complete one. */
        val scanned: Int,
        val exhausted: Boolean,
    )

    /** One page of a chat's history, reduced to its videos, plus how to ask for
     *  the page after it — see [chatVideosPage]. */
    data class ChatHistoryVideos(
        val videos: List<ChatVideo>,
        /** The oldest message id on the page (0 when it was empty) — hand it
         *  back as `before` to walk further into the past. */
        val nextCursor: Long,
        /** Whether the page carried any messages at all: false is history's end
         *  (an exhausted walk is answered with an empty batch). */
        val more: Boolean,
        val note: String? = null,
    )

    data class FileState(
        val path: String,
        val size: Long,
        /** Where TDLib's current download run starts. */
        val downloadOffset: Long,
        /** Contiguous bytes on disk STARTING AT [downloadOffset]. */
        val readyFromOffset: Long,
        val complete: Boolean,
        val active: Boolean,
        /** False when TDLib has no usable remote location — see [canBeDownloaded]. */
        val canBeDownloaded: Boolean,
    )

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Set when the app itself asked for the client to be rebuilt (see [restart]). */
    private val restartWanted = AtomicBoolean(false)

    @Volatile
    private var client: Client? = null

    /** The credentials the RUNNING client was created with (null before one is). */
    @Volatile
    private var live: Pair<Int, String>? = null

    @Volatile
    private var pending: Pair<Int, String>? = null

    @Volatile
    private var myId: Long = 0

    /**
     * The id of the Saved Messages chat (the chat with our own user), once
     * [ensureSavedChat] has made sure it exists.
     */
    @Volatile
    private var savedChatId: Long = 0

    private val _auth = MutableStateFlow<Auth>(Auth.Idle)
    val auth: StateFlow<Auth> = _auth.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _me = MutableStateFlow("")
    val me: StateFlow<String> = _me.asStateFlow()

    /** Bumped whenever a file's download state changes, so the UI repaints. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** True while a login step is with TDLib and has not been answered yet. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The number the code was asked for ("" until one is submitted). */
    private val _sentTo = MutableStateFlow("")
    val sentTo: StateFlow<String> = _sentTo.asStateFlow()

    private val chatValues = ConcurrentHashMap<Long, TdApi.Chat>()
    /** Position order in the MAIN chat list; 0/absent means "not in it". */
    private val mainOrder = ConcurrentHashMap<Long, Long>()
    private val archiveOrder = ConcurrentHashMap<Long, Long>()
    private val leftBehind = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val fileValues = ConcurrentHashMap<Int, TdApi.File>()

    private val fetchedFiles = ConcurrentHashMap<Int, TdApi.File>()

    // ---- lifecycle -------------------------------------------------------

    /**
     * Load the native library once, and start TDLib when api_id/api_hash are
     * already stored. Cheap and safe to call from a composition.
     */
    fun init(context: Context) {
        if (client != null) return
        if (loadError != null) {
            _auth.value = Auth.Unavailable
            return
        }
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val app = context.applicationContext as? HikariApp
            val apiId = runCatching { app?.store?.telegramApiId() ?: 0 }.getOrDefault(0)
            val apiHash = runCatching { app?.store?.telegramApiHash() ?: "" }.getOrDefault("")
            if (apiId <= 0 || apiHash.isBlank()) {
                started.set(false)
                _auth.value = Auth.Idle
                return@launch
            }
            start(apiId, apiHash)
        }
    }

    /** The user's own api_id/api_hash, entered in the tab. */
    fun setCredentials(apiId: Int, apiHash: String) {
        if (apiId <= 0 || apiHash.isBlank()) return
        val wanted = apiId to apiHash
        val changed = pending != wanted
        pending = wanted
        _problem.value = null
        val running = client
        if (running == null) {
            start(apiId, apiHash)
            return
        }
        if (changed && live != wanted) restart()
    }

    fun restart() {
        _problem.value = null
        _busy.value = false
        val running = client
        if (running == null) {
            live = null
            started.set(false)
            pending?.let { (id, hash) -> start(id, hash) }
            return
        }
        restartWanted.set(true)
        _auth.value = Auth.Starting
        runCatching { running.send(TdApi.Close(), null, null) }
    }

    /** Forget the last error (a fresh attempt is about to be made). */
    fun clearProblem() {
        _problem.value = null
    }

    fun signInAgain() {
        _problem.value = null
        _busy.value = false
        _sentTo.value = ""
        pending = null
        live = null
        restartWanted.set(false)
        val running = client
        if (running == null) {
            started.set(false)
            _auth.value = Auth.Idle
            return
        }
        // Show the credentials page at once; the client finishes closing on its
        // own thread and lands on Auth.Closed, which draws the same fields.
        _auth.value = Auth.Idle
        runCatching { running.send(TdApi.Close(), null, null) }
    }

    /** The api_id as it should be SENT: the digits my.telegram.org shows. */
    fun apiIdOf(raw: String): Int? = raw.trim().filter { it in '0'..'9' }.toIntOrNull()?.takeIf { it > 0 }

    fun apiHashChars(raw: String): String =
        raw.trim().filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.lowercase()

    /** The api_hash as it should be sent, or null when it is not 32 hex chars. */
    fun apiHashOf(raw: String): String? = apiHashChars(raw).takeIf { it.length == 32 }

    fun keyProblem(idText: String, hashText: String): String? {
        if (apiIdOf(idText) == null) {
            return "The api_id is the NUMBER shown on my.telegram.org (digits only)."
        }
        val chars = apiHashChars(hashText)
        if (chars.length != 32) {
            return "An api_hash is exactly 32 hex characters — this one has ${chars.length}. " +
                "Copy both values from the SAME app on my.telegram.org → API development tools."
        }
        return null
    }

    private fun fail(error: TdApi.Error) {
        _busy.value = false
        _problem.value = TelegramError.explain(error.message)
    }

    private fun note(t: Throwable) {
        _busy.value = false
        _problem.value = "Telegram library error: " + (t.message ?: t.javaClass.simpleName)
    }

    private fun armBusyTimeout() {
        scope.launch {
            delay(30_000)
            if (_busy.value) _busy.value = false
        }
    }

    /** Create the client for [apiId]/[apiHash]. Idempotent, never throws. */
    private fun start(apiId: Int, apiHash: String) {
        if (client != null) return
        _auth.value = Auth.Starting
        val linkError = runCatching {
            System.loadLibrary("tdjni")
            null
        }.getOrElse { it.message ?: it.toString() }
        if (linkError != null) {
            loadError = linkError
            available = false
            started.set(false)
            _auth.value = Auth.Unavailable
            return
        }
        runCatching {
            Client.create(
                { update -> onUpdate(update) },
                { t -> note(t) },
                { t -> note(t) },
            )
        }.onSuccess { c ->
            available = true
            loadError = null
            client = c
            live = apiId to apiHash
            pending = apiId to apiHash
            started.set(true)
            _auth.value = Auth.Starting
        }.onFailure { e ->
            available = false
            loadError = e.message ?: e.toString()
            started.set(false)
            _auth.value = Auth.Unavailable
        }
    }

    fun signOut() {
        _problem.value = null
        _busy.value = false
        runCatching { client?.send(TdApi.LogOut(), null, null) }
        chatValues.clear()
        mainOrder.clear()
        archiveOrder.clear()
        leftBehind.clear()
        fileValues.clear()
        fetchedFiles.clear()
        _chats.value = emptyList()
        _me.value = ""
        _sentTo.value = ""
        myId = 0
        savedChatId = 0
    }

    fun normalizePhone(phone: String): String {
        val keep = phone.filter { it.isDigit() || it == '+' }
        val digits = keep.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return "+" + digits.trimStart('0').ifEmpty { digits }
    }

    /** Ask Telegram to send the login code to [phone]. */
    fun submitPhone(phone: String) {
        val number = normalizePhone(phone)
        if (number.length < 8) {
            _problem.value =
                "Enter the full phone number with its country code, e.g. +91 98512 27864."
            return
        }
        val c = client ?: run {
            _problem.value = "Telegram is still starting — give it a second and try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        _sentTo.value = number
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            /* allowFlashCall = */ false,
            /* allowMissedCall = */ false,
            /* isCurrentPhoneNumber = */ false,
            /* hasUnknownPhoneNumber = */ false,
            /* allowSmsRetrieverApi = */ true,
            /* firebaseAuthenticationSettings = */ null,
            /* authenticationTokens = */ emptyArray<String>(),
        )
        runCatching {
            c.send(
                TdApi.SetAuthenticationPhoneNumber(number, settings),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    /** The code Telegram sent. */
    fun submitCode(code: String) {
        val value = code.filter { !it.isWhitespace() }
        if (value.isEmpty()) return
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.CheckAuthenticationCode(value),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    /** The two-step (cloud) password. */
    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.CheckAuthenticationPassword(password),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun submitRegistration(first: String, last: String) {
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.RegisterUser(first, last, true),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    /** How many chats are brought in per [TdApi.LoadChats] call. */
    private const val CHAT_LOAD_PAGE = 100

    private const val CHAT_LOAD_MAX = 2_000

    fun loadChats() {
        val c = client ?: return
        scope.launch { refreshMe() }
        loadChatPage(c, TdApi.ChatListMain(), 0)
        loadChatPage(c, TdApi.ChatListArchive(), 0)
    }

    private fun loadChatPage(c: Client, list: TdApi.ChatList, loaded: Int) {
        if (loaded >= CHAT_LOAD_MAX) return
        runCatching {
            c.send(
                TdApi.LoadChats(list, CHAT_LOAD_PAGE),
                Client.ResultHandler { result ->
                    // Error 404 is TDLib's "the chat list is fully loaded";
                    // anything else is a refusal and asking again would not help.
                    if (result !is TdApi.Error) loadChatPage(c, list, loaded + CHAT_LOAD_PAGE)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    private suspend fun refreshMe() {
        val user = query(TdApi.GetMe()) as? TdApi.User ?: return
        myId = user.id
        _me.value = listOf(user.firstName, user.lastName)
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Me" }
        ensureSavedChat()
        publishChats()
    }

    private suspend fun ensureSavedChat() {
        val me = myId
        if (me == 0L) return
        val chat = query(TdApi.CreatePrivateChat(me, false)) as? TdApi.Chat ?: return
        savedChatId = chat.id
        chatValues[chat.id] = chat
    }

    // ---- TDLib plumbing --------------------------------------------------

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthState(update.authorizationState)
            is TdApi.UpdateNewChat -> {
                chatValues[update.chat.id] = update.chat
                absorbPositions(update.chat.id, update.chat.positions)
                publishChats()
            }
            is TdApi.UpdateChatPosition -> {
                absorbPosition(update.chatId, update.position)
                publishChats()
            }
            is TdApi.UpdateChatLastMessage -> {
                absorbPositions(update.chatId, update.positions)
                publishChats()
            }
            is TdApi.UpdateChatTitle -> {
                chatValues[update.chatId]?.let { c ->
                    c.title = update.title
                    publishChats()
                }
            }
            is TdApi.UpdateChatReadInbox -> publishChats()
            is TdApi.UpdateChatRemovedFromList -> {
                forgetInList(update.chatId, update.chatList)
                publishChats()
            }
            is TdApi.UpdateChatAddedToList -> {
                leftBehind.remove(update.chatId)
                publishChats()
            }
            is TdApi.UpdateFile -> {
                fileValues[update.file.id] = update.file
                _revision.value = _revision.value + 1
            }
            is TdApi.UpdateConnectionState ->
                _online.value = update.state is TdApi.ConnectionStateReady
        }
    }

    /** Records one chat position. Order 0 means the chat LEFT that list. */
    private fun absorbPosition(chatId: Long, position: TdApi.ChatPosition) {
        when (position.list) {
            is TdApi.ChatListMain ->
                if (position.order == 0L) mainOrder.remove(chatId) else mainOrder[chatId] = position.order
            is TdApi.ChatListArchive ->
                if (position.order == 0L) archiveOrder.remove(chatId) else archiveOrder[chatId] = position.order
            else -> Unit
        }
        if (position.order != 0L) leftBehind.remove(chatId)
    }

    private fun absorbPositions(chatId: Long, positions: Array<TdApi.ChatPosition>?) {
        positions?.forEach { absorbPosition(chatId, it) }
    }

    private fun forgetInList(chatId: Long, list: TdApi.ChatList?) {
        when (list) {
            is TdApi.ChatListMain -> mainOrder.remove(chatId)
            is TdApi.ChatListArchive -> archiveOrder.remove(chatId)
            else -> Unit
        }
        leftBehind.add(chatId)
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _busy.value = false
                // Reached again after a restart: nothing has been sent yet.
                if (_auth.value != Auth.WaitPhone) _sentTo.value = ""
                _auth.value = Auth.WaitPhone
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.WaitCode
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.WaitPassword(state.passwordHint.orEmpty())
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                _busy.value = false
                _auth.value = Auth.WaitRegistration(state.termsOfService?.text?.text.orEmpty())
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _busy.value = false
                _auth.value = Auth.WaitOtherDevice(state.link)
            }
            is TdApi.AuthorizationStateReady -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.Ready
                loadChats()
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> Unit
            is TdApi.AuthorizationStateClosed -> {
                client = null
                live = null
                started.set(false)
                chatValues.clear()
                mainOrder.clear()
                archiveOrder.clear()
                leftBehind.clear()
                fileValues.clear()
                fetchedFiles.clear()
                _chats.value = emptyList()
                _me.value = ""
                _busy.value = false
                _auth.value = Auth.Closed
                if (restartWanted.getAndSet(false)) {
                    pending?.let { (id, hash) -> start(id, hash) }
                }
            }
            // Login variants this build does not implement (email/Google/Apple
            // sign-in and the premium-purchase step). The phone-number flow in
            // the tab is the one every Telegram account has; the tab says so and
            // offers to start over rather than sitting on a blank state.
            else -> {
                _busy.value = false
                _auth.value = Auth.Closed
            }
        }
    }

    private fun sendParameters() {
        val creds = pending ?: return
        val root = File(HikariApp.instance.filesDir, "tdlib")
        File(root, "db").mkdirs()
        File(root, "files").mkdirs()
        client?.send(
            TdApi.SetTdlibParameters(
                /* useTestDc = */ false,
                /* databaseDirectory = */ File(root, "db").absolutePath,
                /* filesDirectory = */ File(root, "files").absolutePath,
                /* databaseEncryptionKey = */ ByteArray(0),
                /* useFileDatabase = */ true,
                /* useChatInfoDatabase = */ true,
                /* useMessageDatabase = */ true,
                /* useSecretChats = */ false,
                /* apiId = */ creds.first,
                /* apiHash = */ creds.second,
                /* systemLanguageCode = */ Locale.getDefault().language.ifBlank { "en" },
                /* deviceModel = */ Build.MANUFACTURER + " " + Build.MODEL,
                /* systemVersion = */ "Android " + Build.VERSION.RELEASE,
                /* applicationVersion = */ "Hikari " + BuildConfig.VERSION_NAME,
            ),
            Client.ResultHandler { result ->
                if (result is TdApi.Error) fail(result)
            },
            null,
        )
    }

    /** One query as a suspend function. Null on error (and when there is no client). */
    private suspend fun <T : TdApi.Object> query(
        function: TdApi.Function<T>,
        onError: ((TdApi.Error) -> Unit)? = null,
    ): TdApi.Object? {
        val c = client ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                c.send(function) { result ->
                    if (result is TdApi.Error) {
                        onError?.invoke(result)
                        cont.resume(null)
                    } else {
                        cont.resume(result)
                    }
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    /** A TDLib refusal as one line the tab can print under an empty list. */
    private fun errorText(e: TdApi.Error): String =
        "Telegram said ${e.code}: ${e.message.ifBlank { "refused" }}"

    /** Synchronous TDLib call — safe from any thread, never touches the network. */
    private fun <T : TdApi.Object> execute(function: TdApi.Function<T>): TdApi.Object? =
        runCatching { Client.execute(function) }.getOrNull()

    // ---- chats -----------------------------------------------------------

    private fun publishChats() {
        val me = myId
        val saved = savedChatId
        val list = chatValues.values.mapNotNull { chat ->
            val order = mainOrder[chat.id] ?: 0L
            val archive = archiveOrder[chat.id] ?: 0L
            val kind = when (val type = chat.type) {
                is TdApi.ChatTypePrivate ->
                    // The chat whose other end is us. [ensureSavedChat]'s id is
                    // the authority (that is the chat we created); the user-id
                    // comparisons are the same test arrived at from the chat's
                    // own side, for builds where TDLib reports a private chat's
                    // id separately from the user it is with.
                    if (chat.id == saved || (me != 0L && (type.userId == me || chat.id == me)))
                        Chat.Kind.SAVED
                    else Chat.Kind.PRIVATE
                is TdApi.ChatTypeSecret -> Chat.Kind.SECRET
                is TdApi.ChatTypeSupergroup ->
                    if (type.isChannel) Chat.Kind.CHANNEL else Chat.Kind.GROUP
                else -> Chat.Kind.GROUP
            }
            // Which lists is this chat in?
            //
            // The positions are the authority (order 0 = not in that list), and
            // the ARCHIVE is checked as well as the main list — that is the
            // whole point of [archiveOrder]. When TDLib has told us about a chat
            // but has not positioned it yet, the chat's OWN list membership is
            // the fallback, so a chat the user is a member of can never be
            // silently missing; an explicit removal from a list always wins over
            // that fallback (see [leftBehind]). Saved Messages is the one chat
            // that is always shown, even when nothing has ordered it yet.
            val membership = if (mainOrder.containsKey(chat.id) || archiveOrder.containsKey(chat.id)) {
                false
            } else {
                (chat.chatLists?.size ?: 0) > 0 && !leftBehind.contains(chat.id)
            }
            if (order == 0L && archive == 0L && !membership && kind != Chat.Kind.SAVED) {
                return@mapNotNull null
            }
            Chat(
                id = chat.id,
                title = if (kind == Chat.Kind.SAVED) "Saved Messages"
                else chat.title.ifBlank { "Chat" },
                kind = kind,
                order = if (order != 0L) order else archive,
                unread = chat.unreadCount,
                archived = order == 0L && archive != 0L,
            )
        }.sortedWith(
            compareBy(
                { if (it.kind == Chat.Kind.SAVED) 0 else if (it.archived) 2 else 1 },
                { -it.order },
            )
        )
        if (list != _chats.value) _chats.value = list
    }

    /** True when a query can be answered right now — the account is signed in
     *  and the client is running. */
    fun isSignedIn(): Boolean = _auth.value is Auth.Ready

    suspend fun publicChatId(username: String): Long? = publicChat(username)?.first

    suspend fun joinedChat(username: String): Pair<Long, String>? {
        val name = handleOf(username) ?: return null
        val found = query(TdApi.SearchChats(name, null, 20)) as? TdApi.Chats ?: return null
        val ids = found.chatIds?.toList().orEmpty().take(12)
        if (ids.isEmpty()) return null
        var titleHit: Pair<Long, String>? = null
        for (id in ids) {
            val chat = query(TdApi.GetChat(id)) as? TdApi.Chat ?: continue
            val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: 0L
            val handles = if (sgId != 0L) {
                val sg = query(TdApi.GetSupergroup(sgId)) as? TdApi.Supergroup
                sg?.usernames?.activeUsernames?.toList().orEmpty() +
                    sg?.usernames?.disabledUsernames?.toList().orEmpty()
            } else emptyList()
            if (handles.any { it.equals(name, ignoreCase = true) }) return chat.id to chat.title
            if (titleHit == null && chat.title.equals(name, ignoreCase = true)) {
                titleHit = chat.id to chat.title
            }
        }
        return titleHit
    }

    suspend fun publicChat(username: String): Pair<Long, String>? {
        val name = handleOf(username) ?: return null
        val chat = query(TdApi.SearchPublicChat(name)) as? TdApi.Chat ?: return null
        return chat.id to chat.title
    }

    /** `"@name"`, `"name"`, a `t.me` link or a post link → the bare name. */
    private fun handleOf(username: String): String? {
        val name = username.trim()
            .removePrefix("@")
            .removePrefix("https://t.me/")
            .removePrefix("http://t.me/")
            .removePrefix("t.me/")
            .substringBefore('/')
        return name.takeIf { it.isNotBlank() }
    }

    suspend fun chatVideosPage(chatId: Long, before: Long = 0, limit: Int = 60): ChatHistoryVideos {
        val videos = LinkedHashMap<Long, ChatVideo>()
        var cursor = Long.MAX_VALUE
        var sawMessages = false
        var note: String? = null

        var indexError: String? = null
        val indexed = query(
            TdApi.SearchChatMessages(
                chatId,
                /* topicId = */ null,
                /* query = */ "",
                /* senderId = */ null,
                before,
                /* offset = */ 0,
                limit.coerceIn(1, SEARCH_HITS_PER_CALL),
                TdApi.SearchMessagesFilterVideo(),
            ),
        ) { indexError = errorText(it) } as? TdApi.FoundChatMessages
        if (indexed != null) {
            for (m in indexed.messages) {
                sawMessages = true
                cursor = minOf(cursor, m.id)
                videoOf(chatId, m)?.let { videos.putIfAbsent(it.messageId, it) }
            }
        } else if (indexError != null) {
            note = indexError
        }

        var historyError: String? = null
        val history = query(TdApi.GetChatHistory(chatId, before, 0, limit, false)) {
            historyError = errorText(it)
        } as? TdApi.Messages
        if (history != null) {
            for (m in history.messages) {
                sawMessages = true
                cursor = minOf(cursor, m.id)
                videoOf(chatId, m)?.let { videos.putIfAbsent(it.messageId, it) }
            }
        } else if (historyError != null) {
            note = note ?: historyError
        }

        val page = videos.values.sortedByDescending { it.messageId }
        return ChatHistoryVideos(
            videos = page,
            nextCursor = if (cursor == Long.MAX_VALUE) 0L else cursor,
            more = sawMessages,
            // Only worth printing when the page really is empty — one dead
            // reader beside a working one is not a reason to say anything.
            note = if (page.isEmpty()) note else null,
        )
    }

    suspend fun chatVideos(chatId: Long, before: Long = 0, limit: Int = 60): List<ChatVideo> =
        chatVideosPage(chatId, before, limit).videos

    suspend fun chatMediaCounts(chatId: Long): Pair<Int?, Int?> {
        suspend fun total(filter: TdApi.SearchMessagesFilter): Int? {
            val found = query(
                TdApi.SearchChatMessages(
                    chatId,
                    null,
                    "",
                    null,
                    0,
                    0,
                    1,
                    filter,
                ),
            ) as? TdApi.FoundChatMessages
            return found?.totalCount
        }
        return total(TdApi.SearchMessagesFilterPhoto()) to total(TdApi.SearchMessagesFilterVideo())
    }

    suspend fun linkVideo(url: String): ChatVideo? {
        val info = query(TdApi.GetMessageLinkInfo(url)) as? TdApi.MessageLinkInfo ?: return null
        val message = info.message ?: return null
        val chatId = if (info.chatId != 0L) info.chatId else message.chatId
        if (chatId == 0L) return null
        videoOf(chatId, message)?.let { return it }
        var from = message.id
        var pages = 0
        while (pages < 2) {
            pages++
            val page = query(
                TdApi.GetChatHistory(chatId, from, -(LINK_TAIL - 1), LINK_TAIL, false),
            ) as? TdApi.Messages ?: break
            val messages = page.messages
            if (messages.isEmpty()) break
            var newest = from
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                i--
                if (m.id > newest) newest = m.id
                if (m.id <= message.id) continue
                videoOf(chatId, m)?.let { return it }
            }
            if (newest <= from) break
            from = newest
        }
        return null
    }

    /** How many messages after a linked post are checked for its video (an
     *  album's file sits immediately after the caption; see [linkVideo]). */
    private const val LINK_TAIL = 10

    private fun videoOf(chatId: Long, message: TdApi.Message): ChatVideo? =
        when (val c = message.content) {
            is TdApi.MessageVideo -> ChatVideo(
                chatId = chatId,
                messageId = message.id,
                fileId = c.video.video.id,
                title = c.caption.text.ifBlank { c.video.fileName }.ifBlank { "Video" },
                duration = c.video.duration,
                date = message.date,
                size = c.video.video.size,
                thumb = minithumb(c.video.minithumbnail),
                caption = c.caption.text,
                fileName = c.video.fileName,
                thumbFileId = c.video.thumbnail?.file?.id ?: 0,
            )
            is TdApi.MessageAnimation -> ChatVideo(
                chatId = chatId,
                messageId = message.id,
                fileId = c.animation.animation.id,
                title = c.caption.text.ifBlank { c.animation.fileName }.ifBlank { "Video" },
                duration = c.animation.duration,
                date = message.date,
                size = c.animation.animation.size,
                thumb = minithumb(c.animation.minithumbnail),
                caption = c.caption.text,
                fileName = c.animation.fileName,
                thumbFileId = c.animation.thumbnail?.file?.id ?: 0,
            )
            is TdApi.MessageDocument -> {
                val doc = c.document
                if (doc.mimeType.startsWith("video/")) {
                    ChatVideo(
                        chatId = chatId,
                        messageId = message.id,
                        fileId = doc.document.id,
                        title = c.caption.text.ifBlank { doc.fileName }.ifBlank { "Video" },
                        duration = 0,
                        date = message.date,
                        size = doc.document.size,
                        thumb = minithumb(doc.minithumbnail),
                        caption = c.caption.text,
                        fileName = doc.fileName,
                        thumbFileId = doc.thumbnail?.file?.id ?: 0,
                    )
                } else null
            }
            else -> null
        }

    /** How many posts one [searchChatVideos] call will walk before handing
     *  control back to the caller (so a 50 000-post channel cannot sit in one
     *  call without the UI ever seeing a page). */
    private const val SCAN_MESSAGES_PER_CALL = 400

    /** The most videos the post-text half of [SearchIn.BOTH] may pull in (see
     *  [videosOfPost] — one tag post can carry a whole batch). */
    private const val BOTH_TEXT_MAX = 2_000

    /** How many messages one `GetChatHistory` call asks for when walking a
     *  chat's history. TDLib's own maximum. */
    private const val HISTORY_PAGE = 100

    /** How many tag hits one `SearchChatMessages` call asks for. TDLib's own
     *  maximum; this was 50, which made a tag with more than 50 matching posts
     *  take two round trips for no reason. */
    private const val SEARCH_HITS_PER_CALL = 100

    private const val POST_VIDEOS_MAX = 2_000

    /** How many messages one tail page asks for. TDLib requires the limit to be
     *  at least as large as the (negative) offset, so a page is
     *  `TAIL_PAGE - 1` newer messages plus the message it started from. */
    private const val TAIL_PAGE = 100

    /** The furthest a single tail walk may read, in pages — a hard stop so a
     *  chat that never ends cannot sit in one call. 60 × 99 ≈ 5 900 messages. */
    private const val TAIL_MAX_PAGES = 60

    /** The words a message carries itself — its text, or its media's caption. */
    private fun textOf(message: TdApi.Message): String = when (val c = message.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageAudio -> c.caption.text
        else -> ""
    }

    private suspend fun videosOfPost(chatId: Long, anchor: TdApi.Message, q: String): List<ChatVideo> {
        val out = mutableListOf<ChatVideo>()
        val seen = HashSet<Long>()
        val anchorText = textOf(anchor)
        videoOf(chatId, anchor)?.let { if (seen.add(it.messageId)) out += it }
        var from = anchor.id
        var pages = 0
        var done = false
        while (!done && out.size < POST_VIDEOS_MAX && pages < TAIL_MAX_PAGES) {
            pages++
            val newer = query(
                TdApi.GetChatHistory(chatId, from, -(TAIL_PAGE - 1), TAIL_PAGE, false),
            ) as? TdApi.Messages ?: break
            val messages = newer.messages
            if (messages.isEmpty()) break
            var newest = from
            // TDLib answers newest-first; a post reads oldest-first. Walked by
            // index on purpose: `messages` is a Java array, and this avoids
            // depending on which `reversed`/`asReversed` overload the stdlib
            // exposes for one.
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                i--
                if (m.id > newest) newest = m.id
                if (m.id <= anchor.id || !seen.add(m.id)) continue
                val video = videoOf(chatId, m)
                if (video != null) {
                    val own = video.caption
                    if (own.isNotBlank() && !own.contains(q, ignoreCase = true)) {
                        done = true
                        break
                    }
                    out += if (anchorText.isBlank()) video else video.copy(caption = anchorText)
                    if (out.size >= POST_VIDEOS_MAX) {
                        done = true
                        break
                    }
                    continue
                }
                if (textOf(m).isNotBlank()) {
                    done = true
                    break
                }
            }
            if (done) break
            // Nothing newer than what we already have: the end of the chat.
            if (newest <= from) break
            from = newest
        }
        return out
    }

    suspend fun searchChatVideos(
        chatId: Long,
        text: String,
        inText: SearchIn,
        cursor: Long = 0,
        limit: Int = 60,
    ): ChatVideoPage {
        val q = text.trim()
        if (q.isEmpty()) return ChatVideoPage(emptyList(), 0, 0, true)

        if (inText == SearchIn.BOTH) {
            val byText = searchChatVideos(chatId, text, SearchIn.CHAT, cursor = 0L, limit = BOTH_TEXT_MAX)
            val byName = searchChatVideos(chatId, text, SearchIn.VIDEO, cursor, limit)
            val seen = HashSet<Long>()
            return ChatVideoPage(
                videos = (byText.videos + byName.videos).filter { seen.add(it.messageId) },
                // The walk is the half with more to ask; the text search is
                // complete on its first call.
                nextCursor = byName.nextCursor,
                scanned = byName.scanned + byText.scanned,
                exhausted = byName.exhausted,
            )
        }

        if (inText == SearchIn.CHAT) {
            val found = query(
                TdApi.SearchChatMessages(
                    chatId,
                    /* topicId = */ null,
                    q,
                    /* senderId = */ null,
                    cursor,
                    /* offset = */ 0,
                    limit.coerceIn(1, SEARCH_HITS_PER_CALL),
                    TdApi.SearchMessagesFilterEmpty(),
                ),
            ) as? TdApi.FoundChatMessages
                ?: return ChatVideoPage(emptyList(), 0, 0, true)
            val out = mutableListOf<ChatVideo>()
            val seen = HashSet<Long>()
            for (hit in found.messages) {
                for (v in videosOfPost(chatId, hit, q)) {
                    if (seen.add(v.messageId)) out += v
                }
                if (out.size >= limit) break
            }
            val next = found.nextFromMessageId
            return ChatVideoPage(out, next, found.messages.size, next == 0L)
        }

        val out = mutableListOf<ChatVideo>()
        val seen = HashSet<Long>()
        var from = cursor
        var scanned = 0
        var more = true
        while (more && scanned < SCAN_MESSAGES_PER_CALL && out.size < limit) {
            val page = query(TdApi.GetChatHistory(chatId, from, 0, HISTORY_PAGE, false))
                as? TdApi.Messages
                ?: return ChatVideoPage(out, 0, scanned, false)
            if (page.messages.isEmpty()) {
                more = false
                break
            }
            var advanced = false
            for (m in page.messages) {
                scanned++
                if (!seen.add(m.id)) continue
                advanced = true
                val v = videoOf(chatId, m) ?: continue
                if (v.fileName.contains(q, ignoreCase = true)) out += v
            }
            from = page.messages.last().id
            more = advanced
        }
        val exhausted = !more
        return ChatVideoPage(
            videos = out,
            nextCursor = if (exhausted) 0L else from,
            scanned = scanned,
            exhausted = exhausted,
        )
    }

    suspend fun thumbPath(fileId: Int, waitMs: Long = 8_000): String? {
        if (fileId == 0) return null
        runCatching { loadFile(fileId) }
        val deadline = System.currentTimeMillis() + waitMs
        var asked = false
        while (true) {
            val f = fileValues[fileId] ?: fetchedFiles[fileId]
            val local = f?.local
            if (local?.isDownloadingCompleted == true && local.path.isNotBlank()) return local.path
            if (!asked) {
                request(fileId, 0, 0)
                asked = true
            }
            if (System.currentTimeMillis() >= deadline) return null
            delay(200)
        }
    }

    private fun minithumb(t: TdApi.Minithumbnail?): String? {
        val data = t?.data ?: return null
        if (data.isEmpty()) return null
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }

    // ---- files (playback) ------------------------------------------------

    /** Everything known about one file, read straight out of TDLib. */
    fun fileState(fileId: Int): FileState? {
        val f = fileValues[fileId] ?: fetchedFiles[fileId] ?: return null
        val local = f.local
        return FileState(
            path = local?.path.orEmpty(),
            size = maxOf(f.size, f.expectedSize),
            downloadOffset = local?.downloadOffset ?: 0L,
            readyFromOffset = local?.downloadedPrefixSize ?: 0L,
            complete = local?.isDownloadingCompleted == true,
            active = local?.isDownloadingActive == true,
            canBeDownloaded = local?.canBeDownloaded == true ||
                local?.isDownloadingActive == true ||
                local?.isDownloadingCompleted == true,
        )
    }

    suspend fun loadFile(fileId: Int): TdApi.File? {
        fileValues[fileId]?.let { return it }
        fetchedFiles[fileId]?.let { return it }
        val f = query(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
        fetchedFiles[fileId] = f
        return f
    }

    /** [fileState], with a real lookup behind it — see [loadFile]. Suspend
     *  because that lookup goes to TDLib. */
    suspend fun stateOf(fileId: Int): FileState? {
        loadFile(fileId)
        return fileState(fileId)
    }

    private val downloadErrors = ConcurrentHashMap<Int, String>()

    /** Why TDLib refused to download [fileId], or null. See [request]. */
    fun downloadError(fileId: Int): String? = downloadErrors[fileId]

    /** Forget a recorded refusal — a fresh attempt (after a repair) is about to be made. */
    fun clearDownloadError(fileId: Int) {
        downloadErrors.remove(fileId)
    }

    fun forgetPlayedFile(fileId: Int) {
        val c = client ?: return
        fileValues.remove(fileId)
        fetchedFiles.remove(fileId)
        downloadErrors.remove(fileId)
        runCatching {
            c.send(
                TdApi.DeleteFile(fileId),
                org.drinkless.tdlib.Client.ResultHandler { },
                null,
            )
        }
    }

    fun request(fileId: Int, offset: Long, limit: Long) {
        val c = client ?: return
        runCatching {
            c.send(
                TdApi.DownloadFile(fileId, 32, offset, limit, false),
                Client.ResultHandler { result ->
                    if (result is TdApi.Error) {
                        downloadErrors[fileId] = result.message
                        com.hikari.app.data.Logs.log(
                            "Telegram",
                            "TDLib refused to download file $fileId from $offset: ${result.message}",
                        )
                    } else {
                        downloadErrors.remove(fileId)
                    }
                    _revision.value = _revision.value + 1
                },
                null,
            )
        }
    }

    fun prewarm(fileId: Int) {
        downloadErrors.remove(fileId)
        request(fileId, 0L, AHEAD_BYTES)
    }

    suspend fun availableFrom(fileId: Int, offset: Long): Long =
        (query(TdApi.GetFileDownloadedPrefixSize(fileId, offset)) as? TdApi.FileDownloadedPrefixSize)
            ?.size ?: -1L

    suspend fun touchMessage(chatId: Long, messageId: Long): Int {
        if (chatId == 0L || messageId == 0L) return 0
        val msg = query(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return 0
        return when (val c = msg.content) {
            is TdApi.MessageVideo -> c.video.video.id
            is TdApi.MessageAnimation -> c.animation.animation.id
            is TdApi.MessageDocument ->
                if (c.document.mimeType.startsWith("video/")) c.document.document.id else 0
            else -> 0
        }
    }

    const val AHEAD_BYTES: Long = 64L * 1024 * 1024
}
