package com.hikari.app.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp DNS resolver: the phone's resolver, a DNS-over-HTTPS fallback, and the
 * resolver the user picks in Settings → Network and Internet → DNS mode.
 *
 * Two jobs share this class because they are the same network path.
 *
 * **Fallback.** Some devices/ISPs hand out a resolver that refuses or silently
 * swallows the domains a few of the extension repos live on (the Eclipsia Nuvio
 * repo at `plugin.eclipsia.dpdns.org` is the common one — the host itself is
 * fine and answers 200 from any public resolver, but the phone's own DNS answers
 * with "No address associated with hostname"). When the platform resolver throws
 * [UnknownHostException] we re-ask over HTTPS, whose query goes to a hard-coded
 * IP literal, so it needs no working DNS of its own. That chain still runs, and
 * still ends every lookup, whatever the user picked.
 *
 * **DNS mode.** With an explicit provider chosen, that provider answers *first*
 * — that is the whole point of choosing one (AdGuard and CleanBrowsing exist to
 * withhold addresses). Because it has the final say, a refusal from it is
 * honoured rather than papered over by the fallback: an answer that carries no
 * address means the name did not resolve. It only steps aside when it could not
 * be reached at all, and then only for a minute, so a bad or offline address
 * costs one slow lookup rather than one on every request. A chosen resolver is
 * asked over DNS-over-HTTPS when it offers one, or over plain port-53 UDP when
 * it doesn't (see [DnsProviders]).
 *
 * Successes are cached (the platform already caches well, but the DoH path is a
 * full round-trip, so a short in-process cache keeps repeated loads snappy).
 */
object DohDns : Dns {

    private const val TTL_MS = 5 * 60 * 1000L

    /** How long "the provider says this name has no address" is remembered —
     *  long enough to stop a blocked name being re-asked on every request,
     *  short enough that un-blocking it upstream takes effect while the user is
     *  still in the app. */
    private const val NEGATIVE_TTL_MS = 60 * 1000L

    /** How long a provider that failed at the transport level is skipped. */
    private const val DOWN_MS = 60 * 1000L

    /** How long a plain-DNS server gets to answer one datagram. */
    private const val PLAIN_TIMEOUT_MS = 4000

    /** JSON DoH endpoints, reached BY IP so resolving them needs no DNS.
     *  `application/dns-json` is the Cloudflare/Google shared JSON format — the
     *  built-in fallback, and the way a provider that has no bootstrap address
     *  of its own gets found when the phone's resolver is the broken one. */
    private val jsonEndpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long, val refused: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    @Volatile
    private var providerDownUntil = 0L

    /** What the last lookup saw the setting as, so a change made anywhere clears
     *  the caches without this class having to be wired into the change. */
    @Volatile
    private var providerStamp = ""

    init {
        NetTuning.onChange { invalidate() }
    }

    /** Forget every cached name and client — the setting changed. */
    fun invalidate() {
        cache.clear()
        clients.clear()
        providerDownUntil = 0L
        providerStamp = stampOf(NetTuning.activeDns())
    }

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

