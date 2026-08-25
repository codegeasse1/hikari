@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import com.hikari.app.net.Http
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.newExtractorLink

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

        // --- megaplay family (getSources AJAX) — the jar ships no megaplay
        //     extractor; plugins either reimplement it (and can stall/403
        //     in-app) or fall to loadExtractor and get nothing. Registering
        //     this lets every plugin's loadExtractor resolve megaplay embeds. ---
        add("https://megaplay.buzz") { HikariMegaPlayHost("https://megaplay.buzz") }
        add("https://megacloud.tv") { HikariMegaPlayHost("https://megacloud.tv") }
        add("https://rapid-cloud.co") { HikariMegaPlayHost("https://rapid-cloud.co") }
        add("https://vidplay.site") { HikariMegaPlayHost("https://vidplay.site") }
    }
}

/**
 * megaplay family — the `getSources` JSON dance anime CDNs use. Mirrors the
 * resolver Anikoto & friends implement in their plugins (XHR header required,
 * otherwise megaplay answers 403 "AJAX requests only"), so Hikari's player
 * gets the same MegaPlay servers CloudStream shows.
 */
private class HikariMegaPlayHost(override val mainUrl: String) : ExtractorApi() {
    override val name = "MegaPlay"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val clean = url.replace("\\/", "/")
        val host = runCatching { java.net.URI(clean).host }.getOrNull() ?: return
        val epId = Regex("""/stream/s-\d+/(\d+)""").find(clean)?.groupValues?.get(1)
            ?: Regex("""[?&]id=(\d+)""").find(clean)?.groupValues?.get(1)
            ?: return
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$host/",
            "Origin" to "https://$host",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to Http.UA,
        )
        val text = runCatching {
            app.get("https://$host/stream/getSources?id=$epId", headers = headers).text
        }.getOrNull() ?: return
        if (text.isBlank() || text.contains("Forbidden")) return
        val ref = "https://$host/"
        var found = false

        runCatching {
            val j = org.json.JSONObject(text)
            val tracks = j.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val subUrl = t.optString("file")
                    if (subUrl.isBlank() || !subUrl.startsWith("http")) continue
                    subtitleCallback(
                        newSubtitleFile(t.optString("label", "English"), subUrl.replace("\\/", "/")) {
                            this.headers = mapOf("Referer" to ref, "User-Agent" to Http.UA)
                        }
                    )
                }
            }
            val sources = j.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val file = s.optString("file")
                    if (file.isBlank() || !file.startsWith("http")) continue
                    val label = s.optString("label").ifBlank { s.optString("quality") }.ifBlank { "HD" }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "MegaPlay $label",
                            url = file.replace("\\/", "/"),
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = ref
                            this.headers = mapOf("Referer" to ref, "User-Agent" to Http.UA)
                        }
                    )
                    found = true
                }
            }
        }

        // Not JSON / empty arrays — scan the raw response instead.
        if (!found) {
            for (m in Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)""").findAll(text)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "MegaPlay",
                        url = m.value.replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = ref
                        this.headers = mapOf("Referer" to ref, "User-Agent" to Http.UA)
                    }
                )
            }
        }
    }
}

/** Pure aliases — the base classes' getUrl is fully host-driven via mainUrl. */
private class HikariLuluHost(override val mainUrl: String) : LuluStream()
private class HikariVidHideHost(override val mainUrl: String) : VidHidePro()
private class HikariWishHost(override val mainUrl: String) : StreamWishExtractor()
private class HikariByseHost(override val mainUrl: String) : ByseSX()
