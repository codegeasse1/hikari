package com.hikari.app.data

import com.hikari.app.HikariApp
import java.util.concurrent.ConcurrentHashMap

/**
 * "Is this an IPTV item / provider?" — the one test every TMDB-shaped feature in
 * the app has to consult.
 *
 * An IPTV provider's whole catalogue is LIVE CHANNELS, and a live channel has no
 * entry in any on-demand database. Asking TMDB for the artwork of a channel
 * whose playlist shipped no `tvg-logo` matched an unrelated film and put that
 * film's POSTER on the channel row, while the ratings queue matched the channel
 * NAME ("Pardesi TV") against IMDb/Wikidata and printed a bogus score badge next
 * to it, and the detail page filled a channel with a Cast row, trailers and a
 * "More like this" shelf of films that have nothing to do with it. All of it was
 * reported as "on iptv links the tmdb poster loading and data loading… for only
 * iptv don't apply it", and all of it starts here.
 *
 * The test is cheap and thread-safe on purpose: it runs from poster composition,
 * from the artwork lookup's own worker thread and from the ratings queue. An
 * IPTV provider's id is minted as `iptv|<hash>` (see the Add-playlist flow in
 * the Extensions screen), so the ordinary case is a prefix test; anything else
 * falls back to ONE provider-map read, and only yes answers are memoised (so an
 * uninstalled provider can never stay sticky, and the map never grows with
 * negatives).
 */
object IptvMark {

    /** How every playlist provider's id begins. */
    const val ID_PREFIX = "iptv|"

    private val known = ConcurrentHashMap<String, Boolean>()

    fun isIptvProvider(providerId: String): Boolean {
        val id = providerId.trim()
        if (id.isEmpty()) return false
        if (id.startsWith(ID_PREFIX)) return true
        if (known.containsKey(id)) return true
        val hit = runCatching {
            HikariApp.instance.providers.byId(id)?.config?.type
        }.getOrNull() == ProviderType.IPTV
        if (hit) known[id] = true
        return hit
    }

    /** True when [item] is a channel of an installed playlist. */
    fun of(item: MediaItem): Boolean = isIptvProvider(item.providerId)

    /** Drops the memoised ids (nothing in the app needs this yet; it exists so a
     *  future provider edit has one obvious thing to call). */
    fun forget() {
        known.clear()
    }
}
