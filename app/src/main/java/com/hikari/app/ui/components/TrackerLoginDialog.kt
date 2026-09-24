package com.hikari.app.ui.components
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hikari.app.HikariApp
import com.hikari.app.data.TrackerAccount
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerFlow
import com.hikari.app.data.TrackerKind
import com.hikari.app.net.Http
import com.hikari.app.tracker.TrackerApi
import com.hikari.app.ui.openUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Signing in to a tracker (Settings → Trackers).
 *
 * Every one of these services needs an app of the user's own registered with it
 * before anyone may log in — a property of the services, not of Hikari — so the
 * first step is "paste the id you just created", with the exact page to create
 * it and the exact redirect URI to paste into it. After that the sign-in is
 * whatever that service uses: a login page in a WebView whose redirect we
 * intercept ([TrackerFlow.CODE]/[TrackerFlow.TOKEN]), the account's own
 * credentials ([TrackerFlow.PASSWORD]), or a short code the user confirms on the
 * service's own site ([TrackerFlow.PIN]/[TrackerFlow.DEVICE]).
 *
 * Every path ends in a sentence — the sign-in either works and names the account
 * it signed in as, or it says what the service refused. There is no state where
 * this dialog quietly does nothing.
 */
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
    /** Non-null once the login page should be shown. */
    var webUrl by remember { mutableStateOf<String?>(null) }
    /** The state we send, echoed back by the redirect — and MAL's PKCE verifier. */
    var loginState by remember { mutableStateOf(TrackerApi.newState()) }
    var device by remember { mutableStateOf<TrackerApi.DeviceLogin?>(null) }
    /** The WebView currently on screen, so it can be destroyed with the dialog. */
    var web by remember { mutableStateOf<WebView?>(null) }
    /** True once a redirect has been turned into a sign-in (guards the double fire). */
    var handled by remember { mutableStateOf(false) }
    /** Whether the credential fields are behind us (the sign-in step is showing). */
    var credentialsDone by remember { mutableStateOf(false) }

    // Strings used from NON-composable places (callbacks, coroutines): `tr` is
    // itself composable and cannot be called from there, so those go through
    // I18n.t — the same rule the rest of the app follows.
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
            onSignedIn(account.describe + " — connected")
        }
    }

    fun saveClient() {
        val id = idField.trim()
        error = null
        if (id.isBlank()) {
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
        scope.launch { runCatching { app.store.setTrackerClient(made) } }
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
                webUrl = TrackerApi.authorizeUrl(kind, client.id, loginState)
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

    // The app credentials the user pasted last time decide which step opens:
    // the credential fields, or the sign-in itself.
    LaunchedEffect(kind) {
        val stored = runCatching { app.store.trackerClient(kind) }.getOrDefault(TrackerClient(kind))
        client = stored
        idField = stored.id
        secretField = stored.secret
        credentialsDone = stored.ready
        loading = false
    }

    // The login page's WebView is destroyed as soon as it leaves the screen: a
    // WebView left alive keeps a cookie jar, a session and tens of megabytes with
    // it, and the next sign-in (a different account) would silently reuse the
    // first one's session.
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

    // A code sign-in is a loop: ask the service until the user confirms the code,
    // it expires, or the dialog goes away. Deliberately slow (the service's own
    // interval) — polling faster is how an app gets rate-limited out of its own
    // sign-in.
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
                            "${kind.label} needs an app registered by you before it will let " +
                                "anyone log in. It is free and takes a minute:"
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
                        TextButton(onClick = { openUrl(context, kind.registerUrl) }) {
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = idField,
                        onValueChange = { idField = it; error = null },
                        label = { Text(tr("Client ID")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind.needsSecret) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = secretField,
                            onValueChange = { secretField = it; error = null },
                            label = { Text(tr("Client secret")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(
                            "Both values stay on this device and are only ever sent to " +
                                kind.label + "."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordField,
                            onValueChange = { passwordField = it; error = null },
                            label = { Text(tr("Kitsu password")) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tr(
                                "The password goes straight to Kitsu over HTTPS and is never " +
                                    "stored — only the token Kitsu returns is."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            TextButton(onClick = { openUrl(context, login.verifyUrl) }) {
                                Text(tr("Open the confirmation page"))
                            }
                        }
                    }

                    TrackerFlow.CODE, TrackerFlow.TOKEN -> {
                        val url = webUrl
                        Spacer(Modifier.height(8.dp))
                        if (url == null) {
                            Text(
                                tr("Press Sign in to open the service's own login page."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(360.dp),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = Http.WEBVIEW_UA
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

                                            override fun onPageStarted(
                                                view: WebView?,
                                                url: String?,
                                                favicon: android.graphics.Bitmap?,
                                            ) {
                                                // A custom scheme the WebView cannot load
                                                // is where the flow ENDS, and on some
                                                // builds the hand-off never reaches
                                                // shouldOverrideUrlLoading — so it is
                                                // caught here as well.
                                                val target = url.orEmpty()
                                                if (target.startsWith("hikari://")) {
                                                    handleRedirect(target)
                                                    view?.stopLoading()
                                                }
                                            }
                                        }
                                        loadUrl(url)
                                    }.also { web = it }
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("Logged in there but nothing happened? Paste the code from the address bar below."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Some services (AniList is the reliable one) put a
                            // Cloudflare "verify you are human" step in front of
                            // their login page, and a WebView is exactly what
                            // that check is suspicious of — it can sit there
                            // failing its own verification forever. A real
                            // browser passes it, and the redirect it ends on is
                            // then in the address bar to copy into the field
                            // below, which is what the line above tells the user
                            // to do.
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
                                modifier = Modifier.fillMaxWidth(),
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
