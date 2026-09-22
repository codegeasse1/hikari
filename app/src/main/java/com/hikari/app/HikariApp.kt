package com.hikari.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RedirectAllow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import org.conscrypt.Conscrypt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

class HikariApp : Application() {

    companion object {
        lateinit var instance: HikariApp
            private set

        /**
         * How long the one blocking settings read at startup may take before the
         * app gives up on it and comes up with defaults (see onCreate).
         *
         * Deliberately short. Every other store read in the app is asynchronous
         * and cancellable; this single one has to be synchronous — the app
         * language must be applied before the first Activity exists, or the UI
         * flashes English and rebuilds. The price of that is that a wedged store
         * could hold the whole launch here, which is not a trade worth making:
         * three seconds is more than a cold read of the preferences file ever
         * takes, and past it the honest thing is to start and log why.
         */
        private const val STARTUP_STORE_READ_MS = 3_000L

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
     * (Settings → Appearance & Theme → Title language (TMDB)).
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

    /**
     * Keeps the TELEVISION PERFORMANCE MODE in step with the layout, for as long
     * as the user has not made that choice themselves.
     *
     * The layout is decided by the device (or by the user's override in
     * Settings → TV & Remote, which is the same flag), and the lighter visuals
     * belong with it: on a box the poster treatments and their blur are drawn
     * per card, per frame, on top of decoding 1080p, and a TV viewer would never
     * find that switch in a settings folder. So the TV layout switches it ON by
     * itself, and the phone layout switches it back off — both at launch and the
     * moment the layout changes.
     *
     * The first-run seeding in [onCreate] already does this for a television on a
     * fresh install; this is the part that keeps being true afterwards (a phone
     * whose user turns the TV layout on to look at it, or a box that only settles
     * its UI mode once an activity exists, which is why [com.hikari.app.tv.TvMode.detect]
     * runs again in MainActivity).
     *
     * Once the user works the switch in Settings themselves,
     * [com.hikari.app.data.AppStore.tvPerfChosen] is set and this never touches
     * the setting again — an explicit answer always beats an automatic one.
     */
    private suspend fun syncTvPerformance(store: com.hikari.app.data.AppStore) {
        if (runCatching { store.tvPerfChosen() }.getOrDefault(false)) return
        val want = com.hikari.app.tv.TvMode.isTv
        if (runCatching { store.tvPerf() }.getOrDefault(false) == want) return
        runCatching {
            store.setTvPerf(want)
            Logs.log(
                "App",
                "performance mode " + (if (want) "on" else "off") +
                    " — the " + (if (want) "TV" else "phone") + " layout is active",
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Diagnostics first: everything after this point is logged, and the
        // crash handler below needs the log directory to already exist.
        Logs.init(this)
        Logs.log("App", "onCreate · version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) sha ${BuildConfig.GIT_SHA}")
        installCrashHandler()
        watchForTheUserClosingTheApp()
        // Which kind of device this is — a phone/tablet or a television — asked
        // here, before anything can draw. ONE APK runs on both, and the answer
        // decides the whole layout (see com.hikari.app.tv.TvMode); it is asked
        // again in MainActivity, because a few boxes only settle their UI mode
        // after the application object exists.
        runCatching { com.hikari.app.tv.TvMode.detect(this) }
        // Aniyomi extensions are Mihon/Aniyomi extension APKs: the extension
        // loader builds a class loader over the .ext and instantiates a source,
        // and the source immediately resolves its own dependencies out of
        // Mihon's global `Injekt` container (`Injekt.get<Application>()` for its
        // preferences, `Json`, `NetworkHelper`, `JavaScriptEngine`, …). Hikari
        // has no DI container, so the patched Injekt singleton is installed and
        // primed here — before anything can possibly load an extension.
        runCatching { dev.mihon.injekt.patchInjekt() }
            .onFailure {
                // Swallowing this was a silent trap. `patchInjekt()` REPLACES
                // the global Injekt scope with the registrar that has the
                // singleton-caching fix, so a failure here leaves every
                // extension load on a container nothing primed — and the only
                // symptom is a `java.lang.reflect.InvocationTargetException:
                // null` from whichever source touched `Injekt.get<Application>()`
                // in its constructor. Now it is a line in Settings → Logs.
                Logs.logError(
                    "Injekt",
                    "patchInjekt() failed — extensions will use Injekt's own registrar",
                    it,
                )
            }
        ensureAniyomiInjekt()
        initCloudStream(this)
        store = AppStore(this)
        val startupAt = System.currentTimeMillis()
        // Restore the saved app language BEFORE any Activity is created, so the
        // whole UI (player overlay labels, content descriptions, settings)
        // comes up in the chosen language instead of flashing English first.
        //
        // The read is BOUNDED. It is a blocking DataStore read on the main
        // thread — the one place in the app that is — and if the store is ever
        // wedged (a write that never completed, a file system that stopped
        // answering) an unbounded read here means the app never opens at all:
        // the launch screen sits there until the OS kills it, which is exactly
        // the reported "opening took much longer than it should". With a timeout
        // the worst case is one setting applied a moment late, and the log says
        // so.
        runCatching {
            val tag = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(STARTUP_STORE_READ_MS) { store.language() }
            }
            if (tag == null) {
                Logs.log(
                    "App",
                    "the settings store did not answer within ${STARTUP_STORE_READ_MS}ms — " +
                        "starting with the default language (see Store lines above for why)",
                )
            } else {
                // Only push a language Hikari itself was given: a blank setting
                // means "System default", and applying that would CLEAR a
                // per-app locale the user chose for Hikari in Android's own
                // app-language screen (setApplicationLocales with an empty list
                // is "follow the device", not "leave it alone").
                if (tag.isNotBlank()) {
                    com.hikari.app.ui.LanguageManager.apply(tag)
                }
                // Hand the View-based screens (the player, its dialogs, the
                // WebView) their language map before anything can draw: Compose
                // gets it from I18n.LocalMap in MainActivity, but I18n.t() reads
                // the map pushed here, and a player opened without the main
                // screen having drawn yet would otherwise be English.
                val effective = com.hikari.app.ui.LanguageManager.effectiveTag(tag)
                com.hikari.app.i18n.I18n.setCurrent(
                    com.hikari.app.i18n.I18n.mapFor(this@HikariApp, effective),
                    effective,
                )
            }
        }
        Logs.log("App", "store restored in ${System.currentTimeMillis() - startupAt}ms")
        providers = ProviderManager(store, this)
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
        // Television: the user's layout choice, and the first-run television
        // defaults. Both are reads/writes of the settings store, so they cannot
        // happen on this thread — MainActivity reads the same preference
        // synchronously before its first frame, and this keeps it current from
        // then on.
        appScope.launch {
            runCatching {
                val mode = store.tvMode()
                com.hikari.app.tv.TvMode.setOverride(mode)
                if (!store.tvSeeded()) {
                    // Applied exactly once per install. A television gets the
                    // quiet defaults: a TV stick is decoding 1080p with a chip a
                    // phone would have called slow, so the first thing it shows
                    // should not be a dozen animated posters. A phone's first run
                    // changes nothing.
                    if (com.hikari.app.tv.TvMode.isTv) {
                        store.setPosterEffects(emptySet())
                        store.setPosterBlur(0)
                        store.setLoadingEffects(setOf(com.hikari.app.ui.LoadingEffects.SHEEN))
                        store.setUiScaleEnabled(true)
                        store.setUiScale(110)
                        store.setTvPerf(true)
                        Logs.log(
                            "App",
                            "television detected (${com.hikari.app.tv.TvMode.describe(this@HikariApp)})" +
                                " — applied the TV defaults: no poster effects, 110% UI scale",
                        )
                    } else {
                        Logs.log(
                            "App",
                            "phone/tablet detected (${com.hikari.app.tv.TvMode.describe(this@HikariApp)})",
                        )
                    }
                    store.setTvSeeded(true)
                }
                syncTvPerformance(store)
                // Keep the PERFORMANCE MODE in step with the layout from here
                // on: switching "Layout" in Settings → TV & Remote to the TV
                // layout turns the lighter visuals on by itself, and switching
                // back to the phone layout turns them off. This is the wanted
                // behaviour ("when the app detects the TV layout it should turn
                // performance mode on automatically") and it is also the case
                // the first-run seeding above cannot cover: a phone whose user
                // switches the TV layout on, or a box whose UI mode only settles
                // after the first launch. The moment the user works the switch
                // themselves the choice is theirs and this stops touching it
                // (see [AppStore.tvPerfChosen]).
                store.tvModeFlow().distinctUntilChanged().collect { current ->
                    com.hikari.app.tv.TvMode.setOverride(current)
                    syncTvPerformance(store)
                }
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
        // Same for DNS mode: the resolver (Settings → Network and Internet) is
        // read synchronously by DohDns on every lookup.
        appScope.launch {
            store.dnsProviderFlow().collect { NetTuning.setDnsProvider(it) }
        }
        appScope.launch {
            store.customDnsFlow().collect { NetTuning.setCustomDns(it) }
        }
        // How wide a lookup may search (Settings → Playback → Server search).
        // Read synchronously mid-pass by the target builder, the sweeps and the
        // episode fallback, so it lives in a plain flag (see [SearchScope])
        // mirrored here — a search must never have to await DataStore.
        //
        // Two settings become the one flag the pass reads: the "search all
        // installed extensions" switch, and the exception extensions the user
        // marked (which the pass honours even with that switch off).
        appScope.launch {
            com.hikari.app.data.SearchScope.allExtensions = store.searchAllExtensions()
            store.searchAllExtensionsFlow().collect {
                com.hikari.app.data.SearchScope.allExtensions = it
            }
        }
        appScope.launch {
            // Seeded through a first read of the EFFECTIVE flow (rather than the
            // raw id flow) so a search started before the first emission already
            // sees the right set — engine exceptions included, exactly like the
            // flag above.
            com.hikari.app.data.SearchScope.exceptions =
                runCatching { store.activeSearchExceptionsFlow().first() }.getOrDefault(emptySet())
            store.activeSearchExceptionsFlow().collect {
                com.hikari.app.data.SearchScope.exceptions = it
            }
        }
        // Player UI skin (Settings → Player → Player UI): mirrored into
        // PlayerSkins because PlayerActivity is a View-based screen that has to
        // know the value synchronously while its controller is being inflated.
        appScope.launch {
            store.playerSkinFlow().collect { com.hikari.app.player.PlayerSkins.setCurrent(it) }
        }
        // Adult content (Settings → Content → NSFW): mirrored into [NsfwGate]
        // because the gate is read on the DRAW path — a list being composed cannot
        // await DataStore. Seeded with a first read of the flow so a screen that
        // opens before the first emission already sees the stored value, not the
        // default.
        //
        // A change also rebuilds the provider list: an 18+ extension is kept out
        // of it while the switch is off (see
        // [com.hikari.app.providers.ProviderManager.refresh]), so the switch has
        // to be what puts it back or takes it away — the stored configs are never
        // rewritten, which is what makes the change instant and reversible.
        appScope.launch {
            runCatching { com.hikari.app.data.NsfwGate.setEnabled(store.nsfwEnabled()) }
            store.nsfwEnabledFlow().collect { on ->
                val changed = on != com.hikari.app.data.NsfwGate.enabled
                com.hikari.app.data.NsfwGate.setEnabled(on)
                if (changed) runCatching { providers.refresh() }
            }
        }
        // "Allowed redirect links" (Settings → Privacy & Browsing → WebView
        // safety): mirrored in memory so a WebView being redirected right now —
        // and the Cloudflare-verification view in particular — can honour a link
        // the user added without waiting for DataStore (see RedirectAllow).
        appScope.launch {
            runCatching { store.webviewRedirectAllow() }
            store.webviewRedirectAllowFlow().collect { RedirectAllow.set(it) }
        }
        Http.init()
        setupImageLoader()
        CoroutineScope(Dispatchers.IO).launch {
            // Load the persisted WebView element blocks into the app cache FIRST
            // (fast DataStore read) so a WebView opened right after launch can
            // apply them on its first page instead of showing them after a race.
            runCatching { elementBlocks = store.elementBlocks() }
            // Player UI skin: seed the synchronous mirror as well as collecting
            // the flow, so a player opened immediately after launch (before the
            // flow's first emission) still gets the right skin.
            runCatching { com.hikari.app.player.PlayerSkins.setCurrent(store.playerSkin()) }
            // Registering extractor aliases initializes the jar's full extractor
            // registry — do it off the main thread.
            com.hikari.app.cs3.HikariExtractorRegistry.register()
            // WebView UA override state (Settings) — loaded once, kept current
            // by the settings card.
            runCatching {
                webViewUseDefaultUa = store.webviewUseDefaultUa()
                webViewCustomUa = store.webviewCustomUa()
            }
            // (The bundled yt-dlp "universal extractor" used to be warmed up
            // here. It was removed in 0.9.1 — see the CHANGELOG — so there is
            // no CPython runtime to start, and the app opens slightly faster.)
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
            // First run: seed the Aniyomi extension repo (Aniyomi's official
            // index.min.json) so Aniyomi-extensions are installable from the
            // Extensions screen without hunting for a repo URL.
            runCatching {
                com.hikari.app.aniyomi.AniyomiExtensionManager.seedDefaults(this@HikariApp, store)
            }
            // First run: seed the manga extension repo (keiyoushi's index) so
            // manga extensions are installable without hunting for a repo URL.
            // It is the same bare-array index format the Aniyomi repo uses,
            // which is why it can be added through the same add-repo flow.
            runCatching {
                com.hikari.app.manga.MangaExtensionManager.seedDefaults(this@HikariApp, store)
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
            // Collapse any duplicate repo entries an older build stored (the same
            // repository added twice — two branches or two URL spellings), so the
            // repo list shows one folder per repo from the first launch after the
            // update.
            runCatching { store.dedupeStoredRepos() }
            // Apply vendored nuvio provider patches (see NuvioPluginManager's
            // PROVIDER_PATCHES) so already-installed broken providers get the
            // fixed JS in place without a manual reinstall.
            runCatching {
                com.hikari.app.nuvio.NuvioPluginManager.applyPatchesToInstalled(this@HikariApp)
            }
            providers.refresh()
            Logs.log("Providers", "refreshed: ${providers.providers.value.size} installed")
            // Watch the provider list itself, for the whole session.
            //
            // A lookup asks the providers that were installed when it started, so
            // the one thing that must never happen is for the list to change
            // under it — and when it did, the log was silent about it: a pass
            // would report "Hikari 77 of 181" one minute and "176 of 181" the
            // next, with the repos that went missing named nowhere. This line
            // makes every change to the list, its size, its enabled count and its
            // per-engine split visible in any log the user shares (see also the
            // "start …" line each pass writes, which prints its own snapshot).
            appScope.launch {
                providers.providers.collect { list ->
                    val enabled = list.count { it.config.enabled }
                    Logs.log(
                        "Providers",
                        "provider list: ${list.size} installed, $enabled enabled (" +
                            com.hikari.app.data.ContentRepository.providerCounts(
                                list.filter { it.config.enabled },
                            ) + ")",
                    )
                }
            }
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
            // Aniyomi extensions are the same story: one installed .ext can
            // register several sources, and the set can move when the extension
            // is updated, so rebuild the stored configs from the loaded
            // extension and refresh if anything moved.
            runCatching {
                if (com.hikari.app.aniyomi.AniyomiProviderSync.reconcile(this@HikariApp, store)) {
                    Logs.log("Providers", "Aniyomi sync changed the provider list — refreshing")
                    providers.refresh()
                }
            }
            // Warm the installed Aniyomi extensions here so the first catalog or
            // search tap doesn't pay the class-loading cost on the UI thread.
            runCatching {
                providers.providers.value
                    .filterIsInstance<com.hikari.app.aniyomi.AniyomiProvider>()
                    .forEach { it.warm() }
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
            Logs.log(
                "App",
                "blur support: " + if (android.os.Build.VERSION.SDK_INT >= 31) {
                    "yes (API ${android.os.Build.VERSION.SDK_INT})"
                } else {
                    // Modifier.blur is a no-op below API 31, which is why a user
                    // on Android 11 or older reported "the blur works for you but
                    // not for me" with identical settings. The halo is now drawn
                    // from the artwork itself on every version (see PosterArt), so
                    // this line is for diagnosis, not for behaviour.
                    "no (API ${android.os.Build.VERSION.SDK_INT} — the poster halo is drawn " +
                        "from the artwork pixels instead of Modifier.blur)"
                },
            )
            Logs.log(
                "App",
                "startup complete (${providers.providers.value.size} providers) in " +
                    "${System.currentTimeMillis() - startupAt}ms",
            )
        }
    }

    /**
     * "The user CLOSED the app" — the one moment background work must let go.
     *
     * [com.hikari.app.work.BackgroundWork] exists so a long catalog load, search
     * or source scan survives the user stepping into another app (Android freezes
     * a backgrounded process, which used to stop them dead). That must NOT extend
     * to the app being closed: a search left holding a foreground service after
     * the user is done kept a permanent "Hikari keeps running while you use other
     * apps" notification on screen, kept hundreds of requests alive for minutes,
     * and left the next launch fighting that work for the CPU — reported as "it
     * is still running in the background after I close it, and then it just stays
     * stuck on the Hikari logo".
     *
     * Android has no "the user closed it" callback, so it is inferred, and only
     * from signals that really mean it:
     *
     *  - the last Activity being DESTROYED because it is FINISHING (Back out of
     *    the app, or the launcher's task removal) — here;
     *  - the task being swiped off the recents list — WorkService.onTaskRemoved.
     *
     * Deliberately NOT from onStop: pressing Home, locking the screen or opening
     * another app stops the Activities too, and continuing to work through that
     * is the entire point of the service.
     */
    private fun watchForTheUserClosingTheApp() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            /** Activities between onStart and onStop — 0 means nothing of ours is
             *  on screen (backgrounded OR closing). */
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                // An Activity is up: background work may hold the process again
                // (see BackgroundWork.reopen — closing the app latches it shut).
                com.hikari.app.work.BackgroundWork.reopen()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (!activity.isFinishing) return
                if (started > 0) return
                com.hikari.app.work.BackgroundWork.cancelAll("the last screen was closed")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /**
     * Prime Mihon's Injekt container with the singletons an Aniyomi extension
     * can ask for. `Application` is the one that matters most — every
     * `ConfigurableAnimeSource` / `AnimeHttpSource` preferences accessor is
     * `Injekt.get<Application>().getSharedPreferences(...)`, and a source that
     * can't get its preferences throws before it can list anything. `Json`,
     * `NetworkHelper` and `JavaScriptEngine` are what `JsonExtensions.defaultJson`,
     * `AnimeHttpSource.network` and the JS-driven sources inject. `ProtoBuf` is
     * the odd one out: nothing in Hikari or in the extensions-lib API uses it,
     * but `keiyoushi.utils.ProtobufKt` — a helper library the yuzono/anime-repo
     * extensions bundle — reads it in a top-level property initialiser
     * (`val protoInstance: ProtoBuf = Injekt.get()`), so an extension linking
     * that library throws the moment anything touches the file.
     *
     * All of them are singletons so every installed extension shares Hikari's
     * one OkHttp stack (cookies + 5 MiB cache) instead of building its own.
     * Failures are logged by [ensureAniyomiInjekt]; an extension asking for
     * something still unregistered gets an `InjektionException` at its own call
     * site, which the provider turns into a per-source error message rather
     * than a crash.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun registerAniyomiSingletons() {
        // Every line registers ONE type, and each one is allowed to fail on its
        // own. The whole block used to sit inside a single `runCatching`, and
        // the FIRST failure therefore left the container completely empty —
        // every extension then answered "No registered instance or factory for
        // type class android.app.Application", which is the reported "Anichi:
        // none of its sources could be loaded" for every Aniyomi extension the
        // user installed. Registering one at a time means one bad type costs
        // that type, not the container.
        registerAniyomiSingleton("Application") { Injekt.addSingleton(Ref(Application::class.java), this) }
        registerAniyomiSingleton("Context") { Injekt.addSingleton(Ref(Context::class.java), this) }
        registerAniyomiSingleton("Json") {
            Injekt.addSingletonFactory(Ref(kotlinx.serialization.json.Json::class.java)) {
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            }
        }
        registerAniyomiSingleton("NetworkHelper") {
            Injekt.addSingletonFactory(Ref(eu.kanade.tachiyomi.network.NetworkHelper::class.java)) {
                eu.kanade.tachiyomi.network.NetworkHelper(this)
            }
        }
        registerAniyomiSingleton("JavaScriptEngine") {
            Injekt.addSingletonFactory(Ref(eu.kanade.tachiyomi.network.JavaScriptEngine::class.java)) {
                eu.kanade.tachiyomi.network.JavaScriptEngine(this)
            }
        }
        registerAniyomiSingleton("ProtoBuf") {
            Injekt.addSingletonFactory(Ref(ProtoBuf::class.java)) { ProtoBuf { } }
        }
    }

    /**
     * A [TypeReference] that simply STATES its type.
     *
     * This is the difference between Injekt working in a release build and not
     * working at all. The library's own helpers are
     * `inline fun <reified T> fullType() = object : FullTypeReference<T>(){}`,
     * and `FullTypeReference` reads `javaClass.genericSuperclass`, throwing
     * `IllegalArgumentException: Internal error: TypeReference constructed
     * without actual type information` whenever that is not a parameterized
     * type. Those anonymous objects exist only to carry an erased type
     * argument, R8 rewrites them away in the release build, and the throw took
     * the whole registration down with it — which is why EVERY Aniyomi
     * extension failed to instantiate (the user's own log line:
     * `at uy.kohesive.injekt.api.FullTypeReference.<init>` /
     * `at com.hikari.app.HikariApp.registerAniyomiSingletons`). A reference
     * that answers from a `Class` constant cannot be broken by any optimizer.
     */
    private class Ref<T : Any>(private val cls: Class<T>) : TypeReference<T> {
        override val type: java.lang.reflect.Type get() = cls
    }

    /** One registration, logged if it fails — see [registerAniyomiSingletons]. */
    private inline fun registerAniyomiSingleton(what: String, register: () -> Unit) {
        runCatching(register).onFailure {
            Logs.logError(
                "Injekt",
                "could not register $what for the Aniyomi extensions — an extension " +
                    "that injects $what will fail to load",
                it,
            )
        }
    }

    /** The Injekt scope [registerAniyomiSingletons] last filled, so a scope that
     *  was replaced by somebody else can be noticed and re-primed. */
    @Volatile
    private var primedInjektScope: Any? = null

    /**
     * Make sure the CURRENT Injekt scope carries the singletons an extension
     * needs, and remember WHICH scope was primed.
     *
     * Called from [onCreate] (before anything can load an extension) and again
     * from every extension load (see
     * [com.hikari.app.aniyomi.AniyomiExtensionManager]). Registering the same
     * singletons twice is a no-op as far as instances go — the registrar keys
     * its cache by type, so the already-built `NetworkHelper`/`Json` are handed
     * back — but it is NOT a no-op if something re-ran `patchInjekt()` or
     * installed a different registrar in the meantime, which is exactly the
     * case that turns into "every extension failed to instantiate with
     * InvocationTargetException: null" out of nowhere. Comparing scope identity
     * makes the check free in the normal path (an identity compare per load).
     *
     * A failure is logged rather than swallowed: this is the difference between
     * "the container was never primed" being a mystery and being a log line.
     */
    fun ensureAniyomiInjekt() {
        val current: Any? = Injekt
        if (current === primedInjektScope) return
        runCatching { registerAniyomiSingletons() }
            .onSuccess { primedInjektScope = Injekt }
            .onFailure {
                Logs.logError(
                    "Injekt",
                    "could not register the Aniyomi singletons — an extension that asks " +
                        "Injekt for Application, Json, NetworkHelper, JavaScriptEngine or " +
                        "ProtoBuf will fail to load",
                    it,
                )
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
     * WebView user-agent override (Settings → Privacy & Browsing → WebView user agent). Default ON:
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
                    // PAGE IMAGES, when the manga reader asked for its borders to be
                    // cropped: Nekoread's own Coil decoder (ported with the reader)
                    // decodes the page, measures the blank margins and cuts them, and
                    // it is registered for the whole app because the reader's photo
                    // pages go through this same loader. Opt-in per request (see
                    // `cropBorders` on the builder), so nothing else is affected.
                    add(com.hikari.app.reader.coil.TachiyomiReaderDecoder.Factory())
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
