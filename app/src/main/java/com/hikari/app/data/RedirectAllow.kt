package com.hikari.app.data

import com.hikari.app.net.AdBlocker

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

    fun normalizeEntry(raw: String): String =
        AdBlocker.normalizeDomain(raw).substringBefore(':').trim()

    /** Host [host] is [entry] itself or a subdomain of it. */
    private fun hostMatches(host: String, entry: String): Boolean =
        host == entry || host.endsWith(".$entry")

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
