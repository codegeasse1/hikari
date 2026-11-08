package com.hikari.app.net

import java.net.URI

object PromoGuard {

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

    private val DONATION_PATH_WORDS = listOf(
        "donate", "donation", "donations", "donar", "spenden", "donate-us", "support-us", "supportus",
        "tipjar", "tip-jar", "buymeacoffee", "buy-me-a-coffee", "become-a-sponsor", "sponsors",
        "fundraiser", "fundraise", "fund-us", "fundus", "kofi", "ko-fi", "patreon", "paypal",
    )

    private val PROMO_TEXT = Regex(
        "buy me a coffee|ko[- ]?fi(?:[.]com)?|buymeacoffee|become a (patron|sponsor)|" +
            "support (?:us|me|this|our) (?:on|via|through)|watch an ad to support|send extra love|" +
            "make a donation|donate (?:now|today|to us|to this)|goal (?:achieved|completed)|" +
            "supporters made this happen|sponsor (?:this|our) (?:repo|project|channel|extension)|" +
            "fund (?:this|our) (?:repo|project|extension)|help us (?:keep|stay) (?:the )?(?:lights|servers|repo)|" +
            "not affiliated with the cloudstream app|" +
            "help (?:us )?keep [\\w'\u2019 .-]{1,28}?(?:alive|free|running|online)|" +
            "keep [\\w'\u2019 .-]{1,28}?alive|keep it alive|zero support|extensions? die|" +
            "no ads,? no (?:subscription|ads)|goal missed|monthly (?:goal|target)|" +
            "active repo maintenance|maintain(?:ing)? \\d+\\+? (?:providers|repos|extensions)|" +
            "can'?t donate|supporters? (?:this month|so far)|unlock (?:premium|ad-?free)|" +
            "\\$\\s?\\d{1,6}(?:[,.]\\d{3})?\\s*/\\s*\\$\\s?\\d{1,6}(?:[,.]\\d{3})?",
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

    fun cleanText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var s = TAG_RE.replace(text, " ")
        s = DONATION_URL_RE.replace(s, " ")
        val segments = s.split(Regex("\\s{2,}|[\\n\\r]+")).map { it.trim() }.filter { it.isNotBlank() }
        val kept = segments.filterNot { isPromoText(it) }
        if (kept.isEmpty()) return ""
        val out = kept.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (out.isBlank() || isPromoText(out)) return ""
        return out.take(500)
    }
}
