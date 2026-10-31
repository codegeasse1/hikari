package com.hikari.app.kotatsu.fork

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
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.bitmap.Rect
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentParserSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The [ContentLoaderContext] the skepsun (kototoro) parsers run on — same role
 * [com.hikari.app.kotatsu.KotatsuContext] plays for the official parsers.
 *
 * The skepsun fork is an independent library (`org.skepsun.kototoro.parsers`,
 * no shared classes with the official one) shipping manga/manhwa/novel plus
 * video sources (anime clips, hentai video) whose playback the parsers expose
 * as pages. Network rides the hardened stack, JS runs through Rhino and image
 * descrambling uses real Android bitmaps.
 */
object ForkContentContext : ContentLoaderContext() {

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

	/**
	 * Instantiates the parser for [source]. The fork's KSP factory
	 * (`ContentParserFactoryKt.newParser`, internal) is reached reflectively so
	 * this survives its visibility; a direct `source.newParser` is tried first
	 * in case the published artifact exposes it.
	 */
	override fun newParserInstance(source: ContentSource): ContentParser {
		(source as? ContentParserSource)?.let { enumSrc ->
			runCatching {
				val m = enumSrc.javaClass.getMethod("newParser", ContentLoaderContext::class.java)
				@Suppress("unchecked_cast")
				m.invoke(enumSrc, this) as? ContentParser
			}.getOrNull()?.let { return it }
		}
		val factory = runCatching {
			Class.forName("org.skepsun.kototoro.parsers.ContentParserFactoryKt")
		}.getOrNull() ?: throw IllegalStateException("Skepsun factory missing")
		val m = factory.declaredMethods.firstOrNull {
			it.name == "newParser" && it.parameterTypes.size == 2
		} ?: throw IllegalStateException("Skepsun newParser missing")
		m.isAccessible = true
		@Suppress("unchecked_cast")
		return (if (m.parameterTypes[0].isAssignableFrom(ContentParserSource::class.java)) {
			m.invoke(null, source, this)
		} else {
			m.invoke(null, this, source)
		}) as? ContentParser ?: throw IllegalStateException("Skepsun parser null for $source")
	}

	override fun newLinkResolver(link: HttpUrl): org.skepsun.kototoro.parsers.util.LinkResolver =
		object : org.skepsun.kototoro.parsers.util.LinkResolver {
			override val link: HttpUrl = link
			override suspend fun getSource(): org.skepsun.kototoro.parsers.model.ContentSource? = null
			override suspend fun getContent(): org.skepsun.kototoro.parsers.model.Content? = null
		}

	override fun getConfig(source: ContentSource): ContentSourceConfig = DefaultConfig

	private object DefaultConfig : ContentSourceConfig {
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
					val result = cx.evaluateString(scope, script, baseUrl.ifBlank { "kototoro" }, 1, null)
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
			Logs.log("Fork", "redraw failed: ${it.message}")
			response
		}
	}

	private fun rebuild(response: Response, bytes: ByteArray, mime: String?): Response {
		val type = (mime ?: "image/png").toMediaTypeOrNull()
		return response.newBuilder().body(bytes.toResponseBody(type)).build()
	}

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
