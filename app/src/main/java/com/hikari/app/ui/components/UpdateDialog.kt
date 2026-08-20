package com.hikari.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hikari.app.net.Updater
import com.hikari.app.net.Updater.UpdateStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Check-for-updates dialog. With [initialStatus] == null it checks GitHub itself on
 * open; Settings passes a pre-checked [UpdateStatus] so the row can show a spinner
 * and the dialog opens instantly. Handles download-with-progress and in-app install.
 */
@Composable
fun UpdateDialog(
    context: Context,
    onDismiss: () -> Unit,
    initialStatus: UpdateStatus? = null,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(initialStatus) }
    var checking by remember { mutableStateOf(initialStatus == null) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialStatus == null) {
            status = Updater.checkForUpdate()
            checking = false
        }
    }

    val s = status
    AlertDialog(
        onDismissRequest = { if (downloading == null && !checking) onDismiss() },
        title = {
            Text(if (s?.available == true) "Update available" else "Hikari updates")
        },
        text = {
            Column {
                when {
                    checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(end = 12.dp)
                                .width(22.dp)
                                .height(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Text("Checking for updates…")
                    }

                    downloading != null -> {
                        LinearProgressIndicator(
                            progress = { downloading!! },
                            Modifier.fillMaxWidth()
                        )
                        Spacer8()
                        Text("Downloading update…")
                    }

                    installed -> Text(
                        "Update downloaded. The Android installer will now open — " +
                            "confirm install there, or open GitHub for the manual APK."
                    )

                    error != null -> Text(error!!)

                    s == null -> Text(
                        "Couldn't reach GitHub. Check your connection and try again."
                    )

                    s.available -> Column {
                        Text(
                            "A new version is available " +
                                "(commit ${s.latestSha.take(7)} — you're on ${s.currentSha.take(7)}).\n\n" +
                                "Download and install it right here, or grab the APK from GitHub."
                        )
                    }

                    else -> Text(
                        "You're on the latest build (commit ${s.currentSha.take(7)})."
                    )
                }
            }
        },
        confirmButton = {
            val dl = downloading
            if (dl == null && !installed && error == null && s?.available == true) {
                TextButton(onClick = {
                    scope.launch {
                        downloading = 0f
                        val result = Updater.download(context) { done, total ->
                            // onProgress runs on a background thread — hop back to main.
                            scope.launch {
                                downloading = if (total > 0) done.toFloat() / total else 0f
                            }
                        }
                        downloading = null
                        result.fold(
                            onSuccess = { file: File ->
                                when {
                                    !Updater.canInstall(context) -> {
                                        error =
                                            "Unknown apps are blocked for Hikari. " +
                                                "Grant \"Install unknown apps\" on the next screen, then tap Download again."
                                        Updater.openInstallSettings(context)
                                    }

                                    Updater.install(context, file) -> installed = true

                                    else -> error =
                                        "The system installer couldn't open. Grab the APK from GitHub instead."
                                }
                            },
                            onFailure = { e ->
                                error = "Download failed: ${e.message}"
                            }
                        )
                    }
                }) { Text("Download & install") }
            }
        },
        dismissButton = {
            if (installed || error != null) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else if (!checking && downloading == null) {
                TextButton(onClick = {
                    if (s?.available == true) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))
                        )
                    }
                    onDismiss()
                }) { Text(if (s?.available == true) "Open GitHub" else "Close") }
            }
        }
    )
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
}
