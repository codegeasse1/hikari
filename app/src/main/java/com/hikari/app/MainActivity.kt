package com.hikari.app

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.hikari.app.net.Updater
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.components.TelegramDialog
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.AppRoot
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariTheme
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.tv.TvFocusProvider
import com.hikari.app.tv.TvMode
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    @Volatile
    private var fullscreenOff = false

    /** In-app UI scale: when on, the app ignores the phone's Font size and
     *  Display size settings everywhere (Compose screens scale themselves in
     *  HikariTheme; this covers the Activity's View-based content too). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hikari.app.ui.UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        HikariApp.mainActivity = this
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
        // No window title bar, ever — every screen is header-free by design.
        window.requestFeature(android.view.Window.FEATURE_NO_TITLE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        installSplashScreen()
        runCatching { savedInstanceState?.remove("android:support:fragments") }
        super.onCreate(savedInstanceState)
        // Publish a real result launcher to the CloudStream runtime. Extension
        // settings screens use `MainActivity.companion.activityResultLauncher`
        // for their file/folder pickers (see the shadow's companion in
        // com/lagradost/cloudstream3/MainActivity.kt); without one registered
        // they either crash on a null or silently do nothing. This is the only
        // window androidx allows for it — after super.onCreate, before onStart.
        runCatching {
            com.lagradost.cloudstream3.MainActivity.activityResultLauncher =
                registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                ) { }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                9911,
            )
        }
        // CloudStream plugin settings screens are often
        // BottomSheetDialogFragments whose layout wraps a plain
        // android.widget.ScrollView. Unlike NestedScrollView that is NOT a
        // NestedScrollingChild, so the sheet's BottomSheetBehavior finds no
        // scrolling child and dragging the list instead drags the whole sheet
        // down (which looks like "scrolling closes the settings page").
        // Worse, these sheets open in STATE_COLLAPSED, which lays the sheet out
        // partly BELOW the bottom of the screen, so the last rows of the list
        // can never be scrolled into view. Expand the sheet to its content and
        // make it non-draggable so the inner list scrolls end-to-end; tapping
        // outside or pressing Back still closes it, and a Close button is added
        // next to the plugin's own Save button (see addSettingsCloseButton).
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment,
                    savedInstanceState: android.os.Bundle?,
                ) {
                    dismissExtensionPopup(f)
                }

                override fun onFragmentStarted(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment,
                ) {
                    // Second net: a popup that was already showing when this
                    // activity started is closed here.
                    dismissExtensionPopup(f)
                    fixPluginSheetScrolling(f)
                }
            },
            true,
        )
        val store = (application as HikariApp).store
        TvMode.detect(this)
        (application as HikariApp).syncTvDeviceFlag()
        val storedTvMode = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) { store.tvMode() }
            }
        }.getOrNull() ?: TvMode.AUTO
        TvMode.setOverride(storedTvMode)
        com.hikari.app.data.Logs.log(
            "App",
            "layout: " + (if (TvMode.isTv) "television" else "phone/tablet") +
                " (stored=" + storedTvMode + " override=" + TvMode.override +
                " " + TvMode.describe(this) + ")",
        )
        handleTrackerRedirect(intent)
        // A repo link forwarded by RepoLinkActivity ("Open with → Hikari" in
        // a browser) lands here the same way on a cold start.
        handleRepoLink(intent)
        if (TvMode.isTv) {
            runCatching {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        // True fullscreen (the default): hide the system status + navigation
        // bars everywhere (swipe from any edge to briefly reveal them), so
        // content fills the whole screen instead of stopping below a status
        // bar. Settings → App Layout can turn that off; the mirror is seeded
        // here because onResume/onWindowFocusChanged re-apply the mode and
        // cannot await DataStore.
        // Bounded: this is a blocking settings read on the main thread during
        // onCreate. Every other store read in the app is async; this one cannot
        // be (onResume re-applies the mode and cannot await DataStore), so it
        // gets a timeout instead — a wedged store must never be able to hold the
        // window's first frame (see HikariApp.STARTUP_STORE_READ_MS).
        fullscreenOff = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) { store.fullscreenOff() }
            }
        }.getOrNull() ?: false
        applyImmersiveMode()
        setContent {
            val scope = rememberCoroutineScope()
            // Remember the Flow — a fresh store.themeFlow() per recomposition
            // would make collectAsState reset to the initial key each time.
            val themeFlow = remember { store.themeFlow() }
            val themeKey by themeFlow.collectAsState(
                initial = AccentStore.theme(this@MainActivity)
            )
            val themeMode = HikariThemeMode.fromKey(themeKey)

            val languageFlow = remember { store.languageFlow() }
            val storedLanguage by languageFlow.collectAsState(
                initial = com.hikari.app.ui.LanguageManager.stored(this@MainActivity)
            )
            val languageTag = com.hikari.app.ui.LanguageManager.pending() ?: storedLanguage
            val uiLanguageTag = com.hikari.app.ui.LanguageManager.effectiveTag(languageTag)
            val i18nMap = remember(uiLanguageTag) {
                com.hikari.app.i18n.I18n.mapFor(this@MainActivity, uiLanguageTag)
            }
            val localizedConfig = remember(uiLanguageTag) {
                val base = Configuration(this@MainActivity.resources.configuration)
                val locale = if (uiLanguageTag.isBlank()) {
                    java.util.Locale.getDefault()
                } else {
                    java.util.Locale.forLanguageTag(uiLanguageTag)
                }
                base.setLocale(locale)
                base.setLayoutDirection(locale)
                base
            }
            val rtl = localizedConfig.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            LaunchedEffect(i18nMap, uiLanguageTag) {
                com.hikari.app.i18n.I18n.setCurrent(i18nMap, uiLanguageTag)
                com.hikari.app.data.Logs.log(
                    "i18n",
                    "interface language '$uiLanguageTag' (setting '$languageTag', " +
                        "platform '${com.hikari.app.ui.LanguageManager.platformTag()}'), " +
                        "${i18nMap.size} strings",
                )
            }
            LaunchedEffect(storedLanguage) {
                com.hikari.app.ui.LanguageManager.reconcile(storedLanguage)
                com.hikari.app.ui.LanguageManager.rememberStored(this@MainActivity, storedLanguage)
            }

            // Accent colours (Settings → Appearance & Theme). The app accent repaints
            // the whole Compose UI; the player accent is for the View-based
            // player, which reads it synchronously via AccentStore.
            val appAccentFlow = remember { store.appAccentFlow() }
            val playerAccentFlow = remember { store.playerAccentFlow() }
            val themeLinkedFlow = remember { store.themeLinkedFlow() }
            val appAccentKey by appAccentFlow.collectAsState(
                initial = AccentStore.app(this@MainActivity).key
            )
            val playerAccentKey by playerAccentFlow.collectAsState(
                initial = AccentStore.playerOwn(this@MainActivity).key
            )
            val themeLinked by themeLinkedFlow.collectAsState(
                initial = AccentStore.isLinked(this@MainActivity)
            )
            val appAccent = HikariAccent.fromKey(appAccentKey)

            // Keep the synchronous mirror of the accent preferences current, so
            // the player (and the next cold start) picks them up immediately.
            LaunchedEffect(appAccentKey, playerAccentKey, themeLinked, themeKey) {
                AccentStore.sync(
                    this@MainActivity, appAccentKey, playerAccentKey, themeLinked, themeKey
                )
            }

            // In-app UI scale (Settings → App Layout → In-app UI scale): when on, the app
            // stops following the phone's font/display size and uses this.
            val uiScaleEnabledFlow = remember { store.uiScaleEnabledFlow() }
            val uiScaleMirror = remember {
                com.hikari.app.ui.UiScale.current(this@MainActivity)
            }
            val uiScaleEnabled by uiScaleEnabledFlow.collectAsState(
                initial = uiScaleMirror.first
            )
            val uiScaleFlow = remember { store.uiScaleFlow() }
            val uiScale by uiScaleFlow.collectAsState(initial = uiScaleMirror.second)

            LaunchedEffect(uiScaleEnabled, uiScale) {
                com.hikari.app.ui.UiScale.sync(
                    this@MainActivity, uiScaleEnabled, uiScale
                )
            }

            val fullscreenOffFlow = remember { store.fullscreenOffFlow() }
            val fullscreenOffPref by fullscreenOffFlow.collectAsState(initial = fullscreenOff)
            LaunchedEffect(fullscreenOffPref) {
                fullscreenOff = fullscreenOffPref
                applyImmersiveMode()
            }

            // App-wide font (Settings → Appearance & Theme → App font). The Compose half
            // rides on the theme's typography below; the View-based half (the
            // player, its dialogs, the WebView) reads the synchronous mirror
            // this keeps up to date.
            val appFontFlow = remember { store.appFontFlow() }
            val appFontFileFlow = remember { store.appFontFileFlow() }
            // Seeded from the synchronous mirror for the same reason as the
            // accents and the scale.
            val appFontSaved = remember {
                com.hikari.app.ui.AppFonts.current(this@MainActivity)
            }
            val appFontKey by appFontFlow.collectAsState(initial = appFontSaved.key)
            val appFontFile by appFontFileFlow.collectAsState(initial = appFontSaved.fileName)
            var importedFontLabel by remember { mutableStateOf("") }
            LaunchedEffect(appFontKey, appFontFile) {
                com.hikari.app.ui.AppFonts.sync(this@MainActivity, appFontKey, appFontFile)
                importedFontLabel = if (appFontKey == com.hikari.app.ui.AppFonts.IMPORTED) {
                    runCatching { store.appFontLabel() }.getOrDefault("")
                } else {
                    ""
                }
            }
            val appFontFamily = remember(appFontKey, appFontFile) {
                com.hikari.app.ui.AppFonts.fontFamily(this@MainActivity, appFontKey, appFontFile)
            }

            val hideHelpFlow = remember { store.hideHelpFlow() }
            val hideHelp by hideHelpFlow.collectAsState(initial = false)

            val tmdbLanguageFlow = remember { store.tmdbLanguageFlow() }
            val tmdbLanguageMode by tmdbLanguageFlow.collectAsState(initial = "")
            val tmdbLanguage = when (tmdbLanguageMode) {
                "" -> com.hikari.app.data.TmdbLang.forAppLanguage(languageTag)
                "none" -> ""
                else -> tmdbLanguageMode
            }
            LaunchedEffect(tmdbLanguage) {
                (application as HikariApp).applyContentLanguage(tmdbLanguage)
            }

            LaunchedEffect(themeMode) {
                // Dark status-bar icons on the light theme so they stay visible.
                androidx.core.view.WindowCompat.getInsetsController(
                    window, window.decorView
                ).isAppearanceLightStatusBars = themeMode == HikariThemeMode.LIGHT
            }

            var showUpdateDialog by remember { mutableStateOf(false) }
            var updateChecked by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // One quiet check on launch — the dialog only appears when a
                // newer build exists on GitHub.
                runCatching { Updater.checkForUpdate() }
                    .getOrNull()
                    ?.takeIf { it.available }
                    ?.let { showUpdateDialog = true }
                updateChecked = true
            }

            var showTelegramDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val current = runCatching { com.hikari.app.BuildConfig.VERSION_NAME }.getOrDefault("")
                val dontShowFor = runCatching { store.communityDontShowVersion() }.getOrDefault("")
                val seen = runCatching { store.communitySeenVersion() }.getOrDefault("")
                if (dontShowFor != current && seen != current) {
                    showTelegramDialog = true
                }
            }

            val tvModeFlow = remember { store.tvModeFlow() }
            LaunchedEffect(tvModeFlow) {
                tvModeFlow.collect {
                    TvMode.setOverride(it)
                    runCatching {
                        requestedOrientation =
                            if (TvMode.isTv) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    applyImmersiveMode()
                }
            }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfig,
                LocalLayoutDirection provides
                    if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                com.hikari.app.i18n.I18n.LocalMap provides i18nMap,
                // One switch, every explanation line in the app (Settings → App
                // Layout → "Explanations") — see LocalHideHelp.
                com.hikari.app.ui.components.LocalHideHelp provides hideHelp,
            ) {
            HikariTheme(
                mode = themeMode,
                accent = appAccent,
                uiScaleEnabled = uiScaleEnabled,
                uiScale = uiScale,
                fontFamily = appFontFamily,
            ) {
                TvFocusProvider(androidx.compose.material3.MaterialTheme.colorScheme.primary) {
                com.hikari.app.ui.AppLockGate(this@MainActivity) {
                AppRoot(themeMode.key)
                if (showUpdateDialog) {
                    UpdateDialog(
                        context = this@MainActivity,
                        onDismiss = { showUpdateDialog = false },
                    )
                }
                if (showTelegramDialog && updateChecked && !showUpdateDialog) {
                    TelegramDialog(
                        context = this@MainActivity,
                        onDismiss = {
                            showTelegramDialog = false
                            scope.launch {
                                runCatching {
                                    store.setCommunitySeenVersion(
                                        com.hikari.app.BuildConfig.VERSION_NAME
                                    )
                                }
                            }
                        },
                        onDontShowAgain = {
                            showTelegramDialog = false
                            scope.launch {
                                runCatching {
                                    val v = com.hikari.app.BuildConfig.VERSION_NAME
                                    store.setCommunitySeenVersion(v)
                                    store.setCommunityDontShowVersion(v)
                                }
                            }
                        },
                    )
                }
                }
                }
            }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.hikari.app.tv.TvInput.noteKey(event)
        return super.dispatchKeyEvent(event)
    }

    /** A finger means the platform ripple is wanted again (on a non-TV device). */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            com.hikari.app.tv.TvInput.noteTouch()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        // The CloudStream runtime's Torrent engine needs an activity reference
        // for its cache dir (it throws "No activity" otherwise).
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
        // The real CloudStream host exposes its activity as MainAPI.app; some
        // plugins read it (or cast load()'s context) and throw when it's null
        // or not an Activity. Set it reflectively — the jar's MainAPI shape
        // varies, so each strategy is guarded.
        setMainApiApp(this)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        runCatching {
            requestedOrientation =
                if (TvMode.isTv) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyImmersiveMode() {
        runCatching {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller =
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreenOff && !TvMode.isTv) {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            } else {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onStop() {
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) {
            com.lagradost.cloudstream3.CommonActivity.setActivityInstance(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (HikariApp.mainActivity === this) {
            HikariApp.mainActivity = null
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // A dialog (plugin settings sheet, resume prompt, update dialog) taking
        // focus shows the system bars again; re-hide them the moment we get
        // focus back so the UI stays fullscreen.
        applyImmersiveMode()
        val app = application as HikariApp
        app.appScope.launch {
            runCatching {
                com.hikari.app.net.ExtensionVerifyGuard.apply(
                    app,
                    app.store.extensionVerifyWebview(),
                )
            }
        }
        val path = com.hikari.app.cs3.Cs3PluginManager.pendingSettingsReload ?: return
        com.hikari.app.cs3.Cs3PluginManager.pendingSettingsReload = null
        app.appScope.launch {
            runCatching {
                val file = java.io.File(path)
                if (file.exists()) com.hikari.app.cs3.Cs3PluginManager.reload(app, file)
                com.hikari.app.cs3.Cs3ProviderSync.reconcile(app, app.store)
                app.providers.refresh()
            }
        }
    }

    private fun handleTrackerRedirect(intent: android.content.Intent?) {
        val url = intent?.data?.toString().orEmpty()
        if (!url.startsWith(com.hikari.app.tracker.TrackerApi.REDIRECT_URI)) return
        if (com.hikari.app.tracker.TrackerRedirect.dialogOpen) {
            com.hikari.app.tracker.TrackerRedirect.deliver(url)
            return
        }
        val token = com.hikari.app.tracker.TrackerApi.tokenFromRedirect(url)
        val app = application as HikariApp
        if (token != null) {
            lifecycleScope.launch {
                val client = runCatching { app.store.trackerClient(com.hikari.app.data.TrackerKind.ANILIST) }
                    .getOrDefault(com.hikari.app.data.TrackerClient(com.hikari.app.data.TrackerKind.ANILIST))
                if (client.id.isBlank()) {
                    toast(
                        com.hikari.app.i18n.I18n.t("AniList signed you in, but this app does not know your client id yet — Settings → Trackers, paste it, then resend the link.")
                    )
                    return@launch
                }
                val done = runCatching { com.hikari.app.tracker.TrackerApi.signInWithToken(client, token) }.getOrNull()
                if (done == null) {
                    toast(com.hikari.app.i18n.I18n.t("AniList: could not finish the sign-in from that link."))
                    return@launch
                }
                done.onSuccess { account ->
                    runCatching { app.store.setTrackerAccount(account) }
                    runCatching { app.store.setTrackerPending(null) }
                    toast(account.describe + " — connected")
                }.onFailure { toast("AniList: " + it.message) }
            }
            return
        }
        val code = com.hikari.app.tracker.TrackerApi.codeFromRedirect(url)
        val echoed = com.hikari.app.tracker.TrackerApi.stateFromRedirect(url)
        if (code != null && echoed != null) {
            lifecycleScope.launch {
                val pending = runCatching { app.store.trackerPending() }.getOrNull()
                if (pending == null || pending.state != echoed) {
                    toast(
                        com.hikari.app.i18n.I18n.t(
                            "That sign-in link no longer matches a sign-in started in this app — " +
                                "start it again from Settings → Trackers."
                        )
                    )
                    return@launch
                }
                val stored = runCatching { app.store.trackerClient(pending.kind) }
                    .getOrDefault(com.hikari.app.data.TrackerClient(pending.kind))
                // One link is one sign-in, whatever the outcome: dropping the
                // record here is what stops a stale `state` being reused.
                runCatching { app.store.setTrackerPending(null) }
                val done = runCatching {
                    com.hikari.app.tracker.TrackerApi.exchangeCode(
                        if (stored.id.isBlank()) stored.copy(id = pending.clientId) else stored,
                        code,
                        pending.state,
                    )
                }.getOrNull()
                if (done == null) {
                    toast(
                        com.hikari.app.i18n.I18n.t(
                            "${pending.kind.label}: could not finish the sign-in from that link."
                        )
                    )
                    return@launch
                }
                done.onSuccess { account ->
                    runCatching { app.store.setTrackerAccount(account) }
                    toast(account.describe + " — connected")
                }.onFailure { toast(it.message ?: "${pending.kind.label}: the sign-in failed.") }
            }
            return
        }
        toast(com.hikari.app.i18n.I18n.t("This sign-in link has to be finished in the app: Settings → Trackers → paste it into the code field."))
    }

    /** A short message from a non-Compose place. */
    private fun toast(text: String) {
        runCatching { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's current intent so anything that reads it later
        // (and a recreation, which re-runs onCreate) sees the same link.
        setIntent(intent)
        handleTrackerRedirect(intent)
        handleRepoLink(intent)
    }

    private fun handleRepoLink(intent: android.content.Intent?) {
        val url = intent?.getStringExtra(com.hikari.app.deeplink.RepoLinkActivity.EXTRA_REPO_LINK)
            .orEmpty().trim()
        if (url.isBlank()) return
        (application as HikariApp).repoLinkRequest.value = url
        intent?.removeExtra(com.hikari.app.deeplink.RepoLinkActivity.EXTRA_REPO_LINK)
    }

    /** Set MainAPI.app to this activity, whichever form the jar compiles it
     *  as (plain static field, Kotlin object, or companion instance). */
    private fun setMainApiApp(activity: MainActivity) {
        runCatching {
            val cls = Class.forName("com.lagradost.cloudstream3.MainAPI")
            runCatching { cls.getField("app").set(null, activity) }
            val holder = runCatching { cls.getField("INSTANCE").get(null) }
                .getOrNull() ?: runCatching { cls.getField("Companion").get(null) }.getOrNull()
            if (holder != null) {
                holder.javaClass.getField("app").set(holder, activity)
            }
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

    private fun dismissExtensionPopup(fragment: androidx.fragment.app.Fragment) {
        if (fragment.javaClass.classLoader === javaClass.classLoader) return
        val popup = fragment as? androidx.fragment.app.DialogFragment ?: return
        if (!com.hikari.app.net.ExtensionVerifyGuard.blocksPopup(
                fragment.javaClass.name,
                fragment.tag,
            )
        ) {
            return
        }
        com.hikari.app.data.Logs.log(
            "Extensions",
            "closed a popup an extension opened on its own: " +
                fragment.javaClass.name + " (tag=" + fragment.tag + ")",
        )
        runCatching { popup.dismissAllowingStateLoss() }
    }

    private fun fixPluginSheetScrolling(fragment: androidx.fragment.app.Fragment) {
        val dialog = (fragment as? androidx.fragment.app.DialogFragment)?.dialog ?: return
        if (fragment.javaClass.classLoader === javaClass.classLoader) return
        val decor = dialog.window?.decorView ?: return
        if (dialog is com.google.android.material.bottomsheet.BottomSheetDialog) {
            addSettingsCloseButton(dialog, decor)
            expandSheet(dialog)
            decor.post {
                addSettingsCloseButton(dialog, decor)
                expandSheet(dialog)
            }
        }
        val scrollView = findPlainScrollView(decor) ?: return
        if (dialog is com.google.android.material.bottomsheet.BottomSheetDialog) {
            boundScrollView(scrollView)
            decor.postDelayed({ clampScrollViewToScreen(dialog, decor, scrollView) }, 600L)
            decor.postDelayed({ clampScrollViewToScreen(dialog, decor, scrollView) }, 1600L)
        }
    }

    private fun addSettingsCloseButton(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        decor: android.view.View,
    ) {
        runCatching {
            if (decor.findViewById<android.view.View>(settingsCloseButtonId) != null) return@runCatching
            val save = findSettingsHeaderAction(decor) ?: return@runCatching
            val header = save.parent as? android.widget.RelativeLayout ?: return@runCatching
            val title = settingsHeaderTitle(header) ?: return@runCatching
            val context = header.context
            val size = dpToPx(context, 44)
            val close = android.widget.ImageButton(context).apply {
                id = settingsCloseButtonId
                contentDescription = com.hikari.app.i18n.I18n.t("Close settings")
                scaleType = android.widget.ImageView.ScaleType.CENTER
                // Same touch feedback a Material icon button uses; falls back to
                // a plain transparent background if the sheet's theme has none.
                val bg = android.util.TypedValue()
                val hasRipple = runCatching {
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, bg, true
                    ) && bg.resourceId != 0
                }.getOrDefault(false)
                if (hasRipple) setBackgroundResource(bg.resourceId)
                else setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val drawable = androidx.core.content.ContextCompat
                    .getDrawable(context, R.drawable.ic_close)?.mutate()
                drawable?.setTint(title.currentTextColor)
                setImageDrawable(drawable)
                setOnClickListener { runCatching { dialog.dismiss() } }
            }
            val lp = android.widget.RelativeLayout.LayoutParams(size, size)
            if (save.id != android.view.View.NO_ID) {
                lp.addRule(android.widget.RelativeLayout.LEFT_OF, save.id)
            } else {
                lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                lp.rightMargin = dpToPx(context, 56)
            }
            lp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            header.addView(close, lp)
            runCatching {
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
            }
        }
    }

    /** The header action in a plugin settings sheet: the ImageButton whose
     *  contentDescription mentions "save" (SK Tech's is "Save settings"), else
     *  the last ImageButton of the tree — where plugin headers keep it. */
    private fun findSettingsHeaderAction(root: android.view.View): android.view.View? {
        var byDescription: android.view.View? = null
        var lastImageButton: android.widget.ImageButton? = null
        val stack = ArrayDeque<android.view.View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is android.widget.ImageButton) {
                lastImageButton = v
                val desc = v.contentDescription?.toString().orEmpty()
                if (byDescription == null && desc.contains("save", ignoreCase = true)) {
                    byDescription = v
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
        return byDescription ?: lastImageButton
    }

    /** The settings header's own title (its first TextView), or null when the
     *  row is not a titled header bar. Its text color is the color the sheet's
     *  icons are drawn in, so the Close button matches whatever theme the
     *  plugin's sheet uses. */
    private fun settingsHeaderTitle(header: android.view.ViewGroup): android.widget.TextView? =
        (0 until header.childCount)
            .map { header.getChildAt(it) }
            .firstOrNull { it is android.widget.TextView } as? android.widget.TextView

    private fun dpToPx(context: android.content.Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun clampScrollViewToScreen(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        decor: android.view.View,
        scrollView: android.widget.ScrollView,
    ) {
        runCatching {
            if (!scrollView.isAttachedToWindow) return@runCatching
            if (dialog.behavior.state !=
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            ) {
                return@runCatching
            }
            val available = decor.height
            if (available <= 0) return@runCatching
            val scrollLoc = IntArray(2)
            scrollView.getLocationInWindow(scrollLoc)
            val decorLoc = IntArray(2)
            decor.getLocationInWindow(decorLoc)
            val top = (scrollLoc[1] - decorLoc[1]).coerceAtLeast(0)
            val desired = available - top
            if (desired <= 0 || scrollView.height <= desired) return@runCatching
            val lp = scrollView.layoutParams ?: return@runCatching
            lp.height = desired
            scrollView.layoutParams = lp
            scrollView.requestLayout()
        }
    }

    /** Opens a plugin's settings sheet fully so none of its list hides below
     *  the bottom edge of the screen, and stops it from being drag-dismissed. */
    private fun expandSheet(dialog: com.google.android.material.bottomsheet.BottomSheetDialog) {
        runCatching {
            val behavior = dialog.behavior
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = false
            behavior.isDraggable = false
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun boundScrollView(scrollView: android.widget.ScrollView) {
        runCatching {
            val lp = scrollView.layoutParams ?: return@runCatching
            if (lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                scrollView.layoutParams = lp
            }
            scrollView.isVerticalScrollBarEnabled = true
            scrollView.requestLayout()
        }
    }

    /** The plain android.widget.ScrollView (the non-nested-scrolling kind that
     *  confuses BottomSheetBehavior) in [view]'s subtree, or null. */
    private fun findPlainScrollView(view: android.view.View): android.widget.ScrollView? {
        if (view is android.widget.ScrollView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findPlainScrollView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        /** A stable id for the injected Close button: the sheet is visited twice
         *  (once immediately, once after its first layout pass), and this is how
         *  the second visit knows the button is already there. */
        private val settingsCloseButtonId = android.view.View.generateViewId()
    }
}
