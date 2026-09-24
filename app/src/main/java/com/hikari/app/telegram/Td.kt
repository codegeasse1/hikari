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

    /**
     * The last thing that went wrong, in the user's own words ([TelegramError]
     * turns TDLib's `PHONE_NUMBER_INVALID` into a sentence). TDLib reports every
     * refusal as a RESULT, not as an update, so a send whose result is ignored
     * is a button that does nothing at all — which is what "Send code does
     * nothing" was.
     */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** True while a login step is with TDLib and has not been answered yet. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The number the code was asked for ("" until one is submitted). */
    private val _sentTo = MutableStateFlow("")
    val sentTo: StateFlow<String> = _sentTo.asStateFlow()

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
        val wanted = apiId to apiHash
        val changed = pending != wanted
        pending = wanted
        _problem.value = null
        val running = client
        if (running == null) {
            start(apiId, apiHash)
            return
        }
        // Credentials that CHANGE have to actually reach TDLib, and they are
        // handed over exactly once, in setTdlibParameters, at the start of a
        // session. A client already running on a pair that does not work keeps
        // failing with it forever — which is the other half of "it never gets
        // past Send code": correcting the api_id/api_hash in the card has to
        // rebuild the client on the new pair, not just remember it.
        if (changed && live != wanted) restart()
    }

    /**
     * Start over with the stored credentials: close the client and build a fresh
     * one. Used by the tab's "Start over" button (a wrong phone number, a stuck
     * login variant) and by [setCredentials] when the pair changes.
     */
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

    private fun fail(error: TdApi.Error) {
        _busy.value = false
        _problem.value = TelegramError.explain(error.message)
    }

    /**
     * An exception TDLib itself threw at us (the update/query handlers and the
     * default handler). Kept as a visible message rather than a silent swallow:
     * a client that is quietly throwing answers every request with nothing.
     */
    private fun note(t: Throwable) {
        _busy.value = false
        _problem.value = "Telegram library error: " + (t.message ?: t.javaClass.simpleName)
    }

    /**
     * Releases the "a step is in flight" flag after half a minute.
     *
     * TDLib answers every request, so this should never fire — but if one is
     * ever lost (a client torn down mid-request, a native call that does not
     * come back), the alternative is buttons that stay greyed out permanently
     * and a screen that looks frozen. The timeout is what guarantees the tab
     * always becomes usable again.
     */
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
        fileValues.clear()
        _chats.value = emptyList()
        _me.value = ""
        _sentTo.value = ""
        myId = 0
    }

    /**
     * The phone number as TDLib wants it: international format, with the `+`.
     *
     * TDLib answers `PHONE_NUMBER_INVALID` for a number typed without it — which
     * is exactly what a phone field whose keyboard lacks a `+` produces, and
     * what "Send code does nothing" was: the refusal came back as a result
     * nobody was reading. Spaces, dashes and brackets are the way people write
     * numbers down, so they are stripped rather than refused, and a leading `00`
     * (the other way to write a country code) becomes the `+` it means.
     */
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
        // The result handler is not decoration: TDLib reports a refusal
        // ("PHONE_NUMBER_INVALID", "PHONE_NUMBER_FLOOD", …) as the RESULT of
        // this call, and with the result ignored the button simply did nothing.
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
                fileValues.clear()
                _chats.value = emptyList()
                _me.value = ""
                _busy.value = false
                _auth.value = Auth.Closed
                // A close the APP asked for (a corrected api_id/api_hash, or
                // "Start over") is immediately followed by a fresh client,
                // because the user is standing there waiting for it. A close
                // TDLib decided on its own — an authorization variant this
                // build does not implement, say — is left alone: rebuilding on
                // every one of those would spin forever.
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
            // A wrong api_id/api_hash is answered HERE, as the result of this
            // call — and a client that is never told keeps asking TDLib for
            // parameters, so the tab sat on "Starting Telegram…" forever with no
            // reason given. The message is now shown in the card.
            Client.ResultHandler { result ->
                if (result is TdApi.Error) fail(result)
            },
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
