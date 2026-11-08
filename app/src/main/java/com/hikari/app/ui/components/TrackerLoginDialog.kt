package com.hikari.app.ui.components
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hikari.app.HikariApp
import com.hikari.app.data.TrackerAccount
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerFlow
import com.hikari.app.data.TrackerKind
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import com.hikari.app.tracker.TrackerApi
import com.hikari.app.tracker.TrackerRedirect
import com.hikari.app.ui.openUrl
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hikari.app.tv.tvTextFieldKeys

@Composable
fun TrackerLoginDialog(
    app: HikariApp,
    kind: TrackerKind,
    onSignedIn: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val redirect = remember { TrackerApi.redirectHint() }

    var client by remember { mutableStateOf(TrackerClient(kind)) }
    var loading by remember { mutableStateOf(true) }
    var idField by rememberSaveable { mutableStateOf("") }
    var secretField by rememberSaveable { mutableStateOf("") }
    var emailField by rememberSaveable { mutableStateOf("") }
    var passwordField by rememberSaveable { mutableStateOf("") }
    var codeField by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    /** Non-null once the in-app fallback page should be shown. */
    var webUrl by remember { mutableStateOf<String?>(null) }
    /** The last authorize URL produced, so it can be re-opened or fall back. */
    var lastAuthUrl by remember { mutableStateOf<String?>(null) }
    /** True once the login page has been opened in the user's browser. */
    var browserOpened by remember { mutableStateOf(false) }
    /** The state we send, echoed back by the redirect — and MAL's PKCE verifier. */
    var loginState by remember { mutableStateOf(TrackerApi.newState()) }
    var device by remember { mutableStateOf<TrackerApi.DeviceLogin?>(null) }
    /** The WebView currently on screen, so it can be destroyed with the dialog. */
    var web by remember { mutableStateOf<WebView?>(null) }
    /** True once a redirect has been turned into a sign-in (guards the double fire). */
    var handled by remember { mutableStateOf(false) }
    /** Whether the credential fields are behind us (the sign-in step is showing). */
    var credentialsDone by remember { mutableStateOf(false) }
    /** Whether the app ships its own registration for this service. */
    val hasBuiltIn = remember(kind) {
        com.hikari.app.data.TrackerBuiltIn.clientFor(kind) != null
    }

    val copiedMsg = I18n.t("Copied")
    val missingId = I18n.t("Paste the app id you created — it is the value labelled \"Client ID\".")
    val missingSecret = I18n.t("This service also shows a client secret — both values are needed.")
    val expiredCode = I18n.t("That code expired before it was confirmed — press Start again.")
    val waitingMsg = I18n.t("Waiting for you to confirm the code…")

    fun copy(text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Hikari", text))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    fun finish(account: TrackerAccount) {
        scope.launch {
            runCatching { app.store.setTrackerAccount(account) }
            runCatching { app.store.setTrackerClient(client) }
            // The sign-in is over, so nothing is waiting for a redirect any
            // more (see TrackerApi.Pending).
            runCatching { app.store.setTrackerPending(null) }
            onSignedIn(account.describe + " — connected")
        }
    }

    fun saveClient() {
        val id = idField.trim()
        error = null
        if (id.isBlank() && kind != TrackerKind.KITSU) {
            error = missingId
            return
        }
        if (kind.needsSecret && secretField.trim().isBlank()) {
            error = missingSecret
            return
        }
        val made = TrackerClient(kind, id, secretField.trim())
        client = made
        credentialsDone = true
        scope.launch {
            runCatching { app.store.setTrackerClient(made) }
            // An explicitly typed id sticks per device (it shadows the
            // shipped one until reset); re-typing the shipped id re-adopts it.
            val builtIn = com.hikari.app.data.TrackerBuiltIn.clientFor(kind)
            runCatching {
                app.store.setTrackerClientOverride(
                    kind,
                    builtIn != null && (id != builtIn.id || secretField.trim() != builtIn.secret),
                )
            }
        }
    }

    /** Starts whatever sign-in this service uses. */
    fun start() {
        error = null
        handled = false
        when (kind.flow) {
            TrackerFlow.PASSWORD -> status = null
            TrackerFlow.PIN, TrackerFlow.DEVICE -> {
                busy = true
                scope.launch {
                    TrackerApi.startDevice(client)
                        .onSuccess {
                            device = it
                            status = null
                        }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
            TrackerFlow.CODE, TrackerFlow.TOKEN -> {
                loginState = TrackerApi.newState()
                val url = TrackerApi.authorizeUrl(kind, client.id, loginState)
                busy = true
                scope.launch {
                    val check = TrackerApi.checkAuthorize(kind, client.id, loginState)
                    busy = false
                    if (check.isFailure) {
                        error = check.exceptionOrNull()?.message
                        return@launch
                    }
                    runCatching {
                        app.store.setTrackerPending(
                            TrackerApi.Pending(kind, client.id, loginState)
                        )
                    }
                    lastAuthUrl = url
                    webUrl = url
                    browserOpened = false
                }
            }
        }
    }

    /**
     * One redirect, handled once — whether it arrived in the WebView or was
     * pasted by the user because the WebView's hand-off produced nothing.
     */
    fun handleRedirect(raw: String, fromPaste: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!fromPaste && handled) return
        if (!fromPaste && !text.startsWith("hikari://")) return
        TrackerApi.errorFromRedirect(text)?.let {
            handled = true
            error = "${kind.label}: $it"
            return
        }
        when (kind.flow) {
            TrackerFlow.TOKEN -> {
                if (fromPaste && (text == client.secret || text == client.id)) {
                    handled = true
                    error = I18n.t(
                        "That is this app's client id/secret from the developer page — it is not a " +
                            "sign-in token. Press Sign in above and approve the app first."
                    )
                    return
                }
                val token = TrackerApi.tokenFromRedirect(text)
                    ?: text.takeIf { fromPaste && it.matches(Regex("[A-Za-z0-9._~-]{20,}")) }
                    ?: return
                handled = true
                busy = true
                scope.launch {
                    TrackerApi.signInWithToken(client, token)
                        .onSuccess { finish(it) }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
            TrackerFlow.CODE -> {
                val code = TrackerApi.codeFromRedirect(text)
                    ?: text.takeIf { fromPaste && !it.startsWith("http") && it.isNotBlank() }
                    ?: return
                handled = true
                busy = true
                scope.launch {
                    TrackerApi.exchangeCode(client, code, loginState)
                        .onSuccess { finish(it) }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
            else -> Unit
        }
    }

    DisposableEffect(Unit) {
        TrackerRedirect.dialogOpen = true
        onDispose {
            TrackerRedirect.dialogOpen = false
            // A link that arrived for a dialog the user then closed must not be
            // applied later, against a different `state`.
            TrackerRedirect.consume()
        }
    }
    val incomingRedirect by TrackerRedirect.incoming.collectAsState()
    LaunchedEffect(incomingRedirect) {
        val url = incomingRedirect ?: return@LaunchedEffect
        TrackerRedirect.consume()
        handleRedirect(url, fromPaste = true)
    }

    LaunchedEffect(kind) {
        val stored = runCatching { app.store.trackerClient(kind) }.getOrDefault(TrackerClient(kind))
        val builtIn = com.hikari.app.data.TrackerBuiltIn.clientFor(kind)
        val override = runCatching { app.store.trackerClientOverride(kind) }.getOrDefault(false)
        val effective = if (override && stored.ready) stored
        else builtIn ?: stored
        client = effective
        idField = effective.id
        secretField = effective.secret
        credentialsDone = effective.ready || kind.flow == TrackerFlow.PASSWORD
        loading = false
    }

    DisposableEffect(webUrl) {
        onDispose {
            runCatching {
                web?.stopLoading()
                web?.clearCache(true)
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                web?.destroy()
            }
            web = null
        }
    }

    LaunchedEffect(device) {
        val login = device ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + login.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(login.intervalSec.coerceAtLeast(2) * 1000L)
            val result = TrackerApi.pollDevice(client, login)
            val account = result.getOrNull()
            if (account != null) {
                finish(account)
                return@LaunchedEffect
            }
            result.exceptionOrNull()?.let {
                error = it.message
                device = null
                return@LaunchedEffect
            }
            status = waitingMsg
        }
        error = expiredCode
        device = null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        },
        title = { Text(kind.label) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Loading…"), style = MaterialTheme.typography.bodySmall)
                    }
                    return@Column
                }

                // ---- step 1: the user's own app registration -----------------
                if (!credentialsDone) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr(
                            if (hasBuiltIn) {
                                "${kind.label} is ready to sign in with Hikari's own app id — " +
                                    "you are only seeing these fields because this device was set " +
                                    "to a different one. Either go back to the built-in below, or " +
                                    "paste another app id:"
                            } else {
                                "${kind.label} needs an app registered by you before it will let " +
                                    "anyone log in. It is free and takes a minute:"
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. " + tr("Open the developer page and create an app.") + "\n" +
                            "2. " + tr("If it asks for a redirect URI, paste exactly:") + " " + redirect + "\n" +
                            "3. " + tr("Copy the client id it gives you into the field below."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            // Browser first; our own web view when the
                            // device has no browser to open — a developer
                            // page is just a page, and the client id is
                            // copied out of it by hand either way.
                            if (!openUrl(context, kind.registerUrl)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(context, WebViewActivity::class.java).apply {
                                            putExtra("url", kind.registerUrl)
                                            putExtra("title", kind.label)
                                        }
                                    )
                                }
                            }
                        }) {
                            Text(tr("Open developer page"))
                        }
                        TextButton(onClick = { copy(redirect) }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Copy redirect URI"))
                        }
                    }
                    if (hasBuiltIn) {
                        // The way back out of an override: forget the typed id
                        // and sign in with the shipped one.
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { app.store.setTrackerClientOverride(kind, false) }
                                com.hikari.app.data.TrackerBuiltIn.clientFor(kind)?.let {
                                    client = it
                                    idField = it.id
                                    secretField = it.secret
                                }
                                error = null
                                credentialsDone = true
                            }
                        }) { Text(tr("Use Hikari's built-in id instead")) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = idField,
                        onValueChange = { idField = it; error = null },
                        label = { Text(tr("Client ID")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(idField),
                    )
                    if (kind.needsSecret) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = secretField,
                            onValueChange = { secretField = it; error = null },
                            label = { Text(tr("Client secret")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(secretField),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!LocalHideHelp.current) {
                    Text(
                        tr(
                            "Both values stay on this device and are only ever sent to " +
                                kind.label + "."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    return@Column
                }

                // ---- step 2: the sign-in itself ------------------------------
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("App id: ") + client.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (kind.flow) {
                    TrackerFlow.PASSWORD -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = emailField,
                            onValueChange = { emailField = it; error = null },
                            label = { Text(tr("Kitsu e-mail or username")) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(emailField),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordField,
                            onValueChange = { passwordField = it; error = null },
                            label = { Text(tr("Kitsu password")) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(passwordField),
                        )
                        Spacer(Modifier.height(4.dp))
                        if (!LocalHideHelp.current) {
                        Text(
                            tr(
                                "The password goes straight to Kitsu over HTTPS and is never " +
                                    "stored — only the token Kitsu returns is."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                    }

                    TrackerFlow.PIN, TrackerFlow.DEVICE -> {
                        val login = device
                        Spacer(Modifier.height(10.dp))
                        if (login == null) {
                            Text(
                                tr("Press Sign in to get a code, then type it on the service's own page."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                tr("Type this code on the service's page:"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    login.userCode,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(10.dp))
                                TextButton(onClick = { copy(login.userCode) }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                Logs.log("Trackers", "${kind.label} confirm tap url=" + login.verifyUrl.take(160))
                                if (!openUrl(context, login.verifyUrl)) {
                                    val inApp = runCatching {
                                        context.startActivity(
                                            Intent(context, WebViewActivity::class.java).apply {
                                                putExtra("url", login.verifyUrl)
                                                putExtra("title", kind.label)
                                            }
                                        )
                                    }.isSuccess
                                    if (!inApp) {
                                        Logs.log("Trackers", "${kind.label} confirm open failed everywhere — address copied instead")
                                        copy(login.verifyUrl)
                                        error = I18n.t(
                                            "Nothing on this device would open the page, so the confirmation address " +
                                                "was copied — paste it into your browser, then type " +
                                                "the code there."
                                        )
                                    }
                                }
                            }) {
                                Text(tr("Open the confirmation page"))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    login.verifyUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { copy(login.verifyUrl) }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    TrackerFlow.CODE, TrackerFlow.TOKEN -> {
                        val url = webUrl
                        Spacer(Modifier.height(8.dp))
                        if (url == null) {
                            if (browserOpened && lastAuthUrl != null) {
                                Text(
                                    tr(
                                        "The login page is open in your browser — approve the app " +
                                            "there, then paste the " +
                                            (if (kind.flow == TrackerFlow.TOKEN) "token" else "code") +
                                            " below."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(6.dp))
                                val auth = lastAuthUrl.orEmpty()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        auth,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { copy(auth) }) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        if (openUrl(context, auth)) {
                                            webUrl = null
                                            browserOpened = true
                                        } else {
                                            webUrl = auth
                                        }
                                    }) {
                                        Text(tr("Reopen the page"))
                                    }
                                    TextButton(onClick = { webUrl = auth }) {
                                        Text(tr("Use the in-app page instead"))
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = codeField,
                                    onValueChange = { codeField = it; error = null },
                                    label = {
                                        Text(
                                            if (kind.flow == TrackerFlow.TOKEN) tr("Paste the token")
                                            else tr("Paste the code")
                                        )
                                    },
                                    singleLine = true,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(codeField),
                                )
                                TextButton(
                                    enabled = !busy && codeField.isNotBlank(),
                                    onClick = { handleRedirect(codeField, fromPaste = true) },
                                ) { Text(tr("Use this")) }
                            } else {
                                Text(
                                    tr("Press Sign in to open the service's own login page."),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(430.dp),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = Http.WEBVIEW_UA
                                        // ---- the page must FIT, and whatever
                                        // does not fit must still be reachable.
                                        //
                                        // These four lines are the whole of the
                                        // "the login page opens but I cannot
                                        // scroll it sideways, so half of it is
                                        // off the edge and I can't use the page"
                                        // report. A WebView by default lays a
                                        // desktop-width page out at its natural
                                        // width INSIDE a phone-width box, and
                                        // then does not let you pan horizontally
                                        // — so the right-hand half of the page
                                        // (and any control out there, including
                                        // AniList's Turnstile checkbox and its
                                        // Sign in button) is simply unreachable.
                                        //
                                        // `useWideViewPort` + `loadWithOverviewMode`
                                        // lay the page out at its own width and
                                        // then scale it down to fit the box, so
                                        // the WHOLE page is visible at once; and
                                        // pinch-zoom (which needs both
                                        // `setSupportZoom` and
                                        // `builtInZoomControls`) lets the user
                                        // zoom into a corner and pan around it,
                                        // with the +/- buttons hidden because
                                        // they only ever sat on top of the page.
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.setSupportMultipleWindows(false)
                                        isVerticalScrollBarEnabled = true
                                        isHorizontalScrollBarEnabled = true
                                        isScrollbarFadingEnabled = false
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest,
                                            ): Boolean {
                                                val target = request.url?.toString().orEmpty()
                                                if (target.startsWith("hikari://")) {
                                                    handleRedirect(target)
                                                    return true
                                                }
                                                return false
                                            }

                                            @Suppress("DEPRECATION")
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                url: String,
                                            ): Boolean {
                                                if (url.startsWith("hikari://")) {
                                                    handleRedirect(url)
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onPageStarted(
                                                view: WebView?,
                                                url: String?,
                                                favicon: android.graphics.Bitmap?,
                                            ) {
                                                val target = url.orEmpty()
                                                if (target.startsWith("hikari://")) {
                                                    handleRedirect(target)
                                                    view?.stopLoading()
                                                }
                                            }

                                            override fun onReceivedHttpAuthRequest(
                                                view: WebView?,
                                                handler: android.webkit.HttpAuthHandler?,
                                                host: String?,
                                                realm: String?,
                                            ) {
                                                handler?.cancel()
                                                view?.stopLoading()
                                                error = I18n.t(
                                                    "That page asked for a username and password " +
                                                        "instead of showing the service's login " +
                                                        "page — which means the app id above was " +
                                                        "refused. Check it against the developer " +
                                                        "page (it is the Client ID, not the " +
                                                        "Client Secret), then press Sign in again."
                                                )
                                                webUrl = null
                                            }
                                        }
                                        loadUrl(url)
                                    }.also { web = it }
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                            if (!LocalHideHelp.current) {
                            Text(
                                tr("Pinch to zoom the page, then drag to pan — the whole page is reachable."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            }
                            Spacer(Modifier.height(4.dp))
                            if (!LocalHideHelp.current) {
                            Text(
                                (if (kind.flow == TrackerFlow.TOKEN) {
                                    tr(
                                        "Signed in but nothing happened? The token comes back inside the " +
                                            "redirect link, and this app does not always get to see it — so " +
                                            "open the page in your browser, approve the app, then copy the " +
                                            "hikari:// link out of the address bar and paste it below."
                                    )
                                } else {
                                    tr(
                                        "Signed in but nothing happened? Copy the code out of the address " +
                                            "bar of the page you land on and paste it below."
                                    )
                                }) +
                                    if (kind == TrackerKind.ANILIST) {
                                        " " + tr(
                                            "If your browser will not let you copy the address either, set " +
                                                "this app's redirect URL to"
                                        ) + " https://anilist.co/api/v2/oauth/pin " + tr(
                                            "in AniList's developer page: AniList will then show the code " +
                                                "as text you can copy. (That redirect URL works for the " +
                                                "browser route only — the WebView above needs hikari://oauth.)"
                                        )
                                    } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { copy(url) }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { openUrl(context, url) }) {
                                    Text(tr("Open the login page in your browser"))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = codeField,
                                onValueChange = { codeField = it; error = null },
                                label = {
                                    Text(
                                        if (kind.flow == TrackerFlow.TOKEN) tr("Paste the token")
                                        else tr("Paste the code")
                                    )
                                },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(codeField),
                            )
                            TextButton(
                                enabled = !busy && codeField.isNotBlank(),
                                onClick = { handleRedirect(codeField, fromPaste = true) },
                            ) { Text(tr("Use this")) }
                        }
                    }
                }

                // ---- the state of the attempt --------------------------------
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Talking to the service…"), style = MaterialTheme.typography.labelSmall)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                // Password-grant services (Kitsu) have no app id at all, so
                // there is nothing different to use.
                if (kind.flow != TrackerFlow.PASSWORD) {
                TextButton(onClick = {
                    // A wrong app id, or one belonging to another service: back to
                    // the fields, keeping what was typed.
                    credentialsDone = false
                    webUrl = null
                    device = null
                    error = null
                    status = null
                }) { Text(tr("Use a different app id")) }
                }
            }
        },
        confirmButton = {
            when {
                loading -> TextButton(onClick = onDismiss) { Text(tr("Close")) }
                !credentialsDone -> TextButton(onClick = { saveClient() }) { Text(tr("Continue")) }
                kind.flow == TrackerFlow.PASSWORD -> TextButton(
                    enabled = !busy && emailField.isNotBlank() && passwordField.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            TrackerApi.loginPassword(client, emailField.trim(), passwordField)
                                .onSuccess { finish(it) }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { if (busy) Text(tr("Signing in…")) else Text(tr("Sign in")) }
                else -> TextButton(enabled = !busy, onClick = { start() }) { Text(tr("Sign in")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}
