package com.hikari.app.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp DNS resolver with a DNS-over-HTTPS fallback.
 *
 * Some devices/ISPs hand out a resolver that refuses or silently swallows the
 * domains a few of the extension repos live on (the Eclipsia Nuvio repo at
 * `plugin.eclipsia.dpdns.org` is the common one — the host itself is fine and
 * answers 200 from any public resolver, but the phone's own DNS answers with
 * "No address associated with hostname"). When the platform resolver throws
 * [UnknownHostException] we re-ask over HTTPS, whose query goes to a hard-coded
 * IP literal, so it needs no working DNS of its own.
 *
 * Successes are cached (the platform already caches well, but the DoH path is a
 * full round-trip, so a short in-process cache keeps repeated loads snappy).
 */
object DohDns : Dns {

    private const val TTL_MS = 5 * 60 * 1000L

    /** JSON DoH endpoints, reached BY IP so resolving them needs no DNS.
     *  `application/dns-json` is the Cloudflare/Google shared JSON format. */
    private val endpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Dedicated, bare client (no interceptors) so the DoH request can never
     *  recurse into the interceptor chain or back into this resolver. */
    private val doh: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val v6 = Regex("^[0-9a-fA-F:]{2,45}$")

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase()
        // IP literals and single-label names (localhost, a LAN host, …) are the
        // platform resolver's business — there is nothing for DoH to add.
        if (host.isEmpty() || v4.matches(host) || v6.matches(host) || !host.contains('.')) {
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[host]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.addresses }
        try {
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (resolved.isNotEmpty()) {
                cache[host] = Entry(resolved, System.currentTimeMillis() + TTL_MS)
                return resolved
            }
        } catch (e: UnknownHostException) {
            // fall through to DoH
        }
        val viaDoh = tryDoh(host)
        if (viaDoh.isNotEmpty()) {
            cache[host] = Entry(viaDoh, System.currentTimeMillis() + TTL_MS)
            return viaDoh
        }
        throw UnknownHostException("Unable to resolve host \"$hostname\": no address")
    }

    private fun tryDoh(host: String): List<InetAddress> {
        val name = URLEncoder.encode(host, "UTF-8")
        for (base in endpoints) {
            val found = LinkedHashMap<String, InetAddress>()
            for (type in listOf("A", "AAAA")) {
                for (addr in query("$base?name=$name&type=$type")) {
                    found[addr.hostAddress ?: addr.toString()] = addr
                }
            }
            if (found.isNotEmpty()) return found.values.toList()
        }
        return emptyList()
    }

    private fun query(url: String): List<InetAddress> {
        val response = try {
            doh.newCall(
                Request.Builder()
                    .url(url)
                    .header("accept", "application/dns-json")
                    .build()
            ).execute()
        } catch (e: Exception) {
            return emptyList()
        }
        return response.use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = try {
                resp.body?.string()
            } catch (e: Exception) {
                null
            } ?: return emptyList()
            parse(body)
        }
    }

    private fun parse(json: String): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return out
        }
        if (root.optInt("Status", 0) != 0) return out
        val answers = root.optJSONArray("Answer") ?: return out
        for (i in 0 until answers.length()) {
            val answer = answers.optJSONObject(i) ?: continue
            val type = answer.optInt("type", -1)
            if (type != 1 && type != 28) continue
            val data = answer.optString("data", "").trim()
            if (!(v4.matches(data) || v6.matches(data))) continue
            // Both forms are numeric literals, so getByName parses them without
            // ever touching DNS (the platform resolver may be the broken part).
            runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }
}
