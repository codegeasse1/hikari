package com.hikari.app

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import com.hikari.app.net.NetTuning
import com.hikari.app.net.SlowNetTip
import com.hikari.app.providers.ProviderManager
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SettingsJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

class HikariApp : Application() {

    companion object {
        lateinit var instance: HikariApp
            private set

        /** Stack trace of the last uncaught crash (shown as a Home banner). */
        @Volatile
        var lastCrash: String? = null
            private set

        /**
         * The current MainActivity, set on create and cleared on destroy. The
         * real CloudStream host passes its AppCompatActivity to plugin load()
         * (some plugins cast it — e.g. SKTech's `as AppCompatActivity`), so a
         * bare Application context makes those plugins throw
         * ClassCastException. Kept set while the app is merely backgrounded so a
         * plugin load from a background coroutine still gets an Activity.
         */
        @Volatile
        var mainActivity: MainActivity? = null

        /**
         * Extension repositories added on the very first run, so a fresh install
         * can install extensions without pasting a URL: the Hikari (.hiki) repo
         * and the CloudStream repo. Seeded once — see [AppStore.seededRepos] —
         * so removing one afterwards sticks.
         */
        private val DEFAULT_EXTENSION_REPOS = listOf(
            Cs3Repo(
                url = "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json",
                name = "Hikari Extensions",
                description = "Official .hiki extensions for Hikari.",
                kind = RepoKind.HIKARI,
            ),
            Cs3Repo(
                url = "https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json",
                name = "Codegeasse Repo",
                description = "Anime4i CloudStream extensions",
                kind = RepoKind.CS3,
            ),
        )
    }

    lateinit var store: AppStore
        private set
    lateinit var providers: ProviderManager
        private set

    /**
     * Process-wide IO scope for work that must OUTLIVE an Activity. The player
     * records resume position / last-used server on its way out (onStop,
     * onDestroy) — an Activity-scoped `lifecycleScope` job is cancelled at
     * DESTROYED before it can commit, which is exactly how watch progress used
     * to vanish and why Continue Watching stayed empty.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Live copy of the persisted element-block selectors (WebView element
     * blocker). Loaded at startup and kept in sync by every block/undo/clear,
     * so a freshly opened WebView can apply them synchronously BEFORE its
     * first page finishes loading — reading the store itself is async and was
     * racing the first page load, which made blocks look \"reset\" after
     * closing and reopening the WebView.
     */
    @Volatile
    var elementBlocks: List<String> = emptyList()

