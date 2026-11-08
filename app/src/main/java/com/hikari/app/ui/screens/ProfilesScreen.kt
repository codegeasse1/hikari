package com.hikari.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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

@Composable
fun ProfilesScreen(app: HikariApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Profiles position held across visits (see ScrollKeeper).
    val listState = com.hikari.app.ui.components.keptListState("profiles")

    val profiles by Profiles.all.collectAsState()
    val activeId by Profiles.activeId.collectAsState()

    val msgNowUsing = tr("Now using")
    val msgOtherKept = tr("The other setup is still here — switch back any time.")
    val msgSwitchFailed = tr("Could not switch profile. The current one is unchanged.")
    val msgLockedFirst = tr("That profile is locked — enter its password first.")
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
    // Creation-sheet picture: a built-in gradient marker ("grad:N"), or a
    // storage photo picked below (applied after the profile exists). A picked
    // photo always passes through the crop sheet first (pinch to zoom, drag to
    // fit); the cropped square is what the preview shows and what gets stored.
    var namingAvatar by remember { mutableStateOf("") }
    var namingPhoto by remember { mutableStateOf<Uri?>(null) }
    var namingCropped by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var namingCropUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) namingCropUri = uri
    }
    fun openNaming(defaultName: String) {
        typed = defaultName
        namingAvatar = ""
        namingPhoto = null
        namingCropped = null
        namingCropUri = null
        naming = true
    }
    // The app-lock gate over this page: when an app lock is set, creating,
    // renaming, deleting or SWITCHING a profile asks for the lock's password
    // first — otherwise anyone holding the unlocked phone could open or change
    // a profile that is not theirs. The pending action runs only after the
    // password verifies; nothing about the password is kept.
    var gateOpen by remember { mutableStateOf(false) }
    var gatePassword by remember { mutableStateOf("") }
    var gateWrong by remember { mutableStateOf(false) }
    var gateBusy by remember { mutableStateOf(false) }
    var gateSecret by remember { mutableStateOf("") }
    var pendingGate by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun askGate(action: () -> Unit) {
        scope.launch {
            val on = runCatching { app.store.appLock() }.getOrDefault(false)
            val secret = runCatching { app.store.appLockSecret() }.getOrDefault("")
            if (on && secret.isNotBlank()) {
                pendingGate = action
                gateSecret = secret
                gatePassword = ""
                gateWrong = false
                gateOpen = true
            } else {
                action()
            }
        }
    }
    var profileGateTarget by remember { mutableStateOf<Profiles.Profile?>(null) }
    var profileGateAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var profileGateIsSwitch by remember { mutableStateOf(false) }
    var profileGatePw by remember { mutableStateOf("") }
    var profileGateWrong by remember { mutableStateOf(false) }
    var profileGateBusy by remember { mutableStateOf(false) }
    // The set/change/remove-password sheet, and its fields.
    var lockSetupTarget by remember { mutableStateOf<Profiles.Profile?>(null) }
    var lockCurrent by remember { mutableStateOf("") }
    var lockNew by remember { mutableStateOf("") }
    var lockConfirm by remember { mutableStateOf("") }
    var lockApplyAll by remember { mutableStateOf(false) }
    var lockBusy by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf("") }
    fun resetLockSheet() {
        lockCurrent = ""
        lockNew = ""
        lockConfirm = ""
        lockApplyAll = false
        lockBusy = false
        lockError = ""
    }
    fun askProfileGate(profile: Profiles.Profile, isSwitch: Boolean = false, action: () -> Unit) {
        if (Profiles.needsPassword(profile)) {
            profileGateTarget = profile
            profileGateAction = action
            profileGateIsSwitch = isSwitch
            profileGatePw = ""
            profileGateWrong = false
        } else {
            action()
        }
    }
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

    LaunchedEffect(Unit) { runCatching { Profiles.load(app) } }

    BackHandler { onBack() }

    fun switchTo(profile: Profiles.Profile) {
        if (profile.id == activeId || busy) return
        busy = true
        status = ""
        scope.launch {
            val result = runCatching { Profiles.switchTo(app, profile.id) }
            busy = false
            val moved = result.getOrDefault(false)
            status = if (moved) {
                msgNowUsing + " \"" + profile.name + "\". " + msgOtherKept
            } else if (result.isFailure) {
                msgSwitchFailed
            } else {
                msgLockedFirst
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
                            askGate { openNaming(msgDefaultName) }
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
                    canDelete = profiles.size > 1,
                    onOpen = { askProfileGate(profile, isSwitch = true) { switchTo(profile) } },
                    onRename = {
                        askProfileGate(profile) {
                            typed = profile.name
                            renameTarget = profile
                        }
                    },
                    onDelete = { askProfileGate(profile) { deleteTarget = profile } },
                    onLock = {
                        askProfileGate(profile) {
                            resetLockSheet()
                            lockSetupTarget = profile
                        }
                    },
                )
            }
            item(key = "profiles-new") {
                GlassCard(
                    onClick = {
                        askGate { openNaming("") }
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
        ProfileCreateDialog(
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
            onValueChange = { typed = it },
            avatar = namingAvatar,
            onAvatarChange = {
                namingAvatar = it
                namingPhoto = null
                namingCropped = null
            },
            photoUri = namingPhoto,
            cropped = namingCropped,
            onPickPhoto = { photoPicker.launch("image/*") },
            onClearPhoto = { namingPhoto = null; namingCropped = null },
            confirmLabel = if (profiles.isEmpty()) tr("Save") else tr("Create"),
            onConfirm = {
                val name = typed.trim()
                val grad = namingAvatar
                val photo = namingPhoto
                val cropped = namingCropped
                naming = false
                namingPhoto = null
                namingCropped = null
                namingCropUri = null
                busy = true
                scope.launch {
                    val result = runCatching {
                        val created = if (profiles.isEmpty()) Profiles.adopt(app, name.ifBlank { "Default" }, grad)
                        else Profiles.createEmpty(app, name.ifBlank { "Profile" }, grad)
                        if (cropped != null) runCatching { Profiles.saveAvatarBitmap(app, created.id, cropped) }
                        else if (photo != null) runCatching { Profiles.setAvatar(app, created.id, photo) }
                        created
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
    // Picked photo -> square crop sheet (pinch to zoom, drag to fit). It sits
    // above the creation sheet; confirming stores the cropped square for the
    // preview and the later save, cancelling keeps the previous picture.
    namingCropUri?.let { cropUri ->
        ProfileCropDialog(
            uri = cropUri,
            onConfirm = { bmp ->
                namingCropped = bmp
                namingPhoto = null
                namingAvatar = ""
                namingCropUri = null
            },
            onDismiss = { namingCropUri = null },
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

    if (gateOpen) {
        ProfileLockDialog(
            value = gatePassword,
            onValueChange = {
                gatePassword = it
                gateWrong = false
            },
            wrong = gateWrong,
            busy = gateBusy,
            onUnlock = {
                if (gateBusy) return@ProfileLockDialog
                gateBusy = true
                val pw = gatePassword
                val sec = gateSecret
                val run = pendingGate
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        com.hikari.app.lock.AppLock.verify(pw, sec)
                    }
                    gateBusy = false
                    if (ok) {
                        gateOpen = false
                        gatePassword = ""
                        pendingGate = null
                        run?.invoke()
                    } else {
                        gateWrong = true
                    }
                }
            },
            onDismiss = {
                if (!gateBusy) {
                    gateOpen = false
                    gatePassword = ""
                    pendingGate = null
                }
            },
        )
    }

    // The per-profile password prompt: opening, renaming, deleting or
    // re-locking a profile that has its own password.
    profileGateTarget?.let { target ->
        ProfileLockDialog(
            title = tr("Enter the password for") + " \"" + target.name + "\"",
            value = profileGatePw,
            onValueChange = {
                profileGatePw = it
                profileGateWrong = false
            },
            wrong = profileGateWrong,
            busy = profileGateBusy,
            onUnlock = {
                if (profileGateBusy) return@ProfileLockDialog
                profileGateBusy = true
                val pw = profileGatePw
                scope.launch {
                    val res = runCatching { Profiles.unlock(app, target.id, pw) }.getOrNull()
                    profileGateBusy = false
                    if (res == Profiles.UnlockResult.OK || res == Profiles.UnlockResult.NO_SECRET) {
                        profileGateTarget = null
                        profileGatePw = ""
                        val run = profileGateAction
                        val wasSwitch = profileGateIsSwitch
                        profileGateAction = null
                        profileGateIsSwitch = false
                        run?.invoke()
                        if (!wasSwitch && target.id != Profiles.activeId.value) {
                            Profiles.reLock(target.id)
                        }
                    } else {
                        profileGateWrong = true
                    }
                }
            },
            onDismiss = {
                if (!profileGateBusy) {
                    profileGateTarget = null
                    profileGatePw = ""
                    profileGateAction = null
                    profileGateIsSwitch = false
                }
            },
        )
    }

    // Set, change or remove one profile's OWN password — never the app lock's.
    // "Use the same password for every profile" writes it onto all of them;
    // otherwise only this profile changes.
    lockSetupTarget?.let { target ->
        val canSave = !lockBusy && lockNew.length >= 4 && lockNew == lockConfirm &&
            (!target.locked || lockCurrent.isNotBlank())
        // Hoisted: `tr` is @Composable and the save/remove below run in a
        // coroutine, where composable calls are illegal.
        val errWrongPw = tr("Wrong password — try again.")
        val errSaveFail = tr("Could not save it — try again.")
        val msgAllSet = tr("Every profile now uses the new password.")
        val msgOneSet = tr("Password set for")
        val msgRemoved = tr("Password removed from")
        AlertDialog(
            onDismissRequest = { if (!lockBusy) lockSetupTarget = null },
            title = {
                Text(
                    if (target.locked) tr("Change the password for") + " \"" + target.name + "\""
                    else tr("Lock") + " \"" + target.name + "\" " + tr("with a password")
                )
            },
            text = {
                Column {
                    if (target.locked) {
                        OutlinedTextField(
                            value = lockCurrent,
                            onValueChange = { lockCurrent = it; lockError = "" },
                            singleLine = true,
                            enabled = !lockBusy,
                            label = { Text(tr("Current password")) },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = lockNew,
                        onValueChange = { lockNew = it; lockError = "" },
                        singleLine = true,
                        enabled = !lockBusy,
                        label = { Text(tr("New password (4+ characters)")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockConfirm,
                        onValueChange = { lockConfirm = it; lockError = "" },
                        singleLine = true,
                        enabled = !lockBusy,
                        label = { Text(tr("Repeat the new password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = lockConfirm.isNotBlank() && lockNew != lockConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = lockApplyAll,
                            enabled = !lockBusy,
                            onCheckedChange = { lockApplyAll = it },
                        )
                        Text(
                            tr("Use the same password for every profile"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lockError.isNotBlank()) {
                        Text(
                            lockError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (!LocalHideHelp.current) {
                        Text(
                            tr("Only this profile asks for it — the app lock keeps its own password."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = canSave, onClick = {
                    scope.launch {
                        lockBusy = true
                        lockError = ""
                        // A locked profile proves the current password first.
                        if (target.locked) {
                            val res = runCatching { Profiles.unlock(app, target.id, lockCurrent) }.getOrNull()
                            if (res != Profiles.UnlockResult.OK && res != Profiles.UnlockResult.NO_SECRET) {
                                lockBusy = false
                                lockError = errWrongPw
                                return@launch
                            }
                        }
                        val fail = runCatching {
                            Profiles.setLock(app, target.id, lockNew)
                            if (lockApplyAll) {
                                for (p in profiles) {
                                    if (p.id != target.id) Profiles.setLock(app, p.id, lockNew)
                                }
                            }
                        }.isFailure
                        lockBusy = false
                        if (fail) {
                            lockError = errSaveFail
                        } else {
                            lockSetupTarget = null
                            status = if (lockApplyAll) msgAllSet
                            else msgOneSet + " \"" + target.name + "\"."
                        }
                    }
                }) {
                    Text(if (lockBusy) tr("Saving…") else tr("Save"))
                }
            },
            dismissButton = {
                Row {
                    if (target.locked) {
                        TextButton(enabled = !lockBusy, onClick = {
                            scope.launch {
                                lockBusy = true
                                val res = runCatching { Profiles.unlock(app, target.id, lockCurrent) }.getOrNull()
                                lockBusy = false
                                if (res == Profiles.UnlockResult.OK || res == Profiles.UnlockResult.NO_SECRET) {
                                    runCatching { Profiles.clearLock(app, target.id) }
                                    lockSetupTarget = null
                                    status = msgRemoved + " \"" + target.name + "\"."
                                } else {
                                    lockError = errWrongPw
                                }
                            }
                        }) {
                            Text(tr("Remove"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(enabled = !lockBusy, onClick = { lockSetupTarget = null }) {
                        Text(tr("Cancel"))
                    }
                }
            },
        )
    }
}

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

/** Built-in picture tiles: deterministic, no storage, no network. */
private val ProfileGradients = listOf(
    Color(0xFF7C4DFF) to Color(0xFF448AFF),
    Color(0xFF00BFA5) to Color(0xFF64DD17),
    Color(0xFFFF6D00) to Color(0xFFFF1744),
    Color(0xFFD81B60) to Color(0xFF7C4DFF),
    Color(0xFF0288D1) to Color(0xFF26C6DA),
    Color(0xFFFFB300) to Color(0xFFFF5722),
    Color(0xFF43A047) to Color(0xFFC0CA33),
    Color(0xFF546E7A) to Color(0xFF303F9F),
)

private fun gradientFor(marker: String): Pair<Color, Color>? {
    val n = marker.removePrefix("grad:").toIntOrNull() ?: return null
    if (n !in ProfileGradients.indices) return null
    return ProfileGradients[n]
}

/**
 * One profile's picture: the storage photo, a gradient tile, or the initial
 * tile. An active row keeps a thin primary ring so "in use" still reads.
 */
@Composable
private fun ProfileAvatar(
    profile: Profiles.Profile,
    name: String,
    active: Boolean,
    size: Dp = 44.dp,
    photoUri: Uri? = null,
    cropped: android.graphics.Bitmap? = null,
) {
    val context = LocalContext.current
    val file = remember(profile.avatar) { Profiles.avatarFile(context, profile.avatar) }
    val grad = gradientFor(profile.avatar)
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val ring = if (active) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else Modifier
    when {
        cropped != null -> Image(
            bitmap = cropped.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).then(ring),
        )
        photoUri != null -> AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).then(ring),
        )
        file != null -> AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).then(ring),
        )
        grad != null -> Box(
            Modifier.size(size).clip(CircleShape).then(ring)
                .background(Brush.linearGradient(listOf(grad.first, grad.second))),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
        else -> Box(
            Modifier.size(size).clip(CircleShape).then(ring)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(initial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ProfileCreateDialog(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    avatar: String,
    onAvatarChange: (String) -> Unit,
    photoUri: Uri?,
    cropped: android.graphics.Bitmap? = null,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(
                        profile = Profiles.Profile(id = "", name = value.ifBlank { "?" }, createdAt = 0L, avatar = avatar),
                        name = value.ifBlank { "?" },
                        active = false,
                        size = 64.dp,
                        photoUri = photoUri,
                        cropped = cropped,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Pick a picture"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            tr("A tile, or a photo from storage."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val hasPhoto = photoUri != null || cropped != null
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileGradients.take(5).forEachIndexed { i, g ->
                            val marker = "grad:$i"
                            val selected = !hasPhoto && avatar == marker
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(g.first, g.second)))
                                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                    .clickable { onAvatarChange(marker) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileGradients.drop(5).forEachIndexed { k, g ->
                            val i = k + 5
                            val marker = "grad:$i"
                            val selected = !hasPhoto && avatar == marker
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(g.first, g.second)))
                                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                    .clickable { onAvatarChange(marker) },
                            )
                        }
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .then(if (hasPhoto) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .clickable { onPickPhoto() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hasPhoto) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    IconButton(onClick = onClearPhoto, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = tr("Remove"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                                    }
                                }
                            } else {
                                Icon(Icons.Filled.PhotoLibrary, contentDescription = tr("Choose from storage"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
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

@Composable
private fun ProfileCropDialog(
    uri: Uri,
    onConfirm: (android.graphics.Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val src = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                android.graphics.BitmapFactory.decodeStream(ins)
            }
        }.getOrNull()
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        val max = 220f * scale
        offset = androidx.compose.ui.geometry.Offset(
            (offset.x + panChange.x).coerceIn(-max, max),
            (offset.y + panChange.y).coerceIn(-max, max),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Crop picture")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (src == null) {
                    Text(tr("Could not read that photo."))
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .transformable(transform),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = src.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Pinch to zoom, drag to fit — the circle is the picture."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = src != null,
                onClick = {
                    val bmp = src ?: return@TextButton
                    val sideFull = minOf(bmp.width, bmp.height)
                    // Zoomed-in preview shows a smaller center region enlarged:
                    // crop that region so the saved picture matches the preview.
                    val side = (sideFull / scale).toInt().coerceIn(64, sideFull)
                    var cx = (bmp.width / 2f) + (-offset.x / scale)
                    var cy = (bmp.height / 2f) + (-offset.y / scale)
                    cx = cx.coerceIn(side / 2f, (bmp.width - side / 2f).coerceAtLeast(side / 2f))
                    cy = cy.coerceIn(side / 2f, (bmp.height - side / 2f).coerceAtLeast(side / 2f))
                    val out = runCatching {
                        android.graphics.Bitmap.createBitmap(bmp, (cx - side / 2f).toInt().coerceAtLeast(0), (cy - side / 2f).toInt().coerceAtLeast(0), side.coerceAtMost(bmp.width - (cx - side / 2f).toInt().coerceAtLeast(0)), side.coerceAtMost(bmp.height - (cy - side / 2f).toInt().coerceAtLeast(0)))
                    }.getOrNull() ?: bmp
                    onConfirm(out)
                },
            ) { Text(tr("Crop")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
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
    onLock: () -> Unit,
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
            ProfileAvatar(
                profile = profile,
                name = profile.name,
                active = active,
                size = 44.dp,
            )
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
            IconButton(enabled = !busy, onClick = onLock) {
                Icon(
                    if (profile.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (profile.locked) {
                        tr("Locked — change or remove the password")
                    } else {
                        tr("Lock this profile with its own password")
                    },
                    tint = if (profile.locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** The app-lock password prompt that guards creating, renaming, deleting and
 *  opening a profile. The password itself is never stored — it is verified
 *  against the lock's derivation and dropped (see
 *  [com.hikari.app.lock.AppLock]). */
@Composable
private fun ProfileLockDialog(
    title: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    wrong: Boolean,
    busy: Boolean,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: tr("Enter your app-lock password")) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(tr("Password")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = wrong,
                    supportingText = {
                        if (wrong) {
                            Text(
                                tr("Wrong password — try again."),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Only the person who set this password can create, change or open a profile."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank() && !busy, onClick = onUnlock) {
                Text(if (busy) tr("Checking…") else tr("Unlock"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
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
