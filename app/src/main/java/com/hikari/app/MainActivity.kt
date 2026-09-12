package com.hikari.app

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hikari.app.net.Updater
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.AppRoot
import com.hikari.app.ui.theme.HikariTheme
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
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
        // CloudStream plugin settings screens are often
        // BottomSheetDialogFragments whose layout wraps a plain
        // android.widget.ScrollView. Unlike NestedScrollView that is NOT a
        // NestedScrollingChild, so the sheet's BottomSheetBehavior finds no
        // scrolling child and dragging the list instead drags the whole sheet
        // down (which looks like "scrolling closes the settings page").
        // Make those sheets non-draggable so the inner list scrolls; tapping
        // outside or pressing Back still closes them.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment,
                ) {
                    fixPluginSheetScrolling(f)
                }
            },
            true,
        )
        // True fullscreen: hide the system status + navigation bars everywhere
        // (swipe from any edge to briefly reveal them). Content fills the whole
        // screen instead of stopping below a status bar.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val store = (application as HikariApp).store
        setContent {
            // Remember the Flow — a fresh store.themeFlow() per recomposition
            // would make collectAsState reset to the initial key each time.
            val themeFlow = remember { store.themeFlow() }
            val themeKey by themeFlow.collectAsState(initial = HikariThemeMode.DARK.key)
            val themeMode = HikariThemeMode.fromKey(themeKey)

            LaunchedEffect(themeMode) {
                // Dark status-bar icons on the light theme so they stay visible.
                androidx.core.view.WindowCompat.getInsetsController(
                    window, window.decorView
                ).isAppearanceLightStatusBars = themeMode == HikariThemeMode.LIGHT
            }

            var showUpdateDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // One quiet check on launch — the dialog only appears when a
                // newer build exists on GitHub.
                runCatching { Updater.checkForUpdate() }
                    .getOrNull()
                    ?.takeIf { it.available }
                    ?.let { showUpdateDialog = true }
            }

            HikariTheme(themeMode) {
                AppRoot(themeMode.key)
                if (showUpdateDialog) {
                    UpdateDialog(
                        context = this@MainActivity,
                        onDismiss = { showUpdateDialog = false },
                    )
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
        val path = com.hikari.app.cs3.Cs3PluginManager.pendingSettingsReload ?: return
        com.hikari.app.cs3.Cs3PluginManager.pendingSettingsReload = null
        val app = application as HikariApp
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
     * Stops a plugin's BottomSheetDialogFragment from being drag-dismissed when
     * its content is a plain scroll view, so the content scrolls instead. Only
     * plugin-supplied sheets are touched — their classes are loaded by the
     * plugin's own PathClassLoader, never this activity's.
     */
    private fun fixPluginSheetScrolling(fragment: androidx.fragment.app.Fragment) {
        val dialog = (fragment as? androidx.fragment.app.DialogFragment)?.dialog ?: return
        if (dialog !is com.google.android.material.bottomsheet.BottomSheetDialog) return
        if (fragment.javaClass.classLoader === javaClass.classLoader) return
        val decor = dialog.window?.decorView ?: return
        if (!containsPlainScrollView(decor)) return
        runCatching { dialog.behavior.isDraggable = false }
    }

    /** True when [view]'s subtree contains an android.widget.ScrollView (the
     *  non-nested-scrolling kind that confuses BottomSheetBehavior). */
    private fun containsPlainScrollView(view: android.view.View): Boolean {
        if (view is android.widget.ScrollView) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsPlainScrollView(view.getChildAt(i))) return true
            }
        }
        return false
    }
}