    /**
     * Asks the chosen provider whether it answers at all, for the "Test" line in
     * Settings. Returns null when it works, else a short reason. Never throws.
     */
    fun probe(provider: DnsProvider?): String? {
        val target = "example.com"
        if (provider == null) {
            return try {
                if (Dns.SYSTEM.lookup(target).isNotEmpty()) null else "The phone returned no address."
            } catch (e: Exception) {
                "The phone couldn't resolve it (${cause(e)})."
            }
        }
        if (!provider.usable) return "No address given."
        return try {
            if (providerLookup(provider, target).isNotEmpty()) null
            else "$target came back without an address — the server refused the query."
        } catch (e: Exception) {
            "Didn't answer — ${cause(e)}. Check the address."
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase()
        // IP literals and single-label names (localhost, a LAN host, …) are the
        // platform resolver's business — there is nothing for DoH to add.
        if (host.isEmpty() || v4.matches(host) || v6.matches(host) || !host.contains('.')) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val fresh = currentProvider()
        cache[host]?.let {
            if (it.expiresAt > System.currentTimeMillis()) {
                if (it.refused) throw unknown(hostname)
                return it.addresses
            }
        }

        if (fresh != null && System.currentTimeMillis() >= providerDownUntil) {
            val viaProvider = try {
                providerLookup(fresh, host)
            } catch (e: Exception) {
                // Unreachable (not a refusal): stand aside for a minute so a bad
                // address costs one slow lookup instead of every lookup.
                providerDownUntil = System.currentTimeMillis() + DOWN_MS
                null
            }
            if (viaProvider != null) {
                if (viaProvider.isEmpty()) {
                    cache[host] = Entry(emptyList(), System.currentTimeMillis() + NEGATIVE_TTL_MS, true)
                    throw unknown(hostname)
                }
                cache[host] = Entry(viaProvider, System.currentTimeMillis() + TTL_MS, false)
                return viaProvider
            }
        }

        try {
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (resolved.isNotEmpty()) {
                cache[host] = Entry(resolved, System.currentTimeMillis() + TTL_MS, false)
                return resolved
            }
        } catch (e: UnknownHostException) {
            // fall through to DoH
        }

        val viaJson = tryJson(host)
        if (viaJson.isNotEmpty()) {
            cache[host] = Entry(viaJson, System.currentTimeMillis() + TTL_MS, false)
            return viaJson
        }
        throw unknown(hostname)
    }

    private fun currentProvider(): DnsProvider? {
        val chosen = NetTuning.activeDns()
        val stamp = stampOf(chosen)
        if (stamp != providerStamp) {
            providerStamp = stamp
            cache.clear()
            clients.clear()
            providerDownUntil = 0L
        }
        return chosen
    }

    private fun stampOf(chosen: DnsProvider?): String =
        "${NetTuning.dnsProvider}|${NetTuning.customDns}|${chosen?.url.orEmpty()}"

    private fun unknown(hostname: String) =
        UnknownHostException("Unable to resolve host \"$hostname\": no address")

    /** A short, human-sized cause for the Settings test line. */
    private fun cause(e: Throwable): String = when (e) {
        is UnknownHostException -> "its own hostname wouldn't resolve"
        is java.net.SocketTimeoutException -> "it timed out"
        is java.io.InterruptedIOException -> "it timed out"
        is javax.net.ssl.SSLException -> "the secure connection failed"
        is IOException -> "the network refused the request"
        else -> e.javaClass.simpleName
    }

    /**
     * The answer the chosen provider gives for [host] — its A and AAAA records
     * merged, over whichever transport it offers. Throws when the provider could
     * not be reached or answered with something that isn't a DNS message; an
     * empty list means it answered and the name has no address.
     */
    private fun providerLookup(provider: DnsProvider, host: String): List<InetAddress> =
        if (provider.url.isNotEmpty()) dohLookup(provider, host)
        else plainLookup(provider, host)

    private fun dohLookup(provider: DnsProvider, host: String): List<InetAddress> {
        val client = clientFor(provider)
        val found = LinkedHashMap<String, InetAddress>()
        var answered = false
        var failure: Exception? = null
        for (type in listOf(1, 28)) {
            val body = try {
                wireQuery(client, provider, host, type)
            } catch (e: Exception) {
                failure = e
                continue
            }
            answered = true
            for (addr in DnsWire.parseAnswers(body)) {
                found[addr.hostAddress ?: addr.toString()] = addr
            }
        }
        if (!answered) throw failure ?: IOException("no query went out")
        return found.values.toList()
    }

    private fun wireQuery(client: OkHttpClient, provider: DnsProvider, host: String, type: Int): ByteArray {
        val separator = if (provider.url.contains('?')) '&' else '?'
        val url = provider.url + separator + "dns=" + DnsWire.queryUrlSafe(host, type)
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("accept", "application/dns-message")
                .build()
        ).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("provider answered ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("provider answered with no body")
        }
    }

