package com.hikari.app.nuvio

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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Silent background updater for installed Nuvio JS scrapers — the Nuvio half
 * of "plays like the reference app".
 *
 * NuvioMobile refreshes its providers from the plugin repos on its own, so a
 * site rebuild becomes a fixed provider within days there, while Hikari's
 * installed copy stayed frozen at install time — every site change arrived as
 * another "server failed / no playable links in Hikari" report (MovieBlast,
 * 4KHDHub, …). This pass runs the same manifest comparison the Extensions
 * screen's Update buttons use, headless, at most once a day, and replaces at
 * most a bounded number of scraper files per run. NUVIO-only: no other
 * engine's files are ever touched.
 *
 * Nuvio manifests carry no sha256 (only a `version` int per scraper, and the
 * installed row stores no version), so the signal is a content-hash diff: the
 * served bytes are downloaded and compared against the installed file. The
 * vendored [NuvioPluginManager] patches are applied to the served bytes BEFORE
 * comparing (content-gated, exactly like the install path), so a provider the
 * app patched on purpose is not "updated" every day back and forth.
 *
 * Safety, mirroring the manual install path:
 * - a file is replaced only when the served bytes differ from the installed
 *   ones AND validate as a nuvio provider (a `getStreams` export);
 * - the previous file is kept as a backup until the new bytes validate — an
 *   update that loads nothing restores the backup, so a bad publish can never
 *   delete a working extension;
 * - the file name never changes, so provider ids (and through them the
 *   Library/history) survive the swap.
 */
object NuvioAutoUpdate {

    private const val TAG = "NuvioAutoUpdate"

    /** At most one silent pass per day (manual Update buttons are unaffected). */
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** At most this many scraper files replaced per run — a repo-wide rewrite
     *  must not churn the whole library in one launch. */
    private const val MAX_UPDATES_PER_RUN = 10

    /** Same ceiling as the manual installer (see installScraper). */
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** Whole pass must finish inside this, or it stops and retries next launch. */
    private const val RUN_BUDGET_MS = 10L * 60 * 1000

    private const val REPO_TIMEOUT_SEC = 30L

