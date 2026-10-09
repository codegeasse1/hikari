package com.hikari.app.cs3

import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.SourceUrls
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Silent background updater for installed CloudStream (.cs3) plugins — the
 * second half of "plays like the CloudStream app".
 *
 * The CloudStream app refreshes its extensions from the plugin repos on its
 * own, so a site rebuild (MovieLinkBD, Pelispedia, …) becomes a fixed plugin
 * within days there, while Hikari's installed copy stayed stale until the
 * user opened Extensions and tapped Update — every site change arrived as a
 * separate "not playing in Hikari" report. This pass runs the same
 * manifest-hash comparison the Update buttons use (repo.json + pluginLists,
 * sha256 of the served file), headless, at most once a day, and replaces at
 * most a bounded number of plugin files per run. CS3-only: no other engine's
 * files are ever touched.
 *
 * Safety, mirroring the manual install path:
 * - a file is replaced only when the repo manifest pins a DIFFERENT sha256
 *   for its exact source URL (URL spellings matched loosely, like the manual
 *   check) and the downloaded bytes hash to exactly that value;
 * - the previous file is kept as a backup until the reloaded plugin registers
 *   at least one provider — an update that loads nothing restores the backup,
 *   so a bad publish can never delete a working extension;
 * - provider configs are keyed by file name, which never changes here, so ids
 *   (and through them the Library/history) survive the swap; the caller
 *   re-runs [Cs3ProviderSync.reconcile] afterwards for renames.
 */
object Cs3AutoUpdate {

    private const val TAG = "Cs3AutoUpdate"

    /** At most one silent pass per day (manual Update buttons are unaffected). */
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** At most this many plugin files replaced per run — a repo-wide key
     *  rotation must not rewrite the whole library in one launch. */
    private const val MAX_UPDATES_PER_RUN = 10

    /** Same ceiling as the manual installer (see installCs3Bytes). */
    private const val MAX_BYTES = 10 * 1024 * 1024

    /** Whole pass must finish inside this, or it stops and retries next launch. */
    private const val RUN_BUDGET_MS = 10L * 60 * 1000

    private const val REPO_TIMEOUT_SEC = 30L

