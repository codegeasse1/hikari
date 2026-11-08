package com.hikari.app.web

import com.hikari.app.i18n.I18n
import android.app.AlertDialog
import android.widget.Button
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.hikari.app.HikariApp
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.Http
import com.hikari.app.net.PromoGuard
import com.hikari.app.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class WebViewActivity : ComponentActivity() {

    /** In-app UI scale: keeps this View-based screen on the same interface size
     *  as the rest of the app (and off the phone's own font/display size when
     *  the in-app scale is enabled). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hikari.app.ui.UiScale.wrap(newBase))
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var videoChip: TextView
    private lateinit var rootView: LinearLayout
    private var contentFrame: FrameLayout? = null
    private var mouseMode = false
    private var cursorView: View? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private val detectedVideos = LinkedHashSet<String>()
    private var pageUrl: String? = null
    private var pageTitle: String = ""
    private var startUrl: String = ""

    private var providerId: String? = null

    @Volatile
    private var translateEnabled = false

    // "Verify for Cloudflare" mode (opened from the Home tab): the site is
    // shown so the user can complete a CF challenge; once a challenge was seen
    // and the page turns into real content, the activity finishes by itself so
    // the caller can reload the extension catalog with the now-valid cookies.
    private var autoCloseWhenCloudflarePassed = false
    private var challengeSeen = false
    private var verifyDone = false
    private var blockedCount = 0
    private var noChallengePolls = 0
    // Set when this view was opened to pass a Cloudflare challenge for a host
    // (intent extra "verifyHost"). CloudflareVerifier is NOT the launcher — it
    // never opens a visible view (see its class doc); the host is passed so the
    // verifier can wake its waiters the moment this view closes and retry
    // immediately.
    private var verifyHost: String? = null
    // Renderer-crash recoveries already spent by this view (carried across the
    // relaunch via the intent, see onRenderProcessGone).
    private var renderRestarts = 0
    @Volatile
    private var verifyAllowRedirects = false
    @Volatile
    private var addonConfig = false
    private val verifyHandler = Handler(Looper.getMainLooper())
    private var verifyRunnable: Runnable? = null

    private val blockedSelectors = java.util.LinkedHashSet<String>()

    // Ad blocking (WebView requests only) — resolved from the user's lists on
    // launch, and never applied to the ExoPlayer's own network I/O.
    @Volatile
    private var blockedDomains: Set<String> = emptySet()
    @Volatile
    private var whitelistDomains: Set<String> = emptySet()

    // WebView safety toggles (Settings → Privacy & Browsing → WebView safety). Default ON:
    //  - redirectProtection: the main frame can only navigate within the site
    //    it was opened for — ad-hijack redirects (ad.twinrdengine.com & co)
    //    are cancelled before they load.
    //  - popupProtection: window.open() popups are only relayed into the view
    //    when they belong to the same site; a popup the user's own tap opened
    //    to a foreign site asks (Allow once / Always / Cancel) instead of
    //    being dropped, and automatic popunders are still dropped unasked.
    @Volatile
    private var redirectProtection = true
    @Volatile
    private var popupProtection = true
    @Volatile private var blockedToastShown = false
    @Volatile
    private var allowedRedirectHosts: Set<String> = com.hikari.app.data.RedirectAllow.now()

    @Volatile
    private var lastBlockedRedirectUrl: String? = null
    @Volatile
    private var lastBlockedRedirectHost: String? = null

    /** The user's allowed redirect hosts, mirror included. */
    private fun allowedNow(): Set<String> =
        allowedRedirectHosts + com.hikari.app.data.RedirectAllow.now()

    private fun isRedirectAllowed(url: String?): Boolean =
        com.hikari.app.data.RedirectAllow.allowsIn(url, allowedNow())

    /** Records a refused main-frame navigation so the ⋯ menu can allow it (see
     *  [allowBlockedRedirect]). */
    private fun rememberBlockedRedirect(url: String) {
        lastBlockedRedirectUrl = url
        lastBlockedRedirectHost =
            runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
    }

    private fun blockedRedirectToast(url: Uri): String =
        "Blocked redirect to " + (url.host ?: "unknown") + " — open the menu to allow it"

    private var autoLaunched = false

    @Volatile
    private var videoPlaying = false


    // Fullscreen HTML5 video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // File uploads (sites with <input type=file>)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Periodic re-scan for SPA players that add <video> without a navigation
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    private var popupChild: WebView? = null

    private val popupAllowPrefs by lazy {
        getSharedPreferences("webview_popup_allow", MODE_PRIVATE)
    }

    /** Popup allow/block hosts for this view (Settings → Privacy & Browsing → WebView safety). */
    @Volatile
    private var popupAllowHosts: Set<String> = emptySet()
    @Volatile
    private var popupBlockHosts: Set<String> = emptySet()

    /** Last popup URL/host the page tried to open (for the ⋯ "block this ad" offer). */
    @Volatile
    private var lastPopupUrl: String? = null
    @Volatile
    private var lastPopupHost: String? = null

    private fun isPopupHostAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        if (popupAllowHosts.contains(h)) return true
        return popupAllowPrefs.getStringSet("hosts", emptySet()).orEmpty()
            .contains(h)
    }

    private fun isPopupHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return popupBlockHosts.contains(host.lowercase())
    }

    private fun rememberPopupHost(host: String) {
        val norm = host.lowercase()
        val allow = popupAllowPrefs.getStringSet("hosts", emptySet()).orEmpty()
            .toMutableSet()
        if (allow.add(norm)) {
            popupAllowPrefs.edit().putStringSet("hosts", allow).apply()
        }
        popupAllowHosts = popupAllowHosts + norm
        popupBlockHosts = popupBlockHosts - norm
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            runCatching {
                val cur = app.store.webviewPopupAllow().toMutableSet()
                if (cur.add(norm)) app.store.setWebviewPopupAllow(cur.toList())
                val blk = app.store.webviewPopupBlock().toMutableSet()
                if (blk.remove(norm)) app.store.setWebviewPopupBlock(blk.toList())
            }
        }
    }

    /** "Block always": popups from this host are dropped silently, never asked again. */
    private fun rememberPopupBlocked(host: String) {
        val norm = host.lowercase()
        popupBlockHosts = popupBlockHosts + norm
        popupAllowHosts = popupAllowHosts - norm
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            runCatching {
                val cur = app.store.webviewPopupBlock().toMutableSet()
                if (cur.add(norm)) app.store.setWebviewPopupBlock(cur.toList())
                val allow = app.store.webviewPopupAllow().toMutableSet()
                if (allow.remove(norm)) app.store.setWebviewPopupAllow(allow.toList())
            }
        }
        val prefsAllow = popupAllowPrefs.getStringSet("hosts", emptySet()).orEmpty().toMutableSet()
        if (prefsAllow.remove(norm)) popupAllowPrefs.edit().putStringSet("hosts", prefsAllow).apply()
    }

    private fun askPopupPermission(url: String, host: String?, onAllow: (String) -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val label = host ?: url
            val dlg = AlertDialog.Builder(this@WebViewActivity)
                .setTitle(I18n.t("Open this popup?"))
                .setMessage(
                    I18n.t("This page tried to open a popup:") + "\n" + label
                )
                .create()
            dlg.setView(choiceBox(
                I18n.t("Allow once") to { onAllow(url) },
                (I18n.t("Always allow") + (if (!host.isNullOrBlank()) " $host" else "")) to {
                    if (!host.isNullOrBlank()) {
                        rememberPopupHost(host)
                        Toast.makeText(
                            this@WebViewActivity,
                            "Popups from $host will always open — change it in Settings → Privacy & Browsing",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    onAllow(url)
                },
                (I18n.t("Block always") + (if (!host.isNullOrBlank()) " $host" else "")) to {
                    if (!host.isNullOrBlank()) {
                        rememberPopupBlocked(host)
                        Toast.makeText(
                            this@WebViewActivity,
                            "Popups from $host will always be blocked — change it in Settings → Privacy & Browsing",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                I18n.t("Cancel") to { },
            ) { dlg.dismiss() })
            dlg.show()
        }
    }

    private fun choiceBox(vararg options: Pair<String, () -> Unit>, onPick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(4), pad, pad)
        }
        for ((text, action) in options) {
            box.addView(Button(this).apply {
                this.text = text
                setOnClickListener { onPick(); action() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        return box
    }

    /** When the last choice dialog (popup or redirect) was shown for a URL, by
     *  URL: asking twice for one tap — once from shouldOverrideUrlLoading and
     *  once from the request backup — would stack two dialogs. */
    @Volatile
    private var lastChoiceAt = 0L
    @Volatile
    private var lastChoiceUrl: String? = null

    private fun choiceDue(url: String): Boolean {
        val now = System.currentTimeMillis()
        if (url == lastChoiceUrl && now - lastChoiceAt < 5_000L) return false
        lastChoiceUrl = url
        lastChoiceAt = now
        return true
    }

    private fun askRedirectPermission(url: String, host: String?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!choiceDue(url)) return@runOnUiThread
            rememberBlockedRedirect(url)
            val label = host ?: url
            val dlg = AlertDialog.Builder(this@WebViewActivity)
                .setTitle(I18n.t("Open this link?"))
                .setMessage(
                    I18n.t("This page tried to open a link:") + "\n" + label
                )
                .create()
            dlg.setView(choiceBox(
                I18n.t("Allow once") to {
                    runCatching { webView.loadUrl(url) }
                },
                (I18n.t("Always allow") + (if (!host.isNullOrBlank()) " $host" else "")) to {
                    allowRedirectTo(url, host)
                },
                (I18n.t("Block always") + (if (!host.isNullOrBlank()) " $host" else "")) to {
                    if (!host.isNullOrBlank()) blockHostForever(host)
                },
                I18n.t("Cancel") to { },
            ) { dlg.dismiss() })
            dlg.show()
        }
    }

    private fun allowRedirectTo(url: String, host: String?) {
        val entry = host?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            val current = runCatching { app.store.webviewRedirectAllow() }.getOrDefault(emptyList())
            val next = (current + entry).distinct()
            runCatching { app.store.setWebviewRedirectAllow(next) }
            runOnUiThread {
                allowedRedirectHosts = allowedRedirectHosts + entry
                Toast.makeText(
                    this@WebViewActivity,
                    "Allowed redirect to $entry",
                    Toast.LENGTH_SHORT,
                ).show()
                runCatching { webView.loadUrl(url) }
            }
        }
    }

    private fun blockHostForever(host: String) {
        val norm = AdBlocker.normalizeDomain(host.trim().trimEnd('.').lowercase())
        if (norm.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            runCatching {
                val cur = app.store.adBlock()
                if (cur.none { AdBlocker.normalizeDomain(it) == norm }) {
                    runCatching { app.store.setAdBlock((cur + norm).distinct()) }
                }
                val blk = app.store.webviewPopupBlock().toMutableSet()
                if (blk.add(norm)) app.store.setWebviewPopupBlock(blk.toList())
                val allow = app.store.webviewPopupAllow().toMutableSet()
                if (allow.remove(norm)) app.store.setWebviewPopupAllow(allow.toList())
            }
            val (blocked, white) = runCatching {
                AdBlocker.resolve(app, app.store.adLists(), app.store.adBlock(), app.store.adWhite())
            }.getOrDefault(blockedDomains to whitelistDomains)
            blockedDomains = blocked
            whitelistDomains = white
            popupBlockHosts = popupBlockHosts + norm
            popupAllowHosts = popupAllowHosts - norm
            runOnUiThread {
                Toast.makeText(
                    this@WebViewActivity,
                    "Blocked $host — ads and popups from it will not load. Revert in Settings → Privacy.",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching { webView.reload() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never allow a window title bar — the app draws no header anywhere.
        window.requestFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // True fullscreen: no status bar, no nav bar (swipe to reveal).
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val startUrl = intent.getStringExtra("url") ?: "https://www.google.com"
        this.startUrl = startUrl
        pageTitle = intent.getStringExtra("title").orEmpty().ifBlank { startUrl }
        autoCloseWhenCloudflarePassed = intent.getBooleanExtra("autoCloseWhenCloudflarePassed", false)
        verifyHost = intent.getStringExtra("verifyHost")
        verifyAllowRedirects = intent.getBooleanExtra("verifyAllowRedirects", false)
        addonConfig = intent.getBooleanExtra("stremioConfig", false)
        providerId = intent.getStringExtra("providerId")
        renderRestarts = intent.getIntExtra("renderRestarts", 0)
        val forceTranslate = intent.getBooleanExtra("translate", false)
        // Record every open (and why) so a "the site opened by itself" report
        // can be traced in Settings › Logs & diagnostics.
        com.hikari.app.data.Logs.log(
            "WebView",
            "open \"$startUrl\" (title=\"$pageTitle\", verifyHost=${verifyHost ?: "-"}, " +
                "autoClose=$autoCloseWhenCloudflarePassed, provider=${providerId ?: "-"}, " +
                "addonConfig=$addonConfig)"
        )

        if (PromoGuard.isDonationUrl(startUrl)) {
            com.hikari.app.data.Logs.log("WebView", "refused donation page $startUrl")
            Toast.makeText(this, "Blocked a donation page", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        videoChip = TextView(this).apply {
            text = I18n.t("\u25B6 Play video")
            visibility = View.GONE
            setBackgroundColor(0xFF3D5AFE.toInt())
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { playVideo() }
        }

        webView = try {
            WebView(this)
        } catch (t: Throwable) {
            com.hikari.app.data.Logs.log(
                "WebView",
                "this device has no usable WebView ($t) — cannot open $startUrl",
            )
            Toast.makeText(
                this,
                I18n.t("This device has no web browser (WebView), so this page can't be opened"),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        webView.addJavascriptInterface(HikariJsBridge(), "HikariBridge")

        val togglePill = TextView(this).apply {
            text = I18n.t("\u22EF")
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x661A1A1A.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isClickable = true
            setOnClickListener { showMenu(it) }
        }

        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                togglePill,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply { topMargin = dp(2) }
            )
            addView(
                videoChip,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(28) }
            )
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(rootView)
        contentFrame = rootView.getChildAt(1) as? FrameLayout
        mouseMode = com.hikari.app.tv.MouseMode.enabled
        if (mouseMode) showCursor(center = true)
        val pillLp = togglePill.layoutParams as FrameLayout.LayoutParams
        var pillDownX = 0f
        var pillDownY = 0f
        var pillStartRight = 0
        var pillStartTop = 0
        var pillDragging = false
        val pillTouchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        togglePill.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pillDownX = e.rawX
                    pillDownY = e.rawY
                    pillStartRight = pillLp.rightMargin
                    pillStartTop = pillLp.topMargin
                    pillDragging = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - pillDownX
                    val dy = e.rawY - pillDownY
                    if (!pillDragging &&
                        (kotlin.math.abs(dx) > pillTouchSlop || kotlin.math.abs(dy) > pillTouchSlop)
                    ) {
                        pillDragging = true
                    }
                    if (pillDragging) {
                        val maxRight = (content.width - v.width).coerceAtLeast(0)
                        val maxTop = (content.height - v.height).coerceAtLeast(0)
                        pillLp.rightMargin = (pillStartRight - dx.toInt()).coerceIn(0, maxRight)
                        pillLp.topMargin = (pillStartTop + dy.toInt()).coerceIn(0, maxTop)
                        v.layoutParams = pillLp
                    }
                    pillDragging
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (pillDragging) {
                        pillDragging = false
                        true
                    } else {
                        v.performClick()
                        false
                    }
                }
                else -> false
            }
        }

        // Load the user's ad-blocking config (hosts lists are cached on disk;
        // lists the user never downloaded get fetched lazily here). Blocking
        // applies to WebView requests only — the ExoPlayer is untouched.
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            // Safety toggles from Settings.
            redirectProtection = app.store.webviewRedirect()
            popupProtection = app.store.webviewPopup()
            allowedRedirectHosts = app.store.webviewRedirectAllow().toSet() +
                com.hikari.app.data.RedirectAllow.now()
            popupAllowHosts = runCatching { app.store.webviewPopupAllow() }.getOrDefault(emptyList())
                .map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotBlank() }.toSet()
            popupBlockHosts = runCatching { app.store.webviewPopupBlock() }.getOrDefault(emptyList())
                .map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotBlank() }.toSet()
            runCatching {
                val legacy = getSharedPreferences("webview_popup_allow", MODE_PRIVATE)
                    .getStringSet("hosts", emptySet()).orEmpty()
                    .map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotBlank() }.toSet()
                if (legacy.isNotEmpty() && popupAllowHosts.isEmpty() && popupBlockHosts.isEmpty()) {
                    popupAllowHosts = legacy
                    app.store.setWebviewPopupAllow(legacy.toList())
                } else if (legacy.isNotEmpty()) {
                    val merged = (popupAllowHosts + legacy) - popupBlockHosts
                    if (merged != popupAllowHosts) {
                        popupAllowHosts = merged
                        app.store.setWebviewPopupAllow(merged.toList())
                    }
                }
            }
            val enabled = app.store.adEnabled()
            if (enabled) {
                val lists = app.store.adLists()
                for (l in lists) {
                    // Lazy first download of any list the user added but never
                    // updated from Settings.
                    if (AdBlocker.loadCached(l.url, app).isEmpty()) {
                        runCatching { AdBlocker.download(l.url, app) }
                    }
                }
                val (blocked, white) = AdBlocker.resolve(app, lists, app.store.adBlock(), app.store.adWhite())
                blockedDomains = blocked
                whitelistDomains = white
            } else {
                blockedDomains = emptySet()
                whitelistDomains = emptySet()
            }
            // Userscripts (Tampermonkey-style, WebView only). Loaded here so
            // they're ready before the first page finishes; if the page already
            // started loading, inject the document-start scripts for it now.
            // pageUrl is read INSTEAD of webView.url: reading WebView state off
            // the main thread crashes (checkThread) — this coroutine runs on IO.
            UserscriptManager.reload(app)
            val startUrlNow = pageUrl
            if (startUrlNow != null) {
                val inject = UserscriptManager.scriptsFor(startUrlNow, atStart = true)
                if (inject.isNotEmpty()) {
                    runOnUiThread {
                        for (js in inject) runCatching { webView.evaluateJavascript(js, null) }
                    }
                }
            }
            // Persisted element-block selectors, applied by ELEMENT_BLOCK_JS on
            // every page load (see injectElementBlocker). The app-level cache
            // (HikariApp.elementBlocks) is already synced from disk at startup,
            // so blocks are ready BEFORE the first page loads — this is what
            // previously made them look "reset" after closing and reopening the
            // WebView (the async store read was racing the first onPageFinished).
            // The store re-read below is a safety net for a cold-start race; once
            // it lands we re-inject so an already-finished page still gets its
            // blocks applied.
            blockedSelectors.clear()
            blockedSelectors += app.elementBlocks
            runCatching { blockedSelectors += app.store.elementBlocks() }
            runOnUiThread { injectElementBlocker(webView) }
            translateEnabled = forceTranslate ||
                (providerId != null && providerId in app.store.translateProviders())
            if (translateEnabled) {
                runOnUiThread { runCatching { webView.evaluateJavascript(TRANSLATE_JS, null) } }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        setupWebView(startUrl)
    }

    private fun setupWebView(startUrl: String) {
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.setSupportMultipleWindows(true)
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.offscreenPreRaster = true
        ws.userAgentString = (application as HikariApp).effectiveWebViewUa()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setWebViewClient(object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                if (PromoGuard.isDonationUrl(request.url.toString())) {
                    showBlockedToast("Blocked a donation page")
                    return true
                }
                if (addonConfig) {
                    val install = addonInstallUrl(request.url.toString())
                    if (install != null) {
                        finishWithManifest(install)
                        return true
                    }
                }
                if (!verifyAllowRedirects && !addonConfig) {
                    if (autoCloseWhenCloudflarePassed && request.isForMainFrame &&
                        !isVerifyAllowed(request.url.toString())
                    ) {
                        showBlockedToast(blockedRedirectToast(request.url))
                        rememberBlockedRedirect(request.url.toString())
                        return true
                    }
                    if (redirectProtection && request.isForMainFrame) {
                        val host = request.url.host
                        val cur = currentPageHost()
                        if (host != null && cur != null && host != cur &&
                            !AdBlocker.matches(host, whitelistDomains) &&
                            !isRedirectAllowed(request.url.toString()) &&
                            !isSameSite(host, cur)
                        ) {
                            if (request.hasGesture()) {
                                askRedirectPermission(request.url.toString(), host)
                            } else {
                                showBlockedToast(blockedRedirectToast(request.url))
                                rememberBlockedRedirect(request.url.toString())
                            }
                            return true
                        }
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url.toString()
                val host = request.url.host ?: ""
                if (isCloudflareInfra(u)) return null
                if (request.isForMainFrame && PromoGuard.isDonationUrl(u)) {
                    showBlockedToast("Blocked a donation page")
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (host.isNotBlank()) {
                    // Whitelist wins first — a site the user unblocked keeps
                    // all its subdomains usable.
                    if (AdBlocker.matches(host, whitelistDomains)) {
                        return null
                    }
                    if (AdBlocker.matches(host, blockedDomains)) {
                        if (!isMediaRequest(request)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    if (!isMediaRequest(request) &&
                        AdBlocker.matchesPattern(request, currentPageHost())
                    ) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                if (VIDEO_URL_RE.containsMatchIn(u)) {
                    maybeAddVideo(u)
                }
                if (!verifyAllowRedirects) {
                    // Cloudflare-verification mode: never let a redirect land on a
                    // foreign page while the view is just passing a challenge.
                    if (autoCloseWhenCloudflarePassed && request.isForMainFrame &&
                        !isVerifyAllowed(u)
                    ) {
                        showBlockedToast(blockedRedirectToast(request.url))
                        rememberBlockedRedirect(u)
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    // Redirect protection: never let an ad hijack navigate the main
                    // frame away to a foreign domain (the twinrdengine.com-class
                    // redirects). Same-site pages, subdomains, whitelisted hosts and
                    // user-allowed redirect hosts still navigate normally; turning
                    // the toggle off disables this entirely.
                    if (redirectProtection && request.isForMainFrame) {
                        val host = request.url.host
                        val cur = currentPageHost()
                        if (host != null && cur != null && host != cur &&
                            !AdBlocker.matches(host, whitelistDomains) &&
                            !isRedirectAllowed(u) &&
                            !isSameSite(host, cur)
                        ) {
                            if (request.hasGesture()) {
                                askRedirectPermission(u, host)
                            } else {
                                showBlockedToast(blockedRedirectToast(request.url))
                                rememberBlockedRedirect(u)
                            }
                            return WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageUrl = url
                detectedVideos.clear()
                autoLaunched = false
                scanHandler.removeCallbacksAndMessages(null)
                progressBar.visibility = View.VISIBLE
                blockedToastShown = false
                // The "allow the redirect that was just blocked" offer belongs
                // to the page it was made on (see [lastBlockedRedirectUrl]).
                lastBlockedRedirectUrl = null
                lastBlockedRedirectHost = null
                // Userscripts declaring @run-at document-start run before the
                // page's own scripts.
                if (UserscriptManager.isLoaded() && url != null) {
                    for (js in UserscriptManager.scriptsFor(url, atStart = true)) {
                        runCatching { view?.evaluateJavascript(js, null) }
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageUrl = url
                progressBar.visibility = View.GONE
                // The popup relay child (if any) served its purpose — free it.
                popupChild?.let { runCatching { it.destroy() } }
                popupChild = null
                if (UserscriptManager.isLoaded() && url != null) {
                    for (js in UserscriptManager.scriptsFor(url, atStart = false)) {
                        runCatching { view?.evaluateJavascript(js, null) }
                    }
                }
                view?.evaluateJavascript(AD_CLEAN_JS, null)
                view?.evaluateJavascript(PROMO_CLEAN_JS, null)
                if (autoCloseWhenCloudflarePassed) {
                    videoChip.visibility = View.GONE
                    if (translateEnabled) view?.evaluateJavascript(TRANSLATE_JS, null)
                    injectElementBlocker(view)
                    pollCloudflarePass()
                    return
                }
                view?.evaluateJavascript(VIDEO_POLYFILL_JS, null)
                view?.evaluateJavascript(STUCK_MONITOR_JS, null)
                if (translateEnabled) view?.evaluateJavascript(TRANSLATE_JS, null)
                injectElementBlocker(view)
                scanRunnable?.let { scanHandler.removeCallbacks(it) }
                val r = object : Runnable {
                    override fun run() {
                        val v = webView
                        if (v == null) return
                        v.evaluateJavascript(STUCK_MONITOR_JS, null)
                        v.evaluateJavascript(VIDEO_SCAN_JS) { res ->
                            for (u in extractUrls(res)) maybeAddVideo(u)
                        }
                        v.evaluateJavascript(VIDEO_PLAYING_JS) { res ->
                            videoPlaying = res?.trim()?.trim('"') == "true"
                        }
                        scanHandler.postDelayed(this, 2500)
                    }
                }
                scanRunnable = r
                scanHandler.postDelayed(r, 1500)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                if (autoCloseWhenCloudflarePassed) {
                    verifyDone = true
                    runCatching { finish() }
                    return true
                }
                if (renderRestarts >= MAX_RENDER_RESTARTS) {
                    runCatching { finish() }
                    return true
                }
                renderRestarts++
                runCatching {
                    finish()
                    startActivity(Intent(intent).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("renderRestarts", renderRestarts)
                    })
                }
                return true
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (autoCloseWhenCloudflarePassed) return false
                if (popupProtection && !isUserGesture && !addonConfig) {
                    showBlockedToast("Blocked popup")
                    return false
                }
                if (videoPlaying) {
                    showBlockedToast("Blocked popup during playback")
                    return false
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val child = WebView(this@WebViewActivity).apply {
                    setBackgroundColor(0xFF000000.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = (application as HikariApp).effectiveWebViewUa()
                    webViewClient = object : WebViewClient() {
                        private var relayed = false

                        private fun relay(url: String?) {
                            if (relayed || url.isNullOrBlank()) return
                            relayed = true
                            runCatching {
                                lastPopupUrl = url
                                lastPopupHost = runCatching { java.net.URI(url).host }
                                    .getOrNull()?.trim()?.trimEnd('.')?.lowercase()
                            }
                            if (addonConfig) {
                                addonInstallUrl(url)?.let { finishWithManifest(it) }
                                return
                            }
                            // A popup to a donation page is an ad-style popup
                            // with a cause: never relayed into the main view.
                            if (PromoGuard.isDonationUrl(url)) {
                                showBlockedToast("Blocked a donation page")
                                return
                            }
                            if (popupProtection && !addonConfig) {
                                val ph = runCatching { java.net.URI(url).host }
                                    .getOrNull()?.trim()?.trimEnd('.').orEmpty()
                                if (ph.isNotEmpty() &&
                                    !AdBlocker.matches(ph, whitelistDomains) &&
                                    AdBlocker.matchesPatternUrl(url, currentPageHost())
                                ) {
                                    showBlockedToast("Blocked ad popup")
                                    return
                                }
                            }
                            if (popupProtection && !addonConfig) {
                                val host = runCatching { java.net.URI(url).host }.getOrNull()
                                val cur = currentPageHost()
                                if (!host.isNullOrBlank() && isPopupHostBlocked(host)) {
                                    showBlockedToast("Blocked popup from $host")
                                    return
                                }
                                if (host == null || cur == null ||
                                    (host != cur && !AdBlocker.matches(host, whitelistDomains) && !isSameSite(host, cur))
                                ) {
                                    if (isPopupHostAllowed(host)) {
                                        runOnUiThread { webView.loadUrl(url) }
                                    } else {
                                        askPopupPermission(url, host) { allowed ->
                                            runOnUiThread { webView.loadUrl(allowed) }
                                        }
                                    }
                                    return
                                }
                            }
                            runOnUiThread { webView.loadUrl(url) }
                        }

                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            relay(request?.url?.toString())
                            return true
                        }

                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            relay(url)
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            // The throwaway popup renderer died — reclaim it and
                            // keep the browsing activity (and the app) alive.
                            runCatching { if (popupChild === view) popupChild = null }
                            runCatching { view?.destroy() }
                            return true
                        }
                    }
                }
                popupChild = child
                transport.webView = child
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                return if (intent != null) {
                    try {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        false
                    }
                } else {
                    fileChooserCallback = null
                    false
                }
            }

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                view?.let { v ->
                    rootView.addView(
                        v,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
                hideSystemBars()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.loadUrl(startUrl)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val cb = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode == RESULT_OK && data != null) {
            val results = if (data.clipData != null) {
                (0 until data.clipData!!.itemCount).mapNotNull { data.clipData!!.getItemAt(it).uri }
            } else {
                data.data?.let { listOf(it) }.orEmpty()
            }
            cb.onReceiveValue(results.toTypedArray())
        } else {
            cb.onReceiveValue(null)
        }
    }

    private fun hideCustomView() {
        customView?.let { v -> rootView.removeView(v) }
        customView = null
        customViewCallback?.let { runCatching { it.onCustomViewHidden() } }
        customViewCallback = null
    }

    // The ⋯ pill opens this tiny menu — the app draws NO header bar, so the
    // site's own header/search stay fully visible.
    private fun showMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor, Gravity.END)
        menu.menu.add(0, 1, 0, "\u2190 Back")
        menu.menu.add(0, 2, 0, "\u2192 Forward")
        menu.menu.add(0, 3, 0, "\u21BB Reload")
        menu.menu.add(0, 4, 0, "\u2302 Go to app home")
        menu.menu.add(0, 5, 0, "\u25B6 Open in player")
        menu.menu.add(0, 6, 0, "Open in browser")
        val blockedHost = lastBlockedRedirectHost
        if (blockedHost != null && blockedHost.isNotBlank()) {
            menu.menu.add(0, 11, 6, "✔ Allow redirect to $blockedHost")
        }
        val adHost = (lastPopupHost?.takeIf { it.isNotBlank() }
            ?: lastBlockedRedirectHost?.takeIf { it.isNotBlank() })
            ?.trim()?.trimEnd('.')?.lowercase()
        val curHost = currentPageHost()?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (!adHost.isNullOrBlank() && adHost != curHost) {
            menu.menu.add(0, 14, 6, "⛔ Block this ad site ($adHost)")
        }
        menu.menu.add(0, 7, 7, "\u2298 Element blocker")
        menu.menu.add(0, 8, 8, "\u21A9 Undo last block")
        menu.menu.add(0, 9, 9, "\u2715 Clear all blocks")
        if (translateEnabled) menu.menu.add(0, 10, 10, "\u2716 Translation off")
        menu.menu.add(0, 12, 12, if (mouseMode) "\u2716 Mouse cursor off" else "\u2316 Mouse cursor on")
        val menuHost = currentPageHost()?.trim()?.trimEnd('.').orEmpty()
        if (menuHost.isNotBlank()) {
            val siteOpen = AdBlocker.matches(menuHost, whitelistDomains)
            menu.menu.add(
                0, 13, 11,
                if (siteOpen) "\u2714 Ads allowed on $menuHost — tap to block"
                else "\u2298 Allow ads on $menuHost",
            )
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (webView.canGoBack()) webView.goBack()
                2 -> if (webView.canGoForward()) webView.goForward()
                3 -> webView.reload()
                4 -> {
                    // Leave the site view and jump the app back to its Home tab
                    // (NOT the website's home page — AppRoot watches this).
                    HikariApp.instance.homeTabRequest.value += 1
                    finish()
                }
                5 -> playVideo()
                6 -> runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl ?: webView.url)))
                }
                7 -> enableElementBlocker()
                8 -> undoLastBlock()
                9 -> clearAllBlocks()
                10 -> {
                    // Per-extension auto-translate off (menu is only offered
                    // while a translation is active on this page).
                    translateEnabled = false
                    val pid = providerId
                    if (pid != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { com.hikari.app.data.Translator.enable(pid, false) }
                        }
                    }
                    Toast.makeText(this, I18n.t("Translation off"), Toast.LENGTH_SHORT).show()
                }
                11 -> allowBlockedRedirect()
                12 -> toggleMouse()
                13 -> toggleSiteAdblock()
                14 -> blockAdSite()
            }
            true
        }
        menu.show()
    }

    private fun blockAdSite() {
        val host = (lastPopupHost?.takeIf { it.isNotBlank() }
            ?: lastBlockedRedirectHost?.takeIf { it.isNotBlank() })
            ?.trim()?.trimEnd('.')?.lowercase()
        if (host.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            val norm = AdBlocker.normalizeDomain(host)
            runCatching {
                val cur = app.store.adBlock()
                if (cur.none { AdBlocker.normalizeDomain(it) == norm }) {
                    runCatching { app.store.setAdBlock((cur + norm).distinct()) }
                }
                val blk = app.store.webviewPopupBlock().toMutableSet()
                if (blk.add(norm)) app.store.setWebviewPopupBlock(blk.toList())
                val allow = app.store.webviewPopupAllow().toMutableSet()
                if (allow.remove(norm)) app.store.setWebviewPopupAllow(allow.toList())
            }
            val (blocked, white) = runCatching {
                AdBlocker.resolve(app, app.store.adLists(), app.store.adBlock(), app.store.adWhite())
            }.getOrDefault(blockedDomains to whitelistDomains)
            blockedDomains = blocked
            whitelistDomains = white
            popupBlockHosts = popupBlockHosts + norm
            popupAllowHosts = popupAllowHosts - norm
            runOnUiThread {
                Toast.makeText(
                    this@WebViewActivity,
                    "Blocked $host — ads and popups from it will not load. Revert in Settings → Privacy.",
                    Toast.LENGTH_LONG,
                ).show()
                webView.reload()
            }
        }
    }

    private fun toggleSiteAdblock() {
        val host = currentPageHost()?.trim()?.trimEnd('.')?.lowercase()
        if (host.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            val norm = AdBlocker.normalizeDomain(host)
            val cur = app.store.adWhite()
            val has = cur.any { AdBlocker.normalizeDomain(it) == norm }
            val next = if (has) cur.filterNot { AdBlocker.normalizeDomain(it) == norm }
            else cur + norm
            runCatching { app.store.setAdWhite(next) }
            whitelistDomains =
                next.map { AdBlocker.normalizeDomain(it) }.filter { it.isNotBlank() }.toSet()
            runOnUiThread {
                Toast.makeText(
                    this@WebViewActivity,
                    if (has) "Blocking ads on $host" else "Ads allowed on $host",
                    Toast.LENGTH_SHORT,
                ).show()
                webView.reload()
            }
        }
    }

    private fun toggleMouse() {
        mouseMode = !mouseMode
        com.hikari.app.tv.MouseMode.enabled = mouseMode
        if (mouseMode) {
            showCursor(center = true)
            Toast.makeText(this, I18n.t("Mouse on — arrows move the ring, OK taps the page"), Toast.LENGTH_SHORT).show()
        } else {
            hideCursor()
        }
    }

    private fun cursorStep(): Int = dp(44)

    private fun showCursor(center: Boolean) {
        val frame = contentFrame ?: return
        hideCursor()
        val ring = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(dp(3), 0xFFFF5252.toInt())
            setColor(android.graphics.Color.TRANSPARENT)
        }
        val size = dp(52)
        val v = View(this).apply { background = ring }
        if (center) {
            cursorX = (frame.width / 2f).takeIf { it > 0 } ?: dp(400).toFloat()
            cursorY = (frame.height / 3f).takeIf { it > 0 } ?: dp(400).toFloat()
        }
        cursorX = cursorX.coerceIn(0f, (frame.width - size).coerceAtLeast(0).toFloat())
        cursorY = cursorY.coerceIn(0f, (frame.height - size).coerceAtLeast(0).toFloat())
        frame.addView(v, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = cursorX.toInt()
            topMargin = cursorY.toInt()
        })
        cursorView = v
    }

    private fun hideCursor() {
        cursorView?.let { runCatching { contentFrame?.removeView(it) } }
        cursorView = null
    }

    private fun moveCursor(dx: Int, dy: Int) {
        val frame = contentFrame ?: return
        val v = cursorView ?: run { showCursor(center = true); return }
        val size = dp(52)
        cursorX = (cursorX + dx * cursorStep()).coerceIn(0f, (frame.width - size).coerceAtLeast(0).toFloat())
        cursorY = (cursorY + dy * cursorStep()).coerceIn(0f, (frame.height - size).coerceAtLeast(0).toFloat())
        (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.leftMargin = cursorX.toInt()
            lp.topMargin = cursorY.toInt()
            v.layoutParams = lp
        }
    }

    private fun tapCursor() {
        val v = cursorView ?: return
        val size = dp(52) / 2f
        val x = cursorX + size
        val y = cursorY + size
        val down = android.os.SystemClock.uptimeMillis()
        val downEv = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val upEv = android.view.MotionEvent.obtain(down, down + 90, android.view.MotionEvent.ACTION_UP, x, y, 0)
        runCatching { webView.dispatchTouchEvent(downEv) }
        runCatching { webView.dispatchTouchEvent(upEv) }
        downEv.recycle()
        upEv.recycle()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (mouseMode && event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0, -1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0, 1); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-1, 0); return true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(1, 0); return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { tapCursor(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun allowBlockedRedirect() {
        val url = lastBlockedRedirectUrl ?: return
        // The list names HOSTS or words; a link with no host (a data:/blob:
        // navigation, a bare path) has nothing that can be expressed on it.
        val entry = lastBlockedRedirectHost?.takeIf { it.isNotBlank() } ?: return
        lastBlockedRedirectUrl = null
        lastBlockedRedirectHost = null
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as HikariApp
            val current = runCatching { app.store.webviewRedirectAllow() }.getOrDefault(emptyList())
            val next = (current + entry).distinct()
            runCatching { app.store.setWebviewRedirectAllow(next) }
            runOnUiThread {
                allowedRedirectHosts = allowedRedirectHosts + entry
                Toast.makeText(
                    this@WebViewActivity,
                    "Allowed redirect to $entry",
                    Toast.LENGTH_SHORT,
                ).show()
                runCatching { webView.loadUrl(url) }
            }
        }
    }

    // ---- Element blocker ----

    private fun enableElementBlocker() {
        // Toggle: ON selects (red highlight), a 2nd tap on the same element
        // blocks it; OFF clears the highlight. Reads the state back so the
        // toast always matches reality.
        webView.evaluateJavascript(
            "(window.__hikariBlockerToggle?window.__hikariBlockerToggle():null);" +
                "(window.__hikariBlockerActive?'true':'false')"
        ) { res ->
            val active = res?.trim()?.trim('"') == "true"
            Toast.makeText(
                this@WebViewActivity,
                if (active) I18n.t("Blocker ON — tap an element to select it, tap it again to block")
                else I18n.t("Element blocker OFF"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun undoLastBlock() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sel = runCatching { (applicationContext as HikariApp).store.removeLastElementBlock() }
                .getOrNull()
            runOnUiThread {
                if (sel == null) {
                    Toast.makeText(this@WebViewActivity, I18n.t("Nothing to undo"), Toast.LENGTH_SHORT).show()
                } else {
                    blockedSelectors.remove(sel)
                    (applicationContext as HikariApp).elementBlocks = blockedSelectors.toList()
                    webView.evaluateJavascript(
                        "window.__hikariRestoreSelector?window.__hikariRestoreSelector(" +
                            jsString(sel) + "):null",
                        null
                    )
                    Toast.makeText(this@WebViewActivity, I18n.t("Last block reverted"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearAllBlocks() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { (applicationContext as HikariApp).store.clearElementBlocks() }
            runOnUiThread {
                blockedSelectors.clear()
                (applicationContext as HikariApp).elementBlocks = emptyList()
                webView.evaluateJavascript(
                    "window.__hikariRestoreAll?window.__hikariRestoreAll():null",
                    null
                )
                Toast.makeText(this@WebViewActivity, I18n.t("All element blocks cleared"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** A JS string literal (for embedding a selector into evaluateJavascript). */
    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fetchTranslation(text: String): String {
        if (text.isBlank()) return text
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=auto&tl=en&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8")
        return try {
            val body = Http.get(url).use { it.body?.string() } ?: return text
            JSONArray(body).optJSONArray(0)?.optJSONArray(0)?.optString(0) ?: text
        } catch (e: Exception) {
            text
        }
    }

    /** Injects the blocker script + the persisted selectors into the page. */
    private fun injectElementBlocker(view: WebView?) {
        if (autoCloseWhenCloudflarePassed) return
        view?.evaluateJavascript(ELEMENT_BLOCK_JS, null)
        val arr = JSONArray().apply { blockedSelectors.forEach { put(it) } }
        view?.evaluateJavascript(
            "window.__hikariBlocks=" + arr.toString() +
                ";window.__hikariHideAll&&window.__hikariHideAll();",
            null
        )
    }

    /** Host of the page currently shown (or being loaded). */
    private fun currentPageHost(): String? = runCatching {
        java.net.URI(pageUrl ?: webView.url).host
    }.getOrNull()

    /** Same host or one being a subdomain of the other (registrable-domain-ish). */
    private fun isSameSite(host: String, current: String): Boolean =
        host == current || host.endsWith("." + current) || current.endsWith("." + host)

    private fun isCloudflareInfra(url: String?): Boolean {
        if (url == null) return false
        if (url.contains("/cdn-cgi/")) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com") ||
            host == "cloudflare.com" || host.endsWith(".cloudflare.com") ||
            host == "cloudflareinsights.com" || host.endsWith(".cloudflareinsights.com")
    }

    /** In Cloudflare-verification mode ONLY the challenge may be shown: the
     *  site we started on plus Cloudflare's own challenge infra. Anything else
     *  (ad-hijack redirects, parked/redirect pages, trackers) is blocked —
     *  the auto-opened view must never land on a random site. */
    private fun isVerifyAllowed(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (isRedirectAllowed(url)) return true
        val orig = runCatching { java.net.URI(startUrl).host?.lowercase() }.getOrNull() ?: return true
        if (host == orig) return true
        return host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com") ||
            host == "cloudflare.com" || host.endsWith(".cloudflare.com") ||
            host == "cloudflareinsights.com" || host.endsWith(".cloudflareinsights.com")
    }

    /** One toast per page so ad spam doesn't toast-spam the user. Thread-safe:
     *  shouldInterceptRequest runs on a WebView background thread (no Looper),
     *  so the Toast itself must be posted to the main looper. */
    private fun showBlockedToast(msg: String) {
        if (blockedToastShown) return
        blockedToastShown = true
        verifyHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun addonInstallUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val lower = url.lowercase()
        if (lower.startsWith("stremio://")) {
            val rest = url.substring("stremio://".length)
            val decoded = runCatching { java.net.URLDecoder.decode(rest, "UTF-8") }
                .getOrDefault(rest)
            val absolute =
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded
                else "https://" + decoded
            return manifestUrlOf(absolute)
        }
        if ((lower.startsWith("http://") || lower.startsWith("https://")) &&
            lower.substringBefore('?').endsWith("/manifest.json")
        ) {
            return url
        }
        return null
    }

    /** A manifest URL for [url], adding `/manifest.json` when it names a base. */
    private fun manifestUrlOf(url: String): String =
        if (url.substringBefore('?').endsWith("/manifest.json")) url
        else url.substringBefore('?').trimEnd('/') + "/manifest.json"

    /** The addon's config page handed back an install URL: give it to the caller
     *  (the Extensions screen installs/replaces the addon) and close. */
    private fun finishWithManifest(url: String) {
        com.hikari.app.data.Logs.log("WebView", "addon config page returned $url")
        setResult(RESULT_OK, Intent().putExtra("manifestUrl", url))
        finish()
    }

    private fun pollCloudflarePass() {
        verifyHandler.removeCallbacksAndMessages(null)
        val r = object : Runnable {
            override fun run() {
                if (!autoCloseWhenCloudflarePassed || verifyDone) return
                val v = webView
                if (v == null) return
                v.evaluateJavascript(
                    "(function(){" +
                        "var t=(document.title||'').toLowerCase();" +
                        "var h=location.href.toLowerCase();" +
                        "var b=document.body?document.body.innerText.slice(0,3000).toLowerCase():'';" +
                        "var chal=(t.indexOf('just a moment')>=0||t.indexOf('attention required')>=0||" +
                        "h.indexOf('cdn-cgi/challenge')>=0||h.indexOf('challenge-platform')>=0||" +
                        "b.indexOf('verify you are human')>=0||b.indexOf('performing security verification')>=0||" +
                        "b.indexOf('checking your browser')>=0||b.indexOf('cf-chl')>=0||" +
                        "b.indexOf('turnstile')>=0);" +
                        "var block=(t.indexOf('you have been blocked')>=0||t.indexOf('access denied')>=0||" +
                        "h.indexOf('cf-error')>=0||b.indexOf('you have been blocked')>=0||" +
                        "b.indexOf('access denied')>=0||b.indexOf('request blocked')>=0||" +
                        "b.indexOf('cf-error-details')>=0);" +
                        "return chal?1:(block?2:0);" +
                        "})();"
                ) { res ->
                    when (res?.trim()?.trim('"')) {
                        "1" -> {
                            challengeSeen = true
                            blockedCount = 0
                            noChallengePolls = 0
                        }
                        "2" -> {
                            blockedCount++
                            noChallengePolls = 0
                            if (blockedCount >= 3) {
                                verifyDone = true
                                finish()
                            }
                        }
                        else -> if (challengeSeen) {
                            // Challenge present → gone = verification complete.
                            verifyDone = true
                            finish()
                        } else {
                            // No challenge ever appeared and no hard block — the
                            // page loaded as ordinary content (e.g. the SVG
                            // namespace page a URL scanner mistook for a stream).
                            // The verify view has nothing to do; close it instead
                            // of lingering on screen forever.
                            noChallengePolls++
                            if (noChallengePolls >= VERIFY_NO_CHALLENGE_POLLS) {
                                verifyDone = true
                                finish()
                            }
                        }
                    }
                    if (!verifyDone && autoCloseWhenCloudflarePassed) {
                        verifyHandler.postDelayed(this, 1200)
                    }
                }
            }
        }
        verifyRunnable = r
        verifyHandler.postDelayed(r, 1200)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Immersive fullscreen is not sticky — re-hide the bars whenever this
     *  activity is resumed or regains focus (see PlayerActivity.hideSystemUi). */
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Pair with onPause's media stop: a WebView left paused never runs its
        // scripts again, which would freeze the page for the rest of the session.
        runCatching { webView.onResume() }
        runCatching { popupChild?.onResume() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Adds [url] to the detected set ONLY after confirming it serves real video. */
    private fun maybeAddVideo(url: String) {
        // Verification view never offers video.
        if (autoCloseWhenCloudflarePassed) return
        if (detectedVideos.contains(url)) return
        Thread {
            val isVideo = isRealVideo(url)
            if (isVideo) {
                runOnUiThread {
                    if (detectedVideos.add(url)) {
                        videoChip.text = "\u25B6 ${detectedVideos.size} video" +
                            if (detectedVideos.size > 1) "s" else ""
                        videoChip.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun isMediaRequest(request: WebResourceRequest): Boolean {
        val h = request.requestHeaders
        if (h?.containsKey("Range") == true) return true
        val accept = h?.get("Accept")?.lowercase().orEmpty()
        if (accept.contains("video/") || accept.contains("application/octet-stream")) return true
        val u = request.url.toString().lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mpd") ||
            u.contains(".ts?") || u.contains("videoplayback") || u.contains("/videos/") ||
            u.contains("/media/") || u.contains("phncdn") || u.contains("streamable")
    }

    /** Fetches a few bytes and decides whether [url] is actual video content. */
    private fun isRealVideo(url: String): Boolean {
        // Share the WebView's cookie jar with the probe request — Cloudflare /
        // auth-token CDNs answer with a 403 (or a challenge page) without it.
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val headers = buildMap {
            put("Range", "bytes=0-2047")
            put("Referer", pageUrl ?: url)
            put("User-Agent", (application as HikariApp).effectiveWebViewUa())
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val r = runCatching { Http.get(url, headers) }.getOrNull() ?: return false
        r.use { resp ->
            if (!resp.isSuccessful) return false
            val ct = resp.header("Content-Type")?.lowercase().orEmpty()
            val body = resp.body?.bytes() ?: return false
            val head = String(body, 0, minOf(body.size, 2048), Charsets.ISO_8859_1)
            return when {
                head.startsWith("#EXTM3U") -> true // HLS manifest
                ct.contains("application/dash+xml") || head.contains("<mpd") -> true // DASH
                ct.contains("video/") -> true // mp4/webm/etc
                ct.contains("application/octet-stream") && looksLikeMp4(body) -> true
                else -> false
            }
        }
    }

    private fun looksLikeMp4(b: ByteArray): Boolean {
        if (b.size < 12) return false
        // MP4 boxes start with a size then "ftyp" / "moov" / "mdat" / "styp"
        return b.size >= 12 &&
            ((b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) ||
                (b[4] == 'm'.code.toByte() && b[5] == 'o'.code.toByte() && b[6] == 'o'.code.toByte() && b[7] == 'v'.code.toByte()) ||
                (b[4] == 'm'.code.toByte() && b[5] == 'd'.code.toByte() && b[6] == 'a'.code.toByte() && b[7] == 't'.code.toByte()))
    }

    private fun playVideo() {
        val ref = pageUrl ?: webView.url
        if (detectedVideos.isEmpty()) {
            webView.evaluateJavascript(VIDEO_SCAN_JS) { res ->
                val urls = extractUrls(res)
                if (urls.isEmpty()) {
                    Toast.makeText(this, I18n.t("No video found on this page"), Toast.LENGTH_SHORT).show()
                } else {
                    launchPlayer(urls, ref)
                }
            }
        } else {
            launchPlayer(detectedVideos.toList(), ref)
        }
    }

    private fun extractUrls(jsonArrayLiteral: String): List<String> {
        val t = jsonArrayLiteral.trim()
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = JSONArray(t.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.getOrDefault(emptyList())
    }

    private fun launchPlayer(urls: List<String>, referer: String?) {
        val unique = urls.distinct().filter { it.startsWith("http") }
        if (unique.isEmpty()) {
            Toast.makeText(this, I18n.t("No playable video found"), Toast.LENGTH_SHORT).show()
            return
        }
        val cookie = runCatching {
            unique.firstNotNullOfOrNull { CookieManager.getInstance().getCookie(it) }
        }.getOrNull()
        val sources = JSONArray()
        unique.forEach { u ->
            val headers = JSONObject()
            if (!referer.isNullOrBlank()) headers.put("Referer", referer)
            headers.put("User-Agent", Http.UA)
            if (!cookie.isNullOrBlank()) headers.put("Cookie", cookie)
            sources.put(
                JSONObject()
                    .put("name", "Web · ${urlHost(u)}")
                    .put("url", u)
                    .put("headers", headers)
                    .put("isM3u8", u.contains(".m3u8", true))
                    .put("isMpd", u.contains(".mpd", true))
                    .put("subtitles", JSONArray())
            )
        }
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra("title", pageTitle)
                putExtra("sources", sources.toString())
            }
        )
    }

    private fun urlHost(u: String): String = runCatching {
        java.net.URI(u).host ?: u.take(48)
    }.getOrDefault(u.take(48))

    private inner class HikariJsBridge {
        private inline fun <T> safe(tag: String, block: () -> T): T? =
            runCatching { block() }.onFailure {
                android.util.Log.w("HikariBridge", "$tag failed (swallowed)", it)
            }.getOrNull()

        @android.webkit.JavascriptInterface
        fun stuckVideo(url: String) {
            safe("stuckVideo") {
                // Verification view exists only to pass the challenge — never
                // hand a video off to the external player from it.
                if (autoCloseWhenCloudflarePassed) return@safe
                if (autoLaunched || url.isBlank()) return@safe
                autoLaunched = true
                runOnUiThread {
                    safe("stuckVideo.ui") {
                        detectedVideos.add(url)
                        videoChip.text = "\u25B6 Opening external player…"
                        videoChip.visibility = View.VISIBLE
                        launchPlayer(listOf(url), pageUrl ?: webView.url)
                    }
                }
            }
        }

        // ---- Element blocker bridge ----
        @android.webkit.JavascriptInterface
        fun blockElement(selector: String) {
            safe("blockElement") {
                if (selector.isBlank()) return@safe
                runOnUiThread {
                    safe("blockElement.ui") {
                        blockedSelectors.add(selector)
                        (applicationContext as HikariApp).elementBlocks = blockedSelectors.toList()
                        Toast.makeText(this@WebViewActivity, I18n.t("Element blocked"), Toast.LENGTH_SHORT)
                            .show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                (applicationContext as HikariApp).store.addElementBlock(selector)
                            }
                        }
                    }
                }
            }
        }

        // ---- Userscript GM_* value storage (WebView only) ----
        @android.webkit.JavascriptInterface
        fun userscriptGet(scriptId: String, key: String): String? =
            safe("userscriptGet") {
                UserscriptManager.getValue(applicationContext, scriptId, key)
            }

        @android.webkit.JavascriptInterface
        fun userscriptSet(scriptId: String, key: String, valueJson: String) {
            safe("userscriptSet") {
                UserscriptManager.setValue(applicationContext, scriptId, key, valueJson)
            }
        }

        @android.webkit.JavascriptInterface
        fun userscriptDelete(scriptId: String, key: String) {
            safe("userscriptDelete") {
                UserscriptManager.deleteValue(applicationContext, scriptId, key)
            }
        }

        @android.webkit.JavascriptInterface
        fun userscriptList(scriptId: String): String =
            safe("userscriptList") { UserscriptManager.listValues(applicationContext, scriptId) }
                ?: "{}"

        // ---- Auto-translate bridge ----
        // The page's TRANSLATE_JS calls this with a batch of unique text
        // strings; we fetch English translations (translate.googleapis.com —
        // no key needed) and hand them back via window.__hikariTransResult.
        @android.webkit.JavascriptInterface
        fun translate(id: String, textsJson: String) {
            safe("translate") {
                val arr = runCatching { JSONArray(textsJson) }.getOrNull() ?: return@safe
                val texts = (0 until arr.length()).map { arr.optString(it) }
                if (texts.isEmpty()) return@safe
                lifecycleScope.launch {
                    safe("translate.worker") {
                        val sem = java.util.concurrent.Semaphore(6)
                        val results = arrayOfNulls<String>(texts.size)
                        coroutineScope {
                            for (i in texts.indices) {
                                launch(Dispatchers.IO) {
                                    sem.acquire()
                                    try {
                                        results[i] = fetchTranslation(texts[i])
                                    } catch (e: Exception) {
                                        results[i] = ""
                                    } finally {
                                        sem.release()
                                    }
                                }
                            }
                        }
                        val out = JSONArray()
                        results.forEach { out.put(it ?: "") }
                        val js = "window.__hikariTransResult?window.__hikariTransResult(" +
                            jsString(id) + "," + out.toString() + "):null"
                        runOnUiThread { runCatching { webView.evaluateJavascript(js, null) } }
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        // Persist cookies/DOM-storage to disk the moment the WebView leaves the
        // foreground so logins and sessions survive even a force-kill.
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.onPause() }
        runCatching { popupChild?.onPause() }
        runCatching {
            webView.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio')" +
                    ".forEach(function(m){try{m.pause()}catch(e){}})}catch(e){}})()",
                null,
            )
        }
    }

    override fun onDestroy() {
        verifyHandler.removeCallbacksAndMessages(null)
        scanHandler.removeCallbacksAndMessages(null)
        runCatching { popupChild?.destroy() }
        popupChild = null
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.destroy() }
        // The verify WebView closed (challenge passed or dismissed) — wake any
        // CloudflareVerifier waiters so their retry runs immediately.
        com.hikari.app.net.CloudflareVerifier.onVerifyViewClosed(verifyHost)
        super.onDestroy()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4001

        /** Verify polls (1.2s apart) with no challenge AND no hard block — the
         *  page loaded as normal content (e.g. the SVG-namespace page a URL
         *  scanner mistook for a stream), so the verify view is useless and
         *  should close itself instead of lingering (~14s). */
        private const val VERIFY_NO_CHALLENGE_POLLS = 12

        private const val MAX_RENDER_RESTARTS = 1

        /** HLS/DASH/MP4 URLs ending the request path (optional query). */
        private val VIDEO_URL_RE =
            Regex("""\.(m3u8|mpd|mp4)(\?[^\s"']*)?$""", RegexOption.IGNORE_CASE)

        /** Hides ad containers. HIDING (not removing) keeps page scripts that
         *  query these elements working, and videos are never touched — the
         *  site's own player stays intact. */
        private val AD_CLEAN_JS = """
            (function(){
              var SEL=[
                '[id^="google_ads"]','.adsbygoogle','ins.adsbygoogle','[class*="adsense"]','[class*="advert"]','[id*="advert"]',
                '[class*="ad-banner"]','[id*="ad-banner"]','div[data-ad]','[class*="sponsored"]','[class*="ad-placeholder"]',
                'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','iframe[src*="googleads"]',
                'iframe[src*="advertising"]','iframe[src*="adserver"]','iframe[src*="2mdn"]',
                '[class^="ad_"]','[id^="ad_"]','[class*="ad-pop"]','[class*="popup"]','[class*="popunder"]','[id*="popunder"]',
                '[id*="popup"]','[class*="interstitial"]','[class*="overlay-ad"]','[id*="overlay-ad"]',
                '[class*="ad-overlay"]','[id*="ad-overlay"]','[class*="ad_container"]','[id*="ad_container"]',
                '[class*="ad-slot"]','[id*="ad-slot"]','[class*="ads-container"]','[id*="ads-container"]',
                '[class*="close-ad"]','[id*="close-ad"]','[class*="closead"]','[id*="closead"]',
                'iframe[src*="popads"]','iframe[src*="popcash"]','iframe[src*="propellerads"]','iframe[src*="adnxs"]',
                'iframe[src*="exoclick"]','iframe[src*="juicyads"]','iframe[src*="trafficjunky"]',
                '[class*="chat-widget"]','[id*="chat-widget"]','[class*="chat-bubble"]','[class*="message-from"]',
                '[class*="fake-chat"]','[class*="fake-message"]','[class*="dating-widget"]','[class*="meet-widget"]'
              ];
              var BAIT=/\b\d+\s*messages?\s*from\b|message from\s*:\s*\w+|loved your joke|let'?s chat|chat (with )?me|hot singles|meet (hot |lonely |local )?(singles|girls|women)|verify you are (human|18|over 18)|you'?ve won|claim your (prize|reward)/i;
              function isBaitBox(e){
                try{
                  if(!e||!e.parentNode)return false;
                  if(e.closest('video')||e.querySelector('video'))return false;
                  var cs=getComputedStyle(e);
                  if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return false;
                  var r=e.getBoundingClientRect();
                  var vw=window.innerWidth||0,vh=window.innerHeight||0;
                  if(vw<=0||vh<=0)return false;
                  // A bait card: bigger than a button, smaller than the page.
                  if(r.width<200||r.height<140)return false;
                  if(r.width>vw*0.92||r.height>vh*0.92)return false;
                  var t=(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
                  if(t.length<12||t.length>600)return false;
                  return BAIT.test(t);
                }catch(x){return false;}
              }
              function cleanBait(){
                try{
                  var els=document.querySelectorAll('div,section,aside,dialog');
                  for(var i=0;i<els.length;i++){
                    var e=els[i];
                    if(isBaitBox(e)){
                      e.style.setProperty('display','none','important');
                      e.style.setProperty('pointer-events','none','important');
                    }
                  }
                }catch(x){}
              }
              var CLOSEAD=/^\s*close ad\s*[x\u2715\u00d7]?\$/i;
              function hasVideo(e){
                try{
                  if(!e)return false;
                  if(e.tagName==='VIDEO')return true;
                  if(e.closest&&e.closest('video'))return true;
                  if(e.querySelector&&e.querySelector('video'))return true;
                }catch(x){}
                return false;
              }
              function cleanCloseAd(){
                try{
                  var vw=window.innerWidth||0,vh=window.innerHeight||0;
                  var els=document.querySelectorAll('div,span,button,a,section,aside');
                  for(var i=0;i<els.length;i++){
                    var e=els[i];
                    var t=(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
                    if(t.length>22||!CLOSEAD.test(t))continue;
                    if(hasVideo(e))continue;
                    var cs=getComputedStyle(e);
                    if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')continue;
                    var target=e,up=0;
                    try{
                      var p=e.parentNode;
                      while(p&&up<4){
                        up++;
                        if(!p.getBoundingClientRect||hasVideo(p))break;
                        var pcs=getComputedStyle(p);
                        if(pcs.position!=='fixed'&&pcs.position!=='absolute')break;
                        var r=p.getBoundingClientRect();
                        if(vw>0&&vh>0&&(r.width>vw*0.95&&r.height>vh*0.95))break;
                        target=p;
                        p=p.parentNode;
                      }
                    }catch(x){}
                    target.style.setProperty('display','none','important');
                    target.style.setProperty('pointer-events','none','important');
                  }
                }catch(x){}
              }
              function clean(){
                for(var i=0;i<SEL.length;i++){
                  try{
                    var els=document.querySelectorAll(SEL[i]);
                    for(var j=0;j<els.length;j++){
                      var e=els[j];
                      if(!e||!e.parentNode)continue;
                      if(e.closest('video')||e.querySelector('video'))continue;
                      e.style.setProperty('display','none','important');
                    }
                  }catch(e){}
                }
                cleanBait();
                cleanCloseAd();
              }
              clean();
              try{
                new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});
              }catch(e){}
            })();
        """.trimIndent()

        private val PROMO_CLEAN_JS = """
            (function(){
              if(window.__hikariPromoClean)return;
              window.__hikariPromoClean=true;
              var SEL=[
                '[class*="donate"]','[id*="donate"]','[class*="donation"]','[id*="donation"]',
                '[class*="buymeacoffee"]','[id*="buymeacoffee"]','[class*="ko-fi"]','[id*="ko-fi"]',
                '[class*="kofi"]','[id*="kofi"]','[class*="patreon"]','[id*="patreon"]',
                '[class*="paypal"]','[id*="paypal"]','[class*="sponsor"]','[id*="sponsor"]',
                '[class*="funding"]','[id*="funding"]','[class*="fundrais"]','[id*="fundrais"]',
                '[class*="support-us"]','[class*="supportus"]','[class*="tip-jar"]','[class*="tipjar"]',
                '[class*="support-banner"]','[id*="support-banner"]','[class*="promo-card"]',
                'a[href*="ko-fi."]','a[href*="kofi."]','a[href*="buymeacoffee"]','a[href*="patreon."]',
                'a[href*="paypal."]','a[href*="opencollective"]','a[href*="liberapay"]',
                'a[href*="trakteer"]','a[href*="saweria."]','a[href*="sociabuzz"]','a[href*="donationalerts"]',
                'a[href*="github.com/sponsors"]','a[href*="gofundme"]','a[href*="kickstarter"]'
              ];
              var PROMO=/buy me a coffee|ko-?fi[.]?com|buymeacoffee|become a patron|become a sponsor|support (us|me|this|our) (on|via|through)|watch an ad to support|send extra love|make a donation|donate (now|today|to us|to this)|goal (achieved|completed)|supporters made this happen|sponsor (this|our) (repo|project|channel|extension)|fund (this|our) (repo|project|extension)|not affiliated with the cloudstream app|help (us )?keep [\w'\u2019 .-]{1,28}?(alive|free|running|online)|keep [\w'\u2019 .-]{1,28}?alive|keep it alive|zero support|extensions? die|no ads,? no (subscription|ads)|goal missed|monthly (goal|target)|active repo maintenance|maintain(ing)? \d+\+? (providers|repos|extensions)|can'?t donate|supporters? (this month|so far)|unlock (premium|ad-?free)|\$\s?\d{1,6}([,.]\d{3})?\s*\/\s*\$\s?\d{1,6}([,.]\d{3})?/i;
              var DISMISS=/^(maybe later|not now|no thanks|no, thanks|later|maybe next time|remind me later|skip|close|dismiss|\u2715|\u00d7|x)$/i;
              var lastDismiss=0;
              var MONEY=/ko-?fi[.]|buymeacoffee|patreon[.]com|paypal[.]|github[.]com\/sponsors|opencollective|liberapay|trakteer|saweria[.]|sociabuzz|donationalerts|gofundme|kickstarter|cash[.]app|venmo[.]com/i;
              function hide(e){
                try{
                  if(!e||!e.parentNode)return;
                  if(e.closest('video')||e.querySelector('video'))return;
                  e.style.setProperty('display','none','important');
                }catch(x){}
              }
              function clean(){
                var i,j,els;
                for(i=0;i<SEL.length;i++){
                  try{
                    els=document.querySelectorAll(SEL[i]);
                    for(j=0;j<els.length;j++)hide(els[j]);
                  }catch(x){}
                }
                try{
                  els=document.querySelectorAll('a,button,[role="button"]');
                  for(i=0;i<els.length;i++){
                    var a=els[i];
                    if(!a||!a.parentNode)continue;
                    var href=a.getAttribute('href')||a.getAttribute('data-url')||'';
                    if(!PROMO.test(a.textContent||'')&&!MONEY.test(href))continue;
                    hide(a);
                    var box=a.parentNode,up=0;
                    while(box&&up<6){
                      up++;
                      var t=(box.textContent||'').replace(/\s+/g,' ').trim();
                      if(t.length>0&&t.length<900&&PROMO.test(t)){hide(box);}
                      box=box.parentNode;
                    }
                  }
                }catch(x){}
                try{
                  els=document.querySelectorAll('div,section,aside,article,main,dialog');
                  var vw=window.innerWidth||0,vh=window.innerHeight||0;
                  for(i=0;i<els.length;i++){
                    var o=els[i];
                    if(!o||!o.parentNode)continue;
                    if(o.getAttribute('data-hikari-promo')==='1')continue;
                    if(o.closest('video')||o.querySelector('video'))continue;
                    var cs=getComputedStyle(o);
                    if(cs.position!=='fixed'&&cs.position!=='absolute')continue;
                    var r=o.getBoundingClientRect();
                    if(vw<=0||vh<=0)break;
                    if(r.width<vw*0.45||r.height<vh*0.35)continue;
                    var text=(o.textContent||'').replace(/\s+/g,' ').trim();
                    if(text.length===0||text.length>2500)continue;
                    if(!PROMO.test(text))continue;
                    o.setAttribute('data-hikari-promo','1');
                    var now=Date.now();
                    if(now-lastDismiss>1500){
                      var ctrls=o.querySelectorAll('button,a,[role="button"],span,div');
                      for(j=0;j<ctrls.length;j++){
                        var c=ctrls[j];
                        var ct=(c.textContent||'').replace(/\s+/g,' ').trim();
                        if(ct.length===0||ct.length>24)continue;
                        if(!DISMISS.test(ct))continue;
                        if(c.querySelector('button,a'))continue;
                        lastDismiss=now;
                        try{c.click();}catch(x){}
                        break;
                      }
                    }
                    hide(o);
                  }
                }catch(x){}
              }
              clean();
              var pending=false;
              function later(){
                if(pending)return;
                pending=true;
                setTimeout(function(){pending=false;clean();},400);
              }
              try{
                new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true});
              }catch(x){}
            })();
        """.trimIndent()

        private val VIDEO_POLYFILL_JS = """
            (function(){
              if(window.__hikariHlsInit)return;
              window.__hikariHlsInit=true;
              function attach(v){
                try{
                  // Play inline instead of fullscreen — some players never
                  // start when the WebView tries to promote them.
                  v.setAttribute('playsinline','');
                  v.setAttribute('webkit-playsinline','');
                  if(v.__hikariHlsAttached)return;
                  var src=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||(v.getAttribute('data-src')||'');
                  if(!src||src.indexOf('.m3u8')<0)return;
                  if(src.indexOf('blob:')===0)return;
                  if(v.canPlayType('application/vnd.apple.mpegurl'))return;
                  if(!window.Hls||!window.Hls.isSupported())return;
                  var hls=new window.Hls({enableWorker:true,lowLatencyMode:true});
                  hls.loadSource(src);
                  hls.attachMedia(v);
                  v.__hikariHlsAttached=hls;
                }catch(e){}
              }
              function scan(){
                try{
                  var vs=document.querySelectorAll('video');
                  for(var i=0;i<vs.length;i++)attach(vs[i]);
                  var ss=document.querySelectorAll('video source');
                  for(var i=0;i<ss.length;i++){
                    var s=ss[i];
                    if(s.src&&s.src.indexOf('.m3u8')>=0&&s.parentElement)attach(s.parentElement);
                  }
                }catch(e){}
              }
              function boot(){
                scan();
                setInterval(scan,2000);
                try{
                  new MutationObserver(scan).observe(document.body||document.documentElement,{childList:true,subtree:true});
                }catch(e){}
              }
              if(window.Hls){boot();}
              else{
                var tried=[];
                function loadLib(src){
                  if(tried.indexOf(src)>=0)return;
                  tried.push(src);
                  var s=document.createElement('script');
                  s.src=src;
                  s.async=true;
                  s.onload=function(){boot();};
                  s.onerror=function(){};
                  document.head.appendChild(s);
                }
                loadLib('https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js');
                setTimeout(function(){loadLib('https://unpkg.com/hls.js@1.5.13/dist/hls.min.js');},3500);
                setTimeout(function(){loadLib('https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.13/hls.min.js');},7000);
              }
            })();
        """.trimIndent()

        /** Returns the current page's <video>/<source> URLs as a JSON array string. */
        private val VIDEO_SCAN_JS = """
            (function(){
              var out=[];
              try{
                document.querySelectorAll('video').forEach(function(v){
                  var s=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||(v.getAttribute('data-src')||'');
                  if(s&&s.indexOf('blob:')!==0)out.push(s);
                });
                document.querySelectorAll('video source').forEach(function(s){if(s.src&&s.src.indexOf('blob:')!==0)out.push(s.src);});
              }catch(e){}
              return JSON.stringify(out);
            })();
        """.trimIndent()

        /** True when any <video> on the page is actively playing (used to drop
         *  ad popups that players open on click during playback). */
        private val VIDEO_PLAYING_JS = """
            (function(){
              try{
                var vs=document.querySelectorAll('video');
                for(var i=0;i<vs.length;i++){
                  var v=vs[i];
                  if(v&&!v.paused&&!v.ended&&v.readyState>=2&&v.currentTime>0)return 'true';
                }
              }catch(e){}
              return 'false';
            })();
        """.trimIndent()

        private val STUCK_MONITOR_JS = """
            (function(){
              if(window.__hikariStuckMon)return;
              window.__hikariStuckMon=true;
              var state=new Map();
              var FIRE_AFTER=9000;
              function fire(v,src){
                if(v.__hikariStuckFired)return;
                v.__hikariStuckFired=true;
                try{HikariBridge.stuckVideo(src);}catch(e){}
              }
              function tick(){
                try{
                  document.querySelectorAll('video').forEach(function(v){
                    try{
                      var src=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||'';
                      if(!src||src.indexOf('http')!==0){state.delete(v);return;}
                      var prev=v.__hikariSrc;
                      if(prev!==src){v.__hikariSrc=src;v.__hikariStuckFired=false;state.delete(v);}
                      var stuck=(v.error!==null)||(!v.paused&&v.readyState<2&&!v.videoWidth);
                      if(stuck){
                        var st=state.get(v);
                        if(!st){st={since:Date.now()};state.set(v,st);}
                        if(Date.now()-st.since>=FIRE_AFTER)fire(v,src);
                      }else{
                        state.delete(v);
                      }
                    }catch(e){}
                  });
                }catch(e){}
              }
              setInterval(tick,2000);
              tick();
            })();
        """.trimIndent()

        private val ELEMENT_BLOCK_JS = """
            (function(){
              if(window.__hikariBlockerInit)return;
              window.__hikariBlockerInit=true;
              window.__hikariBlockerActive=false;
              window.__hikariBlocks=window.__hikariBlocks||[];
              window.__hikariBlockRegistry=[];
              window.__hikariPendingEl=null;
              function clearPending(){
                if(window.__hikariPendingEl){
                  try{
                    window.__hikariPendingEl.style.removeProperty('outline');
                    window.__hikariPendingEl.style.removeProperty('outline-offset');
                  }catch(e){}
                  window.__hikariPendingEl=null;
                }
              }
              function hideAll(){
                var sels=window.__hikariBlocks||[];
                for(var i=0;i<sels.length;i++){
                  try{
                    var els=document.querySelectorAll(sels[i]);
                    for(var j=0;j<els.length;j++){
                      var e=els[j];
                      if(!e||!e.parentNode)continue;
                      // Re-apply every pass: sites reset inline styles and the
                      // element would otherwise flicker back into view.
                      if(!e.__hikariBlocked){
                        e.__hikariBlocked=true;
                        window.__hikariBlockRegistry.push({el:e,sel:sels[i]});
                      }
                      e.style.setProperty('display','none','important');
                      e.style.setProperty('pointer-events','none','important');
                    }
                  }catch(e){}
                }
              }
              window.__hikariHideAll=hideAll;
              function restoreOne(sel){
                var rem=[];
                for(var i=0;i<window.__hikariBlockRegistry.length;i++){
                  var en=window.__hikariBlockRegistry[i];
                  if(en.sel===sel){
                    try{
                      en.el.__hikariBlocked=false;
                      en.el.style.removeProperty('display');
                      en.el.style.removeProperty('pointer-events');
                    }catch(e){}
                    rem.push(i);
                  }
                }
                for(var k=rem.length-1;k>=0;k--)window.__hikariBlockRegistry.splice(rem[k],1);
                var bi=window.__hikariBlocks.indexOf(sel);
                if(bi>=0)window.__hikariBlocks.splice(bi,1);
              }
              window.__hikariRestoreSelector=restoreOne;
              function restoreAll(){
                for(var i=0;i<window.__hikariBlockRegistry.length;i++){
                  var en=window.__hikariBlockRegistry[i];
                  try{
                    en.el.__hikariBlocked=false;
                    en.el.style.removeProperty('display');
                    en.el.style.removeProperty('pointer-events');
                  }catch(e){}
                }
                window.__hikariBlockRegistry=[];
                window.__hikariBlocks=[];
              }
              window.__hikariRestoreAll=restoreAll;
              window.__hikariBlockerToggle=function(){
                window.__hikariBlockerActive=!window.__hikariBlockerActive;
                if(!window.__hikariBlockerActive)clearPending();
                return window.__hikariBlockerActive;
              };
              function esc(s){return String(s).replace(/[^a-zA-Z0-9_-]/g,function(c){return '\\'+c;});}
              function buildSelector(el){
                if(!el||el===document.documentElement||el===document.body)return '';
                if(el.id){return '#'+esc(el.id);}
                var t=el;
                for(var k=0;k<4;k++){
                  var r=t.getBoundingClientRect?t.getBoundingClientRect():null;
                  if(r&&(r.width>=24&&r.height>=16))break;
                  if(!t.parentElement||t.parentElement===document.body)break;
                  t=t.parentElement;
                }
                var parts=[];
                var cur=t;
                while(cur&&cur!==document.documentElement&&parts.length<3){
                  var tag=(cur.tagName||'').toLowerCase();
                  if(!tag)break;
                  var seg=tag;
                  var cls=[].slice.call(cur.classList).filter(function(c){return c&&c.length>1&&!c.match(/^(css-|sc-|_)/);});
                  if(cls.length)seg+='.'+cls.slice(0,2).map(esc).join('.');
                  var parent=cur.parentElement;
                  if(parent){
                    var sameTag=[].slice.call(parent.children).filter(function(s){return (s.tagName||'').toLowerCase()===tag;});
                    if(sameTag.length>1)seg+=':nth-of-type('+(sameTag.indexOf(cur)+1)+')';
                  }
                  parts.unshift(seg);
                  cur=cur.parentElement;
                }
                return parts.join(' > ');
              }
              function highlight(el){
                clearPending();
                try{
                  el.style.setProperty('outline','3px solid #ff1744','important');
                  el.style.setProperty('outline-offset','2px','important');
                  el.scrollIntoView({block:'center',inline:'center',behavior:'smooth'});
                }catch(e){}
                window.__hikariPendingEl=el;
              }
              function block(el){
                clearPending();
                var sel=buildSelector(el);
                if(!sel){window.__hikariBlockerActive=false;return;}
                el.__hikariBlocked=true;
                el.style.setProperty('display','none','important');
                el.style.setProperty('pointer-events','none','important');
                window.__hikariBlockRegistry.push({el:el,sel:sel});
                window.__hikariBlocks.push(sel);
                window.__hikariBlockerActive=false;
                try{HikariBridge.blockElement(sel);}catch(e){}
              }
              document.addEventListener('pointerdown',function(e){
                if(!window.__hikariBlockerActive)return;
                e.stopImmediatePropagation();
                e.preventDefault();
              },true);
              document.addEventListener('click',function(e){
                if(!window.__hikariBlockerActive)return;
                e.stopImmediatePropagation();
                e.preventDefault();
                var el=e.target&&e.target.nodeType===3?e.target.parentElement:e.target;
                if(window.__hikariPendingEl===el){
                  block(el);
                }else{
                  highlight(el);
                }
              },true);
              try{new MutationObserver(hideAll).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
              hideAll();
              setInterval(hideAll,500);
            })();
        """.trimIndent()

        private val TRANSLATE_JS = """
            (function(){
              if(window.__hikariTranslateInit)return;
              window.__hikariTranslateInit=true;
              var cache={};        // original text -> translated text
              var done={};         // original text that can't be translated (skip forever)
              var texts={};        // original text -> [text nodes waiting]
              var pending={};      // request id -> {keys:[], nodes:[[]]}
              var inFlight=false;
              var reqCounter=0;
              var walker=null;
              var applied=new WeakSet();
              var EN_STOP=/^(the|and|of|to|in|is|are|was|were|for|with|on|at|by|this|that|these|those|you|your|we|our|they|their|them|it|its|a|an|or|but|as|from|not|be|have|has|had|i|me|my|do|does|did|what|which|who|when|where|why|there|here|can|will|would|should|could|then|than|so|if|up|out|about|just|more|most|all|any|some|also|only|into|over|under|no|yes)$/i;
              function looksEnglish(t){
                var m=t.match(/[A-Za-z]+/g);
                if(!m)return false;
                for(var i=0;i<m.length;i++){if(EN_STOP.test(m[i]))return true;}
                return false;
              }
              function worth(text){
                var t=(text||'').trim();
                if(t.length<2)return false;
                if(!/[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF]/.test(t))return false;
                if(/^[\d\s.,!?%$#@&*()\/\-+='"<>\[\]{}|\\:;_~^`\u00A0]+$/.test(t))return false;
                if(cache[t]||done[t])return false;
                if(/^[\x00-\x7F]+$/.test(t)&&looksEnglish(t))return false;
                return true;
              }
              function shouldSkip(node){
                var p=node.parentElement;
                if(!p)return true;
                var tag=p.tagName;
                if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='TEXTAREA'||tag==='INPUT'||tag==='SELECT'||tag==='OPTION'||tag==='CODE'||tag==='PRE'||tag==='IFRAME')return true;
                if(p.isContentEditable)return true;
                if(tag==='A'){var h=(p.getAttribute('href')||'').trim();if(h&&(node.nodeValue||'').trim()===h)return true;}
                if(applied.has(node))return true;
                return false;
              }
              function process(node){
                var v=node.nodeValue;
                if(!v||!v.trim())return;
                if(cache[v]){
                  if(node.nodeValue===v){node.nodeValue=cache[v];applied.add(node);}
                  return;
                }
                if(!worth(v))return;
                (texts[v]=texts[v]||[]).push(node);
              }
              function flush(){
                if(inFlight)return;
                var keys=Object.keys(texts);
                if(!keys.length)return;
                var batch=[],nodes=[];
                for(var i=0;i<keys.length&&batch.length<40;i++){
                  var k=keys[i];
                  var list=texts[k]||[];
                  var alive=false;
                  for(var j=0;j<list.length;j++){if(list[j]&&list[j].parentNode){alive=true;break;}}
                  if(!alive){delete texts[k];continue;}
                  batch.push(k);
                  nodes.push(list);
                  delete texts[k];
                }
                if(!batch.length)return;
                var id='t'+(reqCounter++);
                pending[id]={keys:batch,nodes:nodes};
                inFlight=true;
                try{window.HikariBridge.translate(id,JSON.stringify(batch));}
                catch(e){inFlight=false;delete pending[id];}
              }
              window.__hikariTransResult=function(id,results){
                var p=pending[id];
                delete pending[id];
                if(!p){inFlight=false;return;}
                for(var i=0;i<p.keys.length;i++){
                  var orig=p.keys[i];
                  var tr=(results&&results[i])||'';
                  if(!tr||!tr.trim()||tr===orig){done[orig]=true;continue;}
                  cache[orig]=tr;
                  var list=p.nodes[i]||[];
                  for(var j=0;j<list.length;j++){
                    var n=list[j];
                    if(n&&n.parentNode&&n.nodeValue===orig){n.nodeValue=tr;applied.add(n);}
                  }
                }
                inFlight=false;
                setTimeout(function(){scan();},50);
              };
              function scan(){
                try{
                  if(!document.body){setTimeout(scan,400);return;}
                  if(walker&&walker.currentNode&&!document.documentElement.contains(walker.currentNode))walker=null;
                  if(!walker)walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
                  var n,count=0,exhausted=true;
                  while((n=walker.nextNode())){
                    if(!n.nodeValue||!n.nodeValue.trim())continue;
                    if(shouldSkip(n))continue;
                    process(n);
                    if(++count>=1500){exhausted=false;break;}
                  }
                  if(exhausted)walker=null;
                  flush();
                }catch(e){walker=null;}
              }
              var timer=null;
              function schedule(){if(timer)return;timer=setTimeout(function(){timer=null;scan();},250);}
              try{new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}
              setInterval(scan,4000);
              setTimeout(scan,150);
            })();
        """.trimIndent()
    }
}
