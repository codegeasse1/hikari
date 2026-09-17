package com.hikari.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.net.ExtensionVerifyGuard
import com.hikari.app.net.NetTuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One file that carries a Hikari install: the preferences store plus the
 * extension/scraper files on disk — i.e. everything the user actually built up
 * over time (installed sources, repos, per-provider settings, history,
 * favourites, appearance, playback preferences).
 *
 * Why a generic dump of the whole preferences store instead of a curated list:
 * the setup lives in ONE DataStore (`hikari` — see AppStore), and a curated list
 * silently drops every key added after the list was written. See
 * [AppStore.snapshotPreferences].
 *
 * What is deliberately NOT in a backup:
 *  * offline copies and exported videos — those are gigabytes, they are visible
 *    in Downloads/Playback and re-downloadable, and a restored queue pointing at
 *    files this device does not have would be worse than no queue at all;
 *  * the poster/artwork/adblock caches — pure caches, re-fetched on demand;
 *  * the download queue store (`hikari_downloads`) — for the first reason.
 *
 * The format is a plain JSON object so a support chat can be told "open it in a
 * text editor" and the user can see it contains no video and no passwords.
 */
object BackupManager {

    /** Marks a file as ours — a restore refuses anything without this. */
    private const val FORMAT = "hikari-backup"

    /** Bumped only for a change a previous version cannot read. A restore of a
     *  NEWER format is refused by name instead of silently restoring half of
     *  it. */
    private const val FORMAT_VERSION = 1

    /**
     * The only directories a restore may write into, relative to `filesDir`:
     * `<files>/cs3/*` and `<files>/hiki/*` are the installed extensions,
     * `<files>/nuvio/scrapers/*` and `<files>/nuvio/settings/*` the Nuvio
     * scrapers and their per-provider settings.
     *
     * This is the security boundary of the whole feature: a backup file is
     * something the user picked from anywhere (a download, a chat attachment),
     * so a restored path is never trusted — it must land under one of these
     * roots, and every segment is checked for traversal below. A backup can
     * therefore only ever replace extensions and their settings, never write an
     * arbitrary file into the app's storage.
     */
    private val FILE_ROOTS = listOf("cs3", "hiki", "nuvio/scrapers", "nuvio/settings")

    /** Nothing legitimate in those directories is anywhere near this big; the
     *  cap keeps a corrupt/hostile file from being expanded into the app's
     *  storage. */
    private const val MAX_FILE_BYTES = 12L * 1024L * 1024L

    data class Report(
        val ok: Boolean,
        /** One line for the user ("Restored." / "That file is not a Hikari backup."). */
        val message: String,
        /** Counts and the first failure, for the status line and the log. */
        val detail: String = "",
    )

    /** `hikari-backup-2026-09-17-1318.json` — sorted by name = sorted by date. */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        "hikari-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".json"

    // ------------------------------------------------------------- backup --

    /** Builds the backup. Runs the file walk on IO; throws only on something
     *  genuinely broken (the caller reports it). */
    suspend fun export(app: HikariApp): ByteArray = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", FORMAT_VERSION)
        root.put("app", BuildConfig.VERSION_NAME)
        root.put("code", BuildConfig.VERSION_CODE)
        root.put("createdAt", System.currentTimeMillis())

        val prefs = JSONArray()
        for (r in app.store.snapshotPreferences()) {
            prefs.put(
                JSONObject().apply {
                    put("key", r.key)
                    put("type", r.type)
                    put("value", r.value ?: JSONObject.NULL)
                }
            )
        }
        root.put("prefs", prefs)