    /**
     * Runs one silent pass when due. Returns how many plugin files were
     * replaced (0 = nothing to do / not due / failed quietly). Never throws,
     * never touches the UI.
     */
    suspend fun runIfDue(app: HikariApp, store: AppStore): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = runCatching { store.cs3AutoUpdateAt() }.getOrDefault(0L)
        if (last > 0 && now - last < INTERVAL_MS) return@withContext 0
        val updated = withTimeoutOrNull(RUN_BUDGET_MS) { run(app, store) } ?: 0
        runCatching { store.setCs3AutoUpdateAt(now) }
        updated
    }

    private suspend fun run(app: HikariApp, store: AppStore): Int {
        // Installed CS3 files with an http source URL, one entry per file
        // (several provider configs share one .cs3).
        val installed = LinkedHashMap<String, String>()
        for (p in runCatching { store.providers() }.getOrDefault(emptyList())) {
            if (p.type != ProviderType.CS3) continue
            val extra = p.extra ?: continue
            if (!extra.startsWith("http")) continue
            if (p.url.isBlank()) continue
            if (!File(p.url).isFile) continue
            installed.putIfAbsent(p.url, extra)
        }
        if (installed.isEmpty()) return 0
        val repos = runCatching { store.repos() }.getOrDefault(emptyList())
            .filter { it.kind == RepoKind.CS3 && it.url.startsWith("http") }
        if (repos.isEmpty()) return 0

        // Manifest hashes: every spelling of a plugin URL -> the sha256 its
        // repo currently serves (repo.json `plugins` + `pluginLists`, like the
        // Extensions screen's own check).
        val servedHashes = ConcurrentHashMap<String, String>()
        val servedUrlFor = ConcurrentHashMap<String, String>()
        coroutineScope {
            val gate = Semaphore(4)
            repos.map { repo ->
                async {
                    gate.withPermit {
                        for ((url, hash) in fetchManifestEntries(repo.url)) {
                            for (key in SourceUrls.matchKeys(url)) {
                                servedHashes.putIfAbsent(key, hash)
                                servedUrlFor.putIfAbsent(key, url)
                            }
                        }
                    }
                }
            }.forEach { runCatching { it.await() } }
        }
        if (servedHashes.isEmpty()) {
            Logs.log(TAG, "no repo manifests readable — will retry next launch")
            return 0
        }

        var updated = 0
        for ((path, source) in installed) {
            if (updated >= MAX_UPDATES_PER_RUN) break
            val keys = SourceUrls.matchKeys(source)
            val key = keys.firstOrNull { servedHashes.containsKey(it) } ?: continue
            val expected = servedHashes[key]?.removePrefix("sha256-")?.lowercase() ?: continue
            if (!expected.matches(Regex("[0-9a-f]{64}"))) continue
            val actual = sha256File(path) ?: continue
            if (actual == expected) continue
            val downloadUrl = servedUrlFor[key] ?: continue
            if (updateOne(app, path, downloadUrl, expected)) {
                updated++
                Logs.log(TAG, "auto-updated ${File(path).name}")
            }
        }
        if (updated > 0) Logs.log(TAG, "replaced $updated plugin file(s)")
        return updated
    }

    /** One file: download, verify against the manifest hash, swap with backup. */
    private suspend fun updateOne(app: HikariApp, path: String, downloadUrl: String, expected: String): Boolean {
        val bytes = runCatching { Http.fetchBytesCancellable(downloadUrl) }.getOrNull()
            ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        if (sha256Bytes(bytes) != expected) {
            Logs.log(TAG, "checksum mismatch for $downloadUrl — skipped")
            return false
        }
        val file = File(path)
        val previous = runCatching { file.readBytes() }.getOrNull() ?: return false
        val backup = File(path + ".bak")
        return try {
            runCatching { backup.writeBytes(previous) }
            file.setWritable(true)
            file.writeBytes(bytes)
            val apis = runCatching { Cs3PluginManager.reload(app, file) }.getOrDefault(emptyList())
            if (apis.isEmpty()) {
                // A publish that loads nothing must never delete a working
                // extension: put the previous file back and reload that.
                runCatching {
                    file.writeBytes(previous)
                    Cs3PluginManager.reload(app, file)
                }
                Logs.log(TAG, "new ${file.name} loads no providers — kept the previous copy")
                false
            } else {
                runCatching { backup.delete() }
                true
            }
        } catch (t: Throwable) {
            runCatching {
                file.writeBytes(previous)
                Cs3PluginManager.reload(app, file)
            }
            Logs.log(TAG, "update of ${file.name} failed (${t.javaClass.simpleName}) — kept the previous copy")
            false
        }
    }

    /** All (url, fileHash) entries a CS3 repo serves: repo.json plugins + pluginLists. */
    private suspend fun fetchManifestEntries(repoUrl: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val rootText = fetchFirst(repoUrl) ?: return out
        val root = runCatching { JSONObject(rootText) }.getOrNull() ?: return out
        root.optJSONArray("plugins")?.let { collectEntries(it, out) }
        root.optJSONArray("pluginLists")?.let { lists ->
            for (i in 0 until lists.length()) {
                val listUrl = lists.optString(i).ifBlank { null } ?: continue
                val text = runCatching {
                    Http.fetchStringRobust(listUrl, emptyMap(), REPO_TIMEOUT_SEC).getOrNull()
                }.getOrNull()
                if (text.isNullOrBlank() || looksLikeHtml(text)) continue
                runCatching { JSONArray(text) }.getOrNull()?.let { collectEntries(it, out) }
            }
        }
        return out
    }

    private fun collectEntries(arr: JSONArray, out: ArrayList<Pair<String, String>>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { continue }
            val hash = o.optString("fileHash").ifBlank { continue }
            if (!hash.startsWith("sha256-")) continue
            out += url to hash
        }
    }

    /** First readable body for a repo's repo.json across URL spellings + mirror. */
    private suspend fun fetchFirst(repoUrl: String): String? {
        for (candidate in manifestCandidates(repoUrl)) {
            val text = runCatching {
                Http.fetchStringRobust(candidate, emptyMap(), REPO_TIMEOUT_SEC).getOrNull()
            }.getOrNull()
            if (text.isNullOrBlank() || looksLikeHtml(text)) continue
            return text
        }
        return null
    }

    private fun manifestCandidates(repoUrl: String): List<String> {
        val t = repoUrl.trim().trimEnd('/')
        val base = ArrayList<String>()
        when {
            t.endsWith("/repo.json", ignoreCase = true) -> base += t
            t.contains("raw.githubusercontent.com") -> {
                val norm = t.replace("/refs/heads/", "/")
                base += norm
                if (norm != t) base += t
            }
            else -> base += "$t/repo.json"
        }
        val out = LinkedHashSet<String>()
        for (v in base) {
            out += v
            jsDelivrMirror(v)?.let { out += it }
        }
        out += repoUrl
        return out.toList()
    }

    private fun jsDelivrMirror(url: String): String? {
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
            .find(url.trim()) ?: return null
        return "https://cdn.jsdelivr.net/gh/${m.groupValues[1]}/${m.groupValues[2]}@${m.groupValues[3]}/${m.groupValues[4]}"
    }

    private fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().take(64).lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(path: String): String? {
        val f = File(path)
        if (!f.isFile) return null
        return runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(f).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