    /** Bumped by the WebView's "Go to app home" menu item; AppRoot watches this
     *  and switches to the app's own Home tab (so the button leaves the site
     *  view instead of reloading the website's home page). */
    val homeTabRequest = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        initCloudStream(this)
        store = AppStore(this)
        providers = ProviderManager(store)
        // "Your connection looks slow?" tip: measures in the background while a
        // play is starting and only speaks up with real evidence (see SlowNetTip).
        SlowNetTip.init(this)
        // Mirror the persisted slow-connection toggle into NetTuning (read
        // synchronously by the search/probe timeouts) and keep it in sync.
        appScope.launch {
            store.slowConnectionFlow().collect { NetTuning.setSlowConnection(it) }
        }
        Http.init()
        setupImageLoader()
        CoroutineScope(Dispatchers.IO).launch {
            // Load the persisted WebView element blocks into the app cache FIRST
            // (fast DataStore read) so a WebView opened right after launch can
            // apply them on its first page instead of showing them after a race.
            runCatching { elementBlocks = store.elementBlocks() }
            // Registering extractor aliases initializes the jar's full extractor
            // registry — do it off the main thread.
            com.hikari.app.cs3.HikariExtractorRegistry.register()
            // WebView UA override state (Settings) — loaded once, kept current
            // by the settings card.
            runCatching {
                webViewUseDefaultUa = store.webviewUseDefaultUa()
                webViewCustomUa = store.webviewCustomUa()
            }
            // Warm up the bundled yt-dlp runtime in the background so the
            // universal fallback extractor is ready when a provider's own
            // engines come up empty. The CPython startup takes a few seconds,
            // so doing it here keeps the first real fallback fast.
            runCatching { com.hikari.app.cs3.YtDlpResolver.ensureInit() }
            // First run: register the bundled Hikari demo extension (YTS) so
            // the extension system ships with a working provider. Harmless if
            // already added — addProvider dedupes by id.
            runCatching {
                if (store.providers().none { it.type == ProviderType.HIKARI }) {
                    store.addProvider(
                        ProviderConfig(
                            id = "hiki|yts",
                            name = "YTS (Hikari)",
                            type = ProviderType.HIKARI,
                            iconUrl = null,
                            extra = "com.hikari.ext.providers.YtsProvider",
                        )
                    )
                }
            }
            // First run: seed the Nuvio provider repos (manifest.json) and a few
            // pre-installed providers so nuvio sources work out of the box.
            runCatching {
                com.hikari.app.nuvio.NuvioPluginManager.seedDefaults(this@HikariApp, store)
            }
            // First run only: add the bundled Hikari (.hiki) and CloudStream
            // extension repos, so the Extensions screen ("Sources, repos &
            // providers") is never empty on a fresh install and the built-in
            // extensions are installable immediately. Guarded by a one-time flag
            // so a user who removes one doesn't get it re-added every launch.
            runCatching {
                if (!store.seededRepos()) {
                    for (r in DEFAULT_EXTENSION_REPOS) store.addCs3Repo(r)
                    store.markReposSeeded()
                }
            }
            // Apply vendored nuvio provider patches (see NuvioPluginManager's
            // PROVIDER_PATCHES) so already-installed broken providers get the
            // fixed JS in place without a manual reinstall.
            runCatching {
                com.hikari.app.nuvio.NuvioPluginManager.applyPatchesToInstalled(this@HikariApp)
            }
            providers.refresh()
            providers.providers.value
                .filterIsInstance<com.hikari.app.cs3.Cs3MainApiProvider>()
                .forEach { it.warm() }
            // A plugin settings change (e.g. SKTech's sub-provider picker) can
            // alter which providers a plugin registers. Warm first so the
            // plugin instances are cached (reconcile then hits the cache), then
            // rebuild the stored configs to match and refresh if anything moved.
            runCatching {
                if (com.hikari.app.cs3.Cs3ProviderSync.reconcile(this@HikariApp, store)) {
                    providers.refresh()
                }
            }
            // Per-extension auto-translate config + persisted translation cache.
            runCatching { com.hikari.app.data.Translator.init(store) }
        }
    }

    /**
     * Never let an uncaught exception (main or background thread) die silently:
     * write the stack to a file, and surface it on the next launch as a banner
     * (see HomeScreen) so crashes get reported instead of guessed at.
     */
    private fun installCrashHandler() {
        runCatching {
            val file = File(cacheDir, "crash.log")
            if (file.exists()) lastCrash = file.readText().take(1600)
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val trace = "${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTrace.take(12).joinToString("\n") { "    at $it" }
                File(cacheDir, "crash.log").writeText(trace)
                lastCrash = trace
            }
            android.util.Log.e("HikariCrash", "Uncaught on ${thread.name}", t)
            // NEVER leave a dead main thread running: that is what turns the
            // screen into a frozen black UI (no back button, nothing responds,
            // the only escape is force-stopping the app). After recording the
            // trace, terminate the process like the platform default would, so
            // Android shows the crash dialog and relaunches cleanly — the Home
            // banner still reports the cause next launch.
            //
            // A BACKGROUND thread is a different story. A dead helper thread
            // hurts nobody, and the exception that most often lands here is a
            // WebView one (Chromium rethrows an escaping @JavascriptInterface
            // call as JniAndroid$UncaughtException, and a crashed renderer used
            // to surface here too) — killing the whole app for that turned an
            // ordinary page glitch into a full crash. Record it so the Home
            // banner still explains what happened, then let the app keep
            // running.
            val onMain = thread === android.os.Looper.getMainLooper().thread
            if (onMain) {
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }
    }

    /**
     * Some CDNs refuse image requests that carry a Referer at all (even a
     * same-host one) while serving the identical URL fine to a bare request —
     * fourhoi.com/surrit.com (MissAV's image+stream CDN) is verified
     * no-referer-only: browsers and plain clients get the JPEG, a same-origin
     * Referer gets 403. These hosts get no Referer header from the image
     * loader; UA stays browser-like for everyone.
     */
    private val NO_REFERER_HOSTS = setOf("fourhoi.com", "surrit.com")

    /** Clear the persisted crash banner after the user dismisses it. */
    fun clearCrash() {
        lastCrash = null
        runCatching { File(cacheDir, "crash.log").delete() }
    }

    /**
     * WebView user-agent override (Settings → WebView user agent). Default ON:
     * the WebView advertises the STOCK Android WebView UA — the fingerprint the
     * engine actually presents, which is what makes Cloudflare's JS challenge
     * (cf_clearance) complete instead of looping on a desktop UA claim. Off +
     * custom UA lets users force a desktop/mobile UA for sites that need one.
     * Loaded from prefs at startup; updated live by the settings card.
     */
    @Volatile
    var webViewUseDefaultUa = true

    @Volatile
    var webViewCustomUa: String? = null

    /** UA string the WebViews should advertise. [pluginUa] is the UA a
     *  CloudStream-style plugin explicitly requested (used only when the user
     *  has turned the override off and typed nothing). */
    fun effectiveWebViewUa(pluginUa: String? = null): String {
        val custom = webViewCustomUa?.trim()
        if (!webViewUseDefaultUa) {
            if (!custom.isNullOrBlank()) return custom
            if (!pluginUa.isNullOrBlank()) return pluginUa
        }
        return runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }
            .getOrDefault(Http.UA)
    }

    /**
     * Most provider CDNs refuse to serve posters to a bare okhttp client: they
     * require a browser User-Agent and a same-site Referer (hotlink protection).
     * Coil's default loader sends neither, so every poster 403s into a blank
     * placeholder. Wire a global loader that sends a browser UA plus a Referer
     * derived from the image's own origin.
     */
    private fun setupImageLoader() {
        runCatching {
            // A home feed renders a hundred-plus posters from ONE host at once.
            // OkHttp's default dispatcher allows only 5 concurrent requests per
            // host, so every row after the first queued behind it and looked
            // like it never loaded ("first some images load and then scrolling
            // horizontal not loading"). Coil gets its own dispatcher with a much
            // higher per-host ceiling so a whole row loads in parallel, plus a
            // bigger connection pool so those parallel requests actually reuse
            // sockets instead of serialising on TCP/TLS handshakes.
            val dispatcher = Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val host = req.url.host?.lowercase() ?: ""
                    val cs3 = com.hikari.app.cs3.Cs3MainApiProvider
                    // Header sets to try, best guess first: the exact headers a
                    // provider declared for this poster URL, then the Referer it
                    // declared for this image host, then a same-origin Referer
                    // (hotlink protection), then a completely bare request.
                    // Hosts that refuse ANY Referer (see NO_REFERER_HOSTS) start
                    // bare. The old code only ever tried two of these and only
                    // when the first answer was a 401/403 — a CDN that answers
                    // a hotlink rejection with a 200 HTML page slipped through
                    // and Coil then failed to decode it into a blank cell.
                    val variants = ArrayList<Map<String, String>>(4)
                    if (host !in NO_REFERER_HOSTS) {
                        cs3.imageHeaders[req.url.toString()]?.let { variants.add(it) }
                        val referer = cs3.imageHostReferers[host]
                            ?: if (host.isNotBlank()) "${req.url.scheme}://$host/" else null
                        if (referer != null && variants.none { v -> v.keys.any { it.equals("Referer", ignoreCase = true) } }) {
                            variants.add(mapOf("Referer" to referer))
                        }
                    }
                    variants.add(emptyMap())

                    var last: Response? = null
                    for (headers in variants) {
                        // OkHttp refuses a second proceed() on a call whose
                        // previous response body is still open ("cannot make a
                        // new request because the previous response is still
                        // open"), and it throws that from a dispatcher thread,
                        // which takes the whole process down. Close the attempt
                        // we are about to replace BEFORE asking for the next
                        // one — closing it after the proceed was the crash.
                        last?.close()
                        last = null
                        val builder = req.newBuilder().header("User-Agent", Http.UA)
                        headers.forEach { (k, v) -> builder.header(k, v) }
                        val response = chain.proceed(builder.build())
                        if (isUsableImage(response)) return@addInterceptor response
                        last = response
                    }
                    last ?: chain.proceed(req)
                }
                .build()
            val loader = ImageLoader.Builder(this)
                .okHttpClient(client)
                .crossfade(true)
                // Posters whose CDN sends no cache headers (very common on the
                // aggregator hosts) should still land in Coil's disk cache.
                .respectCacheHeaders(false)
                .memoryCache {
                    // Decoded bitmaps live in RAM. Coil's default is 25% of the
                    // app heap, which on a poster grid (a few hundred covers,
                    // several full-size) can fill the heap on its own and OOM the
                    // process. Scale to the actual heap instead: 1/8 of it,
                    // floored at 24 MB (a screenful or two of thumbnails) and
                    // capped at 96 MB so a huge-heap device doesn't hoard memory
                    // it doesn't need.
                    val cap = (Runtime.getRuntime().maxMemory() / 8)
                        .coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
                    coil.memory.MemoryCache.Builder(this@HikariApp)
                        .maxSizeBytes(cap.toInt())
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(File(cacheDir, "coil_image_cache"))
                        .maxSizeBytes(250L * 1024 * 1024)
                        .build()
                }
                .build()
            Coil.setImageLoader(loader)
        }
    }

    /**
     * True when [response] actually carries an image: a 2xx whose body is
     * declared as an image type. A missing Content-Type is accepted (Coil sniffs
     * the bytes), but a text/html body is rejected — several CDNs answer a
     * hotlink rejection with a soft 200 HTML page, which Coil would otherwise
     * try to decode into a blank cell.
     */
    private fun isUsableImage(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val type = response.body?.contentType()?.type?.lowercase() ?: return true
        return type == "image" || type == "application" || type == "binary" || type == "octet-stream"
    }

    private fun initCloudStream(context: Context) {
        try {
            // Mirrors the reference host (CloudStreamApp.onCreate). The jar
            // currently ships the JVM stub of com.lagradost.api.ContextHelper
            // (getContext() always null), so this is a no-op today — but if the
            // jar is ever swapped for the Android artifact, the WebViewResolver
            // shadow in com/lagradost/cloudstream3/network needs the host
            // context wired exactly this way.
            try {
                setContext(context)
            } catch (t: Throwable) {
                android.util.Log.e("HikariApp", "setContext failed", t)
            }
            // Plugins read CloudStreamApp.context for their Cloudflare bypass,
            // preference keys and WebView cookies — the jar's CloudStreamApp is
            // shadowed (it compiled against Coil 3 and failed to resolve), so
            // wire the shadow's context to the real app context here.
            try {
                com.lagradost.cloudstream3.CloudStreamApp.setContext(context)
            } catch (t: Throwable) {
                android.util.Log.e("HikariApp", "CloudStreamApp.setContext failed", t)
            }

            // CloudStream's buildDefaultClient inserts Conscrypt as the JSSE
            // provider before building okhttp — mirror it so TLS handshakes to
            // the streaming CDNs behave identically.
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (_: Throwable) {
            }

            // Accessing the jar's MainActivityKt initializes its own default
            // nicehttp Requests (jackson responseParser + CloudStream user-agent).
            // Wire up the real okhttp client (redirects, generous timeouts +
            // connection retry exactly like CloudStream's buildDefaultClient,
            // 50MiB cache, optional SSL-ignore) so slow anime sites don't throw
            // on the 10s okhttp defaults.
            fun build(ignoreSSL: Boolean) = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .apply { if (ignoreSSL) ignoreAllSSLErrors() }
                .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024))
                // Auto Cloudflare handling for CS3 plugin requests (app.get /
                // app.post): same detect → verify-WebView → retry-with-cookie
                // flow Hikari's own Http client uses (see CloudflareVerifier).
                .addInterceptor { chain -> com.hikari.app.net.CloudflareVerifier.intercept(chain) }
                .build()

            val kt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
            fun wire(getter: String, ignoreSSL: Boolean) {
                val req = kt.getMethod(getter).invoke(null) as Requests
                req.baseClient = build(ignoreSSL)
            }
            wire("getApp", ignoreSSL = false)
            wire("getInsecureApp", ignoreSSL = true)
            MainAPI.settingsForProvider = SettingsJson()

            // Warm the 810-extractor registry (constructs every built-in
            // extractor, loading newpipe/cryptography/ksoup classes) on a
            // background thread so the first "load sources" click is instant
            // and any initialization failure surfaces as a caught error
            // instead of a silent hang on first play.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
                } catch (t: Throwable) {
                    android.util.Log.e("HikariApp", "extractor registry init failed", t)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HikariApp", "CloudStream runtime init failed", t)
        }
    }
}
