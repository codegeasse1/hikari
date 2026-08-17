package com.hikari.app.net

import android.content.Context
import java.io.File

/**
 * Hosts-file ad blocker for the WebView tab (sites opened from the Extensions
 * screen). NEVER applied to the video player — ExoPlayer does its own network
 * I/O and is deliberately untouched by this engine, so blocking a CDN domain
 * can never break playback.
 *
 * Supports any hosts file in the classic format (StevenBlack, AdAway, yoyo):
 *   0.0.0.0 ad.doubleclick.net
 *   127.0.0.1 example.com        # comment
 *   plain-domain.example.com
 * plus plain one-domain-per-line lists. Downloads are cached under
 * filesDir/adblock/ so the blocklist works offline; "Update lists" in
 * Settings refreshes them.
 */
object AdBlocker {

    data class HostList(val name: String, val url: String)

    val PRESETS = listOf(
        HostList("StevenBlack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        HostList("AdAway", "https://adaway.org/hosts.txt"),
        HostList("yoyo.org", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"),
    )

    /** Built-in defaults — base protection even with no lists configured. */
    val BUILTIN = listOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "google-analytics.com", "adservice.google.com", "2mdn.net", "adf.ly",
        "taboola.com", "outbrain.com", "adnxs.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "moatads.com", "quantserve.com", "scorecardresearch.com", "amazon-adsystem.com", "adform.net",
        "smartadserver.com", "openx.net", "pubmatic.com", "sovrn.com", "casalemedia.com", "lijit.com",
        "adsafeprotected.com", "popads.net", "popunder.ru", "adsterra.com", "propellerads.com",
        "adroll.com", "media.net", "yieldmo.com", "adcolony.com", "inmobi.com", "admarvel.com",
        "flurry.com", "startappservice.com", "smartyads.com", "admob.com", "applovin.com",
        "vungle.com", "chartboost.com", "mopub.com", "supersonicads.com", "revcontent.com",
        "adpushup.com", "snigelweb.com", "adthrive.com", "ezoic.net", "mediavine.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com",
    )

    fun cacheDir(context: Context): File =
        File(context.filesDir, "adblock").apply { mkdirs() }

    fun cacheFile(context: Context, url: String): File =
        File(cacheDir(context), url.hashCode().toLong().toString(16) + ".txt")

    /** Parses a hosts file into a set of lowercase domains, ignoring comments,
     *  blank lines, IPs and localhost entries. */
    fun parseHosts(text: String): Set<String> {
        val out = HashSet<String>()
        for (line in text.lineSequence()) {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) continue
            val parts = clean.split(Regex("\\s+"))
            val host = when {
                isIp(parts[0]) -> if (parts.size > 1) parts[1] else null
                else -> parts[0]
            } ?: continue
            val h = host.trim().trimEnd('.').lowercase()
            if (h.isEmpty() || h.length > 253 || !h.contains('.')) continue
            if (h.startsWith("localhost") || h.endsWith(".local") || h.endsWith(".localdomain")) continue
            if (h == "broadcasthost" || h == "ip6-localhost" || h == "ip6-loopback") continue
            if (h.all { it.isLetterOrDigit() || it == '.' || it == '-' }) out.add(h)
        }
        return out
    }

    private fun isIp(s: String): Boolean = when (s) {
        "0.0.0.0", "127.0.0.1", "::1", "255.255.255.255", "::ffff:0:0", "::0", "0.0.0.0,", "::" -> true
        else -> s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ") || s.startsWith("::ffff:")
    }

    /** Downloads [url], caches the raw text and returns the parsed domains. */
    suspend fun download(url: String, context: Context): Set<String> {
        val text = Http.fetchStringRobust(url).getOrDefault("")
        if (text.isBlank()) return emptySet()
        val domains = parseHosts(text)
        if (domains.isNotEmpty()) {
            runCatching { cacheFile(context, url).writeText(text) }
        }
        return domains
    }

    /** Reads the cached domains for [url] without touching the network. */
    fun loadCached(url: String, context: Context): Set<String> {
        val f = cacheFile(context, url)
        if (!f.exists()) return emptySet()
        return runCatching { parseHosts(f.readText()) }.getOrDefault(emptySet())
    }

    /** (re)downloads every configured list, returning the combined domain set.
     *  A single failing list is skipped, never fatal. */
    suspend fun refreshAll(lists: List<HostList>, context: Context): Set<String> {
        val all = HashSet<String>()
        for (l in lists) {
            runCatching { download(l.url, context) }.getOrDefault(emptySet()).let { all.addAll(it) }
        }
        return all
    }

    /**
     * Final blocked set for the WebView: cached list domains + built-ins +
     * manual blocklist, minus anything on the whitelist. Returns
     * (blockedDomains, whitelistDomains); check the whitelist FIRST.
     */
    fun resolve(
        context: Context,
        lists: List<HostList>,
        manualBlock: List<String>,
        manualWhite: List<String>,
    ): Pair<Set<String>, Set<String>> {
        val blocked = HashSet<String>()
        for (l in lists) blocked.addAll(loadCached(l.url, context))
        blocked.addAll(BUILTIN)
        for (d in manualBlock) {
            val c = d.trim().trimEnd('.').lowercase()
            if (c.isNotBlank()) blocked.add(c)
        }
        val white = manualWhite
            .map { it.trim().trimEnd('.').lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        for (d in white) blocked.remove(d)
        return blocked to white
    }

    /** True if [host] equals a blocked domain or is a subdomain of one. */
    fun matches(host: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var h = host.trim().trimEnd('.').lowercase()
        if (domains.contains(h)) return true
        while (true) {
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (h.isEmpty()) return false
            if (domains.contains(h)) return true
        }
    }

    fun normalizeDomain(raw: String): String =
        raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?')
            .substringBefore('#').trim().trimEnd('.').lowercase()
}
