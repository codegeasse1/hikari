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
     * `<files>/cs3` and `<files>/hiki` are the installed extensions,
     * `<files>/nuvio/scrapers` and `<files>/nuvio/settings` the Nuvio
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

    // ------------------------------------------- CloudStream backup import --

    /** The key CloudStream's own backup/restore round-trips its repo list under. */
    private const val CS_REPOS_KEY = "REPOSITORIES_KEY"

    /** The typed maps a CloudStream DataStore dump is made of. Their presence is
     *  what identifies the file as a CloudStream backup. */
    private val CS_MAP_TYPES = listOf("_String", "_Int", "_Bool", "_Long", "_Float", "_StringSet")

    /** Nothing sane reaches this; the cap stops a hand-edited file from turning
     *  into thousands of DataStore writes. */
    private const val CS_MAX_REPOS = 500

    /**
     * Imports the repositories out of a **CloudStream** backup so a user
     * migrating from CloudStream does not have to re-type every repo URL.
     *
     * What CloudStream's backup file actually contains (checked against real
     * files): a dump of its DataStore with two typed maps per preference bucket
     * (`_String`, `_Int`, `_Bool`, …), where `datastore._String
     * .REPOSITORIES_KEY` holds a JSON string — an array of
     * `{iconUrl, name, url}`. The *installed* extensions are NOT in it: they
     * live in CloudStream's own database, so no backup file can carry them.
     * This therefore restores the repo list only, and the user then opens
     * Sources & Extensions, where each imported repo lists its extensions
     * (with the per-repo "Install all" button) ready to install.
     *
     * The layout is not a documented API, so the search is layered rather than
     * positional: the known key, then any repos-looking preference, then any
     * array anywhere in the file whose entries carry an `http` url. A file that
     * resembles a CloudStream backup but carries no repos is reported as such
     * instead of being silently accepted. Like [restore] it never throws.
     */
    suspend fun restoreCloudStream(app: HikariApp, bytes: ByteArray): Report =
        withContext(Dispatchers.IO) {
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                ?: return@withContext Report(false, "Could not read that file.")
            val root = runCatching {
                JSONObject(text.trimStart('\uFEFF', ' ', '\n', '\r', '\t'))
            }.getOrNull()
                ?: return@withContext Report(false, "That file is not a CloudStream backup.")
            if (!looksLikeCloudStream(root)) {
                return@withContext Report(false, "That file is not a CloudStream backup.")
            }

            val repos = cloudStreamRepos(root)
            if (repos.isEmpty()) {
                return@withContext Report(
                    false,
                    "No repositories found in that CloudStream backup.",
                    "Nothing to import — the file has no repo list in it.",
                )
            }

            val known = runCatching { app.store.repos() }.getOrDefault(emptyList())
                .mapTo(HashSet()) { normRepoUrl(it.url) }
            var added = 0
            var duplicates = 0
            for (repo in repos) {
                val key = normRepoUrl(repo.url)
                if (key.isBlank() || !known.add(key)) {
                    duplicates++
                    continue
                }
                if (runCatching { app.store.addCs3Repo(repo) }.isSuccess) added++ else duplicates++
            }
            if (added > 0) {
                // The repo list is live in the store, but the plugin lists
                // behind each repo are what the Extensions screen shows.
                runCatching { app.providers.refresh() }
            }

            val detail = "cloudstream: found ${repos.size}, added $added, already present $duplicates"
            Logs.log("Backup", "cloudstream restore — $detail")
            Report(
                true,
                when {
                    added == 0 -> "Those ${repos.size} repositories are already in your list."
                    added == 1 -> "Added 1 repository from CloudStream."
                    else -> "Added $added repositories from CloudStream."
                } + " Open Sources & Extensions to install their plugins.",
                detail,
            )
        }

    /** True when the file has the shape of a CloudStream DataStore dump (either
     *  bucket's typed maps, the repo key, or a bare repos array for a file
     *  someone extracted by hand). */
    private fun looksLikeCloudStream(root: JSONObject): Boolean {
        for (bucket in listOf("datastore", "settings")) {
            val map = root.optJSONObject(bucket) ?: continue
            if (CS_MAP_TYPES.any { map.optJSONObject(it) != null }) return true
        }
        if (root.has(CS_REPOS_KEY)) return true
        return root.optJSONArray("repositories") != null || root.optJSONArray("repos") != null
    }

    /**
     * Every repo in a CloudStream backup, in file order, deduplicated by URL.
     *
     * Layered deliberately: the known `REPOSITORIES_KEY` is tried first, then
     * every preference whose *name* mentions repositories (CloudStream has
     * renamed keys between forks), then a bounded scan of every string in the
     * file for the largest array of objects carrying a url — which is what saves
     * this when a fork stores its repo list under a name we have never seen.
     */
    private fun cloudStreamRepos(root: JSONObject): List<Cs3Repo> {
        val found = LinkedHashMap<String, Cs3Repo>()
        var usedFallback = false

        fun offer(value: Any?) {
            for (repo in reposIn(value)) {
                val key = normRepoUrl(repo.url)
                if (key.isNotBlank() && !found.containsKey(key)) found[key] = repo
            }
        }

        // 1. The key CloudStream itself uses, in either bucket (and any other
        //    top-level object, in case a fork nests it differently).
        for (bucket in listOf("datastore", "settings")) {
            val map = root.optJSONObject(bucket) ?: continue
            for (type in CS_MAP_TYPES) {
                val typed = map.optJSONObject(type) ?: continue
                val names = typed.names() ?: continue
                for (i in 0 until names.length()) {
                    val name = names.optString(i)
                    if (name == CS_REPOS_KEY || name.contains("repositor", ignoreCase = true)) {
                        offer(typed.opt(name))
                    }
                }
            }
        }

        // 2. Some forks/older formats keep the array at the top level.
        offer(root.opt("repositories"))
        offer(root.opt("repos"))

        // 3. Last resort: the biggest repo-shaped JSON array anywhere in the
        //    file. Marked, because it is a guess, and only used when the
        //    targeted lookups came up empty.
        if (found.isEmpty()) {
            largestRepoArray(root)?.let { scanned ->
                offer(scanned)
                if (found.isNotEmpty()) {
                    Logs.log("Backup", "cloudstream repos found by scanning the file")
                }
            }
        }
        return found.values.take(CS_MAX_REPOS)
    }

    /** The `{iconUrl, name, url}` objects inside [value], which may be the array
     *  itself, a JSON string holding it (how CloudStream stores it), a
     *  `_StringSet` wrapper, or an object with a list field. */
    private fun reposIn(value: Any?): List<Cs3Repo> {
        val arr = asRepoArray(value) ?: return emptyList()
        val out = ArrayList<Cs3Repo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = listOf("url", "apiUrl", "repoUrl", "repo")
                .firstNotNullOfOrNull { field ->
                    o.optString(field).trim().takeIf { it.startsWith("http", ignoreCase = true) }
                } ?: continue
            val name = listOf("name", "displayName", "title")
                .firstNotNullOfOrNull { o.optString(it).trim().takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            // The repo file itself is what Hikari fetches, so the URL is kept
            // exactly as CloudStream had it (a fork's URL may not end in
            // `repo.json` and rewriting it would break it).
            out.add(Cs3Repo(url = url, name = name.ifBlank { url }, kind = RepoKind.CS3))
        }
        return out
    }

    /** [value] as a repo-shaped [JSONArray], or null. */
    private fun asRepoArray(value: Any?): JSONArray? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is JSONArray) return value.takeIf { looksLikeRepoArray(it) }
        if (value is JSONObject) {
            value.optJSONArray("_StringSet")?.let { return asRepoArray(it) }
            for (field in listOf("repositories", "repos", "list", "items", "value")) {
                value.opt(field)?.let { return asRepoArray(it) }
            }
            return null
        }
        val s = value.toString().trim()
        if (!s.startsWith("[")) return null
        return runCatching { JSONArray(s) }.getOrNull()?.takeIf { looksLikeRepoArray(it) }
    }

    /** An array where at least half the entries are objects with an http url. */
    private fun looksLikeRepoArray(arr: JSONArray): Boolean {
        if (arr.length() == 0) return false
        var hits = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = listOf("url", "apiUrl", "repoUrl", "repo")
                .firstNotNullOfOrNull { o.optString(it).trim().takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            if (url.startsWith("http", ignoreCase = true)) hits++
        }
        return hits > 0 && hits * 2 >= arr.length()
    }

    /**
     * The repo array with the most entries, found by walking the file's strings
     * (the walk is depth- and count-bounded — a backup is ~10 KB but a hand-made
     * file could be huge). Used only as a last resort; see [cloudStreamRepos].
     */
    private fun largestRepoArray(root: JSONObject): JSONArray? {
        var best: JSONArray? = null
        var visited = 0

        fun walk(value: Any?, depth: Int) {
            if (depth > 8 || visited > 20_000) return
            visited++
            when (value) {
                is JSONObject -> {
                    val names = value.names() ?: return
                    for (i in 0 until names.length()) walk(value.opt(names.optString(i)), depth + 1)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                }
                is String -> {
                    val s = value.trim()
                    if (s.length < 3 || !s.startsWith("[")) return
                    val arr = runCatching { JSONArray(s) }.getOrNull() ?: return
                    if (!looksLikeRepoArray(arr)) return
                    if ((best?.length() ?: -1) < arr.length()) best = arr
                }
            }
        }
        walk(root, 0)
        return best
    }

    /** URL identity for the duplicate check: no surrounding space, no trailing
     *  slash, host case folded. */
    private fun normRepoUrl(url: String): String =
        url.trim().trimEnd('/').lowercase(Locale.US)

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
