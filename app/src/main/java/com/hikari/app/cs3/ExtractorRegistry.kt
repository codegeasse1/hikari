@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

/**
 * The real CloudStream-3 runtime ships ~200 battle-tested extractors inside
 * cloudstream3.jar, but CloudStream's `loadExtractor()` only runs an extractor
 * whose `mainUrl` prefix-matches the embed URL. Plugins routinely embed from
 * domains the registry never heard of (luluvids.top, morencius.com,
 * bysezoxexe.com, player.wishhd.net, lowercase hgcloud.to…), so no extractor
 * runs → "no playable sources" even though the same video plays in CloudStream.
 *
 * This registers alias subclasses of the built-in extractors with the real
 * embed domains. They are appended to the jar's mutable registry, which
 * `loadExtractor` iterates newest-first — so aliases are tried FIRST and the
 * ENTIRE built-in machinery (dl-POST, JWPlayer unpacking, AES-GCM, dood
 * pass_md5, M3u8Helper…) runs for every plugin at once, exactly like it does
 * inside CloudStream. Anything the built-ins still can't resolve is picked up
 * by [FallbackResolver] in Cs3MainApiProvider.
 */
object HikariExtractorRegistry {

    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Idempotent — safe to call repeatedly/from any thread. */
    fun register() {
        fun add(host: String, make: () -> ExtractorApi) {
            if (!seen.add(host)) return
            runCatching { extractorApis.add(make()) }
                .onFailure { android.util.Log.e("HikariExtractors", "alias $host failed: ${it.message}", it) }
        }

        // --- LuluStream family (dl-POST backend; clones use their own domain) ---
        add("https://luluvids.top") { HikariLuluHost("https://luluvids.top") }
        add("https://lulustream.top") { HikariLuluHost("https://lulustream.top") }
        add("https://lulustream.net") { HikariLuluHost("https://lulustream.net") }
        add("https://luluvdoo.com") { HikariLuluHost("https://luluvdoo.com") }

        // --- VidHidePro family (unpacked JWPlayer on the embed page) ---
        add("https://morencius.com") { HikariVidHideHost("https://morencius.com") }
        add("https://vidhide.cc") { HikariVidHideHost("https://vidhide.cc") }
        add("https://movhide.co") { HikariVidHideHost("https://movhide.co") }
        add("https://vidhidefast.com") { HikariVidHideHost("https://vidhidefast.com") }

        // --- StreamWish family (built-ins cover most wish hosts; add the
        //     common variants plugins actually embed with) ---
        add("https://player.wishhd.net") { HikariWishHost("https://player.wishhd.net") }
        add("https://streamwish.app") { HikariWishHost("https://streamwish.app") }
        add("https://wishhd.co") { HikariWishHost("https://wishhd.co") }
        add("https://wishhd.top") { HikariWishHost("https://wishhd.top") }
        add("https://streamwish.site") { HikariWishHost("https://streamwish.site") }

        // --- Hgcloud (built-in Hgcloudto uses a capitalized mainUrl, which
        //     never prefix-matches the lowercased embed URL — CloudStream bug) ---
        add("https://hgcloud.to") { HikariWishHost("https://hgcloud.to") }

        // --- Byse family (AES-GCM player, host-agnostic getUrl) ---
        add("https://bysezoxexe.com") { HikariByseHost("https://bysezoxexe.com") }
        add("https://bysezoxexe.net") { HikariByseHost("https://bysezoxexe.net") }
        add("https://bysezoxexe.org") { HikariByseHost("https://bysezoxexe.org") }
    }
}

/** Pure aliases — the base classes' getUrl is fully host-driven via mainUrl. */
private class HikariLuluHost(override val mainUrl: String) : LuluStream()
private class HikariVidHideHost(override val mainUrl: String) : VidHidePro()
private class HikariWishHost(override val mainUrl: String) : StreamWishExtractor()
private class HikariByseHost(override val mainUrl: String) : ByseSX()
