package com.hikari.app.cs3

import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import com.hikari.app.HikariApp
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LAST-RESORT universal video extraction via a bundled yt-dlp runtime.
 *
 * Runs only when the plugin's own loadLinks, the jar extractor registry and
 * FallbackResolver all came up empty on a page URL. yt-dlp understands 1000+
 * sites, so a page the CloudStream extractors can't decode often still yields a
 * direct m3u8/mp4 here.
 *
 * The engine is the ffmpegkit-maintained yt-dlp-android library: it embeds a
 * CPython 3.13 + yt-dlp inside the AAR via Chaquopy. This resolver does NOT use
 * the library's download API (which returns only an exit code) — it drives the
 * bundled `yt_dlp` module directly through Chaquopy's eval() bridge, running
 * yt-dlp in "simulate" mode (extract_info(download=False)) and reading back the
 * direct stream URLs as JSON.
 *
 * Unsupported ABIs (32-bit devices) make YtDlp.init() throw; [available] stays
 * false and [resolve] returns nothing, so the app degrades to today's behavior.
 */
object YtDlpResolver {

    private const val TAG = "YtDlpResolver"

    /** True once the embedded Python/yt-dlp runtime is up. */
    @Volatile
    var available = false
        private set

    /** Human-readable reason when [available] is false (unsupported ABI,
     *  init crash, …). Shown to the user in the player error panel. */
    @Volatile
    var initFailure: String? = null
        private set

    private val initMutex = Mutex()
    private var initAttempted = false

    /** Starts the bundled CPython + yt-dlp runtime (first call takes a few
     *  seconds; later calls are no-ops). Safe from any thread. */
    suspend fun ensureInit(): Boolean {
        if (available) return true
        initMutex.withLock {
            if (available) return true
            if (initAttempted) return available
            initAttempted = true
            try {
                YtDlp.init(HikariApp.instance)
                available = true
                initFailure = null
            } catch (t: Throwable) {
                available = false
                initFailure = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "yt-dlp init failed", t)
            }
            return available
        }
    }

    /** Runs yt-dlp against [pageUrl] in simulate mode (no download) and returns
     *  every direct stream URL it finds (plus any subtitles). Empty when the
     *  page isn't supported or the runtime is unavailable. */
    suspend fun resolve(pageUrl: String): List<StreamSource> {
        if (pageUrl.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            if (!ensureInit()) return@withContext emptyList()
            try {
                // Base64 keeps the page URL injection-proof when spliced into
                // the Python source (no quotes/backslashes can leak through).
                val b64 = Base64.encodeToString(pageUrl.toByteArray(), Base64.NO_WRAP)
                val json = Python.getInstance()
                    .eval(EXTRACT_SCRIPT.replace("HIKARI_B64", b64))
                    .toString()
                parse(json, pageUrl)
            } catch (t: Throwable) {
                Log.e(TAG, "yt-dlp extract failed for $pageUrl: ${t.message}")
                emptyList()
            }
        }
    }

    private fun parse(json: String, pageUrl: String): List<StreamSource> {
        if (json.isBlank() || json == "None") return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("streams") ?: return emptyList()
        val out = LinkedHashMap<String, StreamSource>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val proto = o.optString("proto")
            val height = o.optInt("height", 0)
            val isM3u8 = proto.contains("m3u8", true) ||
                url.contains(".m3u8", true) || url.contains("master.txt", true)
            val isMpd = proto.contains("dash", true) || url.endsWith(".mpd", true)
            val label = when {
                height > 0 -> "${height}p"
                isM3u8 -> "HLS"
                isMpd -> "DASH"
                else -> "best"
            }
            out.putIfAbsent(
                url,
                StreamSource(
                    name = "yt-dlp $label",
                    url = url,
                    headers = mapOf("Referer" to pageUrl, "User-Agent" to Http.UA),
                    isM3u8 = isM3u8,
                    isMpd = isMpd,
                )
            )
        }
        if (out.isNotEmpty()) {
            val subs = mutableListOf<SubtitleSource>()
            val subArr = root.optJSONArray("subs")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val s = subArr.optJSONObject(i) ?: continue
                    if (s.optString("url").isNotBlank()) {
                        subs += SubtitleSource(s.optString("lang").ifBlank { "Sub" }, s.optString("url"))
                    }
                }
            }
            if (subs.isNotEmpty()) {
                val distinct = subs.distinctBy { it.url }
                out.values.forEach { src ->
                    out[src.url] = src.copy(subtitles = distinct)
                }
            }
        }
        return out.values.toList()
    }

    /**
     * yt-dlp in "simulate" mode: extract the page, DO NOT download, and dump
     * every direct stream URL ExoPlayer can consume, as JSON. Runs inside the
     * embedded Python via Chaquopy's eval() bridge. HIKARI_B64 is replaced with
     * the base64 page URL. The whole thing is one expression so eval() returns
     * the JSON string: `exec(...)` defines `out`, then `or out` yields it.
     */
    private const val EXTRACT_SCRIPT =
        """exec('import base64,json,yt_dlp\nU=base64.b64decode("HIKARI_B64").decode()\no={"quiet":True,"no_warnings":True,"noplaylist":True,"socket_timeout":20,"retries":2}\ni=yt_dlp.YoutubeDL(o).extract_info(U,download=False)\nR=[]\nS=set()\ndef A(u,h,e,p,n,v,a):\n if u and isinstance(u,str) and u not in S:\n  S.add(u)\n  R.append({"url":u,"height":h,"ext":e,"proto":p,"note":n,"vcodec":v,"acodec":a})\nA(i.get("url"),i.get("height"),i.get("ext"),i.get("protocol"),i.get("format_id"),i.get("vcodec"),i.get("acodec"))\nfor f in (i.get("formats") or []):\n if len(R) < 12:\n  A(f.get("url"),f.get("height"),f.get("ext"),f.get("protocol"),f.get("format_id"),f.get("vcodec"),f.get("acodec"))\nsubs=[]\nfor k,v in (i.get("subtitles") or {}).items():\n if isinstance(v,list) and v and isinstance(v[0],dict) and v[0].get("url"):\n  subs.append({"lang":k,"url":v[0]["url"]})\n elif isinstance(v,dict) and v.get("url"):\n  subs.append({"lang":k,"url":v["url"]})\nout=json.dumps({"title":i.get("title"),"streams":R,"subs":subs},default=str)') or out"""
}