    /**
     * Runs one silent pass when due. Returns how many scraper files were
     * replaced (0 = nothing to do / not due / failed quietly). Never throws,
     * never touches the UI.
     */
    suspend fun runIfDue(app: HikariApp, store: AppStore): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = runCatching { store.nuvioAutoUpdateAt() }.getOrDefault(0L)
        if (last > 0 && now - last < INTERVAL_MS) return@withContext 0
        val updated = withTimeoutOrNull(RUN_BUDGET_MS) { run(app, store) } ?: 0
        runCatching { store.setNuvioAutoUpdateAt(now) }
        updated
    }

    private suspend fun run(app: HikariApp, store: AppStore): Int {
        // Installed NUVIO scrapers with an http source URL, one entry per file.
        val installed = LinkedHashMap<String, String>()
        for (p in runCatching { store.providers() }.getOrDefault(emptyList())) {
            if (p.type != ProviderType.NUVIO) continue
            val extra = p.extra ?: continue
            if (!extra.startsWith("http")) continue
            if (p.url.isBlank()) continue
            if (!File(p.url).isFile) continue
            installed.putIfAbsent(p.url, extra)
        }
        if (installed.isEmpty()) return 0
        val repos = runCatching { store.repos() }.getOrDefault(emptyList())
            .filter { it.kind == RepoKind.NUVIO && it.url.startsWith("http") }
        if (repos.isEmpty()) return 0

        // Served scraper URLs: every spelling of a manifest entry's file URL,
        // mapped to the exact bytes URL (manifest `scrapers`, like the
        // Extensions screen's own listing).
        val servedUrlFor = ConcurrentHashMap<String, String>()
        // A loose identity (the branch-blind file key) can name TWO different
        // files in one repo. This updater has no hash gate — a wrong download
        // that still validates would SILENTLY REPLACE the provider — so a key
        // claimed by more than one URL is ambiguous and never used for a swap.
        val ambiguousKeys = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            val gate = Semaphore(4)
            repos.map { repo ->
                async {
                    gate.withPermit {
                        for (url in fetchManifestEntries(repo.url)) {
                            for (key in SourceUrls.matchKeys(url)) {
                                val prev = servedUrlFor.putIfAbsent(key, url)
                                if (prev != null && prev != url) ambiguousKeys.add(key)
                            }
                        }
                    }
                }
            }.forEach { runCatching { it.await() } }
        }
        if (servedUrlFor.isEmpty()) {
            Logs.log(TAG, "no nuvio manifests readable — will retry next launch")
            return 0
        }

        var updated = 0
        for ((path, source) in installed) {
            if (updated >= MAX_UPDATES_PER_RUN) break
            val keys = SourceUrls.matchKeys(source)
            val key = keys.firstOrNull { servedUrlFor.containsKey(it) && it !in ambiguousKeys } ?: continue
            val downloadUrl = servedUrlFor[key] ?: continue
            if (updateOne(app, path, source, downloadUrl)) {
                updated++
                Logs.log(TAG, "auto-updated ${File(path).name}")
            }
        }
        if (updated > 0) Logs.log(TAG, "replaced $updated scraper file(s)")
        return updated
    }

    /** One file: download, diff against the installed bytes, validate, swap. */
    private suspend fun updateOne(app: HikariApp, path: String, source: String, downloadUrl: String): Boolean {
        val served = runCatching { Http.fetchBytesCancellable(downloadUrl) }.getOrNull()
            ?: return false
        if (served.isEmpty() || served.size > MAX_BYTES) return false
        if (looksLikeHtml(served)) {
            Logs.log(TAG, "served HTML for $downloadUrl — skipped")
            return false
        }
        val file = File(path)
        val current = runCatching { file.readBytes() }.getOrNull() ?: return false
        // The vendored patches are part of the installed file by design: apply
        // the same content-gated patch to the served bytes first, so an
        // unchanged upstream compares EQUAL and a changed one is judged (and
        // validated) as the bytes that would actually be installed.
        val effective = runCatching {
            NuvioPluginManager.patchedBytes(source, served) ?: served
        }.getOrNull() ?: served
        if (effective.contentEquals(current)) return false
        if (!NuvioRuntime.validate(app, String(effective, Charsets.UTF_8)).startsWith("OK")) {
            Logs.log(TAG, "new ${file.name} is not a valid provider — kept the previous copy")
            return false
        }
        val backup = File("$path.bak")
        return try {
            runCatching { backup.writeBytes(current) }
            file.setWritable(true)
            file.writeBytes(effective)
            runCatching { backup.delete() }
            true
        } catch (t: Throwable) {
            runCatching {
                file.writeBytes(current)
                backup.delete()
            }
            Logs.log(TAG, "update of ${file.name} failed (${t.javaClass.simpleName}) — kept the previous copy")
            false
        }
    }

    /** Every scraper file URL a Nuvio repo manifest serves. */
    private suspend fun fetchManifestEntries(repoUrl: String): List<String> {
        val out = ArrayList<String>()
        val text = fetchFirst(repoUrl) ?: return out
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return out
        // baseUrl = manifest URL minus /manifest.json (same rule the
        // Extensions screen's listing uses).
        val baseUrl = repoUrl.substringBeforeLast('/')
        root.optJSONArray("scrapers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { NuvioPluginManager.repoPlugin(o, baseUrl)?.url }
                    .getOrNull()?.let { out += it }
            }
        }
        return out
    }

    /** First readable manifest body across URL spellings + CDN mirror. */
    private suspend fun fetchFirst(repoUrl: String): String? {
        for (candidate in manifestCandidates(repoUrl)) {
            val text = runCatching {
                Http.fetchStringRobust(
                    candidate,
                    mapOf("User-Agent" to Http.NUVIO_UA),
                    REPO_TIMEOUT_SEC,
                ).getOrNull()
            }.getOrNull()
            if (text.isNullOrBlank() || looksLikeHtml(text.toByteArray())) continue
            return text
        }
        return null
    }

    private fun manifestCandidates(repoUrl: String): List<String> {
        val t = repoUrl.trim().trimEnd('/')
        val base = ArrayList<String>()
        if (t.endsWith("/manifest.json", ignoreCase = true)) {
            base += t
            base += t.replace("/refs/heads/", "/")
        } else {
            base += "$t/manifest.json"
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

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val t = String(bytes.take(64).toByteArray(), Charsets.UTF_8).trimStart().lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")
    }
}
