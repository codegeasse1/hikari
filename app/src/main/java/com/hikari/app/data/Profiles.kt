package com.hikari.app.data

import android.content.Context
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.download.DownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Profiles: more than one Hikari on one phone, each as if it were the only one.
 *
 * WHAT A PROFILE IS. A profile is a SNAPSHOT of the whole preferences store —
 * everything the user built: the installed extensions and their configs, the
 * library and its categories, watch history, favourites, collections, repos,
 * signed-in accounts, and every setting — kept in its own file under
 * `filesDir/profiles/`. Switching profiles saves the store into the profile
 * being left and applies the one being entered (see
 * [AppStore.replacePreferences]), so a profile that has never been used is an
 * EMPTY store: a phone that looks like Hikari the day it was installed — no
 * extensions installed, an empty Library, an empty history, default settings.
 * Switch back and everything from that profile is exactly where it was.
 *
 * WHY A SNAPSHOT RATHER THAN A COLUMN ON EVERY SETTING. The whole setup is
 * already one DataStore dump — this is the same payload the backup file carries
 * (see [BackupManager]), which is what makes "carry your setup to another
 * device" work. Scoping each of the ~145 keys individually would mean touching
 * every reader in the app and getting one of them wrong silently; swapping the
 * store is one write and cannot half-apply (DataStore makes the edit atomic).
 *
 * WHAT IS SHARED ON PURPOSE.
 *  * **The extension FILES on disk.** `.cs3`, `.hiki`, Nuvio scrapers, IPTV
 *    playlists and Aniyomi extensions live in `filesDir/...` and are not
 *    duplicated per profile: a profile is a *view* of the setup, and a fresh
 *    profile therefore shows every extension as not installed while the bytes
 *    are still there. Installing it there re-uses/re-downloads the same file,
 *    and — important — removing an extension in one profile does NOT delete a
 *    file another profile still lists (see [otherProfilesReference], which every
 *    uninstall checks first).
 *  * **Downloaded videos.** Same reason: the files are the user's, and a
 *    profile only carries the queue (see [DownloadStore.raw]).
 *  * **The device-local settings** — the app lock, the phone/television layout,
 *    interface scale, this device's performance/first-run flags and the launcher
 *    icon. These describe the MACHINE, not the setup (see [AppStore.DeviceLocal]),
 *    so every profile inherits them: a second profile cannot lock or unlock the
 *    phone, and it cannot change the shape of the screen the other profile is
 *    drawn on.
 *
 * WHAT IS NOT ISOLATED (documented rather than pretended): per-extension plugin
 * data that an extension keeps outside the store (a `.cs3` plugin's own files in
 * `filesDir`, an Aniyomi extension's preferences) is shared, because it belongs
 * to the extension's install rather than to a Hikari profile.
 */
object Profiles {

    /** One profile as the picker prints it. */
    data class Profile(
        val id: String,
        val name: String,
        val createdAt: Long,
    )

    /** `filesDir/profiles` — the registry and one snapshot file per profile. */
    private const val DIR = "profiles"

    /** A snapshot's own marker, so a file that is not one is refused by name. */
    private const val FORMAT = "hikari-profile"
    private const val FORMAT_VERSION = 1

    /** The profile list, and which of them the store currently holds. */
    private const val REGISTRY = "registry.json"

    /** One switch at a time: two of them interleaving would save the wrong
     *  store contents into a profile. */
    private val lock = Mutex()

    private val _all = MutableStateFlow<List<Profile>>(emptyList())

    /** Every profile, oldest first (the registry's own order). */
    val all: StateFlow<List<Profile>> = _all.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)

    /** The profile the store currently holds, or null when profiles are unused. */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun activeProfile(): Profile? = _activeId.value?.let { id -> _all.value.firstOrNull { it.id == id } }

    /** What a profile holds, as counts — the picker prints them (see
     *  [com.hikari.app.ui.screens.ProfilesScreen]). */
    data class Summary(val extensions: Int, val saved: Int, val watched: Int) {
        val isEmpty: Boolean get() = extensions == 0 && saved == 0 && watched == 0
    }

    /** True once the user has more than the implicit single setup. */
    fun inUse(): Boolean = _all.value.isNotEmpty()

    // ------------------------------------------------------------- files --

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun registryFile(ctx: Context): File = File(dir(ctx), REGISTRY)

    private fun snapshotFile(ctx: Context, id: String): File = File(dir(ctx), "$id.json")

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---------------------------------------------------------- registry --

    private fun readRegistry(ctx: Context): JSONObject =
        runCatching { JSONObject(registryFile(ctx).readText()) }.getOrDefault(JSONObject())

    private fun profilesOf(reg: JSONObject): List<Profile> {
        val arr = reg.optJSONArray("profiles") ?: return emptyList()
        val out = ArrayList<Profile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            out.add(
                Profile(
                    id = id,
                    name = o.optString("name").trim().ifBlank { "Profile" },
                    createdAt = o.optLong("createdAt", 0L),
                )
            )
        }
        return out
    }

    private fun writeRegistry(ctx: Context, profiles: List<Profile>, active: String?) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("createdAt", p.createdAt)
            )
        }
        val root = JSONObject()
            .put("version", 1)
            .put("active", active ?: "")
            .put("profiles", arr)
        runCatching { registryFile(ctx).writeText(root.toString()) }
    }

    /** Publishes the registry into the flows every screen reads. */
    private fun publish(ctx: Context, reg: JSONObject) {
        val list = profilesOf(reg)
        _all.value = list
        val active = reg.optString("active").trim().takeIf { it.isNotBlank() }
        _activeId.value = active?.takeIf { id -> list.any { it.id == id } }
    }

    // ------------------------------------------------------- snapshot I/O --

    /** The value as JSON can carry it — [PrefRecord]'s own one-letter codes. */
    private fun valueToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(value.filterIsInstance<String>())
        else -> value
    }

    /**
     * The value as the app's own types again. A set comes back as a [JSONArray]
     * (org.json has no other way to carry a list), and it is turned back into a
     * `List<String>` HERE so [AppStore.applyRecord]'s "ss" branch — the one that
     * writes a `stringSetPreferencesKey` — gets the type it tests for. Reading it
     * straight into a PrefRecord is how a restored string set used to be dropped
     * silently (the tabs the user hid, the extensions they switched off).
     */
    private fun valueFromJson(raw: Any?): Any? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        else -> raw
    }

    private fun recordsToJson(records: List<PrefRecord>): JSONArray {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("key", r.key)
                    .put("type", r.type)
                    .put("value", valueToJson(r.value))
            )
        }
        return arr
    }

    /** The settings in a profile's snapshot; empty for a profile never switched
     *  into (that is exactly what makes a new profile a fresh install). */
    private fun readSnapshot(ctx: Context, id: String): List<PrefRecord> {
        val text = runCatching { snapshotFile(ctx, id).readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optString("format") != FORMAT) return emptyList()
        val arr = root.optJSONArray("prefs") ?: return emptyList()
        val out = ArrayList<PrefRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out.add(PrefRecord(key, o.optString("type"), valueFromJson(o.opt("value"))))
        }
        return out
    }

    private fun readSnapshotDownloads(ctx: Context, id: String): String =
        runCatching {
            snapshotFile(ctx, id).takeIf { it.isFile }?.readText()?.let { JSONObject(it).optString("downloads") }
        }.getOrNull().orEmpty()

    private suspend fun saveSnapshot(ctx: Context, app: HikariApp, id: String) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("app", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("savedAt", System.currentTimeMillis())
            .put("prefs", recordsToJson(app.store.snapshotPreferences()))
            .put("downloads", runCatching { DownloadStore.raw(ctx) }.getOrDefault(""))
        runCatching { snapshotFile(ctx, id).writeText(root.toString()) }
            .onFailure { Logs.log("Profiles", "✗ could not save profile $id (${it.javaClass.simpleName})") }
    }

    // ------------------------------------------------------------- public --

    /**
     * Reads the registry, and makes the store agree with it.
     *
     * The agreement check is the point: the registry lives in the app's files
     * directory and the settings live in the preferences file (see
     * [AppStore.K.PROFILE_TAG]), and the two can be separated by something that
     * only clears one of them — "Clear all data" in Settings wipes the store and
     * leaves the profiles, and a backup restore replaces the store wholesale. In
     * both cases the app would otherwise come up with the wrong (or an empty)
     * setup under a profile whose name says otherwise, so a mismatch re-applies
     * the active profile's snapshot.
     *
     * Safe and cheap when profiles have never been used: no registry, no
     * mismatch, nothing happens.
     */
    suspend fun load(app: HikariApp) = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        publish(ctx, reg)
        val active = _activeId.value ?: return@withLock
        val tag = runCatching { app.store.profileTag() }.getOrDefault("")
        if (tag == active) return@withLock
        Logs.log("Profiles", "store does not hold \"${activeProfile()?.name ?: active}\" — applying it")
        applySnapshot(app, active)
    }

    /**
     * Makes the CURRENT setup a profile (the first time the picker is opened, so
     * the user's existing Hikari is the one they can always come back to) and
     * marks it active without touching the store.
     */
    suspend fun adopt(app: HikariApp, name: String): Profile = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        val list = profilesOf(reg)
        val profile = Profile(newId(), name.trim().ifBlank { "Default" }, System.currentTimeMillis())
        saveSnapshot(ctx, app, profile.id)
        runCatching { app.store.setProfileTag(profile.id) }
        writeRegistry(ctx, list + profile, profile.id)
        publish(ctx, readRegistry(ctx))
        Logs.log("Profiles", "adopted the current setup as \"${profile.name}\"")
        profile
    }

    /**
     * Adds an empty profile AND switches into it: "create" means "start a new
     * one now", and the caller is a user who has just tapped New profile. The
     * profile being left is saved on the way out, so nothing is lost.
     */
    suspend fun createEmpty(app: HikariApp, name: String): Profile {
        val profile = lock.withLock {
            val ctx = app
            val reg = readRegistry(ctx)
            val list = profilesOf(reg)
            val created = Profile(newId(), name.trim().ifBlank { "New profile" }, System.currentTimeMillis())
            // The registry keeps the OLD active id here on purpose: the switch
            // below is what saves the profile being left (see [switchLocked]).
            // Marking the new profile active first would make that save a no-op
            // and lose the setup the user was just using.
            writeRegistry(ctx, list + created, _activeId.value)
            publish(ctx, readRegistry(ctx))
            created
        }
        switchTo(app, profile.id)
        return profile
    }

    /** Renames one profile. */
    suspend fun rename(app: HikariApp, id: String, name: String) = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        val next = profilesOf(reg).map { p -> if (p.id == id) p.copy(name = name.trim().ifBlank { p.name }) else p }
        writeRegistry(ctx, next, _activeId.value)
        publish(ctx, readRegistry(ctx))
    }

    /**
     * Switches to [id]: the profile being left is saved (its whole setup) and the
     * one being entered is applied. Everything already on disk — the extension
     * files, the downloads, this device's layout and lock — stays where it is.
     */
    suspend fun switchTo(app: HikariApp, id: String) = lock.withLock {
        switchLocked(app, id)
    }

    private suspend fun switchLocked(app: HikariApp, id: String) {
        val ctx = app
        val reg = readRegistry(ctx)
        val list = profilesOf(reg)
        val target = list.firstOrNull { it.id == id } ?: return
        val current = reg.optString("active").trim().takeIf { it.isNotBlank() }?.takeIf { it != id }
        // The setup being left is saved BEFORE anything is applied. Order matters:
        // reading it after the store had been replaced would save the new
        // profile's settings under the old profile's name.
        if (current != null) saveSnapshot(ctx, app, current)
        applySnapshot(app, id)
        writeRegistry(ctx, list, id)
        publish(ctx, readRegistry(ctx))
        if (current != null) {
            Logs.log(
                "Profiles",
                "switched to \"${target.name}\" (left \"${list.firstOrNull { it.id == current }?.name ?: current}\")",
            )
        } else {
            Logs.log("Profiles", "using \"${target.name}\"")
        }
    }

    /** Applies one profile's snapshot to the store, downloads and live state. */
    private suspend fun applySnapshot(app: HikariApp, id: String) {
        val ctx = app
        val records = withContext(Dispatchers.IO) { readSnapshot(ctx, id) }
        val downloads = withContext(Dispatchers.IO) { readSnapshotDownloads(ctx, id) }
        val applied = runCatching { app.store.replacePreferences(records) }.getOrDefault(0)
        runCatching { app.store.setProfileTag(id) }
        runCatching {
            withContext(Dispatchers.IO) {
                if (applied == 0) DownloadStore.writeRaw(ctx, "") else DownloadStore.writeRaw(ctx, downloads)
            }
        }
        // Everything read once at startup rather than per use (the language, the
        // ad-block selectors, the WebView UA, the DNS mode, the extension
        // instances) has to be re-read, exactly as after a backup restore.
        runCatching { BackupManager.refreshLiveState(app) }
        Logs.log("Profiles", "applied ${records.size} setting(s) from profile $id")
    }

    /**
     * Removes a profile: its file and its registry entry. If it is the active
     * one, the app moves to another (or, when it was the last, back to a single
     * implicit setup, leaving the store as it is).
     */
    suspend fun delete(app: HikariApp, id: String) = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        val list = profilesOf(reg)
        if (list.none { it.id == id }) return@withLock
        val remaining = list.filterNot { it.id == id }
        val wasActive = _activeId.value == id
        // Apply the replacement BEFORE the registry forgets the old one, so a
        // failure leaves a consistent pair rather than an empty app.
        if (wasActive && remaining.isNotEmpty()) applySnapshot(app, remaining.first().id)
        writeRegistry(ctx, remaining, remaining.firstOrNull()?.id)
        runCatching { snapshotFile(ctx, id).delete() }
        publish(ctx, readRegistry(ctx))
        if (wasActive && remaining.isNotEmpty()) runCatching { app.store.setProfileTag(remaining.first().id) }
        Logs.log("Profiles", "deleted profile \"${list.firstOrNull { it.id == id }?.name ?: id}\"")
    }

    /**
     * What one profile holds, read from its own snapshot — the very settings
     * switching to it would bring back, so the picker can say "12 extensions,
     * 34 saved, 120 watched" instead of showing two names and a guess.
     *
     * Counted by parsing just the three lists that are stored as JSON strings
     * (the installed providers, the favourites, the history); everything else in
     * the snapshot is ignored, so this reads one file and allocates nothing big.
     */
    suspend fun summaryOf(ctx: Context, id: String): Summary = withContext(Dispatchers.IO) {
        val text = runCatching { snapshotFile(ctx, id).readText() }.getOrNull()
            ?: return@withContext Summary(0, 0, 0)
        val arr = runCatching { JSONObject(text).optJSONArray("prefs") }.getOrNull()
            ?: return@withContext Summary(0, 0, 0)
        fun count(key: String): Int {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("key") != key) continue
                // A list is a JSON STRING in the store (the providers/favourites/
                // history keys hold encoded arrays), but a snapshot written by a
                // hand-edited file could carry a real array — accept both.
                val json = when (val v = o.opt("value")) {
                    is String -> v
                    is JSONArray -> v.toString()
                    else -> return 0
                }
                return runCatching { JSONArray(json).length() }.getOrDefault(0)
            }
            return 0
        }
        Summary(count("providers"), count("favorites"), count("history"))
    }

    /** The same counts for the profile IN USE, read from the live store: its
     *  snapshot is only as fresh as the last switch away from it. */
    suspend fun liveSummary(app: HikariApp): Summary = runCatching {
        Summary(
            extensions = app.store.providers().size,
            saved = app.store.favorites().size,
            watched = app.store.history().size,
        )
    }.getOrDefault(Summary(0, 0, 0))

    /**
     * True when a profile OTHER than the active one still lists a local file
     * path — an installed extension's own file.
     *
     * Uninstalling is destructive for the shared files (see
     * `ExtensionsScreen.remove`): it deletes the `.cs3`/`.hiki`/scraper file once
     * no provider references it. With profiles, "no provider references it" has
     * to mean NO PROFILE does — otherwise uninstalling an extension in one
     * profile would silently break it in another. The snapshots are plain JSON
     * (the path is a value in the providers list), so the check is a substring
     * test over the other profiles' files: no parsing, and only the profiles'
     * own size to read.
     */
    fun otherProfilesReference(ctx: Context, path: String): Boolean {
        if (path.isBlank()) return false
        val active = _activeId.value
        for (p in _all.value) {
            if (p.id == active) continue
            val f = snapshotFile(ctx, p.id)
            if (!f.isFile) continue
            if (runCatching { f.readText() }.getOrNull()?.contains(path) == true) return true
        }
        return false
    }
}
