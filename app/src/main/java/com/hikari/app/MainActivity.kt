package com.hikari.app

import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    /** Settings → App Layout → "Turn off full screen app mode". The system
     *  bars are shown/hidden from onResume, onWindowFocusChanged and the
     *  Compose side, none of which can await DataStore — so the value is
     *  mirrored here (read once at launch, kept current by a flow collector in
     *  setContent). See [applyImmersiveMode]. */
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
        // Expose the activity to the CloudStream runtime as early as possible:
        // plugins are warmed from HikariApp.onCreate's background coroutine,
        // which can run before onStart, and plugins cast this context to an
        // Activity/AppCompatActivity.
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
        // No window title bar, ever — every screen is header-free by design.
        window.requestFeature(android.view.Window.FEATURE_NO_TITLE)
        // Edge-to-edge: the app draws behind the status + navigation bars so
        // the dark background covers the whole screen (no color band at the
        // top), like a real fullscreen streaming app. Light status icons for
        // the dark themes.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // API 33+: the download and background-work notifications are how the
        // user sees (and controls) work that keeps running while Hikari is in
        // the background, so ask for the permission up front instead of only
        // when the first download starts.
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
                    // Dismissed BEFORE the dialog is built, so a verification
                    // page an extension opens by itself never gets a frame on
                    // screen (see dismissExtensionPopup).
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
        // Which kind of device this is. Asked again here — HikariApp asked at
        // process start — because a few boxes only settle their UI mode once an
        // Activity exists. The user's override is read synchronously for the
        // same reason the fullscreen flag below is: the very first frame has to
        // already be the right layout, and Compose cannot await DataStore.
        TvMode.detect(this)
        TvMode.setOverride(
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(3_000L) { store.tvMode() }
                }
            }.getOrNull() ?: TvMode.AUTO
        )
        com.hikari.app.data.Logs.log(
            "App",
            "layout: " + (if (TvMode.isTv) "television" else "phone/tablet") +
                " (" + TvMode.describe(this) + ")",
        )
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
            val themeKey by themeFlow.collectAsState(initial = HikariThemeMode.DARK.key)
            val themeMode = HikariThemeMode.fromKey(themeKey)

            // App language (Settings → Appearance & Theme). The map is
            // handed to the whole tree through I18n.LocalMap, so every screen
            // that wraps a literal with tr(...) re-renders in the new language
            // the moment the choice changes — app-wide, live, no restart.
            val languageFlow = remember { store.languageFlow() }
            val storedLanguage by languageFlow.collectAsState(initial = "")
            // The picker's choice is kept in memory (LanguageManager.pending)
            // until the store catches up, so the activity recreation the locale
            // change triggers can never show the previous language.
            val languageTag = com.hikari.app.ui.LanguageManager.pending() ?: storedLanguage
            // The language the INTERFACE speaks. `languageTag` is Hikari's own
            // setting (which the TMDB title language follows), but when it is
            // empty — "System default" — the platform's answer (Android's
            // per-app language screen for Hikari, or the device locale) decides,
            // so choosing Arabic for the app ANYWHERE translates it. See
            // LanguageManager.effectiveTag.
            val uiLanguageTag = com.hikari.app.ui.LanguageManager.effectiveTag(languageTag)
            val i18nMap = remember(uiLanguageTag) {
                com.hikari.app.i18n.I18n.mapFor(this@MainActivity, uiLanguageTag)
            }
            LaunchedEffect(i18nMap, uiLanguageTag) {
                com.hikari.app.i18n.I18n.setCurrent(i18nMap, uiLanguageTag)
                // One line, deliberately: the interface language is the one
                // thing a user reports as "I set Arabic and it is still
                // English", and this says which tag was resolved, what Hikari's
                // own setting held, and how many strings the dictionary
                // actually had — the three questions that answer it.
                com.hikari.app.data.Logs.log(
                    "i18n",
                    "interface language '$uiLanguageTag' (setting '$languageTag', " +
                        "platform '${com.hikari.app.ui.LanguageManager.platformTag()}'), " +
                        "${i18nMap.size} strings",
                )
            }
            LaunchedEffect(storedLanguage) {
                com.hikari.app.ui.LanguageManager.reconcile(storedLanguage)
            }

            // Accent colours (Settings → Appearance & Theme). The app accent repaints
            // the whole Compose UI; the player accent is for the View-based
            // player, which reads it synchronously via AccentStore.
            val appAccentFlow = remember { store.appAccentFlow() }
            val playerAccentFlow = remember { store.playerAccentFlow() }
            val themeLinkedFlow = remember { store.themeLinkedFlow() }
            val appAccentKey by appAccentFlow.collectAsState(initial = HikariAccent.DEFAULT_APP.key)
            val playerAccentKey by playerAccentFlow.collectAsState(
                initial = HikariAccent.DEFAULT_PLAYER.key
            )
            val themeLinked by themeLinkedFlow.collectAsState(initial = false)
            val appAccent = HikariAccent.fromKey(appAccentKey)

            // Keep the synchronous mirror of the accent preferences current, so
            // the player (and the next cold start) picks them up immediately.
            LaunchedEffect(appAccentKey, playerAccentKey, themeLinked) {
                AccentStore.sync(
                    this@MainActivity, appAccentKey, playerAccentKey, themeLinked
                )
            }

            // In-app UI scale (Settings → App Layout → In-app UI scale): when on, the app
            // stops following the phone's font/display size and uses this.
            val uiScaleEnabledFlow = remember { store.uiScaleEnabledFlow() }
            val uiScaleEnabled by uiScaleEnabledFlow.collectAsState(initial = false)
            val uiScaleFlow = remember { store.uiScaleFlow() }
            val uiScale by uiScaleFlow.collectAsState(initial = 1f)

            // Keep the synchronous mirror of the preference current, so
            // View-based screens (player, WebView) and the next cold start
            // apply it without waiting on DataStore.
            LaunchedEffect(uiScaleEnabled, uiScale) {
                com.hikari.app.ui.UiScale.sync(
                    this@MainActivity, uiScaleEnabled, uiScale
                )
            }

            // Full screen app mode (Settings → App Layout): switching it shows
            // or hides the phone's own status + navigation bars right away,
            // without a restart. The mirror above is kept in step so the next
            // onResume/onWindowFocusChanged re-applies the same mode.
            val fullscreenOffFlow = remember { store.fullscreenOffFlow() }
            val fullscreenOffPref by fullscreenOffFlow.collectAsState(initial = false)
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
            val appFontKey by appFontFlow.collectAsState(initial = com.hikari.app.ui.AppFonts.DEFAULT)
            val appFontFile by appFontFileFlow.collectAsState(initial = "")
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

            // Which language TMDB answers in. "" follows the app language (the
            // point of the feature: switch the app to Spanish and the movies
            // and series are titled in Spanish too), "none" leaves TMDB on
            // English, anything else is an explicit TMDB code.
            val tmdbLanguageFlow = remember { store.tmdbLanguageFlow() }
            val tmdbLanguageMode by tmdbLanguageFlow.collectAsState(initial = "")
            val tmdbLanguage = when (tmdbLanguageMode) {
                "" -> com.hikari.app.data.TmdbLang.forAppLanguage(languageTag)
                "none" -> ""
                else -> tmdbLanguageMode
            }
            LaunchedEffect(tmdbLanguage) {
                // Applies the language AND, when it really changed, invalidates
                // the content that was localized under the previous one — so the
                // switch is live instead of waiting for a restart.
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

            // One-time Telegram invitation. Held back until the update check has
            // finished so the two dialogs never stack, and skipped wholesale
            // once "Don't show this again" has been ticked.
            var showTelegramDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!runCatching { store.telegramDontShow() }.getOrDefault(false)) {
                    showTelegramDialog = true
                }
            }

            // The television layout can be chosen at runtime (Settings → TV &
            // Remote → "This device is a TV"), so the mirror the whole app reads
            // is kept in step with the stored choice — switching it re-lays-out
            // the interface live, without a restart.
            val tvModeFlow = remember { store.tvModeFlow() }
            LaunchedEffect(tvModeFlow) {
                tvModeFlow.collect { TvMode.setOverride(it) }
            }

            CompositionLocalProvider(
                com.hikari.app.i18n.I18n.LocalMap provides i18nMap
            ) {
            HikariTheme(
                mode = themeMode,
                accent = appAccent,
                uiScaleEnabled = uiScaleEnabled,
                uiScale = uiScale,
                fontFamily = appFontFamily,
            ) {
                // On a television, every clickable in the app inherits the focus
                // ring from here (see TvFocusProvider) — including the dialogs
                // below, which is why the provider wraps them too. On a phone
                // this is a pass-through and the platform ripple is unchanged.
                TvFocusProvider(androidx.compose.material3.MaterialTheme.colorScheme.primary) {
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
                        onDismiss = { showTelegramDialog = false },
                        onDontShowAgain = {
                            showTelegramDialog = false
                            scope.launch { runCatching { store.setTelegramDontShow(true) } }
                        },
                    )
                }
                }
            }
            }
        }
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
        // Coming back from the background (or another activity) the system
        // restores the status/navigation bars, so re-apply the immersive mode —
        // otherwise the app is left with a status-bar-sized blank band that
        // pushes every screen down until the next launch.
        applyImmersiveMode()
    }

    /**
     * Immersive fullscreen: hide the system status + navigation bars so the
     * content fills the entire screen (swiping from an edge briefly reveals
     * them). Applied at launch AND on every resume/focus gain — this is not
     * sticky on its own, and when the bars come back they leave an empty band
     * above the content (the "fullscreen leaves a blank bar under the status
     * bar" report), which shows up on some devices and not others.
     *
     * When Settings → App Layout → "Turn off full screen app mode" is on, the
     * bars are shown instead and left to behave normally. Either way the window
     * stays edge-to-edge (the bars are transparent over the app's own
     * backdrop), and the screens pad themselves by the reported insets.
     */
    private fun applyImmersiveMode() {
        runCatching {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller =
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            // A television is always fullscreen: there is no status bar to read
            // and no navigation bar to reach, and every television app hides the
            // two rows a touch device would show. The "Turn off full screen app
            // mode" preference is therefore a phone-only setting (its card is
            // hidden there too) — it cannot take the bars back on a TV.
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
        // NOTE: HikariApp.mainActivity is intentionally NOT cleared here. A
        // plugin load can happen while the app is backgrounded (a catalog
        // refresh, a settings reload), and plugins cast this context to an
        // Activity — clearing it on stop was exactly what made SKTech throw
        // "HikariApp cannot be cast to AppCompatActivity". It is cleared in
        // onDestroy instead.
        super.onStop()
    }

    override fun onDestroy() {
        if (HikariApp.mainActivity === this) {
            HikariApp.mainActivity = null
        }
        super.onDestroy()
    }

    /**
     * When a plugin's own settings screen (a DialogFragment the plugin shows,
     * like SKTech's sub-provider picker) is dismissed, this window regains
     * focus. Re-load the plugin it belongs to and refresh the provider list so
     * the change is reflected immediately (home catalogs, provider names) —
     * without needing the app restart some plugins ask for.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // A dialog (plugin settings sheet, resume prompt, update dialog) taking
        // focus shows the system bars again; re-hide them the moment we get
        // focus back so the UI stays fullscreen.
        applyImmersiveMode()
        val app = application as HikariApp
        // Focus coming back is also the one moment an extension can flip its own
        // Cloudflare-WebView switch — a plugin's settings sheet writes plugin
        // prefs straight through CloudStreamApp.setKey. Re-assert the user's
        // choice, so closing a plugin's settings can never leave an extension
        // able to open a verification page on its own (see ExtensionVerifyGuard).
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

    /**
     * Fixes a plugin's settings bottom sheet so its whole list is reachable.
     *
     * These sheets (e.g. SK Tech's res/layout/settings.xml) are plain
     * BottomSheetDialogFragments and open in STATE_COLLAPSED. When the list is
     * long the sheet is as tall as the screen yet is positioned at the
     * collapsed offset, so its lower half — including the bottom of the inner
     * ScrollView's viewport — hangs below the bottom edge of the screen: the
     * list scrolls, but its last rows can never be brought into view, and once
     * the sheet is made non-draggable the user cannot expand it either.
     *
     * Expanding the sheet (skipCollapsed) aligns its bottom with the screen so
     * the whole viewport is visible, and leaving it non-draggable means drags
     * on the list scroll it instead of dismissing the sheet. Only
     * plugin-supplied sheets are touched — their classes are loaded by the
     * plugin's own PathClassLoader, never this activity's.
     */
    /**
     * Closes an extension's own popup the moment it appears.
     *
     * Hikari's rule is that a Cloudflare/Turnstile verification page opens only
     * when the user taps the app's own verify (globe) button. Extensions cannot
     * be made to follow that rule by their preferences alone (see
     * [com.hikari.app.net.ExtensionVerifyGuard.blocksPopup] for the disassembled
     * proof — Anichi ships `AnichiTurnstileDialog` and shows it by itself while
     * it resolves links), so this is the enforcement point: every fragment that
     * reaches this activity is offered to the guard, and one that looks like an
     * extension's verification or funding screen is dismissed before it can
     * draw.
     *
     * A fragment from another class loader is an extension's (our own screens
     * are compiled into this APK) — the same test [fixPluginSheetScrolling]
     * uses. A plugin's settings sheet never matches, so that fix is unaffected.
     */
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
        // Plugin settings sheets are dismissed how the plugin's own header says
        // — and SK Tech's has ONLY a Save button. Expanding the sheet to full
        // height (below) then leaves no outside area to tap, so Save was the
        // only visible way out. Mirror it with a Close button.
        if (dialog is com.google.android.material.bottomsheet.BottomSheetDialog) {
            addSettingsCloseButton(dialog, decor)
            expandSheet(dialog)
            // Re-apply once the sheet has actually been laid out: a
            // BottomSheetDialog settles into its collapsed state during the
            // first layout pass, which can undo a state change made before it.
            decor.post {
                addSettingsCloseButton(dialog, decor)
                expandSheet(dialog)
            }
        }
        val scrollView = findPlainScrollView(decor) ?: return
        if (dialog is com.google.android.material.bottomsheet.BottomSheetDialog) {
            boundScrollView(scrollView)
            // Safety net: once the expansion animation has settled, if the
            // list's viewport still reaches below the bottom of the screen,
            // shorten it so its last row can be scrolled into view.
            decor.postDelayed({ clampScrollViewToScreen(dialog, decor, scrollView) }, 600L)
            decor.postDelayed({ clampScrollViewToScreen(dialog, decor, scrollView) }, 1600L)
        }
    }

    /**
     * Adds a Close (✕) button beside the plugin settings header's own Save
     * button, so the screen can be dismissed without changing anything.
     *
     * Plugins normally ship only a Save control in that header (SK Tech's
     * res/layout/settings.xml has a title and one save ImageButton), and since
     * Hikari opens the sheet fully expanded and non-draggable there is no
     * outside area left to tap — Save was literally the only way out. The
     * button is tinted like the header's title so it matches whatever theme the
     * plugin's sheet uses. Best-effort: a header shaped differently just gets
     * no extra button (Back still dismisses the sheet).
     */
    private fun addSettingsCloseButton(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        decor: android.view.View,
    ) {
        runCatching {
            if (decor.findViewById<android.view.View>(settingsCloseButtonId) != null) return@runCatching
            val save = findSettingsHeaderAction(decor) ?: return@runCatching
            val header = save.parent as? android.widget.RelativeLayout ?: return@runCatching
            // Only titled header bars (title + action inside one row) get the
            // extra button — that shape is the settings header, and the title
            // also tells us which color the sheet expects its icons to be.
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
            // Safety net: with the sheet non-draggable, Back/outside-tap are the
            // only other exits. Allow both — this is a settings sheet, so
            // dismissing it never destroys anything.
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

    /**
     * Shortens [scrollView] if, with the sheet fully open, its bottom edge still
     * sits below the bottom of [decor]. Only ever shrinks the view, and only
     * once the sheet has settled in STATE_EXPANDED, so it cannot fight the
     * sheet's own layout.
     */
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

    /**
     * Plugin settings layouts often give their ScrollView
     * android:layout_height="match_parent" while its parent is a wrap_content
     * LinearLayout. Measuring it as wrap_content sizes it against the space
     * that is actually left, so the sheet's content cannot grow past the
     * available height.
     */
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
