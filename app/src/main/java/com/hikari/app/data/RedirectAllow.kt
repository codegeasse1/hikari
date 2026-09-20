package com.hikari.app.data

import com.hikari.app.net.AdBlocker

/**
 * Synchronous mirror of the "Allowed redirect links" list (Settings → Privacy &
 * Browsing → WebView safety).
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
 */
object RedirectAllow {

    @Volatile
    private var hosts: Set<String> = emptySet()

    /** The allowed hosts right now, ready to hand to [AdBlocker.matches]. */
    fun now(): Set<String> = hosts

    /** Replaces the mirror. Entries may be typed as a bare host ("net77.cc"), a
     *  host:port or a full URL — all of them are reduced to the host form the
     *  matcher compares. */
    fun set(list: List<String>) {
        hosts = normalize(list)
    }

    fun normalize(list: List<String>): Set<String> {
        val out = HashSet<String>(list.size)
        for (raw in list) {
            val d = AdBlocker.normalizeDomain(raw)
                .substringBefore(':')
                .trim()
            if (d.isNotBlank()) out.add(d)
        }
        return out
    }
}
