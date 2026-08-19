@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.hikari.app.HikariApp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

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

    private val cache = ConcurrentHashMap<String, List<MainAPI>>()

    // Files whose load() is currently running (re-entrancy guard). Loading
    // itself is serialized under loadLock; the set just lets a re-entrant call
    // from inside load() detect that it is mid-load.
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val loadLock = java.util.concurrent.locks.ReentrantLock()

    // Paths whose load() just failed, with the failure timestamp. A failed
    // load is NOT retried hot — every attempt can block for up to
    // LOAD_TIMEOUT_S — so callers get a fast empty result until the window
    // passes, then the load is attempted again (and can self-heal).
    private val lastFail = ConcurrentHashMap<String, Long>()

    private const val FAIL_RETRY_MS = 60_000L

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = StringBuilder()

    // Plugins run real code in load() and some do network work there (a couple
    // of repos' plugins fetch repo lists on load). A hung load() must never
    // stall an install forever, so load() runs on its own thread with a hard
    // timeout — the caller gets a clean "plugin load timed out" instead of an
    // eternal spinner.
    private val loadExecutor =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "cs3-load").apply { isDaemon = true }
        }

    private const val LOAD_TIMEOUT_S = 45L

    private fun record(what: String, e: Throwable) {
        val line = "$what: ${e.javaClass.simpleName}: ${e.message}"
        if (errorDetails.length < 4000) {
            errorDetails.append(line).append("\n")
        }
        android.util.Log.e("Cs3PluginManager", line, e)
    }

    /**
     * Cached plugin APIs, loading on demand. NEVER loads a plugin on the UI
     * thread (dex loading + plugin load() code can block for seconds → ANR).
     * On IO threads a call BLOCKS until the load finishes and returns the real
     * result — callers cache whatever apisFor returns (e.g. a provider's
     * `api`), so a sneaky empty shortcut would poison that cache permanently
     * and the provider would report "no catalog" forever. A plugin whose load
     * genuinely failed is instead negative-cached for a short window so it is
     * not retried hot.
     */
    fun apisFor(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        cache[path]?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return emptyList()
        val failAt = lastFail[path]
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) return emptyList()
        loadLock.lock()
        try {
            cache[path]?.let { return it }
            if (path in loading) {
                // Re-entrant call from inside a plugin load() — report "not
                // loaded yet" rather than deadlock on our own lock.
                return emptyList()
            }
            loading.add(path)
            try {
                val apis = loadFile(context, file)
                if (apis.isNotEmpty()) {
                    cache[path] = apis
                    lastFail.remove(path)
                } else {
                    lastFail[path] = System.currentTimeMillis()
                }
                return apis
            } finally {
                loading.remove(path)
            }
        } finally {
            loadLock.unlock()
        }
    }

    /** Re-loads after an install/uninstall. The installer runs on IO, so it
     *  may wait for a previous load to finish. */
    fun reload(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        loadLock.lock()
        try {
            loading.add(path)
            val apis = loadFile(context, file)
            if (apis.isNotEmpty()) {
                cache[path] = apis
                lastFail.remove(path)
            } else {
                cache.remove(path)
                lastFail[path] = System.currentTimeMillis()
            }
            return apis
        } finally {
            loading.remove(path)
            loadLock.unlock()
        }
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
            // Plugins can do real (network) work in load() — run it on the
            // executor with a hard timeout so a hung plugin can never leave
            // the install spinner stuck forever.
            val task = java.util.concurrent.Callable<Any?> {
                if (instance is Plugin) {
                    instance.load(HikariApp.mainActivity ?: context)
                } else {
                    instance.load()
                }
                null
            }
            val future = loadExecutor.submit(task)
            try {
                future.get(LOAD_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                record(
                    "load() timed out after ${LOAD_TIMEOUT_S}s",
                    RuntimeException("${manifest.pluginClassName}.load() hung")
                )
                return fail()
            } catch (e: Throwable) {
                future.cancel(true)
                record("load() threw", e)
                return fail()
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
        // Some plugins read the app off their providers (e.g. `MainAPI.app`)
        // after load. The real CloudStream host sets it to the activity —
        // mirror that, locating the field wherever the jar puts it (instance
        // member, companion, or a provider subclass override).
        HikariApp.mainActivity?.let { activity ->
            apis.forEach { api ->
                runCatching {
                    var done = false
                    var c: Class<*>? = api.javaClass
                    while (c != null && !done) {
                        runCatching { c.getField("app").set(api, activity); done = true }
                        if (!done) runCatching {
                            c.getDeclaredField("app").apply { isAccessible = true }
                                .set(api, activity); done = true
                        }
                        c = c.superclass
                    }
                    if (!done) {
                        runCatching {
                            val holder = api.javaClass.getField("Companion").get(null)
                            holder.javaClass.getField("app").set(holder, activity)
                        }
                    }
                }
            }
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
