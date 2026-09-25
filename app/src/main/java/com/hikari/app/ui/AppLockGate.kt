package com.hikari.app.ui

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

/**
 * The in-app lock (Settings → Privacy & Browsing → App lock).
 *
 * Wraps the whole app: while the lock is on and this session has not been
 * unlocked, nothing else is drawn — no screen and no dialog — and the unlock
 * card is all there is.
 *
 * When it locks again: the moment the APP leaves the foreground, which is
 * [ProcessLifecycleOwner]. Locking on the *activity* stopping would re-lock
 * every time the player opens, because the player is its own activity and this
 * one stops behind it — the user would come back from a film to a PIN prompt,
 * which is not what an app lock is for.
 *
 * Two rules refine "the app left the foreground":
 *
 *  * **A screen-off is its own trigger** (`appLockScreenOffFlow`, on by
 *    default). Turning the screen off stops the activity exactly like switching
 *    apps does, so the lock could never tell them apart — with the switch OFF,
 *    only actually leaving the app locks it, and putting the phone down and
 *    picking it up again does not ask for the password.
 *  * **Leaving the app is its own trigger too** (`appLockLeaveFlow`, on by
 *    default). The twin of the switch above: OFF, switching to another app
 *    never draws the unlock card, so the lock answers only to a screen-off (if
 *    that is on) or to a fresh start of the process. Both switches OFF is a
 *    lock that only asks once per app launch.
 *  * **A grace period** (`appLockDelayFlow`, Instant by default). Past zero
 *    minutes the app stays unlocked for that long after it is left, so a glance
 *    at a notification does not cost a PIN. The deadline is a wall-clock
 *    timestamp compared when the app comes back, NOT a running timer: an app
 *    left for an hour has very likely had its process killed (a fresh process
 *    starts locked anyway), and a wall-clock comparison also survives the device
 *    sleeping, which a coroutine timer would not. It applies to whichever
 *    triggers are on.
 *
 * The password is the required half: the fingerprint/face is only ever an
 * additional way in (see [AppLock]), so a device with no enrolled biometric
 * still opens with the password, and there is no recovery for a forgotten one —
 * the settings card says so before it is set.
 */
