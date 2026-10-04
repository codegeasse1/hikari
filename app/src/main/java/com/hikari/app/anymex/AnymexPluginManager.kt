package com.hikari.app.anymex

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AnymexPluginManager {

    fun modulesDir(context: Context): File =
        File(context.filesDir, "anymex/modules").apply { mkdirs() }

    fun moduleDir(context: Context, safeId: String): File =
        File(modulesDir(context), safe(safeId))

    fun dirOf(config: ProviderConfig): File {
        val id = config.id
        val bare = when {
            id.startsWith("anymex|") -> id.removePrefix("anymex|")
            id.startsWith("anymexm|") -> id.removePrefix("anymexm|")
            else -> id
        }
        return moduleDir(HikariApp.instance, bare)
    }

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "module" }.take(80)

    const val MAX_BYTES = 2 * 1024 * 1024

    // MiraiEnoki.github.io indexes 404'd (HTML error pages) — keep only
    // indexes that actually serve JSON. Users can still paste any index URL.
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/gato404/kegareta-sauces/main/anime_index.json",
            "Kegareta Anime",
            "Anymex/Mangayomi JS anime extensions (kegareta-sauces)",
        ),
        Triple(
            "https://raw.githubusercontent.com/gato404/kegareta-sauces/main/index.json",
            "Kegareta Manga",
            "Anymex/Mangayomi JS manga extensions (kegareta-sauces)",
        ),
        // (Mangayomi repo adding was removed — its index never serves a
        // catalogue any installed extension can list. Kept installs keep
        // working; nothing new is seeded from here.)
    )

    suspend fun seedDefaults(context: Context, store: AppStore) {
        // Drop known-dead indexes that still sit in older installs (404 HTML).
        val deadHints = listOf("miraienoki", "anymex anime", "anymex manga", "kodjodevf/mangayomi", "mangayomi-extensions")
        runCatching {
            store.repos()
                .filter { r ->
                    r.kind == RepoKind.ANYMEX && deadHints.any { h ->
                        r.url.contains(h, true) || r.name.contains(h, true)
                    }
                }
                .forEach { r -> runCatching { store.removeCs3Repo(r.url) } }
        }
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANYMEX)) }
        }
    }

    /** Home page for Cloudflare verify — from meta.json written at install. */
    fun siteUrlOf(config: ProviderConfig): String? {
        val metaFile = File(dirOf(config), "meta.json")
        if (!metaFile.exists()) return null
        val base = runCatching {
            JSONObject(metaFile.readText()).optString("baseUrl").trim()
        }.getOrNull().orEmpty()
        if (base.startsWith("http")) return base
        // Fall back to the source URL's origin when the module declares none.
        val src = config.extra?.trim().orEmpty()
        if (src.startsWith("http")) {
            return runCatching {
                val u = java.net.URI(src)
                "${u.scheme}://${u.host}/"
            }.getOrNull()
        }
        return null
    }

    fun repoPlugins(indexText: String, indexUrl: String): List<Cs3RepoPlugin> {
        val t = indexText.trim()
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        runCatching {
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    pluginOf(arr.optJSONObject(i) ?: continue)?.let { out[it.url] = it }
                }
                return out.values.toList()
            }
            val root = JSONObject(t)
            for (key in listOf("extensions", "sources", "plugins", "modules", "list")) {
                val arr = root.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    pluginOf(arr.optJSONObject(i) ?: continue)?.let { out[it.url] = it }
                }
            }
            if (out.isNotEmpty()) return out.values.toList()
            pluginOf(root)?.let { out[it.url] = it }
        }
        return out.values.toList()
    }

    fun looksLikeAnymex(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return runCatching {
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                if (arr.length() == 0) return false
                val o = arr.optJSONObject(0) ?: return false
                o.has("sourceCodeUrl") || o.has("pkgPath")
            } else {
                val o = JSONObject(t)
                o.has("sourceCodeUrl") || o.has("pkgPath") ||
                    o.has("extensions") || o.has("sources")
            }
        }.getOrDefault(false)
    }

    private fun pluginOf(o: JSONObject): Cs3RepoPlugin? {
        val codeUrl = o.optString("sourceCodeUrl").trim()
        val pkgPath = o.optString("pkgPath").trim()
        val url = codeUrl.ifBlank { null } ?: return null
        val name = o.optString("name").trim().ifBlank { return null }
        // Multi-lang packages (MangaDex) only ship langs[] — pick en, else first.
        var lang = o.optString("lang").trim()
        if (lang.isBlank()) {
            val langs = o.optJSONArray("langs")
            if (langs != null && langs.length() > 0) {
                lang = (0 until langs.length()).map { langs.optString(it) }
                    .firstOrNull { it.equals("en", true) }
                    ?: langs.optString(0).trim()
            }
        }
        if (lang.isBlank()) lang = "en"
        val version = o.optString("version").trim()
        val baseUrl = o.optString("baseUrl").trim()
        val apiUrl = o.optString("apiUrl").trim()
        val isMangaEntry = o.optBoolean("isManga", false) || o.optInt("itemType", 1) == 0
        val meta = JSONObject()
            .put("baseUrl", baseUrl)
            .put("apiUrl", apiUrl)
            .put("lang", lang)
            .put("isManga", isMangaEntry)
            .toString()
        val dartOnly = o.optInt("sourceCodeLanguage", 1) == 0 &&
            !url.endsWith(".js", true) &&
            !url.contains("/javascript/", true)
        val kind = when {
            isMangaEntry -> "manga"
            else -> "anime"
        }
        val desc = listOfNotNull(
            lang.ifBlank { null },
            kind,
            version.ifBlank { null }?.let { "v$it" },
            if (dartOnly) "Dart-only" else null,
            if (pkgPath.isNotBlank()) pkgPath.substringBefore("/src/") else null,
        ).joinToString(" · ").ifBlank { "Anymex extension" }
        return Cs3RepoPlugin(
            name = name,
            description = desc,
            url = url,
            iconUrl = o.optString("iconUrl").ifBlank { null },
            version = 1,
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o),
            sourceMeta = meta,
        )
    }

    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            // Dart-only Mangayomi entries have no runnable JS — refuse before
            // download so the UI never falls through to "Module manifest is not JSON".
            val metaHint = plugin.description
            if (metaHint.contains("Dart-only", ignoreCase = true) ||
                (!plugin.url.endsWith(".js", ignoreCase = true) &&
                    !plugin.url.contains("/javascript/", ignoreCase = true))
            ) {
                // Still allow when URL is clearly a .js script.
                if (!plugin.url.endsWith(".js", ignoreCase = true)) {
                    return@withContext Result.failure(
                        Exception(
                            "This extension is Dart-only and needs the Mangayomi app — " +
                                "Hikari runs JavaScript Anymex modules only.",
                        ),
                    )
                }
            }
            val bytes = runCatching {
                Http.fetchBytesCancellable(plugin.url, mapOf("User-Agent" to Http.UA), 60)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("Could not download extension script"))
            }
            val source = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (source.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Extension script is empty"))
            }
            // HTML error pages (404) look like "not an extension".
            val trimmed = source.trimStart()
            if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
                return@withContext Result.failure(
                    Exception("Download returned a web page, not a script — the source URL may be dead."),
                )
            }
            if (!source.contains("DefaultExtension") || !source.contains("MProvider")) {
                return@withContext Result.failure(Exception("Not a Mangayomi/Anymex extension"))
            }
            val entry = runCatching { JSONObject(plugin.sourceMeta) }.getOrNull()
            val hostMeta = JSONObject()
                .put("name", plugin.name)
                .put("baseUrl", entry?.optString("baseUrl").orEmpty())
                .put("lang", entry?.optString("lang").orEmpty())
                .put("iconUrl", plugin.iconUrl.orEmpty())
                .toString()
            val info = runCatching {
                AnymexRuntime.inspect(context, source, hostMeta)
            }.getOrNull()
            // Some scripts throw on first probe (missing optional host APIs) but
            // still define DefaultExtension and run fine once prefs are seeded.
            // Accept any script that clearly is a Mangayomi module and let the
            // first catalog call surface a real error if it cannot run.
            val looksValid = source.contains("DefaultExtension") &&
                (source.contains("MProvider") || source.contains("getPopular") ||
                    source.contains("getVideoList") || source.contains("getPageList"))
            if ((info == null || !info.ok) && !looksValid) {
                val detail = info?.name?.takeIf { it.isNotBlank() }
                return@withContext Result.failure(
                    Exception(
                        if (detail != null) "Extension script failed to load: $detail"
                        else "Extension script failed to load",
                    ),
                )
            }
            val entryManga = runCatching {
                JSONObject(plugin.sourceMeta).optBoolean("isManga", false)
            }.getOrDefault(false)
            val entryBaseUrl = runCatching {
                JSONObject(plugin.sourceMeta).optString("baseUrl")
            }.getOrDefault("")
            val entryLang = runCatching {
                JSONObject(plugin.sourceMeta).optString("lang")
            }.getOrDefault("")
            val isManga = entryManga || (info?.isManga == true)
            val name = info?.name?.ifBlank { null } ?: plugin.name
            val prefix = if (isManga) "anymexm|" else "anymex|"
            val type = if (isManga) ProviderType.ANYMEX_MANGA else ProviderType.ANYMEX
            // ID must be unique per source URL — safe(name) collapsed every
            // non-ASCII name to underscores, so installing one Chinese manga
            // replaced another (Install A → B shows Uninstall, Install B → A gone).
            val idKey = plugin.url.trim().ifBlank { name }
                .substringAfter("://")
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                .takeLast(72)
                .ifBlank { Integer.toHexString(plugin.url.hashCode()) }
            val id = prefix + idKey
            val dir = moduleDir(context, id.removePrefix(prefix))
            runCatching { dir.mkdirs() }
            val wrote = runCatching {
                File(dir, "module.js").writeText(source)
                val entryApiUrl = runCatching {
                    JSONObject(plugin.sourceMeta).optString("apiUrl")
                }.getOrNull().orEmpty()
                File(dir, "meta.json").writeText(
                    JSONObject().put("name", name).put("isManga", isManga)
                        .put("sourceUrl", plugin.url).put("baseUrl", entryBaseUrl)
                        .put("apiUrl", entryApiUrl)
                        .put("lang", entryLang.ifBlank { "en" })
                        .put("iconUrl", plugin.iconUrl.orEmpty()).toString(),
                )
                true
            }.getOrDefault(false)
            if (!wrote) {
                return@withContext Result.failure(Exception("Could not write the extension to storage"))
            }
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = id,
                    name = name,
                    type = type,
                    url = File(dir, "module.js").absolutePath,
                    iconUrl = plugin.iconUrl,
                    extra = plugin.url,
                ),
            )
            HikariApp.instance.providers.refresh()
            Result.success(1)
        }

    suspend fun uninstall(context: Context, pluginUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter {
            (it.type == ProviderType.ANYMEX || it.type == ProviderType.ANYMEX_MANGA) && it.extra == pluginUrl
        }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot {
                (it.type == ProviderType.ANYMEX || it.type == ProviderType.ANYMEX_MANGA) && it.extra == pluginUrl
            }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers()
                .filter { it.type == ProviderType.ANYMEX || it.type == ProviderType.ANYMEX_MANGA }
                .map { it.url }.toSet()
            mine.map { it.url }.distinct().forEach { path ->
                if (path !in keep) {
                    runCatching { File(path).delete() }
                    runCatching {
                        val dir = File(path).parentFile
                        if (dir != null && dir.name != "modules" &&
                            (dir.listFiles()?.isEmpty() != false)
                        ) dir.delete()
                    }
                }
            }
        }
        return mine.size
    }

    fun fileMissing(config: ProviderConfig): Boolean =
        (config.type == ProviderType.ANYMEX || config.type == ProviderType.ANYMEX_MANGA) &&
            (config.url.isBlank() || !File(config.url).exists())
}
