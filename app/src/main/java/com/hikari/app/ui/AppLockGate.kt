package com.hikari.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.lock.AppLock
import kotlinx.coroutines.launch

/**
 * The in-app lock (Settings → Privacy & Browsing → App lock).
 *
 * Wraps the whole app: while the lock is on and the session has not been
 * unlocked, nothing else is drawn — no screen and no dialog — and the unlock
 * card is all there is.
 *
 * When it locks again: the moment the APP leaves the foreground, which is
 * [ProcessLifecycleOwner]. Locking on the *activity* stopping would re-lock
 * every time the player opens, because the player is its own activity and this
 * one stops behind it — the user would come back from a film to a password
 * prompt, which is not what an app lock is for.
 *
 * The password is the required half: the fingerprint/face is only ever an
 * additional way in (see [AppLock]), so a device with no enrolled biometric
 * still opens with the password, and there is no recovery for a forgotten one —
 * the settings card says so before it is set.
 */
@Composable
fun AppLockGate(activity: android.app.Activity, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as HikariApp
    val enabledFlow = remember { app.store.appLockFlow() }
    val enabled by enabledFlow.collectAsState(initial = false)
    val bioFlow = remember { app.store.appLockBioFlow() }
    val bioOn by bioFlow.collectAsState(initial = true)
    // The unlocked flag lives in the composition, not in saved state: a fresh
    // process (or a recreated activity after the process was killed) starts
    // locked, which is the whole point.
    var unlocked by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val owner: LifecycleOwner = ProcessLifecycleOwner.get()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (!enabled || unlocked) {
        content()
        return
    }
    AppLockScreen(activity = activity, bioOn = bioOn, onUnlocked = { unlocked = true })
}

/** The unlock card itself. */
@Composable
private fun AppLockScreen(
    activity: android.app.Activity,
    bioOn: Boolean,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val app = LocalContext.current.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val biometrics = remember(context) { Biometrics.available(context) }

    // The strings the fingerprint prompt is built from are read HERE, in the
    // composable body: `tr` is itself composable and cannot be called from the
    // plain functions below.
    val bioTitle = tr("Unlock Hikari")
    val bioSubtitle = tr("Use your fingerprint to open the app")
    val bioNegative = tr("Use password")

    fun check(entered: String) {
        scope.launch {
            val stored = runCatching { app.store.appLockSecret() }.getOrDefault("")
            if (AppLock.verify(entered, stored)) {
                wrong = false
                onUnlocked()
            } else {
                wrong = true
            }
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

    // Ask for the fingerprint as soon as the card appears — that is the point of
    // having it: the user should not have to tap anything to get in.
    LaunchedEffect(bioOn, biometrics) {
        if (bioOn && biometrics) askFingerprint()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                tr("Hikari is locked"),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Enter your password to open the app."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    wrong = false
                },
                label = { Text(tr("Password")) },
                singleLine = true,
                isError = wrong,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { check(password) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Wrong password"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { check(password) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("Unlock"))
            }
            if (bioOn && biometrics) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { askFingerprint() }) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Unlock with fingerprint"))
                }
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
