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

/**
 * A real Telegram client, on TDLib — the library every official third-party
 * Telegram client is built on.
 *
 * This is what [TelegramWeb] cannot be: `t.me/s/<channel>` only ever shows what
 * an anonymous visitor sees, i.e. PUBLIC channels. Everything else — Saved
 * Messages, private channels and groups, chats with people, reading a chat's
 * history all the way back — needs an authorized MTProto session, and TDLib is
 * what speaks it (the API, MTProto, the crypto, the local database, the media
 * cache). The native library and the Java bindings it registers itself with
 * ship in `app/src/main/jniLibs` (libtdjni.so, one per processor) and
 * `org.drinkless.tdlib` — see the README.
 *
 * Signing in is the user's OWN Telegram account with the user's OWN api_id and
 * api_hash (my.telegram.org → API development tools). There is deliberately no
 * bundled pair: an api_id/api_hash belongs to the application that registered
 * it, and shipping someone else's — or one lifted out of another app's APK — is
 * both against Telegram's terms and something Telegram revokes. The tab asks
 * once and keeps them; after that the session lives in TDLib's own database and
 * survives restarts exactly like the real app's.
 *
 * Everything here FAILS SOFT: if the native library cannot load (an ABI we do
 * not ship, an install that stripped it), [available] stays false and the tab
 * says so — the public-channel browsing that needs no account keeps working.
 */
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
        object WaitOtherDevice : Auth()
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
    )

    /** What a file's download looks like right now. */
    data class FileState(
        val path: String,
        val size: Long,
        val downloaded: Long,
        val complete: Boolean,
        val active: Boolean,
    )

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var pending: Pair<Int, String>? = null

    @Volatile
    private var myId: Long = 0

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

    private val chatValues = ConcurrentHashMap<Long, TdApi.Chat>()
    private val mainOrder = ConcurrentHashMap<Long, Long>()
    private val fileValues = ConcurrentHashMap<Int, TdApi.File>()

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
        pending = apiId to apiHash
        if (client == null) start(apiId, apiHash)
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
                { /* an update handler threw — ignore, TDLib keeps running */ },
                { /* the same, for query results */ },
            )
        }.onSuccess { c ->
            available = true
            loadError = null
            client = c
            pending = apiId to apiHash
            _auth.value = Auth.Starting
        }.onFailure { e ->
            available = false
            loadError = e.message ?: e.toString()
            started.set(false)
            _auth.value = Auth.Unavailable
        }
    }

    fun signOut() {
        client?.send(TdApi.LogOut(), null, null)
        chatValues.clear()
        mainOrder.clear()
        fileValues.clear()
        _chats.value = emptyList()
        _me.value = ""
        myId = 0
    }

    /** Phone number in international format ("+91…"), as Telegram wants it. */
    fun submitPhone(phone: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            /* allowFlashCall = */ false,
            /* allowMissedCall = */ false,
            /* isCurrentPhoneNumber = */ false,
            /* hasUnknownPhoneNumber = */ false,
            /* allowSmsRetrieverApi = */ true,
            /* firebaseAuthenticationSettings = */ null,
            /* authenticationTokens = */ emptyArray<String>(),
        )
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), settings), null, null)
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code.trim()), null, null)
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password), null, null)
    }

    fun submitRegistration(first: String, last: String) {
        client?.send(TdApi.RegisterUser(first, last, true), null, null)
    }

    /** Ask TDLib for the main chat list (again). Safe to repeat. */
    fun loadChats() {
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100), null, null)
        scope.launch { refreshMe() }
    }

    private suspend fun refreshMe() {
        val user = query(TdApi.GetMe()) as? TdApi.User ?: return
        myId = user.id
        _me.value = listOf(user.firstName, user.lastName)
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Me" }
        publishChats()
    }

    // ---- TDLib plumbing --------------------------------------------------

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthState(update.authorizationState)
            is TdApi.UpdateNewChat -> {
                chatValues[update.chat.id] = update.chat
                publishChats()
            }
            is TdApi.UpdateChatPosition -> if (update.position.list is TdApi.ChatListMain) {
                mainOrder[update.chatId] = update.position.order
                publishChats()
            }
            is TdApi.UpdateChatTitle -> {
                chatValues[update.chatId]?.let { c ->
                    c.title = update.title
                    publishChats()
                }
            }
            is TdApi.UpdateChatLastMessage,
            is TdApi.UpdateChatReadInbox,
            is TdApi.UpdateChatRemovedFromList,
            is TdApi.UpdateChatAddedToList -> publishChats()
            is TdApi.UpdateFile -> {
                fileValues[update.file.id] = update.file
                _revision.value = _revision.value + 1
            }
            is TdApi.UpdateConnectionState ->
                _online.value = update.state is TdApi.ConnectionStateReady
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = Auth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _auth.value = Auth.WaitCode
            is TdApi.AuthorizationStateWaitPassword ->
                _auth.value = Auth.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateWaitRegistration ->
                _auth.value = Auth.WaitRegistration(state.termsOfService?.text?.text.orEmpty())
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _auth.value = Auth.WaitOtherDevice
            is TdApi.AuthorizationStateReady -> {
                _auth.value = Auth.Ready
                loadChats()
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> Unit
            is TdApi.AuthorizationStateClosed -> {
                client = null
                started.set(false)
                chatValues.clear()
                mainOrder.clear()
                _chats.value = emptyList()
                _me.value = ""
                _auth.value = Auth.Closed
            }
            // Login variants this build does not implement (email/Google/Apple
            // sign-in and the premium-purchase step). The phone-number flow in
            // the tab is the one every Telegram account has.
            else -> _auth.value = Auth.Closed
        }
    }

    /**
     * The parameters every client sends first.
     *
     * The database is TDLib's own directory under the app's FILES dir, not its
     * cache: the OS deletes a cache whenever storage runs low, and a lost TDLib
     * database is a lost session — the user would be asked for the code again.
     * The database encryption key is deliberately empty (no encryption): the
     * lock this app has is the app lock in Settings, and a key we generated
     * would have to be stored somewhere anyway.
     */
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
            null,
            null,
        )
    }

    /** One query as a suspend function. Null on error (and when there is no client). */
    private suspend fun <T : TdApi.Object> query(function: TdApi.Function<T>): TdApi.Object? {
        val c = client ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                c.send(function) { result ->
                    if (result is TdApi.Error) cont.resume(null) else cont.resume(result)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    /** Synchronous TDLib call — safe from any thread, never touches the network. */
    private fun <T : TdApi.Object> execute(function: TdApi.Function<T>): TdApi.Object? =
        runCatching { Client.execute(function) }.getOrNull()

    // ---- chats -----------------------------------------------------------

    private fun publishChats() {
        val me = myId
        val list = chatValues.values.mapNotNull { chat ->
            val order = mainOrder[chat.id] ?: 0L
            // order 0 means "not in the main list" — archived chats, and chats
            // TDLib has not placed yet. Saved Messages is the one chat that is
            // always in the list even before a position arrives for it.
            if (order == 0L && chat.id != me) return@mapNotNull null
            val kind = when (val type = chat.type) {
                is TdApi.ChatTypePrivate ->
                    if (type.userId == me) Chat.Kind.SAVED else Chat.Kind.PRIVATE
                is TdApi.ChatTypeSecret -> Chat.Kind.SECRET
                is TdApi.ChatTypeSupergroup ->
                    if (type.isChannel) Chat.Kind.CHANNEL else Chat.Kind.GROUP
                else -> Chat.Kind.GROUP
            }
            Chat(
                id = chat.id,
                title = if (kind == Chat.Kind.SAVED) "Saved Messages"
                else chat.title.ifBlank { "Chat" },
                kind = kind,
                order = order,
                unread = chat.unreadCount,
            )
        }.sortedWith(
            // Saved Messages is not "ordered by activity" like a chat is: it is
            // the user's own corner, so it sits at the top of the list.
            compareBy({ if (it.kind == Chat.Kind.SAVED) 0 else 1 }, { -it.order })
        )
        _chats.value = list
    }

    /** The videos of a chat, newest first; [before] walks back through history. */
    suspend fun chatVideos(chatId: Long, before: Long = 0, limit: Int = 60): List<ChatVideo> {
        val result = query(TdApi.GetChatHistory(chatId, before, 0, limit, false)) as? TdApi.Messages
            ?: return emptyList()
        return result.messages.mapNotNull { videoOf(chatId, it) }
    }

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
                    )
                } else null
            }
            else -> null
        }

    /**
     * A post's inline thumbnail as a `data:` URL the app's poster loader already
     * understands. It is a few hundred bytes, so it costs no request and shows
     * the moment the row is drawn — exactly what a list of videos wants.
     */
    private fun minithumb(t: TdApi.Minithumbnail?): String? {
        val data = t?.data ?: return null
        if (data.isEmpty()) return null
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }

    // ---- files (playback) ------------------------------------------------

    /** Everything known about one file, read straight out of TDLib. */
    fun fileState(fileId: Int): FileState? {
        val f = fileValues[fileId] ?: execute(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
        return FileState(
            path = f.local?.path.orEmpty(),
            size = maxOf(f.size, f.expectedSize),
            downloaded = maxOf(f.local?.downloadedPrefixSize ?: 0L, f.local?.downloadedSize ?: 0L),
            complete = f.local?.isDownloadingCompleted == true,
            active = f.local?.isDownloadingActive == true,
        )
    }

    /** Ask TDLib to fetch [limit] bytes of [fileId] starting at [offset]. */
    fun request(fileId: Int, offset: Long, limit: Long) {
        client?.send(TdApi.DownloadFile(fileId, 32, offset, limit, false), null, null)
    }

    /**
     * Wait until [offset] + [bytes] of the file are on disk (or the whole file
     * is), asking for them first. Returns the last state seen on timeout, so the
     * caller can show progress instead of hanging.
     */
    suspend fun await(fileId: Int, offset: Long, bytes: Long, timeoutMs: Long = 60_000): FileState? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var state = fileState(fileId)
        var askedAt = -1L
        while (System.currentTimeMillis() < deadline) {
            state = fileState(fileId) ?: state
            if (state?.complete == true || (state?.downloaded ?: 0L) >= offset + bytes) return state
            // TDLib cancels a previous request for the same file when a new one
            // arrives, so a request must be re-issued for every new offset.
            if (askedAt != offset) {
                request(fileId, offset, bytes)
                askedAt = offset
            }
            delay(150)
        }
        return state
    }
}
