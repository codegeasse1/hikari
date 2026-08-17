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
import com.hikari.app.net.Http
import com.hikari.app.player.PlayerActivity
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
    private lateinit var titleText: TextView
    private lateinit var rootView: LinearLayout
    private val detectedVideos = LinkedHashSet<String>()
    private var pageUrl: String? = null

    // Fullscreen HTML5 video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // File uploads (sites with <input type=file>)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Periodic re-scan for SPA players that add <video> without a navigation
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val startUrl = intent.getStringExtra("url") ?: "https://www.google.com"
        val startTitle = intent.getStringExtra("title").orEmpty().ifBlank { startUrl }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        fun navBtn(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER
            isClickable = true
        }

        val backBtn = navBtn("\u2190")
        backBtn.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        val fwdBtn = navBtn("\u2192")
        fwdBtn.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        val reloadBtn = navBtn("\u21BB")
        reloadBtn.setOnClickListener { webView.reload() }
        val playBtn = navBtn("\u25B6")
        playBtn.setOnClickListener { playVideo() }

        titleText = TextView(this).apply {
            text = startTitle
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        toolbar.addView(backBtn, lp(dp(44), dp(42)))
        toolbar.addView(fwdBtn, lp(dp(44), dp(42)))
        toolbar.addView(reloadBtn, lp(dp(44), dp(42)))
        toolbar.addView(playBtn, lp(dp(44), dp(42)))
        toolbar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

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

        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(rootView)

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
        // A real current Chrome UA (not the bare "Version/4.0 webview" string):
        // sites serve their normal HTML5 player instead of a fallback page, and
        // Cloudflare sees an ordinary Chrome Mobile fingerprint.
        ws.userAgentString = Http.WEBVIEW_UA

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
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url.toString()
                val host = request.url.host ?: ""
                if (AD_HOSTS.any { host.contains(it, ignoreCase = true) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                // Only count it as a video if it actually IS one: probe the
                // content-type in the background so ad previews, gifs and
                // image placeholders never populate the chip.
                if (VIDEO_URL_RE.containsMatchIn(u)) {
                    maybeAddVideo(u)
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageUrl = url
                detectedVideos.clear()
                scanHandler.removeCallbacksAndMessages(null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageUrl = url
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(AD_CLEAN_JS, null)
                view?.evaluateJavascript(VIDEO_POLYFILL_JS, null)
                // Some players create <video> from JS without a network URL we
                // can see — scan the DOM for the real element too. The runnable
                // keeps re-scanning so single-page-app players (which swap the
                // player without a full navigation) still light the chip.
                scanRunnable?.let { scanHandler.removeCallbacks(it) }
                val r = object : Runnable {
                    override fun run() {
                        val v = webView
                        if (v == null) return
                        v.evaluateJavascript(VIDEO_SCAN_JS) { res ->
                            for (u in extractUrls(res)) maybeAddVideo(u)
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
                // Route popups (some players open in window.open) into this
                // same web view so video keeps working.
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = webView
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

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Adds [url] to the detected set ONLY after confirming it serves real video. */
    private fun maybeAddVideo(url: String) {
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

    /** Fetches a few bytes and decides whether [url] is actual video content. */
    private fun isRealVideo(url: String): Boolean {
        // Share the WebView's cookie jar with the probe request — Cloudflare /
        // auth-token CDNs answer with a 403 (or a challenge page) without it.
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val headers = buildMap {
            put("Range", "bytes=0-2047")
            put("Referer", pageUrl ?: url)
            put("User-Agent", Http.WEBVIEW_UA)
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
                putExtra("title", titleText.text.toString())
                putExtra("sources", sources.toString())
            }
        )
    }

    private fun urlHost(u: String): String = runCatching {
        java.net.URI(u).host ?: u.take(48)
    }.getOrDefault(u.take(48))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onDestroy() {
        scanHandler.removeCallbacksAndMessages(null)
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4001

        /** Request-level ad/tracker hosts — requests to these are answered with an empty body. */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "googletagmanager.com", "googletagservices.com", "google-analytics.com",
            "googletagmanager.com", "adservice.google", "2mdn.net", "adf.ly", "adfly",
            "taboola.com", "outbrain.com", "adnxs.com", "criteo.com", "criteo.net",
            "rubiconproject.com", "moatads.com", "quantserve.com", "scorecardresearch.com",
            "amazon-adsystem.com", "adform.net", "smartadserver.com", "openx.net",
            "pubmatic.com", "sovrn.com", "casalemedia.com", "lijit.com", "adsafeprotected.com",
            "popads.net", "popunder", "adsterra.com", "propellerads.com", "adroll.com",
            "media.net", "yieldmo.com", "adcolony.com", "inmobi.com", "admarvel.com",
            "flurry.com", "startappservice.com", "smartyads.com", "admob.com",
            "applovin.com", "unityads.unity3d.com", "vungle.com", "chartboost.com",
            "mopub.com", "supersonicads.com", "revcontent.com", "adpushup.com",
            "snigelweb.com", "adthrive.com", "ezoic.net", "mediavine.com", "akamaized.net/ads"
        )

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
    }
}
