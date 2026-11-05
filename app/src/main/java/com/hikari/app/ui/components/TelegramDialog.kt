package com.hikari.app.ui.components
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.hikari.app.ui.DISCORD_INVITE_URL
import com.hikari.app.ui.REDDIT_COMMUNITY_URL
import com.hikari.app.ui.TELEGRAM_CHANNEL_URL
import com.hikari.app.ui.openCommunity
import com.hikari.app.ui.openTelegram

/**
 * Shown once per app version to point the user at the community: Telegram,
 * Reddit and Discord. "Don't show this again" holds for the current version
 * only — after an update it comes back once. Every join hands off to the
 * matching app / the user's browser — never to Hikari's own WebView, where
 * the hand-off to the app dies (see [openTelegram]).
 */
@Composable
fun TelegramDialog(
    context: Context,
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    var dontShow by remember { mutableStateOf(false) }
    // Television: the D-pad lands on Close the moment the dialog exists, so
    // Enter dismisses it with the focused ring showing — no hunting with the
    // remote first. The short delay is one layout pass: the requester cannot
    // take focus until the button has been placed.
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250L)
        runCatching { closeFocus.requestFocus() }
    }

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
        title = { Text(tr("Join the Hikari community")) },
        text = {
            Column {
                Text(
                    tr("For any query, support, bug reports, title requests or feature " + "ideas — or just to keep up with new releases — join us: ") +
                        I18n.t("Telegram for the fastest reply, Reddit and Discord too.")
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
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    openTelegram(context, TELEGRAM_CHANNEL_URL)
                    if (dontShow) onDontShowAgain() else onDismiss()
                }) { Text(tr("Join Telegram")) }
                TextButton(onClick = {
                    openCommunity(context, REDDIT_COMMUNITY_URL)
                    if (dontShow) onDontShowAgain() else onDismiss()
                }) { Text(tr("Join Reddit")) }
                TextButton(onClick = {
                    openCommunity(context, DISCORD_INVITE_URL)
                    if (dontShow) onDontShowAgain() else onDismiss()
                }) { Text(tr("Join Discord")) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (dontShow) onDontShowAgain() else onDismiss()
                },
                modifier = Modifier.focusRequester(closeFocus)
            ) { Text(tr("Close")) }
        },
    )
}
