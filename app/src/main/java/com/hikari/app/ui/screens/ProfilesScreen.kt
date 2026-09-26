package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.data.Profiles
import com.hikari.app.i18n.tr
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Profiles: the picker, and the place a profile is created, renamed
 * or removed.
 *
 * A profile is a whole setup kept apart from the others on the same device (see
 * [Profiles]) — what the user gets is exactly what the request described: a new
 * profile looks like Hikari freshly installed (no extensions installed, an empty
 * Library, an empty history, its own settings), and choosing an older one brings
 * everything back, because the old setup was never deleted, only put away.
 *
 * The page is deliberately plain about the one thing that IS shared: the
 * extension files and the films already downloaded stay on the device, so a new
 * profile installs an extension without downloading it twice, and deleting a
 * profile never deletes the user's videos.
 */
@Composable
fun ProfilesScreen(app: HikariApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val profiles by Profiles.all.collectAsState()
    val activeId by Profiles.activeId.collectAsState()

    // Hoisted strings: `tr` is composable, and the ones below are also read from
    // plain lambdas — a coroutine that has finished switching, a dialog button —
    // which cannot call it.
    val msgNowUsing = tr("Now using")
    val msgOtherKept = tr("The other setup is still here — switch back any time.")
    val msgSwitchFailed = tr("Could not switch profile. The current one is unchanged.")
    val msgDefaultName = tr("Default")
    val msgCreated = tr("Created. You can switch between this and your other setup any time.")
    val msgCreateFailed = tr("Could not create that profile.")
    val msgDeleted = tr("Profile deleted.")

    var busy by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Profiles.Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profiles.Profile?>(null) }
    var typed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // What each profile holds, read from its own snapshot (the active one is read
    // from the live store instead — its snapshot is only as fresh as the last
    // switch away from it).
    var summaries by remember { mutableStateOf<Map<String, Profiles.Summary>>(emptyMap()) }

    LaunchedEffect(profiles, activeId) {
        val live = withContext(Dispatchers.IO) { runCatching { Profiles.liveSummary(app) }.getOrNull() }
        val rest = withContext(Dispatchers.IO) {
            profiles.filter { it.id != activeId }.mapNotNull { p ->
                runCatching { Profiles.summaryOf(context, p.id) }.getOrNull()?.let { p.id to it }
            }
        }
        val map = HashMap<String, Profiles.Summary>()
        for ((id, summary) in rest) map[id] = summary
        val active = activeId
        if (active != null && live != null) map[active] = live
        summaries = map
    }

    // The registry lives in the files directory, so this page is also what makes
    // sure the app's flows are showing it (a profile restored from a backup, or
    // an app whose data was cleared, is picked up here).
    LaunchedEffect(Unit) { runCatching { Profiles.load(app) } }

    BackHandler { onBack() }

    fun switchTo(profile: Profiles.Profile) {
        if (profile.id == activeId || busy) return
        busy = true
        status = ""
        scope.launch {
            val result = runCatching { Profiles.switchTo(app, profile.id) }
            busy = false
            status = if (result.isSuccess) {
                msgNowUsing + " \"" + profile.name + "\". " + msgOtherKept
            } else {
                msgSwitchFailed
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "profiles-header") {
            SettingsPageHeader(
                title = tr("Profiles"),
                subtitle = tr("More than one setup on this device"),
                onBack = onBack,
            )
        }

        if (profiles.isEmpty()) {
            item(key = "profiles-intro") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            tr("Keep setups apart"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tr(
                                "A profile is a whole Hikari of its own: its installed " +
                                    "extensions, Library, history, accounts and settings. " +
                                    "A new one starts empty — like the app on the day it " +
                                    "was installed — and your current setup is saved as " +
                                    "the first profile, so nothing is lost."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            label = tr("Save this setup as a profile"),
                            enabled = !busy,
                        ) {
                            typed = msgDefaultName
                            naming = true
                        }
                    }
                }
            }
        } else {
            items(profiles, key = { it.id }) { profile ->
                ProfileRow(
                    profile = profile,
                    active = profile.id == activeId,
                    summary = summaryText(summaries[profile.id]),
                    busy = busy,
                    // The last profile is not offered for deletion: with none
                    // left the picker would be an empty page describing a
                    // feature with nothing in it.
                    canDelete = profiles.size > 1,
                    onOpen = { switchTo(profile) },
                    onRename = {
                        typed = profile.name
                        renameTarget = profile
                    },
                    onDelete = { deleteTarget = profile },
                )
            }
            item(key = "profiles-new") {
                GlassCard(
                    onClick = {
                        typed = ""
                        naming = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("New profile"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                tr(
                                    "Starts empty: no extensions installed, an empty " +
                                        "Library and history, its own settings."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // An explanation paragraph, so it obeys the hide-explanations switch
            // like every other one (it was the line that kept showing with the
            // switch off). The `if` is INSIDE the item because `LocalHideHelp`
            // is a composition-local read and the LazyColumn's content lambda is
            // not composable — reading it out there is a compile error.
            item(key = "profiles-note") {
                if (!LocalHideHelp.current) {
                    Text(
                        tr(
                            "Extension files and anything already downloaded stay on this " +
                                "device and are shared — a profile carries the setup, not a " +
                                "second copy of your videos. Your app lock and this device's " +
                                "layout are kept for every profile."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }

        if (busy) {
            item(key = "profiles-busy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("Switching — saving this setup and loading the other…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (status.isNotBlank()) {
            item(key = "profiles-status") {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (naming) {
        ProfileNameDialog(
            title = if (profiles.isEmpty()) tr("Save this setup") else tr("New profile"),
            hint = if (profiles.isEmpty()) {
                tr("Your current extensions, Library, history and settings become this profile.")
            } else {
                tr(
                    "The new profile starts empty. This setup is saved first, so you " +
                        "can switch back to it."
                )
            },
            value = typed,
            confirmLabel = if (profiles.isEmpty()) tr("Save") else tr("Create"),
            onValueChange = { typed = it },
            onConfirm = {
                val name = typed.trim()
                naming = false
                busy = true
                scope.launch {
                    val result = runCatching {
                        if (profiles.isEmpty()) Profiles.adopt(app, name.ifBlank { "Default" })
                        else Profiles.createEmpty(app, name.ifBlank { "Profile" })
                    }
                    busy = false
                    status = if (result.isSuccess) {
                        msgCreated
                    } else {
                        msgCreateFailed
                    }
                }
            },
            onDismiss = { naming = false },
        )
    }

    renameTarget?.let { target ->
        ProfileNameDialog(
            title = tr("Rename profile"),
            hint = tr("Only the name changes — everything inside stays as it is."),
            value = typed,
            confirmLabel = tr("Save"),
            onValueChange = { typed = it },
            onConfirm = {
                val name = typed.trim()
                renameTarget = null
                if (name.isNotBlank()) {
                    scope.launch { runCatching { Profiles.rename(app, target.id, name) } }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("Delete this profile?")) },
            text = {
                Text(
                    tr("Removes") + " \"" + target.name + "\" " + tr(
                        "and everything it holds: its installed extensions, Library, " +
                            "history and settings. The extension files and your " +
                            "downloads stay on the device, and other profiles are " +
                            "untouched."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    busy = true
                    scope.launch {
                        runCatching { Profiles.delete(app, target.id) }
                        busy = false
                        status = msgDeleted
                    }
                }) {
                    Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * What one profile holds, in the user's own words — "12 extensions · 34 saved ·
 * 120 watched", or a sentence when there is nothing in it yet (which is what a
 * profile that has never been used looks like, and worth saying plainly so it
 * does not read as a broken row).
 */
@Composable
private fun summaryText(summary: Profiles.Summary?): String {
    if (summary == null) return ""
    if (summary.isEmpty) return tr("Empty — nothing installed or saved yet")
    val parts = buildList {
        if (summary.extensions > 0) add(summary.extensions.toString() + " " + tr("extensions"))
        if (summary.saved > 0) add(summary.saved.toString() + " " + tr("saved"))
        if (summary.watched > 0) add(summary.watched.toString() + " " + tr("watched"))
    }
    return parts.joinToString(" · ")
}

/** One profile: name, what it holds, and the three things to do with it. */
@Composable
private fun ProfileRow(
    profile: Profiles.Profile,
    active: Boolean,
    summary: String,
    busy: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        onClick = { if (!active) onOpen() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(GlassShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (active) Icons.Filled.Check else Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("In use"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(enabled = !busy, onClick = onRename) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = tr("Rename"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (canDelete) {
                IconButton(enabled = !busy, onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = tr("Delete"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** One name field, used for creating a profile and for renaming one. */
@Composable
private fun ProfileNameDialog(
    title: String,
    hint: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(tr("Profile name")) },
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/** The page's one primary action (the same shape the picker sheets use). */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
