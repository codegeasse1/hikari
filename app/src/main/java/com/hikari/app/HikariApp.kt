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

        private const val STARTUP_STORE_READ_MS = 3_000L

        /** Stack trace of the last uncaught crash (shown as a one-shot Home
         *  warning — see [crashNoticeShown]). */
        @Volatile
        var lastCrash: String? = null
            private set

        @Volatile
        var crashNoticeShown: Boolean = false
            private set

        /** Fingerprint of the crash currently in [lastCrash]. */
        @Volatile
        private var crashFp: Int = 0

        @Volatile
        var mainActivity: MainActivity? = null

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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var elementBlocks: List<String> = emptyList()

    /** Bumped by the WebView's "Go to app home" menu item; AppRoot watches this
     *  and switches to the app's own Home tab (so the button leaves the site
     *  view instead of reloading the website's home page). */
    val homeTabRequest = MutableStateFlow(0)

    /** A repo link handed in from a browser ("Open with → Hikari"); AppRoot
     *  watches this and switches to the Extensions tab, whose screen installs
     *  it into the section it belongs to and opens that repo's folder. Null
     *  when there is none pending — the Extensions screen consumes it. */
    val repoLinkRequest = MutableStateFlow<String?>(null)

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

    fun applyContentLanguage(tag: String) {
        val previous = appliedContentLanguage
        appliedContentLanguage = tag
        com.hikari.app.nuvio.TmdbResolver.contentLanguage = tag
        if (previous != null && previous != tag) onContentLanguageChanged()
    }

    fun onContentLanguageChanged() {
        runCatching { com.hikari.app.data.TmdbSources.clearLocalizedNames() }
        runCatching { com.hikari.app.data.SearchResultsCache.clear() }
        contentLanguageRevision.value = contentLanguageRevision.value + 1L
    }

    fun syncTvDeviceFlag() {
        com.hikari.app.data.PerfMode.setTvDevice(com.hikari.app.tv.TvMode.deviceIsTelevision)
    }

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

    private suspend fun syncTvEnhance(store: com.hikari.app.data.AppStore) {
        if (!com.hikari.app.tv.TvMode.isTv) return
        // Unreadable flag reads as "the user chose": never override a choice we
        // cannot see, on a guess.
        if (runCatching { store.enhanceChosen() }.getOrDefault(true)) return
        val natural = com.hikari.app.player.EnhancePreset.DEFAULT.key
        if (runCatching { store.enhancePreset() }.getOrDefault(natural) == natural) return
        runCatching {
            store.setEnhancePreset(natural)
            Logs.log(
                "App",
                "video enhance reset to $natural — the TV layout does not keep a " +
                    "per-frame colour pass on, see syncTvEnhance",
            )
        }
    }

    override fun startActivity(intent: android.content.Intent) {
        if (com.hikari.app.net.ExtensionUiGuardMixins.startAllowed(intent)) super.startActivity(intent)
    }

    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
        if (com.hikari.app.net.ExtensionUiGuardMixins.startAllowed(intent, options)) {
            super.startActivity(intent, options)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The offline-voice helper process (NeuralTtsService, Dub audio) needs none of the app start-up.
        if (runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000').endsWith(":neuraltts") }.getOrDefault(false)) return
        instance = this
        runCatching { okhttp3.OkHttp.initialize(this) }
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
        syncTvDeviceFlag()
        runCatching { dev.mihon.injekt.patchInjekt() }
            .onFailure {
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
                if (tag.isNotBlank()) {
                    com.hikari.app.ui.LanguageManager.apply(tag)
                }
                val effective = com.hikari.app.ui.LanguageManager.effectiveTag(tag)
                com.hikari.app.i18n.I18n.setCurrent(
                    com.hikari.app.i18n.I18n.mapFor(this@HikariApp, effective),
                    effective,
                )
            }
        }
        Logs.log("App", "store restored in ${System.currentTimeMillis() - startupAt}ms")
        Logs.log("Memory", com.hikari.app.data.MemoryReport.device(this))
        providers = ProviderManager(store, this)
        appScope.launch {
            runCatching { com.hikari.app.data.Profiles.load(this@HikariApp) }
                .onFailure { Logs.log("Profiles", "could not read the profile registry: ${it.message}") }
        }
        appScope.launch {
            runCatching {
                ExtensionVerifyGuard.apply(this@HikariApp, store.extensionVerifyWebview())
            }
        }
        appScope.launch {
            runCatching {
                val mode = store.tvMode()
                com.hikari.app.tv.TvMode.setOverride(mode)
                if (!store.tvSeeded()) {
                    if (com.hikari.app.tv.TvMode.isTv) {
                        store.setPosterEffects(emptySet())
                        store.setPosterBlur(0)
                        // No effect over the loading card here either — the
                        // quietest card for the weakest chip (the default is
                        // NONE anyway; written out so a box that already had an
                        // effect is brought in line with the phone default).
                        store.setLoadingEffects(emptySet())
                        store.setUiScaleEnabled(true)
                        store.setUiScale(110)
                        store.setTvPerf(true)
                        store.setTvDetailBig(true)
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
                if (com.hikari.app.tv.TvMode.isTv &&
                    runCatching { !store.tvDetailBigChosen() }.getOrDefault(false)
                ) {
                    runCatching { store.setTvDetailBig(true) }
                }
                // New player defaults: migrate only the old untouched defaults.
                // Explicit user choices are preserved across the update.
                if (!store.playerUiDefaultsSeeded()) {
                    runCatching {
                        if (store.playerSkin() == com.hikari.app.player.PlayerSkins.MINIMAL) {
                            store.setPlayerSkin(com.hikari.app.player.PlayerSkins.TV)
                        }
                        if (!store.playerIconOnly()) {
                            store.setPlayerIconOnly(true)
                        }
                        if (!store.playerTvPanels()) {
                            store.setPlayerTvPanels(true)
                        }
                        // 24dp remains the historical default; do not overwrite a
                        // different value because that means the user chose it.
                        store.setPlayerUiDefaultsSeeded(true)
                    }
                }
                syncTvPerformance(store)
                syncTvEnhance(store)
                store.tvModeFlow().distinctUntilChanged().collect { current ->
                    com.hikari.app.tv.TvMode.setOverride(current)
                    syncTvPerformance(store)
                    syncTvEnhance(store)
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
        appScope.launch {
            store.perfModeFlow().collect { mode ->
                com.hikari.app.data.PerfMode.set(mode, com.hikari.app.data.PerfMode.tvOn)
            }
        }
        appScope.launch {
            store.tvPerfFlow().collect { on ->
                com.hikari.app.data.PerfMode.set(com.hikari.app.data.PerfMode.on, on)
            }
        }
        appScope.launch {
            com.hikari.app.data.SearchScope.allExtensions = store.searchAllExtensions()
            store.searchAllExtensionsFlow().collect {
                com.hikari.app.data.SearchScope.allExtensions = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.engineFamilies = store.engineFamilies() }
            store.engineFamiliesFlow().collect {
                com.hikari.app.data.SearchScope.engineFamilies = it
            }
        }
        appScope.launch {
            com.hikari.app.data.SearchScope.exceptions =
                runCatching { store.activeSearchExceptionsFlow().first() }.getOrDefault(emptySet())
            store.activeSearchExceptionsFlow().collect {
                com.hikari.app.data.SearchScope.exceptions = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.searchNuvioIds = store.searchNuvioIds() }
            store.searchNuvioIdsFlow().collect {
                com.hikari.app.data.SearchScope.searchNuvioIds = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.searchStremioIds = store.searchStremioIds() }
            store.searchStremioIdsFlow().collect {
                com.hikari.app.data.SearchScope.searchStremioIds = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.searchFamilyIds = store.searchFamilyIds() }
            store.searchFamilyIdsFlow().collect {
                com.hikari.app.data.SearchScope.searchFamilyIds = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.trackerSearchAll = store.trackerServerSearchAll() }
            store.trackerServerSearchAllFlow().collect {
                com.hikari.app.data.SearchScope.trackerSearchAll = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.trackerFamilies = store.trackerEngineFamilies() }
            store.trackerEngineFamiliesFlow().collect {
                com.hikari.app.data.SearchScope.trackerFamilies = it
            }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.SearchScope.trackerProviders = store.trackerProviderIds() }
            store.trackerProviderIdsFlow().collect {
                com.hikari.app.data.SearchScope.trackerProviders = it
            }
        }
        appScope.launch {
            store.playerSkinFlow().collect { com.hikari.app.player.PlayerSkins.setCurrent(it) }
        }
        appScope.launch {
            store.serverLayoutFlow().collect { com.hikari.app.player.ServerLayouts.setCurrent(it) }
        }
        appScope.launch {
            store.serverScreenStyleFlow().collect { com.hikari.app.player.ServerScreenStyles.setCurrent(it) }
        }
        appScope.launch {
            runCatching { com.hikari.app.data.NsfwGate.setEnabled(store.nsfwEnabled()) }
            store.nsfwEnabledFlow().collect { on ->
                val changed = on != com.hikari.app.data.NsfwGate.enabled
                com.hikari.app.data.NsfwGate.setEnabled(on)
                if (changed) runCatching { providers.refresh() }
                if (!on) {
                    val learned = runCatching { providers.learnAdultFlags() }.getOrDefault(false)
                    if (learned) runCatching { providers.refresh() }
                }
            }
        }
        appScope.launch {
            runCatching { store.webviewRedirectAllow() }
            store.webviewRedirectAllowFlow().collect { RedirectAllow.set(it) }
        }
        Http.init()
        setupImageLoader()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { elementBlocks = store.elementBlocks() }
            runCatching { com.hikari.app.player.PlayerSkins.setCurrent(store.playerSkin()) }
            // Sources sheet layout: seed the synchronous mirror too, so a
            // sheet opened immediately after launch gets the stored layout.
            runCatching { com.hikari.app.player.ServerLayouts.setCurrent(store.serverLayout()) }
            runCatching { com.hikari.app.player.ServerScreenStyles.setCurrent(store.serverScreenStyle()) }
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
            // The bundled "demo" Hikari extension used to be registered here on
            // first run (YTS, id "hiki|yts"). It shipped with no catalogue at
            // all — tapping it showed nothing to browse — so it was dropped in
            // 0.10.49 together with its provider class (see the CHANGELOG). An
            // install that already has the row gets it swept once here; the
            // sweep is marked done so it never runs again.
            runCatching {
                if (!store.ytsCleaned()) {
                    runCatching { store.removeProvider("hiki|yts") }
                    store.markYtsCleaned()
                }
            }
            runCatching {
                com.hikari.app.nuvio.NuvioPluginManager.seedDefaults(this@HikariApp, store)
                com.hikari.app.nuvio.NuvioPluginManager.removeFormerlySeededProviders(this@HikariApp)
            }
            runCatching {
                com.hikari.app.skystream.SkyStreamPluginManager.seedDefaults(this@HikariApp, store)
            }
            runCatching {
                com.hikari.app.providers.vega.VegaPluginManager.seedDefaults(this@HikariApp, store)
                com.hikari.app.zangetsu.ZangetsuPluginManager.seedDefaults(this@HikariApp, store)
                com.hikari.app.sora.SoraPluginManager.seedDefaults(this@HikariApp, store)
                com.hikari.app.anymex.AnymexPluginManager.seedDefaults(this@HikariApp, store)
                com.hikari.app.lnreader.LnReaderPluginManager.seedDefaults(this@HikariApp, store)
            }
            runCatching {
                com.hikari.app.aniyomi.AniyomiExtensionManager.seedDefaults(this@HikariApp, store)
            }
            runCatching {
                com.hikari.app.manga.MangaExtensionManager.seedDefaults(this@HikariApp, store)
                // 0.11.69 auto-seeded 1200+ Kotatsu rows and lagged the manga
                // tab into crashes — pruned once here; sources now install
                // one-by-one from Extensions → Kotatsu repos.
                com.hikari.app.kotatsu.KotatsuRegistry.pruneSeeded(store)
                com.hikari.app.kotatsu.KotatsuRegistry.seedDefaults(store)
            }
            runCatching {
                if (!store.seededRepos()) {
                    for (r in DEFAULT_EXTENSION_REPOS) store.addCs3Repo(r)
                    store.markReposSeeded()
                }
            }
            // First run only: install the one Stremio addon that needs nothing
            // configured — SubDL's subtitle addon — so the player's "Load from
            // internet" has somewhere to look on an install the user has not set
            // anything up on yet. Guarded by its own one-way flag (a user who
            // removes it keeps it removed), and skipped entirely when the addon
            // is already installed under ANY spelling: the id is derived from
            // the addon's base URL, so "https://api3.subdl.com",
            // "…/manifest.json" and "…/" are one row (see
            // StremioAddon.providerIdFor).
            runCatching {
                if (!store.stremioAddonSeeded()) {
                    val url = com.hikari.app.providers.StremioAddon.DEFAULT_ADDON_URL
                    val id = com.hikari.app.providers.StremioAddon.providerIdFor(url)
                    val present = store.providers().any { p ->
                        p.type == com.hikari.app.data.ProviderType.STREMIO &&
                            com.hikari.app.providers.StremioAddon.providerIdFor(p.url) == id
                    }
                    if (!present) {
                        store.addProvider(
                            com.hikari.app.data.ProviderConfig(
                                id = id,
                                name = "SubDL",
                                type = com.hikari.app.data.ProviderType.STREMIO,
                                url = url,
                            )
                        )
                    }
                    store.markStremioAddonSeeded()
                }
            }
            runCatching { store.dedupeStoredRepos() }
            runCatching {
                com.hikari.app.nuvio.NuvioPluginManager.applyPatchesToInstalled(this@HikariApp)
            }
            providers.refresh()
            Logs.log("Providers", "refreshed: ${providers.providers.value.size} installed")
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
            // The active profile's snapshot must never predate an install:
            // re-save it (debounced) whenever the provider list moves, so a
            // later switch-away, data-clear or restore cannot vaporize
            // extensions installed since the snapshot was taken (see
            // Profiles.saveActive). Skips the initial emission; later ones are
            // real changes.
            appScope.launch {
                var saveJob: kotlinx.coroutines.Job? = null
                var first = true
                providers.providers.collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    saveJob?.cancel()
                    saveJob = appScope.launch {
                        kotlinx.coroutines.delay(10_000L)
                        runCatching {
                            com.hikari.app.data.Profiles.saveActive(this@HikariApp)
                        }
                    }
                }
            }
            providers.providers.value
                .filterIsInstance<com.hikari.app.cs3.Cs3MainApiProvider>()
                .forEach { it.warm() }
            runCatching {
                if (com.hikari.app.cs3.Cs3ProviderSync.reconcile(this@HikariApp, store)) {
                    Logs.log("Providers", "CS3 sync changed the provider list — refreshing")
                    providers.refresh()
                }
            }
            // Silent CloudStream plugin auto-update (at most once a day): the
            // CloudStream app refreshes its extensions from the plugin repos on
            // its own, while Hikari's installed copies stayed stale until the
            // user tapped Update — every site rebuild arrived as a separate
            // "not playing" report. Same manifest-hash comparison as the Update
            // buttons, headless and bounded; reconciles + refreshes when files
            // actually moved. CS3-only.
            runCatching {
                val updated = com.hikari.app.cs3.Cs3AutoUpdate.runIfDue(this@HikariApp, store)
                if (updated > 0) {
                    Logs.log("Providers", "CS3 auto-update replaced $updated plugin file(s) — reconciling")
                    com.hikari.app.cs3.Cs3ProviderSync.reconcile(this@HikariApp, store)
                    providers.refresh()
                }
            }
            // Silent Nuvio scraper auto-update (at most once a day): the same
            // staleness story as CloudStream — NuvioMobile refreshes its
            // providers from the repos on its own, while Hikari's installed
            // copies stayed frozen at install time, so every site rebuild
            // arrived as another "server failed in Hikari" report. Same
            // manifest comparison as the Update buttons, headless and bounded;
            // a swapped file takes effect on the next provider call (scrapers
            // are read per call), so only a refresh is needed. NUVIO-only.
            runCatching {
                val updated = com.hikari.app.nuvio.NuvioAutoUpdate.runIfDue(this@HikariApp, store)
                if (updated > 0) {
                    Logs.log("Providers", "Nuvio auto-update replaced $updated scraper file(s) — refreshing")
                    providers.refresh()
                }
            }
            runCatching {
                if (com.hikari.app.aniyomi.AniyomiProviderSync.reconcile(this@HikariApp, store)) {
                    Logs.log("Providers", "Aniyomi sync changed the provider list — refreshing")
                    providers.refresh()
                }
            }
            runCatching {
                if (com.hikari.app.manga.MangaExtensionManager.backfillLangs(this@HikariApp, store)) {
                    Logs.log("Providers", "Manga language backfill filled blank rows — refreshing")
                    providers.refresh()
                }
            }
            runCatching {
                val pruned = com.hikari.app.manga.MangaExtensionManager.pruneDeadFiles(this@HikariApp)
                if (pruned > 0) {
                    Logs.log("Providers", "pruned $pruned dead manga/anime row(s) — refreshing")
                    providers.refresh()
                }
            }
            // SkyStream bundles (one JS, many feeds) installed before feed
            // rows existed expand into one row per feed here, once.
            runCatching {
                if (com.hikari.app.skystream.SkyStreamPluginManager.reconcileBundles(this@HikariApp, store)) {
                    Logs.log("Providers", "SkyStream sync expanded bundle feeds — refreshing")
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
            runCatching {
                com.hikari.app.ui.AppIconManager.ensureApplied(this@HikariApp, store.appIcon())
            }
            Logs.log(
                "App",
                "blur support: " + if (android.os.Build.VERSION.SDK_INT >= 31) {
                    "yes (API ${android.os.Build.VERSION.SDK_INT})"
                } else {
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

    private fun heapPressure(): Boolean {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory() > rt.maxMemory() * 4 / 5
    }

    /** Drop the re-fetchable image caches when the heap is nearly full (see
     *  [heapPressure]). Posters and stills decode again on scroll; a dead
     *  process loses the page. No-op without pressure. */
    fun relieveHeapPressure(caller: String) {
        if (!heapPressure()) return
        val before = runCatching { com.hikari.app.data.MemoryReport.short() }.getOrNull().orEmpty()
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        runCatching { com.hikari.app.ui.PosterLoader.clearMemory() }
        // A nudge, not the fix: the caches above are the memory given back.
        System.gc()
        runCatching {
            Logs.log("Memory", "heap pressure ($caller) · $before → dropped image caches")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressured = heapPressure()
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !pressured) return
        // Read BEFORE anything is dropped: the number that matters is what the
        // process held when the device decided it was tight.
        val held = com.hikari.app.data.MemoryReport.short()
        // On a TELEVISION the caches ARE worth dropping — see
        // [com.hikari.app.data.PerfMode.tvDevice]. An evicted poster costs a
        // decode on the next scroll; a killed process costs the user the page
        // they were on, and the native QuickJS engines a detail load is waiting
        // on are exactly what a 1-1.5GB box runs out of room for.
        //
        // Handing back the poster cache is the largest thing the app can give
        // away WITHOUT touching work in flight, so on a television it goes. Off
        // one this stays a pure log line: the cache there is bounded, the device
        // has the memory, and re-decoding a screenful of posters after every
        // trim would be its own bug.
        // OOM guard: probes hold the largest transient buffers (okio segments
        // over HTTP/2). On pressure, unblock their sockets first — withTimeout
        // alone cannot interrupt a parked read; cancelling the Calls can.
        runCatching { com.hikari.app.net.StreamProbe.cancelInFlight() }
        val onTv = com.hikari.app.data.PerfMode.tvDevice
        val critical = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (onTv || critical || pressured) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            // TV already drops stills via the poster path; on a pressured
            // phone the decoded stills go too, plus a GC nudge.
            if (pressured) {
                runCatching { com.hikari.app.ui.PosterLoader.clearMemory() }
                System.gc()
            }
        }
        runCatching {
            Logs.log(
                "Memory",
                "onTrimMemory(" + trimLevelName(level) + ") · " + held +
                    (if (onTv || critical || pressured) " → poster cache cleared" else ""),
            )
        }
    }

    private fun trimLevelName(level: Int): String = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> level.toString()
    }

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
            override fun onActivityResumed(activity: Activity) {
                relieveHeapPressure("resume")
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun registerAniyomiSingletons() {
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
            val onMain = thread === android.os.Looper.getMainLooper().thread
            if (onMain) {
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }
    }

    private val NO_REFERER_HOSTS = setOf("fourhoi.com", "surrit.com")

    /** Clear the persisted crash banner after the user dismisses it. */
    fun clearCrash() {
        lastCrash = null
        runCatching { File(cacheDir, "crash.log").delete() }
        runCatching { Logs.clearCrash(this) }
    }

    /** Where the fingerprint of the already-announced crash is kept. */
    private fun crashNoticeFpFile() = File(filesDir, "logs/crash.notified")

    fun markCrashNoticeShown() {
        lastCrash = null
        crashNoticeShown = true
        runCatching {
            crashNoticeFpFile().parentFile?.mkdirs()
            crashNoticeFpFile().writeText(crashFp.toString())
        }
    }

    @Volatile
    var webViewUseDefaultUa = true

    @Volatile
    var webViewCustomUa: String? = null

    fun effectiveWebViewUa(pluginUa: String? = null): String {
        val custom = webViewCustomUa?.trim()
        if (!webViewUseDefaultUa) {
            if (!custom.isNullOrBlank()) return custom
            if (!pluginUa.isNullOrBlank()) return pluginUa
        }
        val stock = runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }
            .getOrDefault(Http.UA)
        return withoutWebViewMarkers(stock)
    }

    private fun withoutWebViewMarkers(ua: String): String {
        if (!ua.contains("; wv") && !ua.contains("Version/4.0")) return ua
        return ua
            .replace("; wv", "")
            .replace(" Version/4.0", "")
            .replace("  ", " ")
            .trim()
    }

    private fun setupImageLoader() {
        runCatching {
            val dispatcher = Dispatcher().apply {
                maxRequests = 96
                maxRequestsPerHost = 24
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
                    var lastCode = -1
                    var lastType = ""
                    for (headers in variants) {
                        last?.close()
                        last = null
                        val builder = req.newBuilder().header("User-Agent", Http.UA)
                        headers.forEach { (k, v) -> builder.header(k, v) }
                        if (builder.build().header("Cookie") == null) {
                            runCatching {
                                com.hikari.app.net.CloudflareVerifier
                                    .clearanceFor(req.url.toString())
                            }.getOrNull()?.let { builder.header("Cookie", it) }
                        }
                        val response = runCatching { chain.proceed(builder.build()) }.getOrElse { e ->
                            com.hikari.app.data.Logs.trace(
                                "Image",
                                "request threw host=$host kind=" +
                                    (if (headers.isEmpty()) "bare" else "headers(${headers.size})") +
                                    " err=${e.javaClass.simpleName}: ${e.message} " +
                                    "url=${com.hikari.app.data.Logs.safeUrl(req.url.toString())}",
                            )
                            throw e
                        }
                        if (isUsableImage(response)) return@addInterceptor response
                        lastCode = response.code
                        lastType = response.body?.contentType()?.toString() ?: "<no-type>"
                        last = response
                    }
                    // Every variant refused: this is the blank-tile line. The
                    // URL is redacted (tokens masked), the host is not.
                    com.hikari.app.data.Logs.traceSampledEvery(
                        "Image", "refused:$host", 20L,
                    ) {
                        "refused host=$host code=$lastCode type=$lastType " +
                            "url=${com.hikari.app.data.Logs.safeUrl(req.url.toString())}"
                    }
                    last ?: chain.proceed(req)
                }
                .build()
            val loader = ImageLoader.Builder(this)
                .okHttpClient(client)
                .crossfade(true)
                .components {
                    add(coil.decode.SvgDecoder.Factory())
                    add(com.hikari.app.reader.coil.TachiyomiReaderDecoder.Factory())
                    // IMAGES LOADED THROUGH AN EXTENSION'S OWN CLIENT — the fetcher
                    // layer Nekoread registers into its image loader, ported whole
                    // here (see ExtensionPageImageFetcher). A page or a cover whose
                    // model carries an extension's source is fetched with that
                    // source's headers, cookies and interceptors, which is what a
                    // hotlink-protected CDN insists on; without it such an image is
                    // requested bare and comes back refused. ExtensionCoverRef is
                    // the one addition to that port: it names the provider and
                    // resolves it lazily on Coil's dispatcher, because this app
                    // loads extension classes on demand rather than keeping
                    // Nekoread's start-up registry (see its own documentation).
                    add(com.hikari.app.reader.source.ExtensionPageImageFetcherFactory())
                    add(com.hikari.app.reader.source.ExtensionPageImageKeyer())
                    add(com.hikari.app.reader.source.ExtensionCoverImageFetcherFactory())
                    add(com.hikari.app.reader.source.ExtensionCoverImageKeyer())
                    add(com.hikari.app.reader.source.ExtensionCoverRefFetcherFactory())
                    add(com.hikari.app.reader.source.ExtensionCoverRefKeyer())
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
                    val heap = Runtime.getRuntime().maxMemory()
                    val cap = if (com.hikari.app.data.PerfMode.tvDevice) {
                        (heap / 12).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
                    } else if (heap <= 384L * 1024 * 1024) {
                        32L * 1024 * 1024
                    } else {
                        (heap / 8).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
                    }
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

    private fun isUsableImage(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val type = response.body?.contentType()?.type?.lowercase() ?: return true
        return type == "image" || type == "application" || type == "binary" || type == "octet-stream"
    }

    private fun initCloudStream(context: Context) {
        try {
            try {
                setContext(context)
            } catch (t: Throwable) {
                android.util.Log.e("HikariApp", "setContext failed", t)
            }
            try {
                com.lagradost.cloudstream3.CloudStreamApp.setContext(context)
            } catch (t: Throwable) {
                android.util.Log.e("HikariApp", "CloudStreamApp.setContext failed", t)
            }

            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (_: Throwable) {
            }

            fun build(ignoreSSL: Boolean) = OkHttpClient.Builder()
                .dns(DohDns)
                // Same jar as PlayerHttp so Fastream cookies from embed extract
                // are available when the player fetches the signed m3u8.
                .cookieJar(com.hikari.app.net.PlayerHttp.cookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .apply { if (ignoreSSL) ignoreAllSSLErrors() }
                .addInterceptor(com.hikari.app.net.ExtensionCloudflareInterceptor { com.hikari.app.net.Http.UA })
                .build()

            val kt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
            fun wire(getter: String, ignoreSSL: Boolean) {
                val req = kt.getMethod(getter).invoke(null) as Requests
                req.baseClient = build(ignoreSSL)
            }
            wire("getApp", ignoreSSL = false)
            wire("getInsecureApp", ignoreSSL = true)
            MainAPI.settingsForProvider = SettingsJson()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
                } catch (t: Throwable) {
                    android.util.Log.e("HikariApp", "extractor registry init failed", t)
                }
            }

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
