package com.hikari.app.data

import com.hikari.app.net.AdBlocker

/**
 * Synchronous mirror of the "Allowed redirect links" list (Settings → Privacy &
 * Browsing → WebView safety), plus the matcher every redirect decision uses.
 *
 * WHY this exists: the real list lives in DataStore, i.e. it is only readable
 * from a coroutine, and two things need it BEFORE a coroutine can answer —
 *
 *  1. a WebView that is already loading. The order of a page's own redirects is
 *     not ours to choose: the allow list used to be read from the store inside
 *     the WebView's own `lifecycleScope.launch`, so a redirect that arrived in
 *     the first few hundred milliseconds was judged against an EMPTY set and
 *     blocked. That is the reported "I added net77.cc to the allowed redirect
 *     links and the verification WebView still says Blocked redirect to
 *     net77.cc".
 *  2. the Cloudflare-verification view, which refuses every main-frame
 *     navigation that is not the site it started on (see
 *     WebViewActivity.isVerifyAllowed). An extension whose own site redirects to
 *     a mirror host is exactly the case the allow list exists for, so the list
 *     has to be part of that decision too — and it has to be there instantly.
 *
 * Written by [AppStore] on every read and write, so a link the user adds in
 * Settings takes effect for the very next redirect: no restart, no waiting for
 * a store round-trip.
 *
 * TWO KINDS OF ENTRY, one rule each (see [allowsIn]):
 *
 *  * a HOST — "net77.cc", "player.example.com" — matches that host itself and
 *    every subdomain of it. The precise form, for a site the user knows by
 *    name;
 *  * a WORD — "filester" — has no dot in it and matches ANY link that contains
 *    it, whatever sits behind it: filester.com, filester.me, filester.gg,
 *    filester.sh, an-filester-mirror.xyz. This is the form that actually keeps
 *    working for an extension's file host, whose TLD rotates every few weeks —
 *    the reported "I keep adding the new domain and it blocks the next one".
 *    A word is compared against the WHOLE url, case-insensitively, so a link
 *    that carries the host in a query parameter counts as well.
 */
object RedirectAllow {

    @Volatile
    private var hosts: Set<String> = emptySet()

    /** The dot-less entries, matched as a substring of the url (see [allowsIn]). */
    @Volatile
    private var words: List<String> = emptyList()

    /** The allowed hosts right now, ready to hand to [AdBlocker.matches]. */
    fun now(): Set<String> = hosts

    /** Replaces the mirror from the user's stored list. */
    fun set(list: List<String>) {
        val h = HashSet<String>(list.size)
        val w = ArrayList<String>(list.size)
        for (raw in list) {
            val e = normalizeEntry(raw)
            if (e.isBlank()) continue
            // A dot means the user named a host; anything else is a word.
            if (e.contains('.')) h.add(e) else w.add(e)
        }
        hosts = h
        words = w
    }

    /**
     * One entry reduced to what the matcher compares: no scheme, no port, no
     * path, no trailing dot, lower case. "filester" stays "filester" (a word),
     * "htTPS://Net77.cc:443/watch?x=1" becomes "net77.cc" (a host).
     */
    fun normalizeEntry(raw: String): String =
        AdBlocker.normalizeDomain(raw).substringBefore(':').trim()

    /** Host [host] is [entry] itself or a subdomain of it. */
    private fun hostMatches(host: String, entry: String): Boolean =
        host == entry || host.endsWith(".$entry")

    /**
     * True when [url] is allowed by [list] — the user's own "Allowed redirect
     * links" entries, in either form (see the class doc). Used by the WebView's
     * redirect decisions and by the Cloudflare-verification view, which cannot
     * afford a DataStore read (the mirror is only a fast path: the caller's own
     * freshly-read list is honoured the same way).
     *
     * The parameter is an [Iterable], NOT a `Collection`: this package's own
     * media class is called `Collection` (see Models.kt) and shadows
     * `kotlin.collections.Collection` inside every file of
     * `com.hikari.app.data` — a `Collection<String>` parameter here is a compile
     * error ("No type arguments expected for 'data class Collection'").
     */
    fun allowsIn(url: String?, list: Iterable<String>): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim().lowercase()
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull().orEmpty()
        for (raw in list) {
            val e = normalizeEntry(raw)
            if (e.isBlank()) continue
            if (e.contains('.')) {
                if (host.isNotEmpty() && hostMatches(host, e)) return true
            } else if (u.contains(e)) {
                return true
            }
        }
        return false
    }

    /** [allowsIn] against the in-memory mirror — the no-store, no-suspension
     *  form every redirect check can afford. */
    fun allows(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim().lowercase()
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isNotEmpty() && AdBlocker.matches(host, hosts)) return true
        for (w in words) if (u.contains(w)) return true
        return false
    }

    /** True when [raw] is the word form of an entry — i.e. it will be matched
     *  as "contains" rather than as a host. For the UI's own explanation. */
    fun isWordEntry(raw: String): Boolean = normalizeEntry(raw).let { it.isNotBlank() && !it.contains('.') }
}
