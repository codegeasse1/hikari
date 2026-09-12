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
        // Worse, these sheets open in STATE_COLLAPSED, which lays the sheet out
        // partly BELOW the bottom of the screen, so the last rows of the list
        // can never be scrolled into view. Expand the sheet to its content and
        // make it non-draggable so the inner list scrolls end-to-end; tapping
        // outside or pressing Back still closes it, and a Close button is added
        // next to the plugin's own Save button (see addSettingsCloseButton).
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
                contentDescription = "Close settings"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER
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
