package com.hikari.desktop.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * TMDB HTTP layer (port of TmdbResolver.apiGet): personal key first, then the
 * built-in rotation. Keys are public in the Hikari repo source; desktop ships
 * from the same repo, so it reuses them with a user override in Extensions.
 */
object TmdbClient {

    const val API_BASE = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/w500"
    const val IMG_LARGE = "https://image.tmdb.org/t/p/w1280"

    private val BUILTIN_KEYS = listOf(
        "68e094699525b18a70bab2f86b1fa706",
        "439c478a771f35c05022f9feabcca01c",
    )

    fun builtinKey(): String = BUILTIN_KEYS.firstOrNull().orEmpty()

    private val gson = Gson()

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun apiGet(path: String, query: Map<String, String>, personalKey: String): JsonObject? {
        val keys = buildList {
            if (personalKey.isNotBlank()) add(personalKey)
            addAll(BUILTIN_KEYS)
        }.distinct()
        for (key in keys) {
            val params = LinkedHashMap(query)
            params["api_key"] = key
            val qs = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$API_BASE$path?$qs"
            val text = runCatching {
                val req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", EngineHttp.NUVIO_DEFAULT_UA)
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) null else resp.body()
            }.getOrNull() ?: continue
            val obj = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: continue
            if (obj.has("status_message") &&
                obj.get("status_message")?.asString?.contains("Invalid API key", true) == true
            ) continue
            return obj
        }
        return null
    }

    fun img(path: String?, large: Boolean = false): String? {
        if (path.isNullOrBlank()) return null
        return (if (large) IMG_LARGE else IMG) + path
    }

    data class Resolved(val tmdbId: String, val mediaType: String)

    /** Best-effort title -> TMDB id, mirroring TmdbResolver.resolve's movie-first order. */
    fun resolve(title: String, year: Int?, kind: EngineMediaKind, personalKey: String): Resolved? {
        if (title.isBlank()) return null
        val q = mapOf("query" to title, "include_adult" to "false", "page" to "1")
        val multi = apiGet("/search/multi", q, personalKey) ?: return null
        val results = multi.getAsJsonArray("results") ?: return null
        var fallback: Resolved? = null
        for (el in results) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val mt = o.get("media_type")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            if (mt != "movie" && mt != "tv") continue
            val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val date = o.get(if (mt == "movie") "release_date" else "first_air_date")
                ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val y = date.take(4).toIntOrNull()
            val yearOk = year == null || y == null || kotlin.math.abs(y - year) <= 1
            val tmdbKind = if (mt == "movie") "movie" else "tv"
            val wantTv = kind == EngineMediaKind.SERIES
            if ((tmdbKind == "tv") == wantTv && yearOk) {
                return Resolved(id, if (wantTv) "tv" else tmdbKind)
            }
            if (fallback == null) fallback = Resolved(id, tmdbKind)
        }
        return fallback
    }
}
