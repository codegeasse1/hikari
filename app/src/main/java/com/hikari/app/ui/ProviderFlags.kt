package com.hikari.app.ui

/**
 * Country flags for provider/extension rows, the CloudStream extension-list
 * look: the user knows at a glance which language an extension serves.
 *
 * Two shapes share one table:
 * - [codeOf]/[flagOf] read the repo listing's own name+description (what the
 *   Extensions screen, collection rows and plugin cards pass) and answer the
 *   ISO code / the "🇮🇳 Hindi" label;
 * - [flagFor]/[flagForPack] answer a bare emoji for picker rows, whose
 *   language is already known (an Aniyomi/manga pack's member details ARE the
 *   language names — "Bengali", "Hindi", …).
 *
 * Null when the texts name no language — never guessed. Short 2-letter codes
 * only count as EXPLICIT tags ("[HI]", "(EN)"): bare "in"/"is"/"it"/"no" are
 * ordinary English words, and matching them would flag half the catalogue
 * British. "Multi"/"all"/"dual" name no single language and stay flagless.
 */
object ProviderFlags {

    private data class Lang(
        val iso: String,
        val emoji: String,
        val display: String,
        val aliases: Set<String>,
    )

    private val langs: List<Lang> = listOf(
        Lang("gb", "🇬🇧", "English", setOf("en", "eng", "english")),
        Lang("in", "🇮🇳", "Hindi", setOf("hi", "hin", "hindi")),
        Lang("in", "🇮🇳", "Bengali", setOf("bn", "ben", "bengali", "bangla")),
        Lang("in", "🇮🇳", "Tamil", setOf("ta", "tam", "tamil")),
        Lang("in", "🇮🇳", "Telugu", setOf("te", "tel", "telugu")),
        Lang("in", "🇮🇳", "Malayalam", setOf("ml", "mal", "malayalam")),
        Lang("in", "🇮🇳", "Marathi", setOf("mr", "mar", "marathi")),
        Lang("in", "🇮🇳", "Kannada", setOf("kn", "kan", "kannada")),
        Lang("in", "🇮🇳", "Gujarati", setOf("gu", "guj", "gujarati")),
        Lang("in", "🇮🇳", "Punjabi", setOf("pa", "pan", "punjabi")),
        Lang("pk", "🇵🇰", "Urdu", setOf("ur", "urd", "urdu")),
        Lang("np", "🇳🇵", "Nepali", setOf("ne", "nep", "nepali")),
        Lang("lk", "🇱🇰", "Sinhala", setOf("si", "sin", "sinhala", "sinhalese")),
        Lang("sa", "🇸🇦", "Arabic", setOf("ar", "ara", "arabic")),
        Lang("es", "🇪🇸", "Spanish", setOf("es", "spa", "spanish", "espanol", "español", "castellano")),
        Lang("fr", "🇫🇷", "French", setOf("fr", "fre", "fra", "french", "francais", "français")),
        Lang("de", "🇩🇪", "German", setOf("de", "ger", "deu", "german", "deutsch")),
        Lang("br", "🇧🇷", "Portuguese", setOf("pt", "por", "portuguese", "brasileiro")),
        Lang("it", "🇮🇹", "Italian", setOf("it", "ita", "italian", "italiano")),
        Lang("ru", "🇷🇺", "Russian", setOf("ru", "rus", "russian")),
        Lang("jp", "🇯🇵", "Japanese", setOf("ja", "jpn", "japanese")),
        Lang("kr", "🇰🇷", "Korean", setOf("ko", "kor", "korean")),
        Lang("cn", "🇨🇳", "Chinese", setOf("zh", "zho", "chi", "chinese", "mandarin", "cantonese")),
        Lang("tr", "🇹🇷", "Turkish", setOf("tr", "tur", "turkish", "turkce", "türkçe")),
        Lang("id", "🇮🇩", "Indonesian", setOf("id", "ind", "indonesian")),
        Lang("my", "🇲🇾", "Malay", setOf("ms", "may", "msa", "malay", "melayu")),
        Lang("th", "🇹🇭", "Thai", setOf("th", "tha", "thai")),
        Lang("vn", "🇻🇳", "Vietnamese", setOf("vi", "vie", "vietnamese")),
        Lang("ph", "🇵🇭", "Filipino", setOf("fil", "filipino", "tagalog", "tl")),
        Lang("nl", "🇳🇱", "Dutch", setOf("nl", "dut", "nld", "dutch")),
        Lang("pl", "🇵🇱", "Polish", setOf("pl", "pol", "polish", "polski")),
        Lang("ua", "🇺🇦", "Ukrainian", setOf("uk", "ukr", "ukrainian")),
        Lang("ir", "🇮🇷", "Persian", setOf("fa", "per", "fas", "persian", "farsi")),
        Lang("il", "🇮🇱", "Hebrew", setOf("he", "heb", "hebrew")),
        Lang("gr", "🇬🇷", "Greek", setOf("el", "gre", "ell", "greek")),
        Lang("ro", "🇷🇴", "Romanian", setOf("ro", "rum", "ron", "romanian")),
        Lang("hu", "🇭🇺", "Hungarian", setOf("hu", "hun", "hungarian")),
        Lang("cz", "🇨🇿", "Czech", setOf("cs", "cze", "ces", "czech")),
        Lang("se", "🇸🇪", "Swedish", setOf("sv", "swe", "swedish")),
        Lang("no", "🇳🇴", "Norwegian", setOf("no", "nor", "norwegian", "bokmal", "bokmål")),
        Lang("dk", "🇩🇰", "Danish", setOf("da", "dan", "danish")),
        Lang("fi", "🇫🇮", "Finnish", setOf("fi", "fin", "finnish")),
    )

