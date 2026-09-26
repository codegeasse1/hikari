package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.i18n.tr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.hikari.app.HikariApp
import com.hikari.app.data.BackupManager
import com.hikari.app.data.Logs
import com.hikari.app.i18n.I18n
import com.hikari.app.pair.PairClient
import com.hikari.app.pair.PairHost
import com.hikari.app.pair.PairProtocol
import com.hikari.app.pair.PairTarget
import com.hikari.app.pair.QrCode
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.hikari.app.tv.tvTextFieldKeys

/**
 * Settings → Backup & Restore → Pair & sync: moving a Hikari setup from one
 * device to another over the Wi-Fi, with no file, no account and no cable.
 *
 * Why it exists: the backup file works, but getting it from the phone that is
 * set up to a brand-new television is a chain of chores — back up, find the file,
 * move it to a USB stick or a chat, get it onto the TV, find it in a picker.
 * Two devices on the same Wi-Fi can do it directly: this screen is the sending
 * end (show a QR code and a six-character code) and the receiving end (scan the
 * QR, or type the code), and the payload is the exact same backup
 * [BackupManager] writes to a file — so the transfer needs no format of its own
 * and cannot get half of a restore right that the file path gets wrong.
 *
 * The screen is BOTH ends on purpose. A user setting up a new device opens this
 * screen on both of them, and which card they use is decided by which device
 * they are holding — the alternative (a "send" screen and a "receive" screen,
 * chosen from a menu) means guessing a direction before the app has said what
 * either end does.
 *
 * One caution the UI states plainly: a receive REPLACES this device's setup with
 * the other device's. That is the point of it, and it is also the only way to
 * lose data here, which is why it is confirmed in a dialog before anything is
 * fetched.
 */
