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
        /**
         * The post's own TEXT — the caption the user typed under the video.
         *
         * This is what makes a caption work as a tag: people post a batch of
         * videos captioned "abc", then search `abc` to get the batch back. It is
         * kept separate from [fileName] because the two are searched differently
         * (Telegram can search its own message text across the whole chat; it
         * cannot search a file name) — see [SearchIn].
         */
        val caption: String = "",
        /** The video's original file name, as the sender wrote it. */
        val fileName: String = "",
        /**
         * TDLib file id of the video's real THUMBNAIL (`Video.thumbnail.file`),
         * or 0 when the post has none. Fetched on demand so a list of videos
         * shows pictures instead of black rectangles — see [thumbPath].
         */
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

    /**
     * Files fetched on demand with [loadFile], for the ones TDLib has not sent
     * an `UpdateFile` about yet. Kept separate from [fileValues] so a live
     * update always outranks a snapshot, and cleared whenever [fileValues] is.
     */
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

    /**
     * Throw the session away and show the credentials page again.
     *
     * This is the "I typed the wrong api_id/api_hash" path: [restart] rebuilds the
     * client on the SAME pair (which is what a wrong phone number or a stuck login
     * variant needs), while this one drops the pair entirely so the card asks for
     * it again. Without it a user who noticed a typo in the keys had no way back
     * to the fields — the tab kept them in whatever state the bad pair produced.
     *
     * The stored pair is cleared by the caller (see the Telegram tab), so a later
     * [init] cannot quietly restart on the keys the user is trying to replace.
     */
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

    /**
     * The hex characters of an api_hash, lowercased.
     *
     * Telegram compares the api_hash as a STRING in its handshake, so a hash
     * pasted in UPPERCASE — or one that picked up a space, a dash or a newline
     * from the page it was copied off — is refused as "that api_id and api_hash
     * are not a valid pair", which reads as "your credentials are wrong" and
     * sent one user hunting for a new pair that was never the problem. Keeping
     * only the hex and lowercasing it is what makes the pair the user actually
     * has work.
     */
    fun apiHashChars(raw: String): String =
        raw.trim().filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.lowercase()

    /** The api_hash as it should be sent, or null when it is not 32 hex chars. */
    fun apiHashOf(raw: String): String? = apiHashChars(raw).takeIf { it.length == 32 }

    /**
     * What is wrong with the pair as typed, or null when both look usable.
     *
     * Said BEFORE anything is stored or sent, so the answer arrives while the
     * user is looking at the field they got wrong.
     */
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
        fetchedFiles.clear()
        _chats.value = emptyList()
        _me.value = ""
        _sentTo.value = ""
        myId = 0
        savedChatId = 0
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
        ensureSavedChat()
        publishChats()
    }

    /**
     * Makes sure the chat with ourselves — Saved Messages — exists as a chat
     * object, and remembers its id.
     *
     * TDLib only puts Saved Messages in the account's chat list once the account
     * has actually saved something, so a user who opens Hikari before ever
     * forwarding a message to themselves had no Saved Messages row at all: not
     * in the list, and therefore not findable by the search above it either
     * ("No chat matches that name" for the one chat everybody has). TDLib's own
     * documentation for a saved-messages link says exactly what this does —
     * `createPrivateChat` with your own user id. Our own user is always in the
     * local cache, so the call comes back without waiting on Telegram.
     */
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
                fetchedFiles.clear()
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
        val saved = savedChatId
        val list = chatValues.values.mapNotNull { chat ->
            val order = mainOrder[chat.id] ?: 0L
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
            // order 0 means "not in the main list" — archived chats, and chats
            // TDLib has not placed yet. Saved Messages is the one chat that is
            // always shown, even when nothing has ordered it yet.
            if (order == 0L && kind != Chat.Kind.SAVED) return@mapNotNull null
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

    /**
     * Search ONE chat's videos. See [ChatVideo.caption] for why this is split.
     *
     * [SearchIn.CHAT] is answered by Telegram itself
     * ([TdApi.SearchChatMessages], filtered to video messages): the caption is
     * message text, so TDLib can search the chat's WHOLE history for it and come
     * back with the next cursor — this is the "I captioned that batch abc, show
     * me the batch" case, and it is complete.
     *
     * [SearchIn.VIDEO] and [SearchIn.BOTH] cannot be: a file name is not message
     * text, so there is nothing for Telegram to index and the chat's history has
     * to be walked ([TdApi.GetChatHistory], a local database read — no network),
     * [SCAN_MESSAGES_PER_CALL] posts at a time. The returned [ChatVideoPage]
     * reports how far it got so the UI can say so and offer to go further,
     * rather than pretending a partial walk was exhaustive.
     */
    suspend fun searchChatVideos(
        chatId: Long,
        text: String,
        inText: SearchIn,
        cursor: Long = 0,
        limit: Int = 60,
    ): ChatVideoPage {
        // NOT named `query`: this object's `query(...)` is the function that
        // actually talks to TDLib, and a parameter of the same name would
        // shadow it inside this body — TDLib would then be "sent" a String.
        val q = text.trim()
        if (q.isEmpty()) return ChatVideoPage(emptyList(), 0, 0, true)

        if (inText == SearchIn.CHAT) {
            val found = query(
                TdApi.SearchChatMessages(
                    chatId,
                    /* topicId = */ null,
                    q,
                    /* senderId = */ null,
                    cursor,
                    /* offset = */ 0,
                    limit.coerceIn(1, 100),
                    TdApi.SearchMessagesFilterVideo(),
                ),
            ) as? TdApi.FoundChatMessages
                ?: return ChatVideoPage(emptyList(), 0, 0, true)
            val videos = found.messages.mapNotNull { videoOf(chatId, it) }
            val next = if (videos.isEmpty()) 0L else found.nextFromMessageId
            return ChatVideoPage(videos, next, found.messages.size, next == 0L)
        }

        val out = mutableListOf<ChatVideo>()
        val seen = HashSet<Long>()
        var from = cursor
        var scanned = 0
        var more = true
        while (more && scanned < SCAN_MESSAGES_PER_CALL && out.size < limit) {
            val page = query(TdApi.GetChatHistory(chatId, from, 0, 100, false)) as? TdApi.Messages
                ?: return ChatVideoPage(out, 0, scanned, true)
            if (page.messages.isEmpty()) {
                more = false
                break
            }
            for (m in page.messages) {
                scanned++
                // TDLib answers `GetChatHistory` from `fromMessageId` INCLUSIVE,
                // so the post the last page ended on arrives again — deduped here
                // rather than by the caller.
                if (!seen.add(m.id)) continue
                val v = videoOf(chatId, m) ?: continue
                val hit = v.fileName.contains(q, ignoreCase = true) ||
                    (inText == SearchIn.BOTH && v.caption.contains(q, ignoreCase = true))
                if (hit) out += v
            }
            from = page.messages.last().id
            more = page.messages.size >= 100
        }
        val exhausted = !more
        return ChatVideoPage(
            videos = out.take(limit),
            nextCursor = if (exhausted) 0L else from,
            scanned = scanned,
            exhausted = exhausted,
        )
    }

    /**
     * The local path of a small file — a video's thumbnail — asking TDLib to
     * fetch it if it has not, and waiting up to [waitMs] for it.
     *
     * Null when there is nothing (or nothing yet): the caller falls back to the
     * post's inline preimage and then to a placeholder. Thumbnails are a few
     * kilobytes, so a `DownloadFile` for the whole file (limit 0) is the right
     * ask — it arrives in well under a second on any connection.
     */
    suspend fun thumbPath(fileId: Int, waitMs: Long = 8_000): String? {
        if (fileId == 0) return null
        // One query to learn whether TDLib knows the file at all (it may not have
        // sent an UpdateFile for it yet — the same gap that stopped videos
        // playing), then only cheap map reads while polling.
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
        // `fileValues` is the live update stream and always wins. `fetchedFiles`
        // is the fallback for a file TDLib has never sent an `UpdateFile` about —
        // and there is one of those for every video the user has not played yet,
        // which is precisely the case that failed (see [loadFile]).
        val f = fileValues[fileId] ?: fetchedFiles[fileId] ?: return null
        return FileState(
            path = f.local?.path.orEmpty(),
            size = maxOf(f.size, f.expectedSize),
            downloaded = maxOf(f.local?.downloadedPrefixSize ?: 0L, f.local?.downloadedSize ?: 0L),
            complete = f.local?.isDownloadingCompleted == true,
            active = f.local?.isDownloadingActive == true,
        )
    }

    /**
     * Makes sure TDLib has been ASKED for this file, and returns it.
     *
     * This is the fix for "Playback failed — ExoPlaybackException
     * [ERROR_CODE_IO_UNSPECIFIED] Telegram is not available URL:
     * hikari-td://file?id=2033". Nothing was wrong with the id and nothing was
     * wrong with the network: `fileState` only knew the files some
     * `UpdateFile` had already mentioned, and TDLib only sends an update once a
     * download exists. A video the user has never watched has never been
     * downloaded, so TDLib had said nothing about it — and the `Client.execute
     * (GetFile)` the old code fell back to can never answer either, because
     * `execute` only serves a small set of queries that need no database (getFile
     * is not one of them), so the fallback silently returned null every time.
     * Every Telegram video therefore failed to play on the first tap.
     *
     * Asking properly is asynchronous — a real round trip to TDLib — so this is
     * `suspend`, and the answer is cached in [fetchedFiles] for the many
     * `fileState` reads that follow (the player's read loop calls it constantly,
     * and a fresh query per call would be a round trip per 2 MiB chunk).
     */
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

    /** Ask TDLib to fetch [limit] bytes of [fileId] starting at [offset]. */
    fun request(fileId: Int, offset: Long, limit: Long) {
        client?.send(TdApi.DownloadFile(fileId, 32, offset, limit, false), null, null)
    }

    /**
     * How much of a file one TDLib request covers.
     *
     * Playback asks for the window a read lands in (the `prime` step of
     * `TdFileDataSource`), and keeps it: a request that covered less would
     * have to be renewed every few reads, and every renewal CANCELS the range
     * TDLib is already downloading, so the download would restart a few
     * kilobytes further on and never run ahead of the player. Small enough that
     * a seek into the middle of a film starts there instead of pulling the whole
     * file, large enough that 1080p plays through it smoothly.
     */
    const val CHUNK_BYTES: Long = 2L * 1024 * 1024
}
