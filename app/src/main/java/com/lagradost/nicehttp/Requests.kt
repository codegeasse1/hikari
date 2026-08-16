package com.lagradost.nicehttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal reimplementation of the nicehttp library's Requests class.
 * The parameter order and types exactly match what the compiled CloudStream
 * plugins link against (see their dex references) — the plugin call sites
 * resolve against the generated `get$default` / `post$default` bridges.
 */
class Requests {

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .cookieJar(MemoryCookieJar())
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", DEFAULT_UA)
            chain.proceed(builder.build())
        }
        .build()

    @Suppress("UNUSED_PARAMETER")
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        useExtractor: Boolean = true,
        period: Int = 0,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        periodLong: Long = 0,
        interceptor: Interceptor? = null,
        doReferer: Boolean = false,
        responseParser: ResponseParser? = null,
    ): NiceResponse = execute(
        "GET", url, headers, referer, cookies, params, null, null, null, true, interceptor
    )

    @Suppress("UNUSED_PARAMETER")
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        useExtractor: Boolean = true,
        period: Int = 0,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        periodLong: Long = 0,
        interceptor: Interceptor? = null,
        doReferer: Boolean = false,
        responseParser: ResponseParser? = null,
    ): NiceResponse = execute(
        "HEAD", url, headers, referer, cookies, params, null, null, null, true, interceptor
    )

    @Suppress("UNUSED_PARAMETER")
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        multipart: List<Any> = emptyList(),
        file: Any? = null,
        requestBody: RequestBody? = null,
        isJson: Boolean = true,
        period: Int = 0,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        periodLong: Long = 0,
        interceptor: Interceptor? = null,
        doReferer: Boolean = false,
        responseParser: ResponseParser? = null,
    ): NiceResponse = execute(
        "POST", url, headers, referer, cookies, params, data, multipart, requestBody, isJson, interceptor
    )

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        referer: String?,
        cookies: Map<String, String>,
        params: Map<String, String>,
        data: Map<String, String>?,
        multipart: List<Any>?,
        requestBody: RequestBody?,
        isJson: Boolean,
        interceptor: Interceptor?,
    ): NiceResponse = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = url.toHttpUrlOrNull()?.newBuilder()
            params.forEach { (k, v) -> urlBuilder?.addQueryParameter(k, v) }
            val finalUrl = urlBuilder?.build()?.toString() ?: url

            val builder = Request.Builder().url(finalUrl)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (referer != null) builder.header("Referer", referer)
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }

            val body: RequestBody? = when {
                requestBody != null -> requestBody
                method == "POST" && isJson -> JSONObject(data ?: emptyMap()).toString()
                    .toRequestBody("application/json".toMediaType())
                method == "POST" -> buildFormBody(data ?: emptyMap())
                else -> null
            }
            if (body != null) builder.method(method, body) else builder.method(method, null)

            val request = builder.build()
            val active = if (interceptor != null) client.newBuilder().addInterceptor(interceptor).build() else client
            active.newCall(request).execute().use { r -> NiceResponse.from(r) }
        } catch (e: Exception) {
            NiceResponse.failure(0, e.message ?: "Request failed", url)
        }
    }

    private fun buildFormBody(data: Map<String, String>): RequestBody {
        val form = okhttp3.FormBody.Builder()
        data.forEach { (k, v) -> form.add(k, v) }
        return form.build()
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/** Simple in-memory per-host cookie jar so plugin login/cookie flows keep working across requests. */
class MemoryCookieJar : CookieJar {
    private val jar = HashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        jar[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cached = jar[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return cached.filter { it.expiresAt > now }
    }
}

class ResponseParser(val parse: (NiceResponse) -> Any? = { null })