        val files = JSONArray()
        for ((rel, bytes) in collectFiles(app.filesDir)) {
            files.put(
                JSONObject().apply {
                    put("path", rel)
                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            )
        }
        root.put("files", files)
        root.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Every extension/scraper file, as `(relative path, bytes)`.
     *
     * Walks the four [FILE_ROOTS] rather than listing known extensions so a
     * plugin family added later is carried automatically; a file that cannot be
     * read is skipped rather than failing the whole backup (a locked or
     * half-written file must not cost the user their settings).
     */
    private fun collectFiles(filesDir: File): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        for (relRoot in FILE_ROOTS) {
            val dir = File(filesDir, relRoot)
            if (!dir.isDirectory) continue
            val walk = ArrayDeque<File>()
            walk.addLast(dir)
            while (walk.isNotEmpty()) {
                val d = walk.removeFirst()
                val children = runCatching { d.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        walk.addLast(child)
                        continue
                    }
                    if (child.length() <= 0L || child.length() > MAX_FILE_BYTES) continue
                    val rel = child.relativeTo(filesDir).invariantSeparatorsPath
                    val bytes = runCatching { child.readBytes() }.getOrNull() ?: continue
                    out.add(rel to bytes)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ restore --

    /**
     * Applies a backup file: preferences first (so the source list is right when
     * the extensions land), then the extension files, then the in-memory copies
     * of the settings that are read once at startup instead of per use.
     *
     * Returns a [Report]; it never throws, because every failure here is
     * something the user must be told in plain words ("that file is not a Hikari
     * backup") rather than a crash or an empty screen.
     */
    suspend fun restore(app: HikariApp, bytes: ByteArray): Report = withContext(Dispatchers.IO) {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return@withContext Report(false, "Could not read that file.")
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext Report(false, "That file is not a Hikari backup.")
        if (root.optString("format") != FORMAT) {
            return@withContext Report(false, "That file is not a Hikari backup.")
        }
        val version = root.optInt("version", 0)
        if (version > FORMAT_VERSION) {
            return@withContext Report(
                false,
                "That backup was made by a newer Hikari (format $version). Update the app first.",
            )
        }

        val records = ArrayList<PrefRecord>()
        val prefsArr = root.optJSONArray("prefs")
        for (i in 0 until (prefsArr?.length() ?: 0)) {
            val o = prefsArr?.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            val raw = o.opt("value")
            records.add(PrefRecord(key, o.optString("type"), if (raw == null || raw == JSONObject.NULL) null else raw))
        }
        runCatching { app.store.restorePreferences(records) }.onFailure {
            return@withContext Report(false, "Could not restore your settings.", it.message.orEmpty())
        }

        var written = 0
        var skipped = 0
        val filesArr = root.optJSONArray("files")
        val fromApp = root.optString("app").ifBlank { "another Hikari" }
        for (i in 0 until (filesArr?.length() ?: 0)) {
            val o = filesArr?.optJSONObject(i) ?: continue
            val rel = o.optString("path")
            if (!allowedPath(rel)) {
                skipped++
                continue
            }
            val data = runCatching { Base64.decode(o.optString("data"), Base64.NO_WRAP) }.getOrNull()
            if (data == null || data.isEmpty() || data.size.toLong() > MAX_FILE_BYTES) {
                skipped++
                continue
            }
            val target = File(app.filesDir, rel)
            target.parentFile?.mkdirs()
            if (runCatching { target.writeBytes(data) }.isSuccess) written++ else skipped++
        }

        refreshLiveState(app)

        val detail = "settings: ${records.size} · files: $written" +
            (if (skipped > 0) " · skipped: $skipped" else "") +
            " · from $fromApp " + root.optInt("code", 0)
        Logs.log("Backup", "restore ok — $detail")
        Report(
            true,
            if (written > 0) "Restored. Your sources are reloading." else "Restored your settings.",
            detail,
        )
    }

    /**
     * A restore happens under a running app, and several settings are copied
     * into plain fields/globals once at startup rather than read per use (the
     * element blocker's selectors, the WebView UA, slow-connection timeouts, the
     * UI language, the extension-verification guard). Without this the restored
     * values would look like they "did not take" until the next launch.
     */
    private suspend fun refreshLiveState(app: HikariApp) {
        runCatching { app.elementBlocks = app.store.elementBlocks() }
        runCatching {
            app.webViewUseDefaultUa = app.store.webviewUseDefaultUa()
            app.webViewCustomUa = app.store.webviewCustomUa()
        }
        runCatching { NetTuning.setSlowConnection(app.store.slowConnection()) }
        runCatching { com.hikari.app.ui.LanguageManager.apply(app.store.language()) }
        // The restored pref may be the permissive one; re-assert it against the
        // extensions' own switches (see ExtensionVerifyGuard).
        runCatching { ExtensionVerifyGuard.apply(app, app.store.extensionVerifyWebview()) }
        // Re-read the source list and load the restored plugin files. The
        // in-memory plugin cache is keyed by file path, so a restored file that
        // replaces a DIFFERENT version of the same extension keeps the cached
        // instance for this session — the next launch picks up the new bytes.
        runCatching { app.providers.refresh() }
    }

    /**
     * True when [rel] is a safe path under one of [FILE_ROOTS]: relative, no
     * `..`, no absolute/`~` prefix, no drive or scheme separator — i.e. it can
     * only ever resolve to a file inside the app's own extension directories.
     */
    private fun allowedPath(rel: String): Boolean {
        if (rel.isBlank() || rel.length > 200) return false
        if (rel.startsWith("/") || rel.startsWith("~") || rel.contains('\\') || rel.contains(':')) return false
        val parts = rel.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val root = FILE_ROOTS.firstOrNull { rel == it || rel.startsWith("$it/") } ?: return false
        // nn must have something under the root: bare "cs3" is not a file.
        return rel != root
    }

    // ------------------------------------------------------- saving to disk --

    /**
     * Copies [bytes] into the phone's public Downloads folder (a MediaStore
     * entry on Q+, a plain file before that) and returns the file name, or null
     * when the write failed. Same approach as the logs page, so there is one
     * notion of "save a file for the user" in the app.
     */
    fun saveToDownloads(context: Context, bytes: ByteArray, name: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@runCatching null
            name
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            name
        }
    }.getOrNull()
}
