@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.io.InputStreamReader

/**
 * Loads compiled CloudStream `.cs3` plugin archives exactly the way the real
 * CloudStream app does (see CloudStream-3 `PluginManager.loadPlugin`):
 *
 *  1. mark the file read-only — Android 14+ refuses to load a writable dex
 *     file (`SecurityException: Writable dex file ... is not allowed`),
 *  2. open a [dalvik.system.PathClassLoader] on the archive,
 *  3. read `manifest.json` → `pluginClassName` (+ `requiresResources`),
 *  4. instantiate that class with a no-arg constructor,
 *  5. set `filename`, load optional resources, call `load()`,
 *  6. collect the MainAPIs the plugin registered (via `APIHolder.allProviders`).
 *
 * The whole real CloudStream runtime ships inside the app (`libs/cloudstream3.jar`),
 * so plugins get their genuine extractors, M3u8Helper, nicehttp etc. for free.
 *
 * Instances are cached per file path so provider state survives across calls.
 * The last failure (if any) is surfaced on [lastError] so the UI can show the
 * REAL reason a plugin refused to load.
 */
object Cs3PluginManager {

    private val cache = HashMap<String, List<MainAPI>>()

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = StringBuilder()

    private fun record(what: String, e: Throwable) {
        val line = "$what: ${e.javaClass.simpleName}: ${e.message}"
        if (errorDetails.length < 4000) {
            errorDetails.append(line).append("\n")
        }
        android.util.Log.e("Cs3PluginManager", line, e)
    }

    @Synchronized
    fun apisFor(context: Context, file: File): List<MainAPI> =
        cache.getOrPut(file.absolutePath) { loadFile(context, file) }

    @Synchronized
    fun reload(context: Context, file: File): List<MainAPI> {
        val apis = loadFile(context, file)
        cache[file.absolutePath] = apis
        return apis
    }

    private fun loadFile(context: Context, file: File): List<MainAPI> {
        errorDetails.setLength(0)
        lastError = null
        val path = file.absolutePath

        // 1) CloudStream does this first: Android 14+ refuses writable dex files.
        try {
            if (!file.setReadOnly()) {
                record("setReadOnly failed", RuntimeException("could not mark ${file.name} read-only"))
            }
        } catch (e: Throwable) {
            record("setReadOnly threw", e)
        }

        // 2) Open a class loader on the archive. The real CloudStream runtime
        //    classes live in the app itself, so the parent loader resolves them.
        val classLoader = try {
            dalvik.system.PathClassLoader(path, context.classLoader)
        } catch (e: Throwable) {
            record("PathClassLoader failed", e)
            return fail()
        }

        // 3) manifest.json → pluginClassName (+ requiresResources)
        val manifest = try {
            val stream = classLoader.getResourceAsStream("manifest.json")
            if (stream == null) {
                record("manifest missing", RuntimeException("no manifest.json in ${file.name}"))
                return fail()
            }
            stream.use {
                AppUtils.parseJson(InputStreamReader(it).readText(), BasePlugin.Manifest::class)
            }
        } catch (e: Throwable) {
            record("manifest read failed", e)
            return fail()
        }

        // 4) instantiate the plugin class with a no-arg constructor
        val instance = try {
            @Suppress("UNCHECKED_CAST")
            val pluginClass =
                classLoader.loadClass(manifest.pluginClassName) as Class<out BasePlugin>
            pluginClass.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            record("loadClass/instantiate ${manifest.pluginClassName} failed", e)
            return fail()
        }

        // Drop any earlier registrations from this exact file (reinstall).
        try {
            APIHolder.allProviders.removeAll { it.sourcePlugin == path }
            extractorApis.removeAll { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("cleanup old registrations failed", e)
        }

        // 5) CloudStream sets filename + optional resources, then load().
        try {
            instance.filename = path
            if (manifest.requiresResources) {
                try {
                    val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addPath =
                        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                    addPath.invoke(assets, path)
                    @Suppress("DEPRECATION")
                    (instance as? Plugin)?.resources = Resources(
                        assets as AssetManager,
                        context.resources.displayMetrics,
                        context.resources.configuration
                    )
                } catch (e: Throwable) {
                    record("resource loading failed", e)
                }
            }
            if (instance is Plugin) {
                instance.load(context)
            } else {
                instance.load()
            }
        } catch (e: Throwable) {
            record("load() threw", e)
            return fail()
        }

        // 6) collect the providers this plugin registered
        val apis = try {
            APIHolder.allProviders.filter { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("collecting providers failed", e)
            return fail()
        }
        if (apis.isEmpty()) {
            val details = errorDetails.toString().trim()
            lastError = if (details.isNotBlank()) {
                details
            } else {
                "Plugin loaded but registered no providers"
            }
        }
        return apis
    }

    private fun fail(): List<MainAPI> {
        val details = errorDetails.toString().trim()
        lastError = if (details.isNotBlank()) details else "Unknown error loading plugin"
        return emptyList()
    }
}
