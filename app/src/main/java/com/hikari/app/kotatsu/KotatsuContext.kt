package com.hikari.app.kotatsu

import com.hikari.app.data.Logs
import com.hikari.app.net.CloudflareVerifier
import com.hikari.app.net.DohDns
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The [MangaLoaderContext] the bundled Kotatsu parsers run on — the same role
 * Kotatsu's own app plays for them, so the parsers' full extraction logic
 * (listing, search, details, chapters, pages, image descrambling, mirrors,
 * Cloudflare clearance) behaves exactly as it does there.
 *
 * Network rides the app's own hardened stack (Cloudflare solver + DoH, same as
 * the Zangetsu runtime); cookies live in a small in-memory jar per host;
 * `evaluateJs` runs through Rhino (already a dependency); image descrambling
 * uses real Android bitmaps.
 */
object KotatsuContext : MangaLoaderContext() {

    override val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .addInterceptor { chain -> CloudflareVerifier.intercept(chain) }
            .dns(DohDns)
            .build()
    }

    override val cookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty().filter { it.expiresAt > now }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val kept = (store[url.host].orEmpty() + cookies)
                .groupBy { it.name }
                .map { (_, v) -> v.maxByOrNull { it.expiresAt }!! }
            store[url.host] = kept.toMutableList()
        }
    }

    /** Parsers read their domain/mirror/user-agent here; v1 answers stock
     *  defaults (the parser's own primary domain), exactly as a fresh Kotatsu
     *  install does before the user touches settings. */
    override fun getConfig(source: MangaSource): MangaSourceConfig = DefaultConfig

    private object DefaultConfig : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    override fun getDefaultUserAgent(): String = Http.UA

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cx = org.mozilla.javascript.Context.enter()
                cx.optimizationLevel = -1
                try {
                    val scope = cx.initStandardObjects()
                    val result = cx.evaluateString(scope, script, baseUrl.ifBlank { "kotatsu" }, 1, null)
                    org.mozilla.javascript.Context.toString(result).takeIf { it != "undefined" }
                } finally {
                    org.mozilla.javascript.Context.exit()
                }
            }.getOrNull()
        }

    override fun createBitmap(width: Int, height: Int): Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            width.coerceAtLeast(1), height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        return AndroidBitmap(bmp)
    }

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        val body = response.body
        return runCatching {
            val bytes = body?.bytes() ?: return response
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return rebuild(response, bytes, body.contentType()?.toString())
            val out = (redraw(AndroidBitmap(decoded)) as? AndroidBitmap)?.bmp ?: decoded
            val bos = ByteArrayOutputStream(out.width.coerceAtMost(4096) * 4)
            (if (out.hasAlpha()) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG)
                .let { fmt -> out.compress(fmt, 92, bos) }
            rebuild(response, bos.toByteArray(), "image/png")
        }.getOrElse {
            Logs.log("Kotatsu", "redraw failed: ${it.message}")
            response
        }
    }

    private fun rebuild(response: Response, bytes: ByteArray, mime: String?): Response {
        val type = (mime ?: "image/png").toMediaTypeOrNull()
        return response.newBuilder().body(bytes.toResponseBody(type)).build()
    }

    /** [Bitmap] backed by a real Android bitmap, so `drawBitmap` region copies
     *  (the descramble step on the sites that fragment their pages) work. */
    class AndroidBitmap(val bmp: android.graphics.Bitmap) : Bitmap {
        override val width: Int get() = bmp.width
        override val height: Int get() = bmp.height
        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            val srcBmp = (sourceBitmap as? AndroidBitmap)?.bmp ?: return
            runCatching {
                val srcR = android.graphics.Rect(src.left, src.top, src.right, src.bottom)
                val dstR = android.graphics.Rect(dst.left, dst.top, dst.right, dst.bottom)
                if (srcR.width() <= 0 || srcR.height() <= 0 || dstR.width() <= 0 || dstR.height() <= 0) return
                android.graphics.Canvas(bmp).drawBitmap(srcBmp, srcR, dstR, null)
            }
        }
    }
}
