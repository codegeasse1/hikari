package com.hikari.app.cs3

import android.util.Base64
import android.util.Log
import com.chaquo.python.PyObject
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
 * the library's download API (which returns only an exit code) - it drives the
 * bundled yt_dlp module directly through the Chaquopy Java bridge, running
 * yt-dlp in "simulate" mode (extract_info(download=False)) and reading back the
 * direct stream URLs as JSON.
 *
 * Chaquopy 17 has no Python.eval(); instead we exec our extractor script into a
 * fresh globals dict and then call the resulting Python function per URL.
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
     *  init crash, ...). Shown to the user in the player error panel. */
    @Volatile
    var initFailure: String? = null
        private set

    private val initMutex = Mutex()
    private var initAttempted = false

    /** Python callable _extract(url, opts_b64) -> json str, defined by
     *  [EXTRACT_SCRIPT] once Python is started. */
    @Volatile
    private var extractFn: PyObject? = null

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
                val builtins = Python.getInstance().getBuiltins()
                val globals = builtins.get("dict")!!.call()
                builtins.get("exec")!!.call(EXTRACT_SCRIPT, globals)
                extractFn = globals.callAttr("get", "_extract")
                if (extractFn == null) {
                    available = false
                    initFailure = "yt-dlp extractor script failed to load"
                    return available
                }
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
            val fn = extractFn
            if (fn == null) {
                Log.e(TAG, "yt-dlp extract function not ready")
                return@withContext emptyList()
            }
            try {
                // Referer + UA give yt-dlp the same context the plugin had,
                // which many sites require even for the manifest request.
                val opts = JSONObject()
                    .put("Referer", pageUrl)
                    .put("User-Agent", Http.UA)
                    .toString()
                val optsB64 = Base64.encodeToString(opts.toByteArray(), Base64.NO_WRAP)
                val json = fn.call(pageUrl, optsB64).toJava(String::class.java)
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
     * Python extractor defined ONCE into a fresh globals dict at init (via the
     * builtins exec), then called per URL. Returns a JSON object
     * {"streams": [...], "subs": [...]} that [parse] consumes.
     */
    private const val EXTRACT_SCRIPT = """import json, base64

def _extract(url, opts_b64):
    import yt_dlp
    hdrs = json.loads(base64.b64decode(opts_b64).decode('utf-8'))
    o = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'socket_timeout': 20,
        'retries': 2,
        'http_headers': hdrs,
    }
    try:
        i = yt_dlp.YoutubeDL(o).extract_info(url, download=False)
    except Exception as e:
        return json.dumps({'streams': [], 'subs': [], 'error': str(e)}, default=str)
    if i is None:
        return json.dumps({'streams': [], 'subs': []})
    R = []
    S = set()
    def A(f):
        u = f.get('url')
        if u and isinstance(u, str) and u not in S:
            S.add(u)
            R.append({
                'url': u,
                'height': f.get('height'),
                'ext': f.get('ext'),
                'proto': f.get('protocol'),
                'note': f.get('format_note'),
            })
    A(i)
    for f in (i.get('formats') or []):
        if len(R) < 12:
            A(f)
    subs = []
    for k, v in (i.get('subtitles') or {}).items():
        if isinstance(v, list) and v and isinstance(v[0], dict) and v[0].get('url'):
            subs.append({'lang': k, 'url': v[0]['url']})
        elif isinstance(v, dict) and v.get('url'):
            subs.append({'lang': k, 'url': v['url']})
    return json.dumps({'title': i.get('title'), 'streams': R, 'subs': subs}, default=str)"""
}
