package com.hikari.app.net

import java.net.URI

/**
 * The promo guard: the "support us / donate / buy me a coffee" layer.
 *
 * Repos, extensions and the pages they host like to bolt a funding pitch onto
 * their content — a "goal achieved, send extra love, watch an ad to support"
 * card, a Patreon/ko-fi button hung on the side of an extension's settings
 * page, a donation link in a plugin description. Whatever the form, it is the
 * extension talking about MONEY, not about a movie, and Hikari is a place to
 * watch things. So it is dropped everywhere an extension can put it:
 *
 *  - **Pages.** A main-frame navigation to a donation host (or to a
 *    donate/sponsor path on any host) is refused in [com.hikari.app.web.WebViewActivity]
 *    before it loads, and a page that DOES load has its promo blocks hidden by
 *    the injected [com.hikari.app.web.WebViewActivity] promo cleaner — so a
 *    funding card cannot appear inside Hikari even when it rides along with a
 *    real extension page.
 *  - **Text.** Extension-supplied strings (repo and plugin descriptions, the
 *    header/info rows an extension pushes into its own settings screen) are run
 *    through [cleanText], which strips markup and blank-fills anything that is
 *    only a donation pitch, and are dropped outright by [isPromoText].
 *
 * All of this is deliberate and unconditional: it is not a user preference,
 * because there is no version of "show me the extensions' donation dialogs"
 * that belongs in a video app. Nothing about PLAYBACK is touched — a stream URL
 * that happens to contain "donate" is not a page, and [isDonationUrl] is only
 * ever asked about navigations, links and metadata.
 */
object PromoGuard {

    /**
     * Hosts whose entire purpose is taking money. Opening one inside Hikari is
     * never part of watching something, so a main-frame navigation to them is
     * refused. Matched on the registrable-ish suffix, so `www.ko-fi.com` and
     * `cdn.patreon.com` are covered too.
     */
    private val DONATION_HOSTS = listOf(
        // Tip jars / memberships
        "ko-fi.com", "kofi.com", "kofiwidget2.com", "buymeacoffee.com", "buymeacoff.ee", "bmc.link",
        "patreon.com", "patron.com", "membership.io", "subscribestar.com", "boosty.to", "dona.to",
        "liberapay.com", "opencollective.com", "trakteer.id", "saweria.co", "sociabuzz.com",
        "karyakarsa.com", "tipme.in", "tipsme.in", "donationalerts.com", "donatepay.ru",
        "tipeeestream.com", "streamlabs.com", "streamelements.com", "streamtip.com", "tipanddonation.com",
        // Payment rails
        "paypal.com", "paypal.me", "cash.app", "venmo.com", "wise.com", "revolut.me", "razorpay.com",
        "instamojo.com", "paytm.me", "phonepe.com", "paystack.com", "gumroad.com", "buymeacoffees.com",
        // Crowdfunding
        "gofundme.com", "fundrazr.com", "kickstarter.com", "indiegogo.com", "fundly.com",
        "patreon-payments.com", "sponsor.ajay.app", "githubsponsors.org",
    )

    /**
     * Path words that name a donation page on ANY host — a repo's own site, a
     * "support the developer" page, a funding subpage. Kept to unambiguous
     * words: a stream URL is never a donation, and these are only asked about
     * navigations and links.
     */
    private val DONATION_PATH_WORDS = listOf(
        "donate", "donation", "donations", "donar", "spenden", "donate-us", "support-us", "supportus",
        "tipjar", "tip-jar", "buymeacoffee", "buy-me-a-coffee", "become-a-sponsor", "sponsors",
        "fundraiser", "fundraise", "fund-us", "fundus", "kofi", "ko-fi", "patreon", "paypal",
    )

