package com.hikari.app

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.DohDns
import com.hikari.app.net.Http
import com.hikari.app.net.NetTuning
import com.hikari.app.net.ExtensionVerifyGuard
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

        /** Stack trace of the last uncaught crash (shown as a one-shot Home
         *  warning — see [crashNoticeShown]). */
        @Volatile
        var lastCrash: String? = null
            private set

        /**
         * True when this exact crash has already been announced to the user
         * before, so the warning is shown ONCE per crash instead of on every
         * launch (the log file itself is kept for Settings → Logs, which is why
         * the warning can't simply call [clearCrash]).
         */
        @Volatile
        var crashNoticeShown: Boolean = false
            private set

        /** Fingerprint of the crash currently in [lastCrash]. */
        @Volatile
        private var crashFp: Int = 0

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

    /** Bumped by [onContentLanguageChanged] whenever the language TMDB titles
     *  and overviews are fetched in changes. Screens that hold localized
     *  content watch this and rebuild (see HomeViewModel). */
    val contentLanguageRevision = MutableStateFlow(0L)

    /** The TMDB language tag last handed to the resolver — null until the first
     *  one, so the app's own launch value can be told apart from a real change
     *  (and a change can be spotted even after the Activity was recreated for an
     *  app-language switch, when nothing else would survive to compare with). */
    @Volatile
    private var appliedContentLanguage: String? = null

    /**
     * Point TMDB at [tag] and, when that is a CHANGE from the language already in
     * use, drop the localized content that was fetched under the old one. Called
     * from the main screen whenever the setting (or the app language it follows)
     * moves — see [onContentLanguageChanged]. */
    fun applyContentLanguage(tag: String) {
        val previous = appliedContentLanguage
        appliedContentLanguage = tag
        com.hikari.app.nuvio.TmdbResolver.contentLanguage = tag
        if (previous != null && previous != tag) onContentLanguageChanged()
    }

    /**
     * The user just changed the language TMDB metadata is fetched in
     * (Settings → App Layout → "TMDb language titles").
     *
     * `TmdbResolver.contentLanguage` is already switched by then (see
     * [applyContentLanguage]) — this is about the results that were fetched under
     * the OLD language and are still held in memory: the built Home feed, a saved
     * TMDB source's row title, the search grid. Nothing that made those requests
     * can know the language moved, so they are dropped here, and the screens
     * watching [contentLanguageRevision] fetch again. That is what makes the
     * setting take effect the moment it is picked instead of only after the app
     * is restarted.
     */
    fun onContentLanguageChanged() {
        runCatching { com.hikari.app.data.TmdbSources.clearLocalizedNames() }
        runCatching { com.hikari.app.data.SearchResultsCache.clear() }
        contentLanguageRevision.value = contentLanguageRevision.value + 1L
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Diagnostics first: everything after this point is logged, and the
        // crash handler below needs the log directory to already exist.
        Logs.init(this)
        Logs.log("App", "onCreate · version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) sha ${BuildConfig.GIT_SHA}")
        installCrashHandler()
        initCloudStream(this)
        store = AppStore(this)
        // Restore the saved app language BEFORE any Activity is created, so the
        // whole UI (player overlay labels, content descriptions, settings)
        // comes up in the chosen language instead of flashing English first.
        runCatching {
            com.hikari.app.ui.LanguageManager.apply(
                kotlinx.coroutines.runBlocking { store.language() }
            )
        }
        providers = ProviderManager(store)
        // Nothing in Hikari ever loads a Cloudflare challenge on its own: a
        // verification page opens only when the user taps the WebView (globe)
        // button themselves (see CloudflareVerifier).
        //
        // Extensions don't have to play by that rule — Cinemacity opens its own
        // Cloudflare WebView in the middle of loading sources — so the switches
        // that gate those pages are forced off here (and again whenever a
        // plugin's settings sheet closes). Settings → Privacy & Browsing can
        // let them back through.
        appScope.launch {
            runCatching {
                ExtensionVerifyGuard.apply(this@HikariApp, store.extensionVerifyWebview())
            }
        }
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
            // First run: seed the community SkyStream extension repos too, so
            // SkyStream extensions are installable from the Extensions screen
            // without hunting for a repo URL.
            runCatching {
                com.hikari.app.skystream.SkyStreamPluginManager.seedDefaults(this@HikariApp, store)
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
            Logs.log("Providers", "refreshed: ${providers.providers.value.size} installed")
            providers.providers.value
                .filterIsInstance<com.hikari.app.cs3.Cs3MainApiProvider>()
                .forEach { it.warm() }
            // A plugin settings change (e.g. SKTech's sub-provider picker) can
            // alter which providers a plugin registers. Warm first so the
            // plugin instances are cached (reconcile then hits the cache), then
            // rebuild the stored configs to match and refresh if anything moved.
            runCatching {
                if (com.hikari.app.cs3.Cs3ProviderSync.reconcile(this@HikariApp, store)) {
                    Logs.log("Providers", "CS3 sync changed the provider list — refreshing")
                    providers.refresh()
                }
            }
            // Per-extension auto-translate config + persisted translation cache.
            runCatching { com.hikari.app.data.Translator.init(store) }
            // Re-assert the chosen launcher icon. The enabled `activity-alias` is
            // part of the installed app, not of the restored preferences, so a
            // backup restore / device copy would otherwise leave the user with
            // the default icon while Settings still shows their pick.
            runCatching {
                com.hikari.app.ui.AppIconManager.ensureApplied(this@HikariApp, store.appIcon())
            }
            Logs.log("App", "startup complete (${providers.providers.value.size} providers)")
        }
    }

    /**
     * Never let an uncaught exception (main or background thread) die silently:
     * write the stack to a file, and surface it on the next launch as a banner
     * (see HomeScreen) so crashes get reported instead of guessed at.
     */
    private fun installCrashHandler() {
        runCatching {
            val text = Logs.crashText(this)
                ?: File(cacheDir, "crash.log").takeIf { it.exists() }?.readText()
            if (!text.isNullOrBlank()) {
                lastCrash = text.take(1600)
                crashFp = text.hashCode()
                // Announce a given crash once: the log stays in Settings → Logs
                // forever, so re-warning on every launch is pure nagging.
                crashNoticeShown = runCatching {
                    crashNoticeFpFile().takeIf { it.exists() }?.readText()?.trim() == crashFp.toString()
                }.getOrDefault(false)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            // The full report (with breadcrumbs) goes to filesDir/logs/crash.log
            // so Settings → Logs can share it; the banner only needs a preview.
            val trace = runCatching { Logs.recordCrash(thread.name, t) }.getOrElse {
                "${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTrace.take(12).joinToString("\n") { "    at $it" }
            }
            lastCrash = trace.take(1600)
            runCatching { File(cacheDir, "crash.log").writeText(trace) }
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
        runCatching { Logs.clearCrash(this) }
    }

    /** Where the fingerprint of the already-announced crash is kept. */
    private fun crashNoticeFpFile() = File(filesDir, "logs/crash.notified")

    /**
     * The user has seen the crash warning. Unlike [clearCrash] this KEEPS the
     * crash log (Settings → Logs still has it to share with the developer) and
     * only remembers that this crash was already announced, so the warning never
     * reappears for it.
     */
    fun markCrashNoticeShown() {
        lastCrash = null
        crashNoticeShown = true
        runCatching {
            crashNoticeFpFile().parentFile?.mkdirs()
            crashNoticeFpFile().writeText(crashFp.toString())
        }
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
                .dns(DohDns)
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
                // Extension logos and addon icons are frequently `.svg`
                // (SkyStream addon manifests point at e.g.
                // dramayo.stream/static/dramayo.svg, several CS3 repos ship
                // vector icons), and Coil 2 has no SVG support at all — every
                // one of those decodes to a failure, which is why such rows
                // showed the monochrome puzzle-piece glyph. The decoder is
                // registered here (once, for the whole app) so repository
                // listings, installed-extension rows and Stremio addon icons
                // all render their real logo.
                .components {
                    add(coil.decode.SvgDecoder.Factory())
                    // Animated GIF covers (collections/folders): Coil's default
                    // decoders only ever draw the first frame. ImageDecoder
                    // handles GIFs on API 28+ (and is what the platform
                    // recommends); the older GifDecoder covers the rest.
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
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
                .dns(DohDns)
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

            // Pre-warm the classes the plugin path is known to touch, so a
            // missing/broken one shows up as a clear, logged cause chain at
            // startup instead of a bare NoClassDefFoundError thrown from deep
            // inside a plugin (or — worse — a plugin settings dialog, which is
            // where CloudStream's own CommonActivity.showToast died on
            // `databinding/ToastBinding` and took the app down with it).
            CoroutineScope(Dispatchers.IO).launch {
                val probes = listOf(
                    "com.lagradost.cloudstream3.syncproviders.AccountManager",
                    "com.lagradost.cloudstream3.databinding.ToastBinding",
                    "com.lagradost.cloudstream3.CommonActivity",
                    "com.lagradost.cloudstream3.R${'$'}string",
                )
                for (name in probes) {
                    try {
                        Class.forName(name)
                        Logs.log("CloudStream", "pre-warm ok: $name")
                    } catch (t: Throwable) {
                        Logs.log(
                            "CloudStream",
                            "pre-warm FAILED: $name — ${t.javaClass.name}: ${t.message}" +
                                (t.cause?.let { " (cause ${it.javaClass.name}: ${it.message})" } ?: ""),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HikariApp", "CloudStream runtime init failed", t)
        }
    }
}
