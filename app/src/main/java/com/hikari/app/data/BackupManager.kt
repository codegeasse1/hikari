package com.hikari.app.data

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.JsonReader
import android.util.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 *  * the download queue store (`hikari_downloads`) — for the first reason;
 *  * **the device-local settings** — the app lock, the phone/television layout
 *    (interface scale, overscan, full-screen mode), this device's performance
 *    and first-run state, and the launcher-icon alias. See
 *    [AppStore.DeviceLocal] for the list and the rule behind it. A pairing
 *    carries this same payload over Wi-Fi (see [com.hikari.app.pair.PairHost]),
 *    so excluding them here is what stops a television from being handed a
 *    phone's PIN lock and a phone's screen shape — the two things a transfer
 *    must never decide for the device receiving it.
 *
 * The format is a plain JSON object so a support chat can be told "open it in a
 * text editor" and the user can see it contains no video and no app lock.
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
        val tmp = File(app.cacheDir, "outbox/backup-tmp.json")
        exportToFile(app, tmp)
        try {
            tmp.readBytes()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * Writes the backup straight to [dest] as it is built, instead of
     * assembling the whole file in memory first. The old [export] built one
     * giant string for the entire backup — with a large extension set that
     * single allocation reached ~300 MB and the backup died with
     * \"Failed to allocate … byte allocation … until OOM\". Here the JSON
     * streams out through [JsonWriter] and no allocation is ever bigger than
     * one extension file (capped by [MAX_FILE_BYTES]), so a backup of any
     * real size completes. The format on disk is byte-for-byte what [export]
     * used to produce, so old and new files restore through the same code.
     */
    suspend fun exportToFile(app: HikariApp, dest: File): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        java.io.FileOutputStream(tmp).buffered(32 * 1024).use { raw ->
            JsonWriter(raw.writer(Charsets.UTF_8)).use { w ->
                w.setIndent("")
                w.beginObject()
                w.name("format").value(FORMAT)
                w.name("version").value(FORMAT_VERSION.toLong())
                w.name("app").value(BuildConfig.VERSION_NAME)
                w.name("code").value(BuildConfig.VERSION_CODE.toLong())
                w.name("createdAt").value(System.currentTimeMillis())
                w.name("prefs").beginArray()
                for (r in app.store.snapshotPreferences()) {
                    w.beginObject()
                    w.name("key").value(r.key)
                    w.name("type").value(r.type)
                    w.name("value")
                    when (r.type) {
                        "s" -> if (r.value == null) w.nullValue() else w.value(r.value as String)
                        "b" -> if (r.value == null) w.nullValue() else w.value((r.value as Boolean))
                        "i", "l" -> if (r.value == null) w.nullValue() else w.value((r.value as Number).toLong())
                        // JsonWriter rejects non-finite doubles outright
                        // (IllegalArgumentException) — a single NaN anywhere
                        // in the store used to fail the whole backup.
                        "f", "d" -> if (r.value == null) w.nullValue() else {
                            val d = (r.value as Number).toDouble()
                            if (d.isFinite()) w.value(d) else w.nullValue()
                        }
                        "ss" -> {
                            val list = r.value as? List<*> ?: (r.value as? Set<*>)?.toList()
                            if (list == null) {
                                w.nullValue()
                            } else {
                                w.beginArray()
                                for (s in list) w.value(s?.toString())
                                w.endArray()
                            }
                        }
                        "bin" -> if (r.value == null) w.nullValue() else w.value(r.value as String)
                        else -> w.nullValue()
                    }
                    w.endObject()
                }
                w.endArray()
                w.name("files").beginArray()
                // One file at a time: the file list used to be assembled whole
                // (every extension's bytes in RAM at once) and a large
                // extension set died with an OOM that surfaced as "Backup
                // failed". Walking the names first and reading each file only
                // for its own write keeps the peak at one file + its base64.
                for (rel in collectFileRels(app.filesDir)) {
                    val bytes = runCatching { File(app.filesDir, rel).readBytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) continue
                    w.beginObject()
                    w.name("path").value(rel)
                    w.name("data").value(Base64.encodeToString(bytes, Base64.NO_WRAP))
                    w.endObject()
                }
                w.endArray()
                w.name("profiles").beginObject()
                val profiles = exportProfiles(app.filesDir)
                val profileNames = runCatching {
                    val list = ArrayList<String>()
                    val keys = profiles.keys()
                    while (keys.hasNext()) list.add(keys.next() as String)
                    list
                }.getOrDefault(emptyList())
                for (name in profileNames) {
                    // Profiles are small snapshots: parse and re-emit through
                    // the writer (JsonWriter has no raw-value call on this
                    // compile SDK), which keeps the stored content verbatim.
                    val obj = runCatching { JSONObject(profiles.optString(name)) }.getOrNull()
                    if (obj == null) continue
                    w.name(name)
                    writeJsonObject(w, obj)
                }
                w.endObject()
                w.endObject()
            }
        }
        if (dest.exists()) dest.delete()
        // renameTo silently returns false across filesystems and on odd OEMs —
        // an unchecked failure used to hand the saver an empty/missing file.
        if (!tmp.renameTo(dest)) {
            tmp.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            runCatching { tmp.delete() }
        }
        dest.length()
    }

    /**
     * Writes an org.json value through [JsonWriter] (which has no raw-value
     * call on this compile SDK): objects, arrays, strings, numbers, booleans
     * and nulls, recursively. Only used for the small profile snapshots in
     * [exportToFile] — never for extension files, which stream as base64.
     */
    private fun writeJsonValue(w: JsonWriter, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> w.nullValue()
            is JSONObject -> writeJsonObject(w, v)
            is JSONArray -> {
                w.beginArray()
                for (i in 0 until v.length()) writeJsonValue(w, v.opt(i))
                w.endArray()
            }
            is Boolean -> w.value(v)
            is Number -> w.value(v)
            else -> w.value(v.toString())
        }
    }

    private fun writeJsonObject(w: JsonWriter, o: JSONObject) {
        w.beginObject()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next() as String
            w.name(k)
            writeJsonValue(w, o.opt(k))
        }
        w.endObject()
    }

    /**
     * Reads [input] with a cap, for files that must be small (a CloudStream
     * backup is a settings dump, never media). Null when the stream is empty
     * or runs past [maxBytes] — the caller reports that instead of letting an
     * arbitrary picked file grow without limit.
     */
    fun readCapped(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    /**
     * Every extension/scraper file, as `(relative path, bytes)`.
     *
     * Walks the four [FILE_ROOTS] rather than listing known extensions so a
     * plugin family added later is carried automatically; a file that cannot be
     * read is skipped rather than failing the whole backup (a locked or
     * half-written file must not cost the user their settings).
     */
    /** Extension-file paths (relative) for the backup walk — names only, so the
     *  writer can stream one file at a time (see [exportToFile]). */
    private fun collectFileRels(filesDir: File): List<String> {
        val out = ArrayList<String>()
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
                    out.add(child.relativeTo(filesDir).invariantSeparatorsPath)
                }
            }
        }
        return out
    }

    /**
     * Every profile snapshot plus the registry, as raw JSON text keyed by file
     * name. The live store export above is only ever the ACTIVE profile's
     * setup, so without this a backup carried one profile and restoring it
     * orphaned (or lost) all the others.
     */
    private fun exportProfiles(filesDir: File): JSONObject {
        val out = JSONObject()
        val dir = File(filesDir, "profiles")
        if (!dir.isDirectory) return out
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return out
        for (f in kids) {
            if (!f.isFile || f.length() <= 0L || f.length() > 4L * 1024L * 1024L) continue
            val name = f.name
            if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            // Must still be JSON — a half-written snapshot must not poison a backup.
            if (runCatching { JSONObject(text) }.getOrNull() == null) continue
            out.put(name, text)
        }
        return out
    }

    private val PROF_SNAP_RE = Regex("^[A-Za-z0-9_-]{1,64}\\.json$")

    /**
     * Writes the backed-up profiles back, returning "N profile(s)". Runs after
     * the live preferences so [Profiles.load] — which reconciles the registry
     * with the store on the next start — finds both halves agreeing.
     */
    private fun restoreProfiles(filesDir: File, obj: JSONObject?): String {
        if (obj == null) return ""
        val dir = File(filesDir, "profiles").apply { mkdirs() }
        var n = 0
        for (raw in obj.keys()) {
            val name = raw as? String ?: continue
            if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) continue
            val text = obj.optString(name).takeIf { it.isNotBlank() } ?: continue
            if (runCatching { JSONObject(text) }.getOrNull() == null) continue
            if (runCatching { File(dir, name).writeText(text) }.isFailure) continue
            if (name != "registry.json") n++
        }
        // The registry names every profile the snapshots hold: without it the
        // snapshots are unreachable, so it travels or nothing does.
        if (!File(dir, "registry.json").isFile) return ""
        return if (n == 1) "1 profile" else n.toString() + " profiles"
    }


    // ------------------------------------------------------------ restore --

    suspend fun restore(app: HikariApp, bytes: ByteArray): Report =
        restoreStream(app, ByteArrayInputStream(bytes))

    /**
     * Applies a backup file read as a stream — the restore half of the OOM
     * fix (see [exportToFile]): the old code parsed the entire file into one
     * JSONObject, which needed the whole ~300 MB in memory twice over and
     * failed the same way the backup did. Here prefs and profiles (small)
     * collect in memory while each extension file streams straight to a
     * temp file beside its target; nothing is committed until the whole
     * stream has parsed and the format version has checked out, so a
     * corrupt/truncated file can never leave half-written extensions behind.
     */
    suspend fun restoreStream(app: HikariApp, input: InputStream): Report = withContext(Dispatchers.IO) {
        val records = ArrayList<PrefRecord>()
        var deviceLocal = 0
        var format: String? = null
        var version = -1
        var fromApp = ""
        var fromCode = 0
        var sawSections = false
        var written = 0
        var skipped = 0
        val staged = ArrayList<Pair<File, File>>()
        val profileTexts = HashMap<String, String>()
        fun cleanup() {
            for ((_, tmp) in staged) runCatching { tmp.delete() }
            staged.clear()
        }
        try {
            JsonReader(input.reader(Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "format" -> format = r.nextStringOrSkip()
                        "version" -> version = try { r.nextInt() } catch (t: Throwable) { runCatching { r.skipValue() }; -1 }
                        "app" -> fromApp = r.nextStringOrSkip().orEmpty()
                        "code" -> fromCode = try { r.nextInt() } catch (t: Throwable) { runCatching { r.skipValue() }; 0 }
                        "createdAt" -> runCatching { r.skipValue() }
                        "prefs" -> {
                            sawSections = true
                            try {
                                r.beginArray()
                                while (r.hasNext()) {
                                    r.beginObject()
                                    var key = ""
                                    var type = ""
                                    var value: Any? = null
                                    while (r.hasNext()) {
                                        when (r.nextName()) {
                                            "key" -> key = r.nextStringOrSkip().orEmpty()
                                            "type" -> type = r.nextStringOrSkip().orEmpty()
                                            "value" -> value = r.readPrefValue(type)
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                    if (key.isBlank()) continue
                                    if (AppStore.DeviceLocal.contains(key)) {
                                        deviceLocal++
                                        continue
                                    }
                                    records.add(PrefRecord(key, type, value))
                                }
                                r.endArray()
                            } catch (t: Throwable) {
                                runCatching { r.skipValue() }
                            }
                        }
                        "files" -> {
                            sawSections = true
                            try {
                                r.beginArray()
                                while (r.hasNext()) {
                                    r.beginObject()
                                    var rel = ""
                                    var data: String? = null
                                    while (r.hasNext()) {
                                        when (r.nextName()) {
                                            "path" -> rel = r.nextStringOrSkip().orEmpty()
                                            "data" -> data = r.nextStringOrSkip()
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                    if (!allowedPath(rel)) {
                                        skipped++
                                        continue
                                    }
                                    val bytes = runCatching { Base64.decode(data.orEmpty(), Base64.NO_WRAP) }.getOrNull()
                                    if (bytes == null || bytes.isEmpty() || bytes.size.toLong() > MAX_FILE_BYTES) {
                                        skipped++
                                        continue
                                    }
                                    val target = File(app.filesDir, rel)
                                    target.parentFile?.mkdirs()
                                    val tmp = File(target.parentFile, target.name + ".restore-part")
                                    if (runCatching { tmp.writeBytes(bytes) }.isFailure) {
                                        runCatching { tmp.delete() }
                                        skipped++
                                        continue
                                    }
                                    staged.add(target to tmp)
                                }
                                r.endArray()
                            } catch (t: Throwable) {
                                runCatching { r.skipValue() }
                            }
                        }
                        "profiles" -> {
                            sawSections = true
                            try {
                                r.beginObject()
                                while (r.hasNext()) {
                                    val name = r.nextName()
                                    if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) {
                                        r.skipValue()
                                        continue
                                    }
                                    val obj = r.readJsonObject()
                                    if (obj == null) continue
                                    profileTexts[name] = obj.toString()
                                }
                                r.endObject()
                            } catch (t: Throwable) {
                                runCatching { r.skipValue() }
                            }
                        }
                        else -> runCatching { r.skipValue() }
                    }
                }
                r.endObject()
            }
        } catch (t: Throwable) {
            cleanup()
            return@withContext Report(false, "That file is not a Hikari backup.", t.message.orEmpty())
        }
        val legacy = format.isNullOrBlank() && sawSections
        if (!format.isNullOrBlank() && format != FORMAT && !legacy) {
            cleanup()
            return@withContext Report(false, "That file is not a Hikari backup.")
        }
        if (!sawSections) {
            cleanup()
            return@withContext Report(false, "That file is not a Hikari backup.")
        }
        if (version > FORMAT_VERSION) {
            cleanup()
            return@withContext Report(
                false,
                "That backup was made by a newer Hikari (format $version). Update the app first.",
            )
        }
        var applied = 0
        runCatching { applied = app.store.restorePreferences(records) }.onFailure {
            cleanup()
            return@withContext Report(false, "Could not restore your settings.", it.message.orEmpty())
        }
        if (deviceLocal > 0) {
            Logs.log(
                "Backup",
                "kept $deviceLocal device-local setting(s) of this device's own (app lock / layout)",
            )
        }
        val fromName = fromApp.ifBlank { "another Hikari" }
        for ((target, tmp) in staged) {
            val ok = runCatching {
                if (target.exists() && !target.delete()) false else tmp.renameTo(target)
            }.getOrDefault(false)
            if (ok) written++ else {
                runCatching { tmp.delete() }
                skipped++
            }
        }
        staged.clear()
        val profilesObj = JSONObject().apply {
            for ((k, v) in profileTexts) put(k, v)
        }
        val profilesLine = restoreProfiles(app.filesDir, profilesObj)
        refreshLiveState(app)
        val detail = "settings: $applied" +
            (if (profilesLine.isNotBlank()) " · " + profilesLine else "") +
            (if (deviceLocal > 0) " · device-local kept: $deviceLocal" else "") +
            " · files: $written" +
            (if (skipped > 0) " · skipped: $skipped" else "") +
            " · from $fromName $fromCode"
        Logs.log("Backup", "restore ok — $detail")
        Report(
            true,
            if (written > 0) "Restored. Your sources are reloading." else "Restored your settings.",
            detail,
        )
    }

    private fun JsonReader.nextStringOrSkip(): String? {
        return try {
            nextString()
        } catch (t: Throwable) {
            runCatching { skipValue() }
            null
        }
    }

    private fun JsonReader.readPrefValue(type: String): Any? {
        return try {
            if (peek() == android.util.JsonToken.NULL) {
                nextNull()
                return null
            }
            when (type) {
                "ss" -> {
                    beginArray()
                    val list = ArrayList<String>()
                    while (hasNext()) {
                        list.add(if (peek() == android.util.JsonToken.NULL) { nextNull(); "" } else nextString())
                    }
                    endArray()
                    list
                }
                "i", "l" -> try {
                    nextLong()
                } catch (t: Throwable) {
                    nextDouble().toLong()
                }
                "f", "d" -> try {
                    nextDouble()
                } catch (t: Throwable) {
                    nextLong().toDouble()
                }
                "b" -> nextBoolean()
                else -> nextString()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun JsonReader.readJsonObject(): JSONObject? {
        fun readAny(r: JsonReader): Any? = when (r.peek()) {
            android.util.JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                val o = JSONObject()
                while (r.hasNext()) o.put(r.nextName(), readAny(r) ?: JSONObject.NULL)
                r.endObject()
                o
            }
            android.util.JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                val a = JSONArray()
                while (r.hasNext()) a.put(readAny(r) ?: JSONObject.NULL)
                r.endArray()
                a
            }
            android.util.JsonToken.STRING -> r.nextString()
            android.util.JsonToken.NUMBER -> r.nextDouble()
            android.util.JsonToken.BOOLEAN -> r.nextBoolean()
            else -> {
                r.skipValue()
                null
            }
        }
        return readAny(this) as? JSONObject
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
     *
     * Public because a PROFILE switch is the same kind of event — see
     * [com.hikari.app.data.Profiles.switchTo], which re-uses this rather than
     * keeping a second list of "the settings that are read once at startup".
     */
    suspend fun refreshLiveState(app: HikariApp) {
        runCatching { app.elementBlocks = app.store.elementBlocks() }
        runCatching {
            app.webViewUseDefaultUa = app.store.webviewUseDefaultUa()
            app.webViewCustomUa = app.store.webviewCustomUa()
        }
        runCatching { NetTuning.setSlowConnection(app.store.slowConnection()) }
        runCatching {
            NetTuning.setDnsProvider(app.store.dnsProvider())
            NetTuning.setCustomDns(app.store.customDns())
        }
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
     * when the write failed.
     *
     * The write itself lives in [DownloadsSaver] — one implementation for every
     * "save this file for the user" in the app, and the one that actually works
     * (see its header: an insert without `RELATIVE_PATH` is refused on Android
     * 11+, which is why this export could never save either).
     */
    fun saveToDownloads(context: Context, file: File, name: String): String? =
        DownloadsSaver.save(context, file, name, "application/json")
            .getOrNull()
            ?.substringAfterLast('/')
}
