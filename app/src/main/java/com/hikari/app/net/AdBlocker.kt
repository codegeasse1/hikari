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

    /** Blocked set built purely from the on-disk cache + built-ins — no store,
     *  no network, no suspension. For WebViews that can't suspend (e.g. the
     *  hidden Cloudflare solver): covers whatever lists the user configured,
     *  since downloading a list caches it under filesDir/adblock/. */
    fun cachedResolve(context: Context): Set<String> {
        val blocked = HashSet<String>()
        val dir = cacheDir(context)
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".txt")) {
                runCatching { blocked.addAll(parseHosts(f.readText())) }
            }
        }
        blocked.addAll(BUILTIN)
        return blocked
    }

    /** True when a request looks like actual media (never ad-block these —
     *  blocklists routinely contain tube-site CDN domains that serve the
     *  real video). */
    fun isMediaLike(request: android.webkit.WebResourceRequest): Boolean {        val h = request.requestHeaders
        if (h?.containsKey("Range") == true) return true
        val accept = h?.get("Accept")?.lowercase().orEmpty()
        if (accept.contains("video/") || accept.contains("application/octet-stream")) return true
        val u = request.url.toString().lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mpd") ||
            u.contains(".ts?") || u.contains("videoplayback") || u.contains("/videos/") ||
            u.contains("/media/") || u.contains("phncdn") || u.contains("streamable")
    }

    fun normalizeDomain(raw: String): String =
        raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore('?')
            .substringBefore('#').trim().trimEnd('.').lowercase()

    // ------------------------------------------------ URL-pattern blocking
    //
    // The uBO-style half a hosts file cannot do. Hosts only match whole
    // DOMAINS, so everything below sailed straight through even with lists
    // added and enabled (the user's own report): ads served from the site's
    // OWN domain (`site.com/ads/banner.js`, `site.com/pop.js`), from ad
    // subdomains no list names yet (`xyz.ads-network.foo`), and rotating
    // popunder/popup hosts. These EasyList-subset network rules match the
    // URL itself, and run in the WebView's request interceptor right after
    // the hosts check (whitelist and the media exemption both still win —
    // see the call site).
    //
    // Supported syntax (the practical core of EasyList/uBO network rules):
    //   ||domain^path*   domain-anchored (any subdomain, any scheme)
    //   |http…|          start/end anchored
    //   plain substring  (with * wildcards)
    //   ^               a "separator" (end of host, /, ?, &, =, : …)
    //   @@…             exception — never block, wins over everything
    //   $third-party / $~third-party, $domain=a|~b, $script,stylesheet,image,
    //   font,media,xmlhttprequest,subdocument,other, $match-case

    data class PatternRule(
        val raw: String,
        val isException: Boolean,
        val regex: Regex,
        /** null = either; true = third-party requests only. */
        val thirdParty: Boolean?,
        /** null = any page; otherwise page hosts this rule applies to. */
        val domains: List<DomainOpt>?,
        /** null = any resource kind. */
        val types: Set<String>?,
        val matchCase: Boolean,
    )

    data class DomainOpt(val domain: String, val negated: Boolean)

    /** Curated built-ins: the ad networks video/streaming sites actually use
     *  (popunders, redirect gates, in-page widgets), as URL patterns so
     *  same-host servings and rotating subdomains are caught too. */
    val BUILTIN_PATTERNS = listOf(
        // Popunder / popup delivery.
        "||popads.net^", "||popcash.net^", "||popunder.net^",
        "||adsterra.com^", "||propellerads.com^", "||propeller-tracking.com^",
        "||adcash.com^", "||exoclick.com^", "||trafficjunky.net^",
        "||juicyads.com^", "||adxpansion.com^", "||hilltopads.net^",
        "||clickadu.com^", "||mgid.com^", "||revcontent.com^",
        "||outbrain.com^", "||taboola.com^", "||criteo.com^",
        "||adnxs.com^", "||rubiconproject.com^", "||pubmatic.com^",
        "||doubleclick.net^", "||googlesyndication.com^",
        "||googleadservices.com^", "||amazon-adsystem.com^",
        "||imasdk.googleapis.com^",
        "/popunder", "/pop-under", "/popup-ad", "/popad.", "/pop.js",
        "/popunder.js", "/popup.js", "/popcash.js", "/pst.js",
        // Same-host ad scripts and redirect gates (hosts files never see these).
        "/ads/banner", "/ads/pop", "/advertising/", "/adserver/",
        "/promo-redirect", "/redirect-ads", "/out.php?",
        // Video-player ad injection.
        "||vast.com^", "/vast.xml", "/vmap", "imasdk",
        "/jwplayer-ads", "/video-ads/", "/preroll",
        // Trackers that exist only to profile ad clicks.
        "||moatads.com^", "||scorecardresearch.com^", "||quantserve.com^",
        // Never break logins / payments / the video itself.
        "@@||accounts.google.com^", "@@||paypal.com^", "@@||stripe.com^",
        "@@||cloudflare.com^", "@@||challenges.cloudflare.com^",
    )

    private val compiledPatterns: List<PatternRule> by lazy {
        BUILTIN_PATTERNS.mapNotNull { parsePattern(it) }
    }

    private val optionsRe = Regex("^[a-z~\\-]+(=[^,]*)?(,[a-z~\\-]+(=[^,]*)?)*$")

    /** Parses one EasyList-subset rule, or null when it is not a network rule. */
    fun parsePattern(raw: String): PatternRule? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return null
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#")) return null
        val isException = s.startsWith("@@")
        if (isException) s = s.substringAfter("@@")
        var thirdParty: Boolean? = null
        var domains: List<DomainOpt>? = null
        var types: Set<String>? = null
        var matchCase = false
        val optIdx = s.lastIndexOf('$')
        if (optIdx > 0) {
            val opts = s.substring(optIdx + 1)
            // A `$` inside a real pattern (rare) is not options: options look
            // like `name` or `name=value` lists, nothing else.
            if (optionsRe.matches(opts)) {
                s = s.substring(0, optIdx)
                for (o in opts.split(',')) {
                    val name = o.substringBefore('=')
                    val value = o.substringAfter('=', "")
                    when (name) {
                        "third-party" -> thirdParty = true
                        "~third-party" -> thirdParty = false
                        "domain" -> domains = value.split('|')
                            .filter { it.isNotBlank() }
                            .map { DomainOpt(it.trimStart('~').lowercase(), it.startsWith("~")) }
                            .takeIf { it.isNotEmpty() }
                        "match-case" -> matchCase = true
                        "script", "stylesheet", "image", "font", "media",
                        "xmlhttprequest", "subdocument", "other", "object",
                        "ping", "websocket" -> types = (types ?: emptySet()) + name
                        "~script", "~stylesheet", "~image", "~font", "~media",
                        "~xmlhttprequest", "~subdocument", "~other" -> {
                            // Negated types are rare in practice; treat the
                            // rule as untyped rather than misread it.
                        }
                        "document", "elemhide", "generichide", "important",
                        "redirect", "csp", "removeparam", "cookie" -> {
                            // Page-level / cosmetic / header options: not
                            // network-block semantics — ignore the option.
                        }
                        else -> {
                            // Unknown option: safest to keep the rule but
                            // untyped would MISREAD it, so drop the rule.
                            if (name.isNotBlank()) return null
                        }
                    }
                }
            }
        }
        if (s.isEmpty()) return null
        val startAnchor = s.startsWith("|") && !s.startsWith("||")
        val domainAnchor = s.startsWith("||")
        var endAnchor = false
        var core = when {
            domainAnchor -> s.substring(2)
            startAnchor -> s.substring(1)
            else -> s
        }
        if (core.endsWith("|") && !core.endsWith("\\|")) {
            endAnchor = true
            core = core.dropLast(1)
        }
        val body = buildString {
            for (c in core) {
                when (c) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        val pattern = when {
            domainAnchor -> "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]*\\.)?" + body
            startAnchor -> "^" + body
            else -> body
        } + if (endAnchor) "$" else ""
        val regex = runCatching {
            Regex(pattern, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }.getOrNull() ?: return null
        return PatternRule(raw, isException, regex, thirdParty, domains, types, matchCase)
    }

    /** The request's coarse kind, inferred from its URL and headers (the
     *  WebView never tells us the type directly). Unknown → "other". */
    fun requestType(request: android.webkit.WebResourceRequest): String {
        val u = request.url.toString().lowercase()
        val path = u.substringBefore('?')
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") || path.endsWith(".bmp") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> "font"
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                path.endsWith(".ts") || path.endsWith(".webm") || path.endsWith(".m4s") -> "media"
            else -> {
                val accept = request.requestHeaders?.get("Accept")?.lowercase().orEmpty()
                when {
                    accept.contains("javascript") || accept.contains("ecmascript") -> "script"
                    accept.contains("css") -> "stylesheet"
                    accept.startsWith("image/") -> "image"
                    accept.contains("font") -> "font"
                    accept.contains("video/") || accept.contains("audio/") ||
                        accept.contains("mpegurl") || accept.contains("mpd") -> "media"
                    request.method.equals("POST", ignoreCase = true) -> "xmlhttprequest"
                    else -> "other"
                }
            }
        }
    }

    private fun sameParty(reqHost: String, pageHost: String): Boolean {
        val r = reqHost.lowercase()
        val p = pageHost.trim().trimEnd('.').lowercase()
        return r == p || r.endsWith(".$p") || p.endsWith(".$r")
    }

    private fun ruleApplies(
        rule: PatternRule,
        url: String,
        reqHost: String,
        pageHost: String?,
        type: String,
    ): Boolean {
        if (!rule.regex.containsMatchIn(url)) return false
        if (rule.thirdParty != null && pageHost != null) {
            val third = !sameParty(reqHost, pageHost)
            if (rule.thirdParty != third) return false
        }
        rule.domains?.let { opts ->
            val ph = (pageHost ?: "").lowercase()
            var included = opts.none { !it.negated }
            for (o in opts) {
                if (ph == o.domain || ph.endsWith("." + o.domain)) {
                    if (o.negated) return false
                    included = true
                }
            }
            if (!included) return false
        }
        rule.types?.let { if (type !in it) return false }
        return true
    }

    /**
     * True when a URL is blocked by pattern rules (exceptions win). The
     * caller still checks the whitelist first and never blocks media —
     * this answers only "does a rule match".
     */
    fun matchesPatternUrl(url: String, pageHost: String?, type: String = "other"): Boolean {
        val reqHost = runCatching {
            java.net.URI(url).host
        }.getOrNull()?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (reqHost.isEmpty()) return false
        var blocked = false
        for (rule in compiledPatterns) {
            if (!ruleApplies(rule, url, reqHost, pageHost, type)) continue
            if (rule.isException) return false
            blocked = true
        }
        return blocked
    }

    /** Same as [matchesPatternUrl] for a live WebView request (type inferred). */
    fun matchesPattern(request: android.webkit.WebResourceRequest, pageHost: String?): Boolean =
        matchesPatternUrl(request.url.toString(), pageHost, requestType(request))
}
