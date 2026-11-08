package com.hikari.app.data

import android.content.Context
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.download.DownloadStore
import com.hikari.app.lock.AppLock
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

object Profiles {

    /** One profile as the picker prints it. */
    data class Profile(
        val id: String,
        val name: String,
        val createdAt: Long,
        val locked: Boolean = false,
        val avatar: String = "",
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

    // ------------------------------------------------------ profile locks --

    private val unlockedIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** True when [id] currently opens without a password. */
    fun isUnlocked(id: String): Boolean = unlockedIds.contains(id)

    /** True when [profile] demands its password right now. */
    fun needsPassword(profile: Profile): Boolean =
        profile.locked && !unlockedIds.contains(profile.id)

    /** Forget every in-process unlock. */
    fun lockAll() {
        unlockedIds.clear()
    }

    fun reLock(id: String) {
        unlockedIds.remove(id)
    }

    /** The outcome of a password attempt (see [unlock]). */
    enum class UnlockResult { OK, WRONG, NO_SECRET }

    suspend fun unlock(app: HikariApp, id: String, password: String): UnlockResult {
        val secret = runCatching { app.store.profileLockSecret(id) }.getOrNull().orEmpty()
        if (secret.isBlank()) {
            lock.withLock {
                setLockedFlag(app, id, false)
                unlockedIds.add(id)
            }
            return UnlockResult.NO_SECRET
        }
        // Deliberately expensive (120k PBKDF2 rounds): off the main thread.
        val ok = withContext(Dispatchers.Default) { AppLock.verify(password, secret) }
        return if (ok) {
            unlockedIds.add(id)
            UnlockResult.OK
        } else UnlockResult.WRONG
    }

    /** Set (or replace) profile [id]'s password. The profile opens at once —
     *  the user just proved they know it by typing it twice. */
    suspend fun setLock(app: HikariApp, id: String, password: String) {
        val secret = withContext(Dispatchers.Default) { AppLock.encode(password) }
        lock.withLock {
            runCatching { app.store.setProfileLockSecret(id, secret) }
            setLockedFlag(app, id, true)
            unlockedIds.add(id)
        }
    }

    /** Remove profile [id]'s password. */
    suspend fun clearLock(app: HikariApp, id: String) {
        lock.withLock {
            runCatching { app.store.setProfileLockSecret(id, "") }
            setLockedFlag(app, id, false)
            unlockedIds.remove(id)
        }
    }

    private fun setLockedFlag(ctx: Context, id: String, locked: Boolean) {
        val reg = readRegistry(ctx)
        val active = reg.optString("active").trim().takeIf { it.isNotBlank() }
        val list = profilesOf(reg).map { if (it.id == id) it.copy(locked = locked) else it }
        writeRegistry(ctx, list, active)
        publish(ctx, readRegistry(ctx))
    }

    // ------------------------------------------------------------- files --

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun registryFile(ctx: Context): File = File(dir(ctx), REGISTRY)

    private fun snapshotFile(ctx: Context, id: String): File = File(dir(ctx), "$id.json")

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ------------------------------------------------------------ avatars --

    private fun avatarsDir(ctx: Context): File = File(dir(ctx), "avatars").apply { mkdirs() }

    /** The stored picture for [avatar] ("file:<name>"), or null. */
    fun avatarFile(ctx: Context, avatar: String): File? {
        val t = avatar.trim()
        if (!t.startsWith("file:")) return null
        val name = t.removePrefix("file:")
        if (name.isBlank() || "/" in name || ".." in name) return null
        return File(avatarsDir(ctx), name).takeIf { it.isFile }
    }

    suspend fun setAvatar(app: HikariApp, id: String, uri: android.net.Uri): String =
        withContext(Dispatchers.IO) {
            val ctx = app
            val marker = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching ""
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runCatching ""
                val bound = 512
                val scale = minOf(1f, bound / maxOf(src.width, src.height).toFloat())
                val bmp = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        src,
                        (src.width * scale).toInt().coerceAtLeast(1),
                        (src.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else src
                val out = File(avatarsDir(ctx), "$id.jpg")
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                if (bmp !== src) runCatching { src.recycle() }
                runCatching { bmp.recycle() }
                "file:$id.jpg"
            }.getOrDefault("")
            if (marker.isNotBlank()) {
                lock.withLock {
                    val reg = readRegistry(ctx)
                    val next = profilesOf(reg).map { p -> if (p.id == id) p.copy(avatar = marker) else p }
                    writeRegistry(ctx, next, _activeId.value)
                    publish(ctx, readRegistry(ctx))
                }
            }
            marker
        }

    suspend fun saveAvatarBitmap(app: HikariApp, id: String, bmp: android.graphics.Bitmap): String =
        withContext(Dispatchers.IO) {
            val ctx = app
            val marker = runCatching {
                val bound = 512
                val scale = minOf(1f, bound / maxOf(bmp.width, bmp.height).toFloat())
                val outBmp = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else bmp
                val out = File(avatarsDir(ctx), "$id.jpg")
                out.outputStream().use { outBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                if (outBmp !== bmp) runCatching { outBmp.recycle() }
                "file:$id.jpg"
            }.getOrDefault("")
            if (marker.isNotBlank()) {
                lock.withLock {
                    val reg = readRegistry(ctx)
                    val next = profilesOf(reg).map { p -> if (p.id == id) p.copy(avatar = marker) else p }
                    writeRegistry(ctx, next, _activeId.value)
                    publish(ctx, readRegistry(ctx))
                }
            }
            marker
        }

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
                    locked = o.optBoolean("locked", false),
                    avatar = o.optString("avatar").trim(),
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
                    .put("locked", p.locked)
                    .put("avatar", p.avatar)
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

    suspend fun saveActive(app: HikariApp) = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        val id = reg.optString("active").trim().takeIf { it.isNotBlank() } ?: return@withLock
        if (profilesOf(reg).none { it.id == id }) return@withLock
        saveSnapshot(ctx, app, id)
    }

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

    suspend fun adopt(app: HikariApp, name: String, avatar: String = ""): Profile = lock.withLock {
        val ctx = app
        val reg = readRegistry(ctx)
        val list = profilesOf(reg)
        val profile = Profile(newId(), name.trim().ifBlank { "Default" }, System.currentTimeMillis(), avatar = avatar.trim())
        saveSnapshot(ctx, app, profile.id)
        runCatching { app.store.setProfileTag(profile.id) }
        writeRegistry(ctx, list + profile, profile.id)
        publish(ctx, readRegistry(ctx))
        Logs.log("Profiles", "adopted the current setup as \"${profile.name}\"")
        profile
    }

    suspend fun createEmpty(app: HikariApp, name: String, avatar: String = ""): Profile {
        val profile = lock.withLock {
            val ctx = app
            val reg = readRegistry(ctx)
            val list = profilesOf(reg)
            val created = Profile(newId(), name.trim().ifBlank { "New profile" }, System.currentTimeMillis(), avatar = avatar.trim())
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

    suspend fun switchTo(app: HikariApp, id: String): Boolean {
        return lock.withLock { switchLocked(app, id) }
    }

    private suspend fun switchLocked(app: HikariApp, id: String): Boolean {
        val ctx = app
        val reg = readRegistry(ctx)
        val list = profilesOf(reg)
        val target = list.firstOrNull { it.id == id } ?: return false
        if (needsPassword(target)) return false
        val current = reg.optString("active").trim().takeIf { it.isNotBlank() }?.takeIf { it != id }
        if (current != null) saveSnapshot(ctx, app, current)
        applySnapshot(app, id)
        writeRegistry(ctx, list, id)
        publish(ctx, readRegistry(ctx))
        synchronized(unlockedIds) { unlockedIds.retainAll(setOf(id)) }
        if (current != null) {
            Logs.log(
                "Profiles",
                "switched to \"${target.name}\" (left \"${list.firstOrNull { it.id == current }?.name ?: current}\")",
            )
        } else {
            Logs.log("Profiles", "using \"${target.name}\"")
        }
        return true
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
        runCatching { BackupManager.refreshLiveState(app) }
        Logs.log("Profiles", "applied ${records.size} setting(s) from profile $id")
    }

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
        runCatching { File(File(dir(ctx), "avatars"), "$id.jpg").delete() }
        publish(ctx, readRegistry(ctx))
        if (wasActive && remaining.isNotEmpty()) runCatching { app.store.setProfileTag(remaining.first().id) }
        Logs.log("Profiles", "deleted profile \"${list.firstOrNull { it.id == id }?.name ?: id}\"")
    }

    suspend fun summaryOf(ctx: Context, id: String): Summary = withContext(Dispatchers.IO) {
        val text = runCatching { snapshotFile(ctx, id).readText() }.getOrNull()
            ?: return@withContext Summary(0, 0, 0)
        val arr = runCatching { JSONObject(text).optJSONArray("prefs") }.getOrNull()
            ?: return@withContext Summary(0, 0, 0)
        fun count(key: String): Int {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("key") != key) continue
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