    /** Explicit 2-letter tag ("[HI]", "(EN)", "· FR ·") — the only place a
     *  bare 2-letter code is trusted (see the object doc). */
    private val bracketCode = Regex("""[\[\(（]\s*([A-Za-z]{2})\s*[\]\)）]""")

    private fun byIso(code: String): Lang? =
        langs.firstOrNull { it.iso.equals(code, ignoreCase = true) }

    private fun match(text: String): Lang? {
        // Explicit tags first: a "[HI]" in the name beats a prose mention.
        bracketCode.findAll(text).forEach { m ->
            byIso(m.groupValues[1])?.let { return it }
        }
        // Then language names / 3-letter codes as whole words, earliest wins.
        val low = text.lowercase()
        var best: Lang? = null
        var bestAt = Int.MAX_VALUE
        for (lang in langs) {
            for (alias in lang.aliases) {
                if (alias.length < 3) continue
                val at = Regex("""(?<![a-z])${Regex.escape(alias)}(?![a-z])""").find(low)?.range?.first
                    ?: continue
                if (at < bestAt) {
                    best = lang
                    bestAt = at
                }
            }
        }
        return best
    }

    /**
     * ISO country code for an extension row's real flag image ([FlagIcon]) —
     * null draws none. Read from the listing's own name+description, never
     * guessed.
     */
    fun codeOf(name: String, description: String? = null): String? =
        match("$name ${description.orEmpty()}")?.iso

    /**
     * The "🇮🇳 Hindi" label for an extension row's text meta — null when the
     * listing names no language.
     */
    fun flagOf(name: String, description: String? = null): String? =
        match("$name ${description.orEmpty()}")?.let { "${it.emoji} ${it.display}" }

    /**
     * The bare flag emoji for one language token ("Hindi", "hi", "hi-IN") —
     * what the picker rows pass. Null for "Default", "Source 1", "Multi" and
     * friends: no language, no flag.
     */
    fun flagFor(token: String): String? {
        // Tags arrive as "hi", "hi-IN" and "hi_IN" — the primary subtag is the
        // language.
        val code = token.trim().lowercase().substringBefore('-').substringBefore('_')
        if (code.isBlank()) return null
        // Whole-token match only: "Source 1" and "Default" resolve to nothing.
        return langs.firstOrNull { code in it.aliases }?.emoji
    }

    /**
     * The flag for a collapsed pack row: its members' flag when EVERY named
     * member resolves to the SAME one, else null (a mixed-language pack gets
     * no single flag — its members carry their own once expanded).
     */
    fun flagForPack(pack: ProviderPack): String? {
        if (!pack.isPack || pack.cross) return null
        var flag: String? = null
        for (i in pack.members.indices) {
            val f = flagFor(pack.memberLabel(i)) ?: return null
            if (flag == null) flag = f
            else if (flag != f) return null
        }
        return flag
    }
}
