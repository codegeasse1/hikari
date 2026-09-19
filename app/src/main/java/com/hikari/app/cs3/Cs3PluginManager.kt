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

    /**
     * The instantiated plugin object per file path. CloudStream keeps the whole
     * plugin instance around (it owns `openSettings`, resources, etc.); Hikari
     * used to drop it after load(). Keeping it is what lets the Extensions UI
     * show a settings button and open the plugin's own settings screen.
     */
    private val plugins = ConcurrentHashMap<String, BasePlugin>()

    /**
     * Set when a plugin's settings screen has been opened. The host clears it on
     * the next window-focus regain (i.e. when that screen is dismissed) and
     * re-loads the plugin, so settings that don't force an app restart still
     * take effect. Plugins that DO ask for a restart (SKTech) rebuild on launch
     * via [Cs3ProviderSync.reconcile].
     */
    @Volatile
    var pendingSettingsReload: String? = null

    // Files whose load() is currently running (re-entrancy guard). Loading of a
    // given path is serialised under that path's LoadGate lock; this set only
    // lets a re-entrant call from inside a plugin's own load() detect that it is
    // mid-load and report "not loaded yet" instead of recursing.
    private val loading = ConcurrentHashMap.newKeySet<String>()

    // Paths whose load() just failed, with the failure timestamp. A failed
    // load is NOT retried hot — every attempt can block for up to
    // LOAD_TIMEOUT_S — so callers get a fast empty result until the window
    // passes, then the load is attempted again (and can self-heal).
    private val lastFail = ConcurrentHashMap<String, Long>()

    private const val FAIL_RETRY_MS = 60_000L

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Per-thread record of the CURRENT load's failures. This used to be one
     * shared StringBuilder, which meant two plugins loading at once appended to
     * the same buffer and [lastError] could report a completely different
     * plugin's failure than the one that was just asked about. `loadFile` is
     * synchronous on its calling thread, so a ThreadLocal is exactly the right
     * scope: the details collected for one load are the details that load
     * reports.
     */
    private val errorDetails = ThreadLocal.withInitial { StringBuilder() }

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

    /**
     * Unwraps `ExecutionException`/`InvocationTargetException`-style wrappers so
     * the message names the REAL failure. Plugin loads run inside a
     * `Future.get()`, so a plugin that fails to link reported only
     * "ExecutionException: java.lang.NoClassDefFoundError: ..." and the actual
     * missing/duplicate class was hidden one level down. The report is worthless
     * without it, so the whole cause chain is walked.
     */
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
            // Where it was thrown. A third-party settings screen that throws
            // inside its own code is otherwise a dead end: the toast only has
            // room for the exception's name, which names neither the plugin
            // class nor the line that failed.
            for (f in e.stackTrace.take(4)) append("\n    at $f")
        }
        if (errorDetails.get().length < 4000) {
            errorDetails.get().append(line).append("\n")
        }
        android.util.Log.e("Cs3PluginManager", line, e)
        // And into Hikari's own log file, with the full stack trace. A plugin's
        // own settings screen (openSettings) is third-party code that can throw
        // anything, and the one-line message the UI has room for ("… threw:
        // IllegalStateException") names neither the class nor the line — the
        // log trail is what makes it fixable.
        runCatching { com.hikari.app.data.Logs.logError("Cs3PluginManager", line, e) }
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
        // One lock PER PLUGIN, not one for the whole runtime: with a few hundred
        // extensions installed, a single global lock made every load — Home
        // warm-up, cross-extension search, an install — queue behind the longest
        // one (a plugin that does network work in load() can hold it 45s).
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
                // The dex commit itself is one of the process-wide slots, so a
                // Home warm-up and an install can run side by side without
                // loading two dozen archives into memory at once.
                val apis = try {
                    LoadGate.withSlot { loadFile(context, file) }
                } catch (e: LoadGate.LoadQueueBusyException) {
                    // The bounded slot wait expired — report it as THIS plugin's
                    // failure so the caller simply moves on to the next one,
                    // instead of throwing into the coroutine that drives Home
                    // or a cross-extension search (which used to abort the
                    // whole sweep, so nothing after it was ever searched).
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

        // Drop any earlier registrations from this exact file (reinstall).
        try {
            APIHolder.allProviders.removeAll { it.sourcePlugin == path }
            extractorApis.removeAll { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("cleanup old registrations failed", e)
        }

        // 5) CloudStream sets filename + optional resources, then load().
        //
        //    `load()` receives a real Activity whenever one exists: plugins
        //    commonly do `context as AppCompatActivity` (SKTech builds its
        //    settings dialog that way) or read `CommonActivity`, and a bare
        //    Application context throws
        //    `HikariApp cannot be cast to AppCompatActivity`. At startup this
        //    can run before MainActivity exists, so wait briefly for one.
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
            // Plugins can do real (network) work in load() — run it on the
            // executor with a hard timeout so a hung plugin can never leave
            // the install spinner stuck forever.
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
            val details = errorDetails.get().toString().trim()
            lastError = if (details.isNotBlank()) {
                details
            } else {
                "Plugin loaded but registered no providers"
            }
        }
        return apis
    }

    /** True when the cached plugin instance exposes a settings screen. */
    fun hasSettings(file: File): Boolean {
        val plugin = plugins[file.absolutePath] ?: return false
        return plugin is Plugin && plugin.openSettings != null
    }

    /**
     * Makes sure [file]'s plugin is instantiated AND exposes `openSettings`
     * before its settings screen is invoked.
     *
     * The Extensions UI shows its settings gear from the stored provider list,
     * which outlives the in-memory plugin instance: the gear can be tapped
     * before this session ever loaded the plugin, or while a re-load is still
     * running (the instance is published to [plugins] *before* `load()` runs,
     * and plugins assign `openSettings` inside `load()`) — and both cases used
     * to be reported as "this plugin has no settings screen". A tap then just
     * looked broken until a second attempt happened to land after the load.
     *
     * Blocking: [apisFor] waits out any load already in flight and performs the
     * load itself when none is running, so always call this from IO. Returns
     * true when the settings callback is available now.
     */
    fun ensureSettingsLoaded(context: Context, file: File): Boolean {
        if (hasSettings(file)) return true
        apisFor(context, file)
        if (hasSettings(file)) return true
        // Nothing after a real load attempt: the cached instance is the product
        // of a failed/partial load (so `openSettings` never got assigned), and
        // apisFor negative-caches a failed file for a minute. Rebuild it once —
        // that is what clears the combination.
        reload(context, file)
        return hasSettings(file)
    }

    /**
     * Opens the plugin's own settings screen, exactly like CloudStream's tune
     * button. [activity] is the preferred host (may be null — the current
     * activity is resolved instead). Returns false when the plugin has no
     * settings entry point or the callback threw, and sets [lastError] to the
     * specific reason so the UI never reports the misleading catch-all
     * "has no settings screen" for a missing/partial plugin instance.
     */
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
            callback.invoke(host)
            pendingSettingsReload = file.absolutePath
            true
        } catch (e: Throwable) {
            record("openSettings threw", e)
            lastError = errorDetails.get().toString().trim().ifBlank { e.message ?: "settings failed" }
            false
        }
    }

    /**
     * The activity a plugin's settings screen should attach its dialogs and
     * fragments to. A destruction check matters here: a plugin that shows a
     * `DialogFragment` throws `IllegalStateException: FragmentManager has been
     * destroyed` when it is handed an activity that has already gone (the gear
     * is often tapped after a rotation/theme change), so a stale
     * [HikariApp.mainActivity] must never win over a live one.
     */
    private fun liveHost(preferred: android.app.Activity?): android.app.Activity? {
        fun ok(a: android.app.Activity?): Boolean = a != null && !a.isFinishing && !a.isDestroyed
        if (ok(preferred)) return preferred
        if (ok(HikariApp.mainActivity)) return HikariApp.mainActivity
        val common = runCatching { com.lagradost.cloudstream3.CommonActivity.activity }.getOrNull()
        if (ok(common)) return common
        return null
    }

    private const val ACTIVITY_WAIT_MS = 12_000L

    /**
     * Picks the context a plugin's `load()` should receive. Plugins routinely
     * cast it to `AppCompatActivity` or read `CommonActivity`, so an Application
     * context crashes them; prefer the current activity, and if none exists yet
     * (startup warms plugins before MainActivity is up) wait briefly for one.
     * Never called on the main thread — the caller is always loadFile's IO path.
     */
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