@Composable
fun AppLockGate(activity: android.app.Activity, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as HikariApp
    val context = LocalContext.current
    val enabledFlow = remember { app.store.appLockFlow() }
    // `null` until the store has answered, and the gate draws NOTHING for that
    // instant (see below). It used to start at `false`, which means "the lock is
    // off" — so on every cold start the app's own content was composed and shown
    // for a frame or two before the stored value arrived and the lock card
    // replaced it. A lock whose whole purpose is "nobody reads my screen" cannot
    // flash the screen it is protecting.
    val enabledState by enabledFlow.collectAsState(initial = null)
    val bioFlow = remember { app.store.appLockBioFlow() }
    val bioOn by bioFlow.collectAsState(initial = true)
    // The rules beyond "the lock is on" — see the doc comment. All are read
    // live by the lifecycle observer below (they are State-backed, so the
    // observer always compares against the current settings).
    val screenOffFlow = remember { app.store.appLockScreenOffFlow() }
    val screenOffLocks by screenOffFlow.collectAsState(initial = true)
    val leaveFlow = remember { app.store.appLockLeaveFlow() }
    val leaveLocks by leaveFlow.collectAsState(initial = true)
    val delayFlow = remember { app.store.appLockDelayFlow() }
    val delayMin by delayFlow.collectAsState(initial = 0)
    // The unlocked flag lives in the composition, not in saved state: a fresh
    // process (or a recreated activity after the process was killed) starts
    // locked, which is the whole point.
    var unlocked by remember { mutableStateOf(false) }
    // When the app was last left, for the grace period. A one-element array
    // because the lifecycle observer both writes and reads it and nothing draws
    // it: it is a timestamp, not UI state.
    val leftAt = remember { longArrayOf(0L) }
    // Was the SCREEN off the last time the app went away? Written by the
    // screen-off broadcast below and cleared when the screen comes back. The
    // reason it exists at all: `PowerManager.isInteractive` is the honest answer
    // to "did the screen go off or did the user leave?", but it is read from a
    // lifecycle callback that the platform can dispatch either side of the
    // display state actually settling — so on some devices the check read
    // `true` for a screen-off, the "Lock when the screen turns off" switch was
    // never consulted, and the app came back UNLOCKED (the reported "lock when
    // screen off is not working"). The broadcast is the event itself, so it is
    // the one that cannot be raced.
    val screenWasOff = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val owner: LifecycleOwner = ProcessLifecycleOwner.get()
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> screenWasOff.set(true)
                    android.content.Intent.ACTION_SCREEN_ON -> screenWasOff.set(false)
                }
            }
        }
        // Only the two screen actions, and NOT exported: the app is not
        // registering for anything else and nothing outside can reach it. The
        // ContextCompat form is used because targetSdk 34 wants an explicit
        // export flag on a runtime-registered receiver.
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
                // Was this the screen going off, or the user actually leaving?
                // Either signal is enough: the broadcast says the display went
                // off, and the power state catches the case where the broadcast
                // has not been delivered yet. Reading both makes the answer
                // independent of which one arrives first.
                val screenOff = screenWasOff.get() || power?.isInteractive == false
                // Each trigger has its own switch: a screen-off honours
                // "Lock when the screen turns off", anything else honours
                // "Lock when I leave the app".
                val locks = if (screenOff) screenOffLocks else leaveLocks
                if (!locks) {
                    // The user asked for this trigger not to lock: nothing to do
                    // at all, not even a grace period. The other trigger (and a
                    // fresh process start) still locks.
                } else if (delayMin <= 0) {
                    unlocked = false
                } else {
                    leftAt[0] = System.currentTimeMillis()
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
                    unlocked = false
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    if (enabledState == null) {
        // The stored state has not arrived yet: draw a blank card rather than
        // the app. One frame, and the app's content is never composed with the
        // lock possibly on (see the note on [enabledState]).
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    if (enabledState != true || unlocked) {
        content()
        return
    }
    AppLockScreen(activity = activity, bioOn = bioOn, onUnlocked = { unlocked = true })
}

/**
 * The unlock screen: the padlock, the dots, and a numeric keypad with the
 * fingerprint on its bottom-right — the shape every messenger's app lock has,
 * and the one the user asked for by sending a screenshot of it.
 *
 * The keypad is the front door, but it is not the only one: whatever was set as
 * the password may be a word rather than a PIN (the setting's own field is a
 * free text field), so "Enter password instead" swaps the keypad for a real
 * password field. Without it, an alphanumeric password would be unenterable.
 *
 * FOUR THINGS THIS SCREEN HAS TO GET RIGHT, and all four were reported as bugs:
 *
 *  1. **As many dots as the password has.** The length is recorded when the
 *     password is set ([com.hikari.app.data.AppStore.appLockLenFlow]), so a
 *     4-digit PIN draws four dots and is submitted on the fourth digit. Showing
 *     a fixed six made a 4-digit PIN look like the app wanted six.
 *  2. **Never silent.** Verifying is deliberately expensive (120k PBKDF2
 *     rounds), so the check shows a spinner and the input is frozen while it
 *     runs. A screen that accepted a tap and did nothing for a second — and
 *     then, on failure, said nothing at all in text mode — is exactly the
 *     "clicking Unlock does nothing" report.
 *  3. **A wrong answer says so, in both modes**, shows the error on the field as
 *     well, and clears what was typed so the next attempt starts clean.
 *  4. **A way out.** A password that will not verify (a blob from an older build
 *     of this same app, a forgotten PIN) would otherwise leave the owner locked
 *     out of their own app forever. After a failed attempt the screen offers to
 *     turn the lock off, behind a confirmation that says plainly what that
 *     means: anyone holding the phone can then open Hikari.
 */
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

    // Can the stored secret be verified AT ALL? A lock whose blob was written by
    // a build with the old `[B@…` encoding bug (see [AppLock.encode]) can never
    // accept any password, no matter what the user types. Checking here is what
    // turns "wrong password, forever" into a screen that says what is wrong and
    // offers the one honest way out. Null until the store answers, so the normal
    // keypad is what is drawn first (no flash of a scary card).
    var secretOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val stored = runCatching { app.store.appLockSecret() }.getOrDefault("")
        secretOk = AppLock.isSet(stored)
    }

    // The password's length, recorded when it was set (0 = an older lock, whose
    // length was never written down — see the four points above).
    val lenFlow = remember { app.store.appLockLenFlow() }
    val secretLen by lenFlow.collectAsState(initial = 0)

    // The strings the fingerprint prompt is built from are read HERE, in the
    // composable body: `tr` is itself composable and cannot be called from the
    // plain functions below.
    val bioTitle = tr("Unlock Hikari")
    val bioSubtitle = tr("Use your fingerprint to open the app")
    val bioNegative = tr("Use password")

    fun buzz() {
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    fun check(value: String) {
        if (value.isBlank()) return
        // A check that is already running does not DROP the newer value: while a
        // short prefix is being verified the user may have kept typing (the
        // unknown-length case), and that longer entry is the one that deserves
        // the next check. Dropping it is how a six-digit PIN typed over a
        // four-digit prefix could go unverified forever.
        if (checking) {
            queued = value
            return
        }
        checking = true
        val attempt = value
        scope.launch {
            val stored = runCatching { app.store.appLockSecret() }.getOrDefault("")
            // The derivation is deliberately expensive (120k PBKDF2 rounds), so
            // it runs OFF the main thread: on the main thread it would be a
            // visible stall on every digit, and on a slow phone a possible ANR.
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
            // A wrong complete PIN is cleared once the red has been seen — the
            // next attempt starts clean. When the length is NOT known (an older
            // lock) the digits are deliberately LEFT in place: what was just
            // rejected is only a prefix, and the user may be halfway through a
            // longer PIN, which the next digit will check again.
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

    // A typed PIN submits itself as soon as it is complete — or, when the length
    // was never recorded, at every prefix of four or more digits, so a longer
    // PIN still gets its chance as it is typed. A wrong answer clears the entry,
    // which is what re-arms this effect for the next attempt.
    LaunchedEffect(entered, secretLen) {
        val complete = if (secretLen > 0) entered.length == secretLen else entered.length >= 4
        if (complete) {
            // A known length is checked the moment it is reached. An unknown one
            // waits a little longer, so a user typing a six-digit PIN is not
            // interrupted by a check of its four-digit prefix mid-word.
            delay(if (secretLen > 0) 140 else 500)
            check(entered)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            // The dots below say how many characters the password has, because
            // the length is recorded with it — that is the answer to "how many
            // does it want?". The sentence itself stays one whole literal so it
            // translates as a sentence.
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
                    // A PASSWORD keyboard, explicitly: with the plain text
                    // keyboard the IME capitalises the first letter and offers
                    // autocorrect, so a right password could be sent wrong —
                    // which is exactly the "it says wrong even when I type it
                    // right" report this screen exists to fix.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { check(typed) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrong) {
                    Spacer(Modifier.height(6.dp))
                    // The error is shown in BOTH modes: with only a red outline
                    // (the field's isError) a failed attempt in text mode looked
                    // like the Unlock button had done nothing at all.
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
                            // With the length known there is nothing to gain from
                            // an extra digit: the answer is already being checked.
                            // Without it, digits may accumulate (a longer PIN is
                            // being typed) up to a sane ceiling.
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

/**
 * What is drawn when the stored lock cannot be verified by any password at all.
 *
 * This is the state every install that set its password before the encoding fix
 * is in: the blob on disk is not a derivation of anything (see
 * [AppLock.encode]), so no amount of correct typing can open the app. The
 * alternative to this screen is an unlock screen that says "wrong password" to
 * the right one forever, which is the report this exists to answer.
 *
 * There is exactly one honest way forward, and it is the same one the unlock
 * screen offers for a forgotten password: turn the lock off (with the
 * confirmation saying what that means), after which the app opens and a new
 * password can be set in Settings → Privacy & Browsing → App lock.
 */
@Composable
private fun BrokenLockScreen(onTurnOff: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

/**
 * The keypad itself: 1–9 with their letters, then backspace / 0 / fingerprint —
 * Telegram's own layout, including the fingerprint in the corner rather than a
 * button of its own.
 */
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

/**
 * Fingerprint/face unlock, through androidx.biometric.
 *
 * Nothing here is fatal when it is unavailable: [available] answers false on a
 * device with no sensor, no enrolled fingerprint, or a biometric stack that
 * refuses to answer — the password field is then the only way in, which is the
 * required half of the lock anyway (see [AppLock]).
 */
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
