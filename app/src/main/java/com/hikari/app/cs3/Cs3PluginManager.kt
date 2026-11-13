@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.hikari.app.HikariApp
import com.hikari.app.core.LoadGate
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
object Cs3PluginManager {

    private val cache = ConcurrentHashMap<String, List<MainAPI>>()

    private val plugins = ConcurrentHashMap<String, BasePlugin>()

    @Volatile
    var pendingSettingsReload: String? = null

    // Files whose load() is currently running (re-entrancy guard). Loading of a
    // given path is serialised under that path's LoadGate lock; this set only
    // lets a re-entrant call from inside a plugin's own load() detect that it is
    // mid-load and report "not loaded yet" instead of recursing.
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val lastFail = ConcurrentHashMap<String, Long>()

    private const val FAIL_RETRY_MS = 60_000L

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = ThreadLocal.withInitial { StringBuilder() }

    private val loadExecutor =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "cs3-load").apply { isDaemon = true }
        }

    private const val LOAD_TIMEOUT_S = 45L

    private fun rootCause(e: Throwable): Throwable {
        var t = e
        var guard = 0
        while (guard++ < 8) {
            val c = t.cause ?: break
            if (c === t) break
            t = c
        }
        return t
    }

    private fun record(what: String, e: Throwable) {
        val root = rootCause(e)
        val line = buildString {
            append("$what: ${e.javaClass.simpleName}: ${e.message}")
            if (root !== e) {
                append(" — caused by ${root.javaClass.name}: ${root.message}")
                // A linking failure's own cause (e.g. the class that could not
                // be resolved) is the actionable part; keep a couple of frames.
                root.cause?.let { append(" (cause: ${it.javaClass.name}: ${it.message})") }
            }
            for (f in e.stackTrace.take(4)) append("\n    at $f")
        }
        if (errorDetails.get().length < 4000) {
            errorDetails.get().append(line).append("\n")
        }
        android.util.Log.e("Cs3PluginManager", line, e)
        runCatching { com.hikari.app.data.Logs.logError("Cs3PluginManager", line, e) }
    }

    fun apisFor(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        cache[path]?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return emptyList()
        val failAt = lastFail[path]
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) return emptyList()
        val lock = LoadGate.lockFor(path)
        if (!LoadGate.acquire(lock)) {
            record("waiting for another load of ${file.name}", RuntimeException("lock wait timed out"))
            return emptyList()
        }
        try {
            cache[path]?.let { return it }
            if (path in loading) {
                // Re-entrant call from inside a plugin's own load() — report
                // "not loaded yet" rather than deadlock on our own lock.
                return emptyList()
            }
            loading.add(path)
            try {
                val apis = try {
                    LoadGate.withSlot { loadFile(context, file) }
                } catch (e: LoadGate.LoadQueueBusyException) {
                    record("plugin loader busy for ${file.name}", e)
                    emptyList()
                }
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
            lock.unlock()
        }
    }

    /** Re-loads after an install/uninstall. The installer runs on IO, so it
     *  may wait for a previous load of the SAME plugin to finish. */
    fun reload(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        val lock = LoadGate.lockFor(path)
        if (!LoadGate.acquire(lock)) {
            record("waiting to reload ${file.name}", RuntimeException("lock wait timed out"))
            return emptyList()
        }
        try {
            loading.add(path)
            val apis = try {
                LoadGate.withSlot { loadFile(context, file) }
            } catch (e: LoadGate.LoadQueueBusyException) {
                record("plugin loader busy while reloading ${file.name}", e)
                emptyList()
            }
            if (apis.isNotEmpty()) {
                cache[path] = apis
                lastFail.remove(path)
            } else {
                cache.remove(path)
                lastFail[path] = System.currentTimeMillis()
            }
            // Reinstall/update must never replay old servers or old failure banners:
            // drop remembered server lists + per-provider errors so Home/Play refetch
            // fresh (old video-only YouTube lists caused permanent silence; old
            // empty-catalog errors stuck after the cause was fixed).
            runCatching { com.hikari.app.data.StreamCache.clearAll() }
            runCatching { com.hikari.app.cs3.Cs3MainApiProvider.clearTransientState() }
            runCatching { com.hikari.app.data.MetaCache.bumpEpisodesEpoch() }
            return apis
        } finally {
            loading.remove(path)
            lock.unlock()
        }
    }

    private fun loadFile(context: Context, file: File): List<MainAPI> {
        errorDetails.get().setLength(0)
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

        // Remember the instance so the settings button can reach its
        // `openSettings` callback later. Replaced on every reload/reinstall.
        plugins[path] = instance

        // Snapshot before load so providers registered under ANY sourcePlugin value
        // (or via a new registration path) are still found by diff below.
        val beforeApis: Set<com.lagradost.cloudstream3.MainAPI> = try {
            APIHolder.allProviders.toSet()
        } catch (_: Throwable) { emptySet() }
        // Drop any earlier registrations from this exact file (reinstall).
        try {
            APIHolder.allProviders.removeAll { it.sourcePlugin == path }
            extractorApis.removeAll { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("cleanup old registrations failed", e)
        }

        val host: Context = resolveHostActivity(context)
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
            val task = java.util.concurrent.Callable<Any?> {
                if (instance is Plugin) {
                    instance.load(host)
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
        val apis = try {
            val exact = APIHolder.allProviders.filter { it.sourcePlugin == path }
            if (exact.isNotEmpty()) exact
            else {
                val fresh = APIHolder.allProviders.filter { it !in beforeApis }
                if (fresh.isNotEmpty()) {
                    // Repair the stamp so reinstalls/reloads stay clean.
                    fresh.forEach { runCatching { it.sourcePlugin = path } }
                    fresh
                } else scanInstanceForProviders(instance, path)
            }
        } catch (e: Throwable) {
            record("collecting providers failed", e)
            return fail()
        }
        if (host is android.app.Activity) {
            val activity = host
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
            // Settings-driven plugins (M3U playlist managers et al) load
            // cleanly and expose openSettings but register zero providers
            // until the user configures them — CloudStream installs those
            // fine (load success = install success; providers appear after
            // setup). Mirror that: soft success, NOT a failure. The caller
            // distinguishes via hasSettings().
            val needsSetup = (instance as? Plugin)?.openSettings != null
            if (needsSetup) {
                lastError = null
            } else {
                val details = errorDetails.get().toString().trim()
                lastError = if (details.isNotBlank()) {
                    details
                } else {
                    // Never leave the generic line alone: include what WAS seen so a
                    // future new-registration path can be diagnosed from the message.
                    val total = runCatching { APIHolder.allProviders.size }.getOrDefault(-1)
                    "Plugin loaded but registered no providers " +
                        "(manifest=${manifest.pluginClassName}, totalProviders=$total)"
                }
            }
        }
        return apis
    }

    private fun scanInstanceForProviders(
        instance: com.lagradost.cloudstream3.plugins.BasePlugin,
        path: String,
    ): List<MainAPI> {
        val found = ArrayList<MainAPI>()
        try {
            var c: Class<*>? = instance.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(instance) ?: continue
                        when {
                            v is MainAPI -> found.add(v)
                            v is Collection<*> -> v.filterIsInstance<MainAPI>().forEach { found.add(it) }
                            v is Array<*> -> v.filterIsInstance<MainAPI>().forEach { found.add(it) }
                        }
                    } catch (_: Throwable) { }
                }
                c = c.superclass
            }
        } catch (_: Throwable) { }
        if (found.isEmpty()) return emptyList()
        found.forEach { api ->
            runCatching { api.sourcePlugin = path }
            runCatching { APIHolder.allProviders.add(api) }
        }
        return found.distinctBy { it.javaClass.name + "#" + it.name }
    }

    /** True when the cached plugin instance exposes a settings screen. */
    fun hasSettings(file: File): Boolean {
        val plugin = plugins[file.absolutePath] ?: return false
        return plugin is Plugin && plugin.openSettings != null
    }

    fun ensureSettingsLoaded(context: Context, file: File): Boolean {
        if (hasSettings(file)) return true
        apisFor(context, file)
        if (hasSettings(file)) return true
        reload(context, file)
        return hasSettings(file)
    }

    fun openSettings(file: File, activity: android.app.Activity?): Boolean {
        val plugin = plugins[file.absolutePath] as? Plugin
        if (plugin == null) {
            lastError = "the plugin hasn't finished loading — try again"
            return false
        }
        val callback = plugin.openSettings
        if (callback == null) {
            lastError = "this plugin exposes no settings screen"
            return false
        }
        val host = liveHost(activity)
        if (host == null) {
            lastError = "no activity is available to show its settings screen"
            return false
        }
        errorDetails.get().setLength(0)
        lastError = null
        return try {
            com.hikari.app.net.ExtensionUiGuard.allowFor(
                com.hikari.app.net.ExtensionUiGuard.SETTINGS_ALLOW_MS
            )
            callback.invoke(host)
            pendingSettingsReload = file.absolutePath
            true
        } catch (e: Throwable) {
            record("openSettings threw", e)
            lastError = errorDetails.get().toString().trim().ifBlank { e.message ?: "settings failed" }
            false
        }
    }

    private fun liveHost(preferred: android.app.Activity?): android.app.Activity? {
        fun ok(a: android.app.Activity?): Boolean = a != null && !a.isFinishing && !a.isDestroyed
        if (ok(preferred)) return preferred
        if (ok(HikariApp.mainActivity)) return HikariApp.mainActivity
        val common = runCatching { com.lagradost.cloudstream3.CommonActivity.activity }.getOrNull()
        if (ok(common)) return common
        return null
    }

    private const val ACTIVITY_WAIT_MS = 12_000L

    private fun resolveHostActivity(context: Context): Context {
        fun usable(c: Context?): Boolean {
            val a = c as? android.app.Activity ?: return false
            return !a.isFinishing && !a.isDestroyed
        }
        fun current(): Context? {
            HikariApp.mainActivity?.let { if (usable(it)) return it }
            val common = runCatching { com.lagradost.cloudstream3.CommonActivity.activity }
                .getOrNull()
            if (usable(common)) return common
            if (usable(context)) return context
            return null
        }
        current()?.let { return it }
        // Never block the UI thread (defensive — loadFile only runs on IO).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return context
        // The task runs on a background thread (loadExecutor), so a bounded
        // sleep here can never freeze the UI.
        val deadline = System.currentTimeMillis() + ACTIVITY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            current()?.let { return it }
        }
        record(
            "no host activity",
            RuntimeException("waited ${ACTIVITY_WAIT_MS}ms; falling back to app context")
        )
        return context
    }

    private fun fail(): List<MainAPI> {
        val details = errorDetails.get().toString().trim()
        lastError = if (details.isNotBlank()) details else "Unknown error loading plugin"
        return emptyList()
    }
}