    /**
     * Text that is only ever a funding pitch. Deliberately phrase-level: a bare
     * "support" or "fund" would swallow real sentences ("Supports 4K", "funded
     * by viewers"), which is why every entry here is a whole ask.
     */
    private val PROMO_TEXT = Regex(
        "buy me a coffee|ko[- ]?fi(?:[.]com)?|buymeacoffee|become a (patron|sponsor)|" +
            "support (?:us|me|this|our) (?:on|via|through)|watch an ad to support|send extra love|" +
            "make a donation|donate (?:now|today|to us|to this)|goal (?:achieved|completed)|" +
            "supporters made this happen|sponsor (?:this|our) (?:repo|project|channel|extension)|" +
            "fund (?:this|our) (?:repo|project|extension)|help us (?:keep|stay) (?:the )?(?:lights|servers|repo)|" +
            "not affiliated with the cloudstream app",
        RegexOption.IGNORE_CASE,
    )

    /** `<a href=...>`, `<b>` and friends — extension metadata is sometimes HTML. */
    private val TAG_RE = Regex("<[^>]{0,400}>")

    /** Anything that IS a stream (or a page that plays one) keeps working. */
    private val MEDIA_URL_RE = Regex("\\.(m3u8|mpd|mp4|mkv|webm|ts|m4v|mov)([?#]|$)", RegexOption.IGNORE_CASE)

    /** A link to a donation host sitting inside a plain-text metadata field. */
    private val DONATION_URL_RE = Regex(
        "(?:https?://|www[.])[^\\s\"'<>]*(?:ko[-]?fi[.]com|kofi[.]com|buymeacoffee|patreon[.]com|" +
            "paypal[.]|github[.]com/sponsors|opencollective[.]com|liberapay[.]com|trakteer[.]id|" +
            "saweria[.]co|sociabuzz[.]com|donationalerts|gofundme|kickstarter|indiegogo)[^\\s\"'<>]*",
        RegexOption.IGNORE_CASE,
    )

    /** True when [url] is a donation/funding page that must not open in-app. */
    fun isDonationUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        // Never a page: media, data and app-internal schemes are left alone.
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("blob:") ||
            lower.startsWith("javascript:") || lower.startsWith("about:") ||
            lower.startsWith("intent:") || lower.startsWith("file:")
        ) {
            return false
        }
        // A stream is never a donation page, whatever its path happens to say.
        if (MEDIA_URL_RE.containsMatchIn(lower)) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: return false
        if (hostMatches(host)) return true
        // A donate path on any other host (a repo's own "support" subpage).
        val path = (uri.path ?: "").lowercase()
        if (path.isNotEmpty() && DONATION_PATH_WORDS.any { w -> path.contains(w) }) return true
        // A donation host smuggled in as a query parameter (redirector links).
        val query = (uri.query ?: "").lowercase()
        if (query.isNotEmpty() &&
            (query.contains("donate") || query.contains("buymeacoffee") || query.contains("patreon") ||
                query.contains("ko-fi") || query.contains("paypal"))
        ) {
            return true
        }
        return false
    }

    /** [host] is (or is a subdomain of) one of the donation hosts. */
    private fun hostMatches(host: String): Boolean =
        DONATION_HOSTS.any { host == it || host.endsWith("." + it) }

    /** True when [text] is, or is dominated by, a funding ask. */
    fun isPromoText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return PROMO_TEXT.containsMatchIn(text)
    }

    /**
     * An extension-supplied string, made safe to render: markup stripped (a
     * description is text, not HTML — Hikari never executes it), donation links
     * and donation hosts removed, and every kind of whitespace collapsed so a
     * stripped field cannot leave a wall of blank lines. Returns "" when
     * nothing worth showing is left — including when the whole string was
     * nothing but a funding pitch, which is the point.
     */
    fun cleanText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var s = TAG_RE.replace(text, " ")
        s = DONATION_URL_RE.replace(s, " ")
        // A description's tail is often where the ask lives ("… Enjoy! Buy me a
        // coffee"): drop every segment that is only a funding ask, keep the
        // rest, THEN collapse whitespace. If nothing but the ask is left, the
        // field renders empty — which is the point.
        val segments = s.split(Regex("\\s{2,}|[\\n\\r]+")).map { it.trim() }.filter { it.isNotBlank() }
        val kept = segments.filterNot { isPromoText(it) }
        if (kept.isEmpty()) return ""
        val out = kept.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (out.isBlank() || isPromoText(out)) return ""
        return out.take(500)
    }
}
