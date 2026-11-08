package com.hikari.app.ui

import com.hikari.app.ui.components.LocalHideHelp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.hikari.app.ui.theme.pageBackground
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.lock.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppLockGate(activity: android.app.Activity, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as HikariApp
    val context = LocalContext.current
    val enabledFlow = remember { app.store.appLockFlow() }
    val enabledState by enabledFlow.collectAsState(initial = null)
    val bioFlow = remember { app.store.appLockBioFlow() }
    val bioOn by bioFlow.collectAsState(initial = true)
    val screenOffFlow = remember { app.store.appLockScreenOffFlow() }
    val screenOffLocks by screenOffFlow.collectAsState(initial = true)
    val leaveFlow = remember { app.store.appLockLeaveFlow() }
    val leaveLocks by leaveFlow.collectAsState(initial = true)
    val delayFlow = remember { app.store.appLockDelayFlow() }
    val delayMin by delayFlow.collectAsState(initial = 0)
    var unlocked by remember { mutableStateOf(false) }
    var sessionRead by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var profileTick by remember { mutableStateOf(0) }
    var profilesRead by remember { mutableStateOf(false) }
    val profilesAll by com.hikari.app.data.Profiles.all.collectAsState()
    val profilesActiveId by com.hikari.app.data.Profiles.activeId.collectAsState()
    val lockedProfile = remember(profilesAll, profilesActiveId, profileTick) {
        profilesActiveId?.let { id -> profilesAll.firstOrNull { it.id == id } }
            ?.takeIf { com.hikari.app.data.Profiles.needsPassword(it) }
    }
    LaunchedEffect(Unit) {
        runCatching { com.hikari.app.data.Profiles.load(app) }
        profilesRead = true
        profileTick++
    }

    /** Locks the app AND records it, so a fresh process asks again. */
    fun lockNow() {
        if (!unlocked) return
        unlocked = false
        // A re-locked app re-locks its profiles too: their passwords are
        // per-process unlocks by design (see Profiles.unlockedIds).
        com.hikari.app.data.Profiles.lockAll()
        scope.launch { runCatching { app.store.setAppLockSessionClosed() } }
    }

    LaunchedEffect(Unit) {
        val (open, at) = runCatching { app.store.appLockSession() }
            .getOrDefault(false to 0L)
        val leave = runCatching { app.store.appLockLeave() }.getOrDefault(true)
        val screenOff = runCatching { app.store.appLockScreenOff() }.getOrDefault(true)
        val delay = runCatching { app.store.appLockDelay() }.getOrDefault(0)
        unlocked = open && when {
            !leave && !screenOff -> true
            delay > 0 -> at > 0L &&
                System.currentTimeMillis() - at < delay * 60_000L
            leave -> false
            else -> true
        }
        sessionRead = true
    }
    val leftAt = remember { longArrayOf(0L) }
    val screenWasOff = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val owner: LifecycleOwner = ProcessLifecycleOwner.get()
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        screenWasOff.set(true)
                        if (screenOffLocks) lockNow()
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> screenWasOff.set(false)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val screenOff = screenWasOff.get() || power?.isInteractive == false
                val locks = if (screenOff) screenOffLocks else leaveLocks
                if (!locks) {
                } else if (delayMin <= 0) {
                    lockNow()
                } else if (unlocked) {
                    val now = System.currentTimeMillis()
                    leftAt[0] = now
                    scope.launch {
                        runCatching { app.store.setAppLockSessionOpen(at = now) }
                    }
                }
            } else if (event == Lifecycle.Event.ON_START) {
                // The app is back in front of the user: the screen state is
                // settled again, so the next ON_STOP must decide from scratch.
                screenWasOff.set(power?.isInteractive == false)
                val since = leftAt[0]
                leftAt[0] = 0L
                if (since > 0L && unlocked &&
                    System.currentTimeMillis() - since >= delayMin * 60_000L
                ) {
                    lockNow()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    if (enabledState == null || !sessionRead || !profilesRead) {
        Box(Modifier.fillMaxSize().background(pageBackground()))
        return
    }
    if (enabledState != true || unlocked) {
        if (lockedProfile != null) {
            ProfileGateCard(
                profile = lockedProfile,
                others = profilesAll.filter { it.id != lockedProfile.id },
                onUnlocked = { profileTick++ },
                onSwitched = { profileTick++ },
            )
            return
        }
        content()
        return
    }
    AppLockScreen(
        activity = activity,
        bioOn = bioOn,
        onUnlocked = {
            unlocked = true
            scope.launch { runCatching { app.store.setAppLockSessionOpen() } }
        },
    )
}

@Composable
private fun AppLockScreen(
    activity: android.app.Activity,
    bioOn: Boolean,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val app = LocalContext.current.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var entered by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // The value to verify next, when one arrives while a check is already
    // running (see [check]).
    var queued by remember { mutableStateOf<String?>(null) }
    var resetAsk by remember { mutableStateOf(false) }
    val biometrics = remember(context) { Biometrics.available(context) }

    var secretOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val stored = runCatching { app.store.appLockSecret() }.getOrDefault("")
        secretOk = AppLock.isSet(stored)
    }

    // The password's length, recorded when it was set (0 = an older lock, whose
    // length was never written down — see the four points above).
    val lenFlow = remember { app.store.appLockLenFlow() }
    val secretLen by lenFlow.collectAsState(initial = 0)

    val bioTitle = tr("Unlock Hikari")
    val bioSubtitle = tr("Use your fingerprint to open the app")
    val bioNegative = tr("Use password")

    fun buzz() {
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    fun check(value: String) {
        if (value.isBlank()) return
        if (checking) {
            queued = value
            return
        }
        checking = true
        val attempt = value
        scope.launch {
            val stored = runCatching { app.store.appLockSecret() }.getOrDefault("")
            val ok = withContext(Dispatchers.Default) { AppLock.verify(attempt, stored) }
            if (ok) {
                checking = false
                wrong = false
                // Self-healing: a lock set before the length was recorded gets
                // it now, so the dots are right from the next launch on.
                if (secretLen <= 0) {
                    runCatching { app.store.setAppLockLen(attempt.length) }
                }
                onUnlocked()
                return@launch
            }
            wrong = true
            buzz()
            if (secretLen > 0) {
                delay(600)
                // Only when nothing was typed in the meantime: clearing a value
                // the user has already begun to correct eats their keystrokes.
                if (entered == attempt) entered = ""
                if (typed == attempt) typed = ""
            }
            checking = false
            val next = queued
            queued = null
            if (next != null && next != attempt) check(next)
        }
    }

    fun askFingerprint() {
        Biometrics.prompt(
            activity = activity,
            title = bioTitle,
            subtitle = bioSubtitle,
            negative = bioNegative,
            onSuccess = {
                wrong = false
                onUnlocked()
            },
            onError = { },
        )
    }

    fun turnLockOff() {
        scope.launch {
            runCatching {
                app.store.setAppLock(false)
                app.store.setAppLockSecret("")
                app.store.setAppLockLen(0)
                app.store.setAppLockBio(false)
            }
            onUnlocked()
        }
    }

    // Ask for the fingerprint as soon as the card appears — that is the point of
    // having it: the user should not have to tap anything to get in.
    LaunchedEffect(bioOn, biometrics) {
        if (bioOn && biometrics) askFingerprint()
    }

    if (secretOk == false) {
        BrokenLockScreen(onTurnOff = { turnLockOff() })
        return
    }

    LaunchedEffect(entered, secretLen) {
        val complete = if (secretLen > 0) entered.length == secretLen else entered.length >= 4
        if (complete) {
            delay(if (secretLen > 0) 140 else 500)
            check(entered)
        }
    }

    Surface(Modifier.fillMaxSize(), color = pageBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr("Unlock to use Hikari"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (textMode) tr("Enter your password")
                else tr("Enter your PIN or use a fingerprint"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            if (textMode) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        wrong = false
                    },
                    label = { Text(tr("Password")) },
                    singleLine = true,
                    enabled = !checking,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { check(typed) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrong) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("Wrong PIN or password"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { check(typed) },
                    enabled = !checking && typed.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Checking…"))
                    } else {
                        Text(tr("Unlock"))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (wrong && !checking) {
                    TextButton(onClick = { resetAsk = true }) { Text(tr("Forgot password?")) }
                }
                TextButton(onClick = {
                    textMode = false
                    wrong = false
                }) { Text(tr("Use the keypad")) }
            } else {
                PinDots(
                    typed = entered.length,
                    wrong = wrong,
                    shown = if (secretLen > 0) secretLen else maxOf(4, entered.length),
                )
                Spacer(Modifier.height(14.dp))
                Keypad(
                    onDigit = { d ->
                        if (!checking) {
                            wrong = false
                            if (entered.length < MAX_PIN &&
                                (secretLen == 0 || entered.length < secretLen)
                            ) {
                                entered += d
                            }
                        }
                    },
                    onBackspace = {
                        wrong = false
                        if (entered.isNotEmpty()) entered = entered.dropLast(1)
                    },
                    fingerprint = bioOn && biometrics,
                    onFingerprint = { askFingerprint() },
                    onSwitchToText = {
                        textMode = true
                        typed = entered
                        wrong = false
                    },
                )
                Spacer(Modifier.height(10.dp))
                when {
                    checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Checking…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    wrong -> Text(
                        tr("Wrong PIN or password"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        if (secretLen > 0) {
                            tr("The PIN is checked when the last digit is typed.")
                        } else {
                            tr("The PIN is checked as you type it — keep typing if yours is longer.")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (wrong && !checking) {
                    TextButton(onClick = { resetAsk = true }) { Text(tr("Forgot password?")) }
                }
                TextButton(onClick = {
                    textMode = true
                    typed = ""
                    wrong = false
                }) { Text(tr("Enter password instead")) }
            }
        }
    }

    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            title = { Text(tr("Turn the app lock off?")) },
            text = {
                Text(
                    tr(
                        "The password cannot be read back or recovered — it is not stored, " +
                            "only a derivation of it. Turning the lock off turns it off: " +
                            "anyone holding the phone can then open Hikari, and you can set a " +
                            "new password in Settings afterwards."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetAsk = false
                    turnLockOff()
                }) { Text(tr("Turn it off")) }
            },
            dismissButton = {
                TextButton(onClick = { resetAsk = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** The most digits a keypad entry may reach before it is stopped. */
private const val MAX_PIN = 16

@Composable
private fun BrokenLockScreen(onTurnOff: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = pageBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr("The app lock needs to be reset"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr(
                    "The password saved on this device cannot be checked — it was written by " +
                        "an older version of Hikari, in a form this one cannot read. No " +
                        "password will open the app until the lock is reset."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Reset the app lock"))
            }
            Spacer(Modifier.height(6.dp))
            if (!LocalHideHelp.current) {
            Text(
                tr(
                    "Resetting turns the lock off. You can set a new password afterwards in " +
                        "Settings → Privacy & Browsing → App lock."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(tr("Turn the app lock off?")) },
            text = {
                Text(
                    tr(
                        "The lock is turned off and the unreadable password is deleted. Anyone " +
                            "holding the phone can then open Hikari. Set a new password in " +
                            "Settings afterwards."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    onTurnOff()
                }) { Text(tr("Turn it off")) }
            },
            dismissButton = {
                TextButton(onClick = { ask = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** The entered digits, as dots: filled for what is typed, hairline for the rest. */
@Composable
private fun PinDots(typed: Int, wrong: Boolean, shown: Int) {
    val filled = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val count = maxOf(shown, typed)
    // A long password (or, when the length is unknown, a long PIN being typed)
    // shrinks the dots and their gaps rather than running off a phone's width.
    val tight = count > 8
    Row(horizontalArrangement = Arrangement.spacedBy(if (tight) 7.dp else 12.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size((if (i < typed) 12.dp else 10.dp) * (if (tight) 0.75f else 1f))
                    .clip(CircleShape)
                    .background(if (i < typed) filled else empty)
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    fingerprint: Boolean,
    onFingerprint: () -> Unit,
    onSwitchToText: () -> Unit,
) {
    val letters = mapOf(
        '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
        '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    )
    val rows = listOf("123", "456", "789")
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in rows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (key in row) {
                    KeypadKey(
                        label = key.toString(),
                        sub = letters[key].orEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(key) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onBackspace) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = tr("Delete"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            KeypadKey(
                label = "0",
                sub = "",
                modifier = Modifier.weight(1f),
                onClick = { onDigit('0') },
            )
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (fingerprint) {
                    IconButton(onClick = onFingerprint) {
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = tr("Unlock with fingerprint"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    IconButton(onClick = onSwitchToText) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = tr("Enter password instead"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    label: String,
    sub: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

object Biometrics {

    fun available(context: Context): Boolean = runCatching {
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun prompt(
        activity: android.app.Activity,
        title: String,
        subtitle: String,
        negative: String,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        // The androidx prompt needs a FragmentActivity to attach its dialog
        // fragment to; MainActivity is one (it is an AppCompatActivity because
        // CloudStream plugins cast the host context to one — see
        // app/build.gradle.kts). Anything else simply gets no prompt.
        val fragmentActivity = activity as? FragmentActivity ?: return onError()
        runCatching {
            val prompt = androidx.biometric.BiometricPrompt(
                fragmentActivity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                    ) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onError()
                    }
                },
            )
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                .build()
            prompt.authenticate(info)
        }.onFailure { onError() }
    }
}

@Composable
private fun ProfileGateCard(
    profile: com.hikari.app.data.Profiles.Profile,
    others: List<com.hikari.app.data.Profiles.Profile>,
    onUnlocked: () -> Unit,
    onSwitched: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    var pw by remember(profile.id) { mutableStateOf("") }
    var wrong by remember(profile.id) { mutableStateOf(false) }
    var checking by remember(profile.id) { mutableStateOf(false) }
    var pickOther by remember { mutableStateOf(others.isNotEmpty()) }
    var switchingTo by remember { mutableStateOf<String?>(null) }
    var pwTarget by remember(profile.id) {
        mutableStateOf<com.hikari.app.data.Profiles.Profile?>(null)
    }

    fun check() {
        if (pw.isBlank() || checking) return
        checking = true
        val attempt = pw
        val target = pwTarget ?: profile
        scope.launch {
            val res = runCatching {
                com.hikari.app.data.Profiles.unlock(app, target.id, attempt)
            }.getOrNull()
            checking = false
            if (res == com.hikari.app.data.Profiles.UnlockResult.OK ||
                res == com.hikari.app.data.Profiles.UnlockResult.NO_SECRET
            ) {
                if (target.id == profile.id) {
                    onUnlocked()
                } else {
                    runCatching { com.hikari.app.data.Profiles.switchTo(app, target.id) }
                    pwTarget = null
                    onSwitched()
                }
            } else {
                wrong = true
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = pageBackground()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "\"" + (pwTarget ?: profile).name + "\" " + tr("is locked"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Enter this profile's password to open it."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it; wrong = false },
                label = { Text(tr("Password")) },
                singleLine = true,
                enabled = !checking,
                isError = wrong,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { check() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("Wrong password"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { check() },
                enabled = !checking && pw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Checking…"))
                } else {
                    Text(tr("Unlock"))
                }
            }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { pickOther = true }) { Text(tr("Use a different profile")) }
            }
        }
    }

    if (pickOther) {
        AlertDialog(
            onDismissRequest = { if (switchingTo == null) pickOther = false },
            title = { Text(tr("Switch profile")) },
            text = {
                Column {
                    TextButton(
                        enabled = switchingTo == null,
                        onClick = {
                            pwTarget = null
                            pw = ""
                            wrong = false
                            pickOther = false
                        },
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(profile.name)
                    }
                    for (p in others) {
                        val busy = switchingTo == p.id
                        TextButton(
                            enabled = switchingTo == null,
                            onClick = {
                                if (com.hikari.app.data.Profiles.needsPassword(p)) {
                                    pwTarget = p
                                    pw = ""
                                    wrong = false
                                    pickOther = false
                                } else {
                                    switchingTo = p.id
                                    scope.launch {
                                        runCatching { com.hikari.app.data.Profiles.switchTo(app, p.id) }
                                        switchingTo = null
                                        pickOther = false
                                        onSwitched()
                                    }
                                }
                            },
                        ) {
                            if (com.hikari.app.data.Profiles.needsPassword(p)) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (busy) tr("Switching…") else p.name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { if (switchingTo == null) pickOther = false }) {
                    Text(tr("Cancel"))
                }
            },
        )
    }
}
