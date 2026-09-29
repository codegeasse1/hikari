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

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://MiraiEnoki.github.io/anymex_extensions/anime_index.json",
            "Anymex Anime",
            "Anymex/Mangayomi anime extensions (JavaScript)",
        ),
        Triple(
            "https://MiraiEnoki.github.io/anymex_extensions/index.json",
            "Anymex Manga",
            "Anymex/Mangayomi manga extensions (JavaScript)",
        ),
        Triple(
            "https://MiraiEnoki.github.io/anymex_extensions/novel_index.json",
            "Anymex Novel",
            "Anymex/Mangayomi novel extensions (JavaScript)",
        ),
    )

    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANYMEX)) }
        }
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
        val lang = o.optString("lang").trim()
        val version = o.optString("version").trim()
        val baseUrl = o.optString("baseUrl").trim()
        val isMangaEntry = o.optBoolean("isManga", false) || o.optInt("itemType", 1) == 0
        val meta = JSONObject()
            .put("baseUrl", baseUrl)
            .put("lang", lang)
            .put("isManga", isMangaEntry)
            .toString()
        val dartOnly = o.optInt("sourceCodeLanguage", 1) == 0 && !url.endsWith(".js", true)
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
            if (!source.contains("DefaultExtension") || !source.contains("MProvider")) {
                return@withContext Result.failure(Exception("Not a Mangayomi/Anymex extension"))
            }
            val info = runCatching {
                val entry = runCatching { JSONObject(plugin.sourceMeta) }.getOrNull()
                val hostMeta = JSONObject()
                    .put("name", plugin.name)
                    .put("baseUrl", entry?.optString("baseUrl").orEmpty())
                    .put("lang", entry?.optString("lang").orEmpty())
                    .put("iconUrl", plugin.iconUrl.orEmpty())
                    .toString()
                AnymexRuntime.inspect(context, source, hostMeta)
            }.getOrNull()
            if (info == null || !info.ok) {
                return@withContext Result.failure(Exception("Extension script failed to load"))
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
            val isManga = entryManga || info.isManga
            val name = info.name.ifBlank { plugin.name }
            val prefix = if (isManga) "anymexm|" else "anymex|"
            val type = if (isManga) ProviderType.ANYMEX_MANGA else ProviderType.ANYMEX
            val id = prefix + safe(name.ifBlank { plugin.url.substringAfterLast('/').substringBeforeLast('.') })
            val dir = moduleDir(context, id.removePrefix(prefix))
            runCatching { dir.mkdirs() }
            val wrote = runCatching {
                File(dir, "module.js").writeText(source)
                File(dir, "meta.json").writeText(
                    JSONObject().put("name", name).put("isManga", isManga)
                        .put("sourceUrl", plugin.url).put("baseUrl", entryBaseUrl)
                        .put("lang", entryLang).put("iconUrl", plugin.iconUrl.orEmpty()).toString(),
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