    /**
     * The same question asked the old way: one UDP datagram to port 53. Used for
     * the resolvers that never added an encrypted service. If a server answers
     * with a TRUNCATED reply and nothing usable in it, that counts as a failure
     * rather than a refusal, so the lookup falls back to the phone instead of
     * reporting the name as dead.
     */
    private fun plainLookup(provider: DnsProvider, host: String): List<InetAddress> {
        val found = LinkedHashMap<String, InetAddress>()
        var answered = false
        var failure: Exception? = null
        for (server in provider.plain) {
            var serverAnswered = false
            for (type in listOf(1, 28)) {
                val body = try {
                    udpQuery(server, host, type)
                } catch (e: Exception) {
                    failure = e
                    continue
                }
                if (DnsWire.isTruncated(body) && DnsWire.parseAnswers(body).isEmpty()) {
                    failure = IOException("$server truncated the reply")
                    continue
                }
                serverAnswered = true
                for (addr in DnsWire.parseAnswers(body)) {
                    found[addr.hostAddress ?: addr.toString()] = addr
                }
            }
            if (serverAnswered) {
                answered = true
                if (found.isNotEmpty()) break
            }
        }
        if (!answered) throw failure ?: IOException("no server answered")
        return found.values.toList()
    }

    private fun udpQuery(server: String, host: String, type: Int): ByteArray {
        val query = DnsWire.query(host, type)
        val socket = java.net.DatagramSocket()
        try {
            socket.soTimeout = PLAIN_TIMEOUT_MS
            socket.send(
                java.net.DatagramPacket(
                    query, query.size, java.net.InetAddress.getByName(server), 53
                )
            )
            val buffer = ByteArray(1400)
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return buffer.copyOf(packet.length)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** One client per provider, since the resolver it dials and the addresses it
     *  dials through are fixed at build time. */
    private fun clientFor(provider: DnsProvider): OkHttpClient =
        clients.getOrPut(provider.key + "|" + provider.url) { buildClient(provider) }

    private fun buildClient(provider: DnsProvider): OkHttpClient {
        val pinned = provider.bootstrap.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val endpointHost = runCatching { provider.url.toHttpUrl().host }.getOrNull()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // The provider's own host, when we know its addresses: this is
                // what makes the feature work at all on a phone whose resolver
                // is the broken thing.
                if (pinned.isNotEmpty() && endpointHost != null &&
                    hostname.equals(endpointHost, ignoreCase = true) && !v4.matches(hostname)
                ) {
                    return pinned
                }
                if (v4.matches(hostname) || v6.matches(hostname) || !hostname.contains('.')) {
                    return Dns.SYSTEM.lookup(hostname)
                }
                return resolveHost(hostname)
            }
        }
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** The phone first, then the endpoints we can reach with no DNS at all — so
     *  resolving a provider's own host also survives a dead platform resolver. */
    private fun resolveHost(host: String): List<InetAddress> {
        runCatching { Dns.SYSTEM.lookup(host) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val viaJson = tryJson(host)
        if (viaJson.isNotEmpty()) return viaJson
        throw UnknownHostException("Unable to resolve host \"$host\"")
    }

    private fun tryJson(host: String): List<InetAddress> {
        val name = URLEncoder.encode(host, "UTF-8")
        for (base in jsonEndpoints) {
            val found = LinkedHashMap<String, InetAddress>()
            for (type in listOf("A", "AAAA")) {
                for (addr in jsonQuery("$base?name=$name&type=$type")) {
                    found[addr.hostAddress ?: addr.toString()] = addr
                }
            }
            if (found.isNotEmpty()) return found.values.toList()
        }
        return emptyList()
    }

    private fun jsonQuery(url: String): List<InetAddress> {
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
