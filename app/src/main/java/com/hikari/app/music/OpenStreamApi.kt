package com.hikari.app.music

import com.hikari.app.data.Logs
import com.hikari.app.net.DohDns
import com.hikari.app.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Last-resort audio source built on the open-source YouTube front-ends Piped and Invidious.
 * They give back an address on their own proxy servers, so it needs neither a PO token nor
 * the device's own YouTube lookup. Used only after Qobuz / JioSaavn and YouTube have failed.
 */
object OpenStreamApi {
    private const val TAG = "HikariOpenStream"
    private const val TOTAL_WAIT_MS = 12_000L
    private const val HOSTS_TTL_MS = 30L * 60L * 1000L

    private val pipedFallback = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
    )

    private val invidiousFallback = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
    )

    @Volatile
    private var hostsAt = 0L

    @Volatile
    private var pipedHosts: List<String> = pipedFallback

    @Volatile
    private var invidiousHosts: List<String> = invidiousFallback

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .dns(DohDns)
            .build()
    }

    private fun getText(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /** Fresh lists of working mirrors, fetched in the background so a song never waits for them. */
    private fun refreshHosts() {
        val now = System.currentTimeMillis()
        if (now - hostsAt < HOSTS_TTL_MS) return
        hostsAt = now
        val piped = livePiped()
        if (piped.isNotEmpty()) pipedHosts = (piped.take(6) + pipedFallback).distinct()
        val inv = liveInvidious()
        if (inv.isNotEmpty()) invidiousHosts = (inv.take(5) + invidiousFallback).distinct()
    }

    private fun livePiped(): List<String> {
        val text = getText("https://piped-instances.kavin.rocks/") ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val api = o.optString("api_url").trim().trimEnd('/')
            if (api.startsWith("https://")) out.add(api)
        }
        return out
    }

    private fun liveInvidious(): List<String> {
        val text = getText("https://api.invidious.io/instances.json?sort_type=health") ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            val info = pair.optJSONObject(1) ?: continue
            if (!info.optBoolean("api", false)) continue
            val uri = info.optString("uri").trim().trimEnd('/')
            if (uri.startsWith("https://")) out.add(uri)
        }
        return out
    }

    /** Best audio address for a YouTube video id, or null when no mirror could give one. */
    fun resolveBlocking(id: String): String? {
        kotlin.concurrent.thread(start = true, isDaemon = true, name = "open-hosts") { refreshHosts() }
        val hosts = ArrayList<Pair<String, Boolean>>()
        for (h in pipedHosts.take(6)) hosts.add(Pair(h, true))
        for (h in invidiousHosts.take(4)) hosts.add(Pair(h, false))

        val results = LinkedBlockingQueue<String>()
        val done = AtomicBoolean(false)
        val finished = AtomicInteger(0)
        for (entry in hosts) {
            val host = entry.first
            val isPiped = entry.second
            kotlin.concurrent.thread(start = true, isDaemon = true, name = "open-stream") {
                try {
                    if (!done.get()) {
                        val u = if (isPiped) pipedUrl(host, id) else invidiousUrl(host, id)
                        if (u != null && !done.get() && probe(u)) results.add(u)
                    }
                } catch (e: Throwable) {
                    // this mirror is down, the others carry on
                } finally {
                    finished.incrementAndGet()
                }
            }
        }

        var found: String? = null
        val deadline = System.currentTimeMillis() + TOTAL_WAIT_MS
        try {
            while (found == null && System.currentTimeMillis() < deadline) {
                found = results.poll(300, TimeUnit.MILLISECONDS)
                if (found == null && finished.get() >= hosts.size) break
            }
        } catch (e: InterruptedException) {
            done.set(true)
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("stream lookup cancelled")
        }
        done.set(true)
        if (found == null) found = results.poll()
        if (found == null) {
            Logs.log(TAG, "no open mirror gave a stream for $id")
        } else {
            Logs.log(TAG, "open mirror stream ready for $id")
        }
        return found
    }

    private fun pipedUrl(host: String, id: String): String? {
        val text = getText("$host/streams/$id") ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("audioStreams") ?: return null
        return bestAudio(arr, "mimeType")
    }

    private fun invidiousUrl(host: String, id: String): String? {
        val text = getText("$host/api/v1/videos/$id?local=true&fields=adaptiveFormats") ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("adaptiveFormats") ?: return null
        val u = bestAudio(arr, "type") ?: return null
        return if (u.startsWith("http")) u else host + u
    }

    /** Highest-bitrate AAC (audio/mp4) first, then the highest-bitrate audio of any other kind. */
    private fun bestAudio(arr: JSONArray, typeKey: String): String? {
        var aac: String? = null
        var aacRate = -1L
        var other: String? = null
        var otherRate = -1L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            val type = o.optString(typeKey).lowercase()
            if (type.isNotEmpty() && !type.startsWith("audio")) continue
            val rate = o.optString("bitrate").toLongOrNull() ?: 0L
            if (type.startsWith("audio/mp4")) {
                if (rate > aacRate) {
                    aacRate = rate
                    aac = u
                }
            } else if (rate > otherRate) {
                otherRate = rate
                other = u
            }
        }
        return aac ?: other
    }

    private fun probe(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Range", "bytes=0-1")
            .build()
        client.newCall(req).execute().use { r ->
            val type = r.header("Content-Type").orEmpty().lowercase()
            r.isSuccessful && !type.startsWith("text/") && !type.contains("json") && !type.contains("html")
        }
    } catch (e: Exception) {
        false
    }
}
