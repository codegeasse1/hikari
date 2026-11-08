package com.hikari.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class DnsProvider(
    /** Stable id, persisted. */
    val key: String,
    val label: String,
    /** The DNS-over-HTTPS endpoint. Empty for [DnsProviders.SYSTEM], for
     *  [DnsProviders.CUSTOM] until the user writes one, and for the handful of
     *  resolvers that offer no encrypted service at all (they carry [plain]
     *  instead). */
    val url: String = "",
    /** Known addresses of [url]'s own host, so resolving it needs no DNS. Empty
     *  means the endpoint is found with the phone's resolver — fine for most
     *  people, and the reason a provider is never a hard dependency: an
     *  unreachable one falls back to the phone (see [DohDns]). */
    val bootstrap: List<String> = emptyList(),
    /** Servers that answer plain, unencrypted DNS on port 53 — the older
     *  protocol, asked directly by the app over UDP so the phone's own resolver
     *  is out of the loop (which is the point of picking a server at all), but
     *  readable and rewritable by whatever network the phone is on. */
    val plain: List<String> = emptyList(),
    /** One line under the name in Settings. */
    val note: String = "",
) {
    /** True when this entry actually names a server — [DnsProviders.SYSTEM]
     *  never does, and [DnsProviders.CUSTOM] only once it has been filled in. */
    val usable: Boolean get() = url.isNotEmpty() || plain.isNotEmpty()
}

object DnsProviders {

    /** Ask the phone's own resolver first — the app's behaviour before this
     *  setting existed, and still the default. */
    const val SYSTEM = "system"

    /** A URL the user typed. */
    const val CUSTOM = "custom"

    val ALL = listOf(
        DnsProvider(
            SYSTEM,
            "Automatic (this phone)",
            note = "Use the phone's own DNS, with Hikari's built-in encrypted fallback for names it can't answer.",
        ),
        DnsProvider(
            "cloudflare",
            "Cloudflare",
            "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1"),
            note = "Fast, no filtering, logs dropped after 24 hours.",
        ),
        DnsProvider(
            "google",
            "Google",
            "https://dns.google/dns-query",
            listOf("8.8.8.8", "8.8.4.4"),
            note = "Google Public DNS — very fast, no filtering.",
        ),
        DnsProvider(
            "adguard",
            "AdGuard",
            "https://dns.adguard-dns.com/dns-query",
            listOf("94.140.14.14", "94.140.15.15"),
            note = "Blocks ads and trackers at the DNS level.",
        ),
        DnsProvider(
            "quad9",
            "Quad9",
            "https://dns.quad9.net/dns-query",
            listOf("9.9.9.9", "149.112.112.112"),
            note = "Blocks known malicious domains.",
        ),
        DnsProvider(
            "dnssb",
            "DNS.SB",
            "https://doh.sb/dns-query",
            listOf("185.222.222.222", "45.11.45.11"),
            note = "No filtering, no logging.",
        ),
        DnsProvider(
            "mullvad",
            "Mullvad",
            "https://doh.mullvad.net/dns-query",
            note = "Privacy-first, no logging.",
        ),
        DnsProvider(
            "canadianshield",
            "Canadian Shield",
            "https://private.canadianshield.cira.ca/dns-query",
            note = "CIRA's resolver — blocks malware, not content.",
        ),
        DnsProvider(
            "cleanbrowsing",
            "CleanBrowsing (family)",
            "https://doh.cleanbrowsing.org/doh/family-filter/",
            note = "Filters adult content.",
        ),
        DnsProvider(
            "dnswatch",
            "DNS.WATCH",
            plain = listOf("84.200.69.80", "84.200.70.40"),
            note = "Plain DNS only — free, no filtering, no logging, but the answers travel " +
                "unencrypted, so a network in between can read and change them. Pick one of the " +
                "encrypted options above instead if you can.",
        ),
        DnsProvider(
            CUSTOM,
            "Custom",
            note = "Point Hikari at any DNS-over-HTTPS address you like.",
        ),
    )

    /** The entry a stored key refers to, falling back to [SYSTEM] so a key from
     *  a newer build can never leave the app without a resolver. */
    fun byKey(key: String?): DnsProvider =
        ALL.firstOrNull { it.key == key } ?: ALL.first()

    /**
     * The endpoint a user-typed string means, or null if it isn't usable. Bare
     * hosts get `https://` and a `/dns-query` path so "1.1.1.1" or
     * "dns.example.com" both work, and anything the user may have pasted from a
     * browser (a `?dns=…` query, a fragment) is stripped rather than sent.
     */
    fun customEndpoint(raw: String): DnsProvider? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        val path = if (url.encodedPath == "/" || url.encodedPath.isBlank()) "/dns-query" else url.encodedPath
        val clean = url.newBuilder().encodedPath(path).query(null).fragment(null).build()
        return DnsProvider(CUSTOM, "Custom", clean.toString())
    }
}
