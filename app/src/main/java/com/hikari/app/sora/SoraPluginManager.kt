package com.hikari.app.sora

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.AppStore
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Install/uninstall of Sora modules, plus first-run seeding of the Sora
 * module repos so the feature works out of the box.
 *
 * A Sora repo is either ONE module manifest (a `{sourceName, scriptUrl, …}`
 * JSON file like `streamex.json`) or an INDEX (`modules.json`) pointing at
 * many manifests (see [repoPlugins]). Only `anime` (video) modules install:
 * manga/novel modules are listed so the repo reads complete, but installing
 * one fails with the reason — there is no novel reader yet, and manga modules
 * speak a different call shape the manga engine does not run.
 *
 * Like every other engine, nothing is pre-installed for the user: the repos
 * are seeded so the Extensions screen's Sora folders are never empty, and
 * which modules are installed is the user's choice.
 */
object SoraPluginManager {

    fun modulesDir(context: Context): File =
        File(context.filesDir, "sora/modules").apply { mkdirs() }

    fun moduleDir(context: Context, safeId: String): File =
        File(modulesDir(context), safe(safeId))

    fun scriptFile(context: Context, safeId: String): File =
        File(moduleDir(context, safeId), "module.js")

    fun manifestFile(context: Context, safeId: String): File =
        File(moduleDir(context, safeId), "manifest.json")

    fun dirOf(config: ProviderConfig): File =
        moduleDir(HikariApp.instance, config.id.removePrefix("sora|"))

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "module" }.take(80)

    const val MAX_BYTES = 2 * 1024 * 1024

    /** The built-in Sora module repositories. */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/xdfkenny/Sora-Modules/main/modules.json",
            "Sora Modules",
            "Community Sora modules (anime, films & series)",
        ),
        Triple(
            "https://raw.githubusercontent.com/justbbcr/streamex/main/streamex.json",
            "StreameX",
            "French anime/shows/movies Sora module",
        ),
    )

    /** Adds the default repos once. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.SORA)) }
        }
    }

    /**
     * The installable entries of a Sora repo listing.
     *
     * Two shapes: an INDEX (`{modules: [{name, iconUrl, manifestUrl, …}]}`)
     * and a bare MODULE MANIFEST (`{sourceName, scriptUrl, type, …}` — the
     * repo IS the module, e.g. `streamex.json`). A module entry's `url` is
     * its MANIFEST url; the script is resolved at install/test time so a
     * listing never pays for every module's script up front.
     */
    fun repoPlugins(indexText: String, indexUrl: String): List<Cs3RepoPlugin> {
        val root = runCatching { JSONObject(indexText) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        val modules = root.optJSONArray("modules")
        if (modules != null) {
            for (i in 0 until modules.length()) {
                val o = modules.optJSONObject(i) ?: continue
                val manifest = o.optString("manifestUrl").trim().ifBlank { continue }
                val name = o.optString("name").trim()
                    .ifBlank { manifest.substringAfterLast('/').substringBeforeLast('.') }
                    .ifBlank { continue }
                out[manifest] = Cs3RepoPlugin(
                    name = name,
                    description = listOfNotNull(
                        o.optString("category").trim().ifBlank { null },
                        o.optString("note").trim().ifBlank { null },
                    ).joinToString(" · ").ifBlank { "Sora module" },
                    url = manifest,
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    version = 1,
                    nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o),
                )
            }
            return out.values.toList()
        }
        singlePlugin(root, indexUrl)?.let { out[it.url] = it }
        return out.values.toList()
    }

    /** One bare-manifest repo IS one module (see [repoPlugins]). */
    fun singlePlugin(manifest: JSONObject, manifestUrl: String): Cs3RepoPlugin? {
        val script = manifest.optString("scriptUrl").trim()
        if (script.isBlank()) return null
        val name = manifest.optString("sourceName").trim().ifBlank { return null }
        val type = manifest.optString("type").trim()
        val desc = listOfNotNull(
            manifest.optString("language").trim().ifBlank { null },
            type.ifBlank { null },
            manifest.optString("version").trim().ifBlank { null }?.let { "v$it" },
        ).joinToString(" · ").ifBlank { "Sora module" }
        return Cs3RepoPlugin(
            name = name,
            description = desc,
            url = manifestUrl,
            iconUrl = manifest.optString("iconUrl").ifBlank { null },
            version = manifest.optString("version").substringBefore('.').toIntOrNull() ?: 1,
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(manifest),
        )
    }

    /** True when the manifest at [manifestUrl] is a video (`anime`) module. */
    fun manifestType(manifestText: String): String =
        runCatching { JSONObject(manifestText).optString("type").trim() }.getOrDefault("")

    fun isVideoType(type: String): Boolean = type.contains("anime", ignoreCase = true)

    /**
     * Downloads a module manifest + script and registers it as a SORA
     * provider. Only video modules install — manga/novel modules fail with
     * the reason instead of a broken provider row.
     */
    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            val manifestText = runCatching {
                Http.fetchStringRobust(plugin.url, emptyMap(), 30).getOrThrow()
            }.getOrElse {
                return@withContext Result.failure(Exception("Could not download module manifest"))
            }
            val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
                return@withContext Result.failure(Exception("Module manifest is not JSON"))
            }
            val type = manifest.optString("type").trim()
            if (!isVideoType(type)) {
                return@withContext Result.failure(
                    Exception(
                        if (type.isBlank()) "Not a Sora module manifest"
                        else "Only anime (video) modules install — “${plugin.name}” is “$type”"
                    )
                )
            }
            val scriptUrl = manifest.optString("scriptUrl").trim()
            if (scriptUrl.isBlank()) {
                return@withContext Result.failure(Exception("Module manifest has no scriptUrl"))
            }
            val bytes = runCatching {
                Http.fetchBytesCancellable(scriptUrl, mapOf("User-Agent" to Http.UA), 60)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("Could not download module script"))
            }
            val source = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (source.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Module script is empty"))
            }
            val verdict = SoraRuntime.validate(context, source)
            if (verdict != "OK") {
                val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(200)
                else "it does not export the Sora entry points"
                return@withContext Result.failure(Exception("Not a valid Sora module: $detail"))
            }
            val name = manifest.optString("sourceName").trim().ifBlank { plugin.name }
            val id = "sora|" + safe(
                name.ifBlank { scriptUrl.substringAfterLast('/').substringBeforeLast('.') }
            )
            val dir = moduleDir(context, id.removePrefix("sora|"))
            runCatching { dir.mkdirs() }
            val wrote = runCatching {
                File(dir, "module.js").writeText(source)
                File(dir, "manifest.json").writeText(manifestText)
                true
            }.getOrDefault(false)
            if (!wrote) {
                return@withContext Result.failure(Exception("Could not write the module to storage"))
            }
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = id,
                    name = name,
                    type = ProviderType.SORA,
                    url = File(dir, "module.js").absolutePath,
                    iconUrl = manifest.optString("iconUrl").ifBlank { plugin.iconUrl },
                    extra = plugin.url,
                )
            )
            HikariApp.instance.providers.refresh()
            Result.success(1)
        }

    /**
     * Removes every SORA provider installed from [pluginUrl] (and its files).
     * Returns how many were removed.
     */
    suspend fun uninstall(context: Context, pluginUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter { it.type == ProviderType.SORA && it.extra == pluginUrl }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.SORA && it.extra == pluginUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.SORA }.map { it.url }.toSet()
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

    /** Whether the module's script file still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.SORA &&
            (config.url.isBlank() || !File(config.url).exists())
}
