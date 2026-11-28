package com.hikari.desktop.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Blocking HTTP fetch for the JS extension engines (java.net.http, JDK built-in).
 * Returns the response envelope the harnesses expect:
 * `{"ok":true,"status":200,"statusText":"OK","url":"...","body":"...","bodyBase64":"...","headers":{...}}`.
 */
object EngineHttp {

    const val NUVIO_DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private const val MAX_TEXT_BYTES = 5 * 1024 * 1024
    private const val MAX_BINARY_BYTES = 5 * 1024 * 1024

    private val gson = Gson()

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    private val noRedirectClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun fetchJson(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
        userAgent: String = NUVIO_DEFAULT_UA,
    ): String {
        val out = JsonObject()
        try {
            val target = (if (followRedirects) client else noRedirectClient)
            val builder = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(60))
            var hasEncoding = false
            var hasUa = false
            runCatching {
                val parsed = gson.fromJson(headersJson.ifBlank { "{}" }, JsonObject::class.java)
                for ((k, v) in parsed.entrySet()) {
                    val value = if (v.isJsonPrimitive) v.asString else v.toString()
                    if (k.equals("accept-encoding", ignoreCase = true)) hasEncoding = true
                    if (k.equals("user-agent", ignoreCase = true)) hasUa = true
                    runCatching { builder.header(k, value) }
                }
            }
            // java.net.http never decodes gzip itself: force identity unless set.
            if (!hasEncoding) runCatching { builder.header("Accept-Encoding", "identity") }
            if (!hasUa) runCatching { builder.header("User-Agent", userAgent) }
            val m = method.uppercase()
            if (m == "GET" || m == "HEAD") {
                builder.method(m, HttpRequest.BodyPublishers.noBody())
            } else {
                val bytes = decodeBody(body)
                builder.method(m, HttpRequest.BodyPublishers.ofByteArray(bytes))
            }
            val resp = target.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            val raw = resp.body() ?: ByteArray(0)
            val headers = JsonObject()
            for ((k, vs) in resp.headers().map()) {
                if (k == null) continue
                runCatching { headers.addProperty(k.lowercase(), vs.joinToString(", ")) }
            }
            val contentType = resp.headers().firstValue("content-type").orElse("").lowercase()
            val isText = contentType.isBlank() ||
                contentType.contains("text/") ||
                contentType.contains("json") ||
                contentType.contains("javascript") ||
                contentType.contains("xml") ||
                contentType.contains("urlencoded") ||
                contentType.contains("html")
            out.addProperty("ok", resp.statusCode() in 200..299)
            out.addProperty("status", resp.statusCode())
            out.addProperty("statusText", reasonFor(resp.statusCode()))
            out.addProperty("url", resp.uri().toString())
            out.add("headers", headers)
            if (isText) {
                val text = runCatching {
                    String(if (raw.size > MAX_TEXT_BYTES) raw.copyOf(MAX_TEXT_BYTES) else raw, Charsets.UTF_8)
                }.getOrDefault("")
                out.addProperty("body", text)
                out.addProperty("bodyBase64", "")
            } else {
                val bytes = if (raw.size > MAX_BINARY_BYTES) raw.copyOf(MAX_BINARY_BYTES) else raw
                out.addProperty("body", "")
                out.addProperty("bodyBase64", Base64.getEncoder().encodeToString(bytes))
            }
        } catch (e: Exception) {
            out.addProperty("ok", false)
            out.addProperty("status", 0)
            out.addProperty("statusText", "network error")
            out.addProperty("url", url)
            out.add("headers", JsonObject())
            out.addProperty("body", "")
            out.addProperty("bodyBase64", "")
            out.addProperty("error", (e.message ?: "network error").take(300))
        }
        return gson.toJson(out)
    }

    /** The harness sends `b64:<base64>` when a provider POSTs binary bodies. */
    private fun decodeBody(body: String): ByteArray {
        if (body.startsWith("b64:")) {
            return runCatching { Base64.getDecoder().decode(body.removePrefix("b64:")) }.getOrDefault(ByteArray(0))
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    /** Plain-text GET for installers/manifests/M3U (best-effort, capped). */
    fun fetchText(url: String, maxBytes: Int = 8 * 1024 * 1024): String? {
        return runCatching {
            val req = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "*/*")
                .header("User-Agent", NUVIO_DEFAULT_UA)
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() !in 200..299) return null
            val raw = resp.body() ?: return null
            val bytes = if (raw.size > maxBytes) raw.copyOf(maxBytes) else raw
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        0 -> "network error"
        else -> "HTTP $status"
    }
}

/** Reads the bundled JS harness assets (downloaded at build time into resources). */
object EngineAssets {
    fun read(path: String): String {
        val stream = EngineAssets::class.java.getResourceAsStream("/hikari-js/$path")
            ?: return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).readText() }.getOrDefault("")
    }
}