@Composable
fun PairScreen(app: HikariApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ---- The sending end ---------------------------------------------------
    val host = remember { PairHost(app) }
    var session by remember { mutableStateOf<PairHost.Session?>(null) }
    var hosting by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf("") }
    var servedCount by remember { mutableStateOf(0) }
    var lastGuest by remember { mutableStateOf("") }
    // Leaving the screen stops the server. This is the whole security story of
    // the feature: there is nothing listening on the LAN once the user walks
    // away from this page, so a code that was photographed over a shoulder is
    // worthless a minute later.
    DisposableEffect(Unit) {
        onDispose { host.stop() }
    }
    // The one thing a sender can be told: that the other device really connected.
    // A watchdog rather than a callback, because the fetch happens on the
    // NanoHTTPD worker thread and a screen that never learns about it is a screen
    // that says "waiting…" forever.
    LaunchedEffect(hosting) {
        while (hosting) {
            servedCount = host.served
            lastGuest = host.lastGuest
            delay(600)
        }
    }
    // The QR encodes the address it was built with, so it is rebuilt when (and
    // only when) a session exists. Cheap enough to do inline: one bitmap per
    // session, not per frame.
    val payload = session?.takeIf { it.reachable }?.let { s ->
        remember(s) {
            PairProtocol.uri(s.host, s.port, s.code, host.deviceName(), host.appVersion())
        }
    }
    val qr = payload?.let { remember(it) { QrCode.encode(it) } }

    // ---- The receiving end -------------------------------------------------
    var codeInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }
    var showAddress by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PairTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var guestStatus by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<BackupManager.Report?>(null) }
    var scannerOpen by remember { mutableStateOf(false) }

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    fun reset() {
        busy = false
        guestStatus = ""
        report = null
    }

    /** The whole receiving flow, in one place: address (given or discovered),
     *  fetch, restore. Every step says so in [guestStatus] — a pairing that sits
     *  on one word for ten seconds is indistinguishable from one that hung.
     *
     * Every sentence here goes through [I18n.t] rather than `tr`: this runs
     * inside a coroutine, and `tr` is a composable (it re-renders when a
     * translation lands), so it cannot be called from one.
     */
    fun receive(target: PairTarget) {
        busy = true
        report = null
        scope.launch {
            val resolved = if (target.host.isBlank()) {
                guestStatus = I18n.t("Looking for the other device…")
                val found = withContext(Dispatchers.IO) {
                    PairClient.discover(target.code)
                }
                if (found == null) {
                    busy = false
                    guestStatus = I18n.t(
                        "No other device answered. Check that both devices are on the same " +
                            "Wi-Fi, that its pairing screen is still open, and try again."
                    )
                    return@launch
                }
                found
            } else {
                target
            }
            guestStatus = I18n.t("Found %s — downloading…")
                .replace("%s", resolved.deviceName.ifBlank { resolved.label })
            val bytes = runCatching { PairClient.download(resolved) }.getOrElse { e ->
                busy = false
                guestStatus = e.message ?: I18n.t("The transfer failed")
                return@launch
            }
            guestStatus = I18n.t("Downloaded %s — restoring…")
                .replace("%s", "${bytes.size / 1024} KB")
            val result = runCatching {
                withContext(Dispatchers.IO) { BackupManager.restore(app, bytes) }
            }.getOrElse { e ->
                busy = false
                guestStatus = I18n.t("The restore failed")
                report = BackupManager.Report(false, I18n.t("The restore failed"), e.message.orEmpty())
                return@launch
            }
            busy = false
            report = result
            guestStatus = result.message
        }
    }

    if (scannerOpen) {
        QrScannerPage(
            onScanned = { text ->
                scannerOpen = false
                val target = PairProtocol.parse(text)
                if (target == null) {
                    guestStatus = I18n.t("That QR code was not a Hikari pairing code")
                } else {
                    addressInput = target.label
                    codeInput = target.code
                    confirming = target
                }
            },
            onClose = { scannerOpen = false },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("Pair & sync"),
                subtitle = tr("Move your setup to another device over Wi-Fi"),
                icon = Icons.Filled.QrCode2,
                onBack = onBack,
            )
        }
        item {
            if (!LocalHideHelp.current) {
            Text(
                tr(
                    "Both devices have to be on the same Wi-Fi. Nothing is uploaded " +
                        "anywhere — your setup goes straight from one device to the other."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            }
        }

        // ---- Copy from this device ----------------------------------------
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("Copy my setup to another device"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (hosting) {
                            TextButton(onClick = {
                                host.stop()
                                hosting = false
                                session = null
                            }) { Text(tr("Stop")) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!LocalHideHelp.current) {
                    Text(
                        tr(
                            "Shows a code for the other device. Open this same page there and " +
                                "choose \"Set up this device from another device\"."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    if (!hosting) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                hosting = true
                                hostError = ""
                                session = null
                                scope.launch {
                                    val started = runCatching { host.start() }
                                    started.getOrNull()?.let { session = it }
                                    started.exceptionOrNull()?.let {
                                        hosting = false
                                        hostError = I18n.t("Couldn't start sharing your setup.")
                                        Logs.logError("Pair", "host start failed", it)
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Show the code"))
                        }
                    } else if (session == null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tr("Preparing your setup…"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        val s = session!!
                        Spacer(Modifier.height(12.dp))
                        if (qr != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    qr.asImageBitmap(),
                                    contentDescription = tr("Pairing QR code"),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(168.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // The code, big enough to read off a television screen
                        // and type on a phone across the room. This is the path
                        // that has to work: a TV has no camera to scan with.
                        Text(
                            s.code.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.reachable) {
                                    tr("Address: %s").replace("%s", s.label)
                                } else {
                                    tr("This device is not on a Wi-Fi network")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(s.code))
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = tr("Copy the code"),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (servedCount == 0) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                if (servedCount == 0) {
                                    tr("Waiting for the other device…")
                                } else {
                                    tr("Sent your setup to %s").replace(
                                        "%s", lastGuest.ifBlank { tr("the other device") }
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (servedCount == 0) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    if (hostError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            hostError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }

        // ---- Set up from another device -----------------------------------
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("Set up this device from another device"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!LocalHideHelp.current) {
                    Text(
                        tr(
                            "On the other device choose \"Copy my setup\", then scan its QR " +
                                "code or type its code here. Your settings, sources and history " +
                                "on THIS device will be replaced."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = {
                                codeInput = PairProtocol.normalizeCode(it)
                            },
                            singleLine = true,
                            enabled = !busy,
                            label = { Text(tr("Code from the other device")) },
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !busy && PairProtocol.looksComplete(codeInput),
                            onClick = {
                                val typed = addressInput.trim()
                                val target = if (typed.isNotBlank()) {
                                    PairTarget(
                                        host = typed.substringBefore(':'),
                                        port = typed.substringAfter(':', "")
                                            .toIntOrNull() ?: PairProtocol.DEFAULT_PORT,
                                        code = codeInput,
                                    )
                                } else {
                                    PairTarget("", 0, codeInput)
                                }
                                reset()
                                confirming = target
                            },
                        ) { Text(tr("Connect")) }
                    }
                    if (hasCamera) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = !busy,
                            onClick = { scannerOpen = true },
                        ) {
                            Icon(
                                Icons.Filled.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Scan the QR code"))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showAddress = !showAddress }) {
                        Text(
                            if (showAddress) tr("Hide the address")
                            else tr("Can't find it? Type the address")
                        )
                    }
                    if (showAddress) {
                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            singleLine = true,
                            enabled = !busy,
                            label = { Text(tr("Address (192.168.1.5 or 192.168.1.5:8787)")) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(addressInput),
                        )
                    }
                    if (busy || guestStatus.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                guestStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    report?.let { r ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (r.detail.isBlank()) r.message else r.message + " · " + r.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        if (r.ok) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr("Your app lock and this device's layout were left as they are."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr("Reopen Hikari if a screen still looks like it did before."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // The confirm step. Receiving is the only destructive direction here, so it
    // gets the dialog; sending is harmless (it only ever hands out a copy).
    confirming?.let { target ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(tr("Replace this device's setup?")) },
            text = {
                Text(
                    tr(
                        "Your settings, installed extensions, sources and history on this " +
                            "device will be replaced with the other device's. Your app lock and " +
                            "this device's layout stay as they are. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    receive(target)
                }) { Text(tr("Replace and set up")) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * The camera sheet that reads a pairing QR.
 *
 * A full-screen page rather than a box in the settings list, because finding a
 * QR code with a camera is a pointing task: it wants the whole screen, a bright
 * viewfinder and no scrollable content under the thumb that is holding the
 * device. Decoding runs on its own worker thread (the analyzer's executor), so
 * a frame that takes a moment to read cannot stutter the preview — and the
 * moment one decodes, [onScanned] fires once and the page closes itself.
 *
 * No camera is not a dead end: this page is only reachable when the device HAS
 * one (see `hasCamera` at the call site), the permission ask is handled here,
 * and a refusal leaves the user with the message that says to use the code —
 * which is the path every television takes anyway.
 */
@Composable
private fun QrScannerPage(onScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // The Activity behind this page IS a LifecycleOwner (MainActivity is a
    // ComponentActivity), and CameraX needs one to tie the camera session to.
    // Reached by walking the context wrappers rather than through Compose's
    // `LocalLifecycleOwner`: that composition local moved out of compose-ui in
    // 1.7 and its old form is a deprecated alias, and a camera that only works
    // while a deprecation level happens to be WARNING is not worth the coin
    // flip.
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var refused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    // One executor for the analyzer, remembered so a recomposition cannot start a
    // second one, and shut down when the page goes away.
    val analyzer = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val reported = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            analyzer.shutdown()
            // The camera is released with the page: the settings screen behind
            // this one must not keep a camera session alive.
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val executor = ContextCompat.getMainExecutor(ctx)
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            runCatching {
                                val provider = future.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()
                                analysis.setAnalyzer(analyzer) { image ->
                                    if (!reported.get()) {
                                        val plane = image.planes.firstOrNull()
                                        if (plane != null) {
                                            val buffer = plane.buffer
                                            val data = ByteArray(buffer.remaining())
                                            buffer.get(data)
                                            val text = QrCode.decodePlanes(
                                                data,
                                                image.width,
                                                image.height,
                                                plane.rowStride,
                                            )
                                            if (text != null && reported.compareAndSet(false, true)) {
                                                main.post { onScanned(text) }
                                            }
                                        }
                                    }
                                    image.close()
                                }
                                provider.unbindAll()
                                // A null owner can only mean "this Compose tree
                                // is not hosted by an Activity", which the
                                // app's own navigation rules out; failing the
                                // bind (rather than skipping it) means the page
                                // says so instead of showing a black rectangle.
                                provider.bindToLifecycle(
                                    lifecycleOwner ?: error("no lifecycle owner"),
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }.onFailure { e ->
                                // Not a composable position (this runs from the
                                // camera provider's listener), so this one goes
                                // through I18n.t rather than `tr`.
                                failed = I18n.t("Couldn't open the camera")
                                Logs.logError("Pair", "camera bind failed", e)
                            }
                        }, executor)
                    }
                },
            )
        }
        // The reticle: a square the user lines the code up inside. Purely an
        // aimer — decoding does not care where in the frame the code is.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(230.dp)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
        )
        Text(
            tr("Point the camera at the QR code on the other device"),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp, start = 24.dp, end = 24.dp),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = tr("Close"),
                tint = Color.White,
            )
        }
        val problem = when {
            refused -> tr("Hikari needs the camera to scan the code — type the code instead")
            failed.isNotBlank() -> failed + " — " + tr("type the code instead")
            else -> ""
        }
        if (problem.isNotBlank()) {
            Text(
                problem,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            )
        }
    }
}

/**
 * The [LifecycleOwner] behind a context — the Activity, found by unwrapping.
 *
 * CameraX wants one so it can stop the camera when the screen goes to the
 * background; `LocalLifecycleOwner` would give the same object, but that
 * composition local moved packages in Compose 1.7 and its compose-ui form is a
 * deprecated alias (and a deprecated alias can be an ERROR when the deprecation
 * level says so). Walking the wrappers costs three lines and cannot be
 * deprecated out from under the camera.
 */
private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is android.content.ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}