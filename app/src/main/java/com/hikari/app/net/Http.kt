package com.hikari.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private lateinit var client: OkHttpClient

    fun init() {
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }

    fun getStringStrict(url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        try {
            get(url, headers).use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    private val GITHUB_RAW =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

    /**
     * URL + a jsDelivr CDN mirror (global CDN, reachable where GitHub raw often isn't),
     * each retried once. Returns the first success or the last failure.
     */
    private fun urlVariants(url: String): List<String> {
        val variants = mutableListOf(url)
        GITHUB_RAW.matchEntire(url)?.let { m ->
            val user = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3]
            val path = m.groupValues[4]
            variants.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }
        return variants
    }

    fun fetchStringRobust(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var last: Exception = Exception("Failed to fetch $url")
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val r = getStringStrict(u, headers)
                if (r.isSuccess) return r
                r.exceptionOrNull()?.let { last = it }
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return Result.failure(last)
    }

    fun fetchBytesRobust(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val b = getBytes(u, headers)
                if (b != null) return b
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return null
    }
}
