package com.hikari.app.web

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
import com.hikari.app.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Ad-free web view for user-added movie/streaming websites.
 *
 * Everything is tuned to behave like a real Chrome browser so the sites' own
 * HTML5 players work:
 *  - Full cookie support INCLUDING third-party cookies (CDN auth cookies and
 *    Cloudflare cf_clearance are set/read cross-origin), flushed to disk so
 *    they survive activity restarts.
 *  - A current desktop-class Chrome user agent (sites serve their normal
 *    player instead of a mobile/fallback page, and Cloudflare challenges get
 *    the least suspicious fingerprint possible).
 *  - An hls.js polyfill: Android's WebView CANNOT decode HLS (.m3u8) in a
 *    <video> tag, which is exactly why "the site loads but the video buffers
 *    forever". The polyfill injects hls.js and attaches it to any m3u8 video
 *    the page creates (their own hls.js is detected and left alone).
 *  - Ads are HIDDEN (never removed, and never touching <video>), so scripts
 *    that reference ad elements keep working and the player is never torn
 *    down by our cleanup.
 *  - A floating ▶ button hands any REAL detected HLS/DASH/MP4 source straight
 *    to Hikari's ExoPlayer as a fallback when a site's player won't cooperate,
 *    carrying cookies + the page URL as Referer for hotlink-protected CDNs.
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var videoChip: TextView
    private lateinit var rootView: LinearLayout
    private val detectedVideos = LinkedHashSet<String>()
    private var pageUrl: String? = null
    private var pageTitle: String = ""
    private var startUrl: String = ""

    // Provider this WebView was opened for (null = a plain site/URL). Used by
    // the per-extension auto-translate feature to decide whether the page's
    // text should be translated to English.
    private var providerId: String? = null

    // Whether to translate this page's text into English. True when the intent
    // asked for it explicitly ("Just this time") or the provider has "Always
    // translate" turned on in the store.
    @Volatile
    private var translateEnabled = false

    // "Verify for Cloudflare" mode (opened from the Home tab): the site is
    // shown so the user can complete a CF challenge; once a challenge was seen
    // and the page turns into real content, the activity finishes by itself so
    // the caller can reload the extension catalog with the now-valid cookies.
    private var autoCloseWhenCloudflarePassed = false
    private var challengeSeen = false
    private var verifyDone = false
    // Consecutive polls where the verify page looked like a hard WAF block
    // ("you have been blocked") rather than a solvable challenge — a block can
    // never mint a clearance, so after a few ticks the view closes itself
    // instead of lingering on top of the player forever.
    private var blockedCount = 0
    // Consecutive polls where the verify page showed neither a challenge nor a
    // hard block — i.e. it loaded as ordinary content and has nothing to
    // verify. After a few ticks the view closes itself instead of lingering.
    private var noChallengePolls = 0
    // Set when the activity was auto-launched by CloudflareVerifier (a request
    // hit a challenge) — the host lets the verifier wake its waiters when this
    // view closes so the retry runs immediately.
    private var verifyHost: String? = null
    private val verifyHandler = Handler(Looper.getMainLooper())
    private var verifyRunnable: Runnable? = null

    // Persisted element-block selectors (WebView menu → Element blocker). Loaded
    // from the store on launch, kept in sync by the bridge, and handed to the
    // page's ELEMENT_BLOCK_JS on every load so blocked elements stay hidden.
    private val blockedSelectors = java.util.LinkedHashSet<String>()

    // Ad blocking (WebView requests only) — resolved from the user's lists on
    // launch, and never applied to the ExoPlayer's own network I/O.
    @Volatile
    private var blockedDomains: Set<String> = emptySet()
    @Volatile
    private var whitelistDomains: Set<String> = emptySet()

    // WebView safety toggles (Settings → WebView safety). Default ON:
    //  - redirectProtection: the main frame can only navigate within the site
    //    it was opened for — ad-hijack redirects (ad.twinrdengine.com & co)
    //    are cancelled before they load.
    //  - popupProtection: window.open() popups are only relayed into the view
    //    when they belong to the same site; ad popups/popunders are dropped.
    @Volatile
    private var redirectProtection = true
    @Volatile
    private var popupProtection = true
    @Volatile private var blockedToastShown = false
    // Hosts the user explicitly allowed redirects to (Settings → WebView safety
    // → Allowed redirect links). Navigations to these are never blocked.
    @Volatile
    private var allowedRedirectHosts: Set<String> = emptySet()

    // Guards the auto hand-off to the external player: a page that genuinely
    // can't start its own <video> gets handed to Hikari's ExoPlayer ONCE (reset
    // on every navigation), so the user never stares at an infinite spinner.
    private var autoLaunched = false

    // True while any <video> on the page is actively playing (kept fresh by the
    // periodic scan). Popups opened while a video is playing are ads — players
    // pop them on click — so they're dropped instead of replacing the video.
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

    // Temporary child WebView that relays window.open() popups into the main
    // view. Android CRASHES with "Parent WebView cannot host its own popup
    // window" if a popup's transport targets the parent WebView itself, so the
    // popup URL is captured in a throwaway child and loaded in the main view.
    private var popupChild: WebView? = null

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
        providerId = intent.getStringExtra("providerId")
        val forceTranslate = intent.getBooleanExtra("translate", false)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        videoChip = TextView(this).apply {
            text = "\u25B6 Play video"
            visibility = View.GONE
            setBackgroundColor(0xFF3D5AFE.toInt())
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { playVideo() }
        }

        webView = WebView(this)
        // JS → Kotlin bridge for the auto hand-off: when a page's own <video>
        // is stuck buffering forever (common on WebView-hostile tube sites),
        // the page calls HikariBridge.stuckVideo(url) and we hand the real
        // source to Hikari's ExoPlayer so playback actually starts.
        webView.addJavascriptInterface(HikariJsBridge(), "HikariBridge")

        // Small floating ⋯ pill in the top-right corner — the only always-visible
        // control. Tapping it opens a small menu (Back/Forward/Reload/Player) so
        // the app never draws a header bar over the site's own header or search.
        val togglePill = TextView(this).apply {
            text = "\u22EF"
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

        // Draggable ⋯ pill: it floats over the site (top-right by default) and
        // can cover a site's search/header button, so let the user drag it
        // anywhere along the top edge. A plain tap still opens the menu.
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
            allowedRedirectHosts = app.store.webviewRedirectAllow().toSet()
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
            // Per-extension auto-translate: on when the intent asked for it
            // explicitly ("Just this time") or the provider has "Always
            // translate" turned on.
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
        // UA comes from Settings → WebView user agent: stock Android default
        // (passes Cloudflare's JS challenge) unless the user overrides with a
        // custom one. See HikariApp.effectiveWebViewUa.
        ws.userAgentString = (application as HikariApp).effectiveWebViewUa()

        // Cookies are the backbone of most streaming CDNs (session tokens) and
        // Cloudflare (cf_clearance). Accept them, accept THIRD-party ones (the
        // player page is site A, the CDN is site B), and flush to disk so they
        // survive activity restarts — this is what makes "works in my browser"
        // actually work in the app.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setWebViewClient(object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                // Cloudflare-verification mode: ONLY the CF challenge may ever
                // load (the site + Cloudflare's challenge infra). Any other
                // redirect target is cancelled before it loads — the view is
                // open solely to pass the challenge, never to surf.
                if (autoCloseWhenCloudflarePassed && request.isForMainFrame &&
                    !isVerifyAllowed(request.url.toString())
                ) {
                    showBlockedToast("Blocked redirect to ${request.url.host ?: "unknown"}")
                    return true
                }
                // Redirect protection: cancel main-frame navigations away from
                // the site before they load (ad-hijack redirects). Same-site
                // pages, subdomains, whitelisted hosts and user-allowed
                // redirect hosts still work.
                if (redirectProtection && request.isForMainFrame) {
                    val host = request.url.host
                    val cur = currentPageHost()
                    if (host != null && cur != null && host != cur &&
                        !AdBlocker.matches(host, whitelistDomains) &&
                        !AdBlocker.matches(host, allowedRedirectHosts) &&
                        !isSameSite(host, cur)
                    ) {
                        showBlockedToast("Blocked redirect to $host")
                        return true
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
                if (host.isNotBlank()) {
                    // Whitelist wins first — a site the user unblocked keeps
                    // all its subdomains usable.
                    if (AdBlocker.matches(host, whitelistDomains)) {
                        return null
                    }
                    if (AdBlocker.matches(host, blockedDomains)) {
                        // NEVER block actual media — blocklists routinely contain
                        // tube-site CDN domains (phncdn.com, …) that serve the
                        // actual video. Blocking those turns "video loads" into
                        // "video buffering forever" (the Pornhub-style stall).
                        if (!isMediaRequest(request)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                // Only count it as a video if it actually IS one: probe the
                // content-type in the background so ad previews, gifs and
                // image placeholders never populate the chip.
                if (VIDEO_URL_RE.containsMatchIn(u)) {
                    maybeAddVideo(u)
                }
                // Cloudflare-verification mode: never let a redirect land on a
                // foreign page while the view is just passing a challenge.
                if (autoCloseWhenCloudflarePassed && request.isForMainFrame &&
                    !isVerifyAllowed(u)
                ) {
                    showBlockedToast("Blocked redirect to $host")
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
                        !AdBlocker.matches(host, allowedRedirectHosts) &&
                        !isSameSite(host, cur)
                    ) {
                        showBlockedToast("Blocked redirect to $host")
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
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
                // The verification view exists only to pass a Cloudflare
                // challenge — it must never offer to play or hand off video
                // (the site page would just start its player + ads).
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
                // Some players create <video> from JS without a network URL we
                // can see — scan the DOM for the real element too. The runnable
                // keeps re-scanning so single-page-app players (which swap the
                // player without a full navigation) still light the chip.
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
                // The renderer crashed (often a heavyweight site) — relaunch the
                // activity instead of showing a dead white screen.
                runCatching {
                    finish()
                    startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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
                // Cloudflare-verification view: NEVER relay popups. It is only
                // open to pass the challenge, and these streaming pages' players
                // use window.open to pop ads the moment you click the video.
                if (autoCloseWhenCloudflarePassed) return false
                // While a video is actively playing, a window.open popup is an
                // ad (players pop them on click) — relaying it into the main
                // view would replace the playing video with the ad page.
                if (videoPlaying) {
                    showBlockedToast("Blocked popup during playback")
                    return false
                }
                // Route popups (some players open in window.open) into this
                // same web view so video keeps working. The transport CANNOT
                // target the parent WebView itself — Android throws
                // "Parent WebView cannot host its own popup window". Instead a
                // throwaway child WebView captures the popup's URL and loads it
                // in the main view.
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
                            // Popup protection: only relay popups that belong to
                            // the site (same host/subdomain or whitelisted).
                            // about:blank popunders and foreign ad popups are
                            // dropped entirely.
                            if (popupProtection) {
                                val host = runCatching { java.net.URI(url).host }.getOrNull()
                                val cur = currentPageHost()
                                if (host == null || cur == null ||
                                    (host != cur && !AdBlocker.matches(host, whitelistDomains) && !isSameSite(host, cur))
                                ) {
                                    showBlockedToast("Blocked popup")
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
        menu.menu.add(0, 7, 0, "\u2298 Element blocker")
        menu.menu.add(0, 8, 0, "\u21A9 Undo last block")
        menu.menu.add(0, 9, 0, "\u2715 Clear all blocks")
        if (translateEnabled) menu.menu.add(0, 10, 0, "\u2716 Translation off")
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
                    Toast.makeText(this, "Translation off", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        menu.show()
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
                if (active) "Blocker ON — tap an element to select it, tap it again to block"
                else "Element blocker OFF",
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
                    Toast.makeText(this@WebViewActivity, "Nothing to undo", Toast.LENGTH_SHORT).show()
                } else {
                    blockedSelectors.remove(sel)
                    (applicationContext as HikariApp).elementBlocks = blockedSelectors.toList()
                    webView.evaluateJavascript(
                        "window.__hikariRestoreSelector?window.__hikariRestoreSelector(" +
                            jsString(sel) + "):null",
                        null
                    )
                    Toast.makeText(this@WebViewActivity, "Last block reverted", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@WebViewActivity, "All element blocks cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** A JS string literal (for embedding a selector into evaluateJavascript). */
    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Translates one string into English via Google's free gtx endpoint (no
     * API key, CORS-free since the app does the request server-side). Returns
     * the original text when nothing could be translated so the page keeps
     * working even if translation is unavailable.
     */
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

    /** In Cloudflare-verification mode ONLY the challenge may be shown: the
     *  site we started on plus Cloudflare's own challenge infra. Anything else
     *  (ad-hijack redirects, parked/redirect pages, trackers) is blocked —
     *  the auto-opened view must never land on a random site. */
    private fun isVerifyAllowed(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
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

    /**
     * "Verify for Cloudflare" mode: every ~1.2s ask the page whether it still
     * looks like a WAF challenge. The moment a challenge we've already seen
     * turns into real content (the user completed the verification and the
     * page reloaded), finish this activity so the caller reloads the extension
     * catalog with the now-valid cf_clearance cookie. If no challenge ever
     * appears the user just closes the view normally.
     */
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
                            // Hard WAF block — no challenge to pass. Give the
                            // page a beat (Cloudflare may still be mid-redirect)
                            // then close instead of sitting on the player.
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

    /**
     * True for requests that carry actual media (a Range request, a video
     * Accept header, or a media-looking URL). The ad-blocker never blocks
     * these: blocklists contain video-CDN domains (tube-site CDNs especially)
     * and blocking the media itself is what makes videos spin forever.
     */
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
                    Toast.makeText(this, "No video found on this page", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "No playable video found", Toast.LENGTH_SHORT).show()
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

    /**
     * Handed to the page as `window.HikariBridge`. Called by STUCK_MONITOR_JS
     * when a real <video> on the page has been trying to play for ~9s without
     * producing a single frame — the site's player is never going to start on
     * this WebView, so hand the exact source to Hikari's ExoPlayer (with the
     * page as Referer + shared cookies) and let it play there instead.
     */
    private inner class HikariJsBridge {
        @android.webkit.JavascriptInterface
        fun stuckVideo(url: String) {
            // Verification view exists only to pass the challenge — never
            // hand a video off to the external player from it.
            if (autoCloseWhenCloudflarePassed) return
            if (autoLaunched || url.isBlank()) return
            autoLaunched = true
            runOnUiThread {
                detectedVideos.add(url)
                videoChip.text = "\u25B6 Opening external player…"
                videoChip.visibility = View.VISIBLE
                launchPlayer(listOf(url), pageUrl ?: webView.url)
            }
        }

        // ---- Element blocker bridge ----
        @android.webkit.JavascriptInterface
        fun blockElement(selector: String) {
            if (selector.isBlank()) return
            runOnUiThread {
                blockedSelectors.add(selector)
                (applicationContext as HikariApp).elementBlocks = blockedSelectors.toList()
                Toast.makeText(this@WebViewActivity, "Element blocked", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { (applicationContext as HikariApp).store.addElementBlock(selector) }
                }
            }
        }

        // ---- Userscript GM_* value storage (WebView only) ----
        @android.webkit.JavascriptInterface
        fun userscriptGet(scriptId: String, key: String): String? =
            UserscriptManager.getValue(applicationContext, scriptId, key)

        @android.webkit.JavascriptInterface
        fun userscriptSet(scriptId: String, key: String, valueJson: String) {
            UserscriptManager.setValue(applicationContext, scriptId, key, valueJson)
        }

        @android.webkit.JavascriptInterface
        fun userscriptDelete(scriptId: String, key: String) {
            UserscriptManager.deleteValue(applicationContext, scriptId, key)
        }

        @android.webkit.JavascriptInterface
        fun userscriptList(scriptId: String): String =
            UserscriptManager.listValues(applicationContext, scriptId)

        // ---- Auto-translate bridge ----
        // The page's TRANSLATE_JS calls this with a batch of unique text
        // strings; we fetch English translations (translate.googleapis.com —
        // no key needed) and hand them back via window.__hikariTransResult.
        @android.webkit.JavascriptInterface
        fun translate(id: String, textsJson: String) {
            val arr = runCatching { JSONArray(textsJson) }.getOrNull() ?: return
            val texts = (0 until arr.length()).map { arr.optString(it) }
            if (texts.isEmpty()) return
            lifecycleScope.launch {
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        // Persist cookies/DOM-storage to disk the moment the WebView leaves the
        // foreground so logins and sessions survive even a force-kill.
        runCatching { CookieManager.getInstance().flush() }
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
                '[class^="ad_"]','[id^="ad_"]','[class*="ad-pop"]','[class*="popup"]','[class*="popunder"]','[id*="popunder"]'
              ];
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
              }
              clean();
              try{
                new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});
              }catch(e){}
            })();
        """.trimIndent()

        /**
         * hls.js polyfill. Android WebView CANNOT decode HLS in a <video>, so
         * sites that feed the player a plain .m3u8 buffer forever. If the page
         * already runs its own hls.js (window.Hls exists) we reuse it; otherwise
         * we load hls.js from CDN and attach it to every m3u8 video, including
         * ones the site creates later. Videos the page already plays via a
         * blob: src (their own hls.js) are skipped so we never double-attach.
         */
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

        /**
         * Watches every <video> on the page. If one has a real http(s) source
         * but has spent ~9 seconds trying to play without producing a single
         * frame (or failed outright), the site's player is never going to start
         * on this WebView — hand the exact source to the app so it plays in
         * Hikari's ExoPlayer instead of spinning forever.
         */
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

        /**
         * Element blocker. Always injected (guarded by a page-scoped flag) so
         * the menu's "Element blocker" toggle just flips
         * window.__hikariBlockerActive; the next tap on the page picks the
         * tapped element, computes a stable CSS selector, hides it, pushes it
         * to the registry + __hikariBlocks and calls
         * HikariBridge.blockElement(selector) so the app persists it. The
         * toggle switches itself off after one block. Persisted selectors from
         * __hikariBlocks are re-hidden on every load + on DOM mutations (SPAs
         * re-mount nodes), and __hikariRestoreSelector/__hikariRestoreAll undo
         * a block / all blocks without a reload.
         */
                /**
         * Element blocker. Always injected (guarded by a page-scoped flag).
         * Menu → "Element blocker" TOGGLES the mode: when on, the first tap
         * HIGHLIGHTS the element in red (outline + scroll into view) so you see
         * exactly what would be removed; a second tap on the SAME element hides
         * + persists it. Tapping a different element just moves the highlight.
         * Toggle off (or a completed block) clears the highlight.
         * Persisted selectors from __hikariBlocks are re-hidden on every load,
         * on DOM mutations AND on a 500ms interval — sites that re-render ads
         * (rotating GIF banners, SPA sliders) recreate containers with the same
         * selectors, which is what made blocked elements "come back".
         */
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

        /**
         * Auto-translate (per-extension, English only). Walks the page's text
         * nodes, batches the unique strings to `HikariBridge.translate()` (the
         * app fetches Google's gtx endpoint — no CORS issue, no API key) and
         * swaps the translated text back in place. Works for ANY language
         * (CJK, Korean, accented Latin, Cyrillic, …): text that's already
         * English is recognized by its stopwords and left alone, everything
         * else is auto-detected by the translator and turned into English.
         */
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
              // English stopwords: a pure-ASCII text containing any of these is
              // almost certainly already English and is skipped (saves the
              // translation round-trips). Everything else — CJK, Korean,
              // accented Latin, Cyrillic, or ASCII without stopwords — is sent
              // to the translator, which auto-detects the language.
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
