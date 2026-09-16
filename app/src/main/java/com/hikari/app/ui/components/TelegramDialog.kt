package com.hikari.app.ui.components
import com.hikari.app.i18n.tr

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hikari.app.ui.TELEGRAM_CHANNEL_URL
import com.hikari.app.ui.openTelegram

/**
 * Shown once on launch (until "Don't show this again" is ticked) to point the
 * user at the support group. Join hands off to the Telegram app / the user's
 * browser — never to Hikari's own WebView, where Telegram's hand-off to the app
 * dies (see [openTelegram]).
 */
@Composable
fun TelegramDialog(
    context: Context,
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    var dontShow by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = { Text(tr("Join the Hikari Telegram")) },
        text = {
            Column {
                Text(
                    tr("For any query, support, bug reports, title requests or feature " + "ideas — or just to keep up with new releases — join the Hikari ") +
                        "group on Telegram. It's the fastest way to reach me."
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .clickable { dontShow = !dontShow },
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text(tr("Don't show this again"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openTelegram(context, TELEGRAM_CHANNEL_URL)
                if (dontShow) onDontShowAgain() else onDismiss()
            }) { Text(tr("Join")) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (dontShow) onDontShowAgain() else onDismiss()
            }) { Text(tr("Close")) }
        },
    )
}
