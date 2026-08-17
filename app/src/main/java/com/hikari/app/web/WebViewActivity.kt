package com.hikari.app.web

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * Ad-free web view for user-added movie/streaming websites:
 *  - Ads, trackers and popups are blocked (request-level host filtering +
 *    DOM cleanup injected on every page).
 *  - HTML5 video plays inline with zero interaction required.
 *  - A floating ▶ button hands any detected HLS/DASH/MP4 source straight to
 *    Hikari's player, carrying the current page URL as the Referer so
 *    hotlink-protected CDNs accept it.
 */
class WebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var videoChip: TextView
    private lateinit var titleText: TextView
    private val detectedVideos = LinkedHashSet<String>()
    private var pageUrl: String? = null

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        setupWebView(startUrl)
    }

    private fun setupWebView(startUrl: String) {
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.setSupportMultipleWindows(false)
        ws.javaScriptCanOpenWindowsAutomatically = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.cacheMode = WebSettings.LOAD_DEFAULT

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
                if (VIDEO_URL_RE.containsMatchIn(u)) {
                    runOnUiThread {
                        if (detectedVideos.add(u)) {
                            videoChip.text = "\u25B6 ${detectedVideos.size} video" +
                                if (detectedVideos.size > 1) "s" else ""
                            videoChip.visibility = View.VISIBLE
                        }
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageUrl = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageUrl = url
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(AD_REMOVER_JS, null)
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.loadUrl(startUrl)
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
        val sources = JSONArray()
        unique.forEach { u ->
            val headers = JSONObject()
            if (!referer.isNullOrBlank()) headers.put("Referer", referer)
            headers.put("User-Agent", Http.UA)
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
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
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

        /** Injected after every page load; keeps the DOM free of ad containers. */
        private val AD_REMOVER_JS = """
            (function(){
              function kill(){
                var sel=[
                  '[id^="google_ads"]','.adsbygoogle','[class*="adsense"]','[class*="advert"]','[id*="advert"]',
                  '[class*="ad-banner"]','[id*="ad-banner"]','div[data-ad]','[class*="sponsored"]','[class*="ad-placeholder"]',
                  'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','iframe[src*="googleads"]',
                  'iframe[src*="advertising"]','iframe[src*="adserver"]','iframe[src*="2mdn"]',
                  '[class^="ad_"]','[id^="ad_"]','[class*="ad-pop"]','[class*="popup"]','[class*="popunder"]','[id*="popunder"]'
                ];
                for(var i=0;i<sel.length;i++){
                  try{var el=document.querySelectorAll(sel[i]);for(var j=0;j<el.length;j++){var e=el[j];if(e&&e.parentNode)e.parentNode.removeChild(e);}}catch(e){}
                }
                document.querySelectorAll('video').forEach(function(v){
                  v.setAttribute('controls','');v.setAttribute('playsinline','');v.muted=false;
                  try{v.play&&v.play().catch(function(){})}catch(e){}
                });
              }
              kill();
              setInterval(kill,1500);
            })();
        """.trimIndent()

        /** Returns the current page's <video>/<source> URLs as a JSON array string. */
        private val VIDEO_SCAN_JS = """
            (function(){
              var out=[];
              try{
                document.querySelectorAll('video').forEach(function(v){
                  var s=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src);
                  if(s&&s.indexOf('blob:')!==0)out.push(s);
                });
                document.querySelectorAll('video source').forEach(function(s){if(s.src&&s.src.indexOf('blob:')!==0)out.push(s.src);});
              }catch(e){}
              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}
