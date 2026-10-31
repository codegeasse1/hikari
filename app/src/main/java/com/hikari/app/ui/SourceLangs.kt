package com.hikari.app.ui

import com.hikari.app.providers.ContentProvider

/**
 * Mihon-style language groups for the Sources screen: every installed source
 * lands in exactly one group (Multi, English, हिन्दी, Español, … — or Other
 * when it declares and names none), each group with its own enable-all
 * toggle and each source with its own checkbox.
 *
 * A source's language is resolved in this order, and never guessed beyond
 * what is written down:
 *  1. the plugin's own declared language ([ProviderConfig.lang] — CloudStream
 *     banks it at install), matched exactly (`pt-BR` → Portuguese);
 *  2. multi markers in the name (`Multi`, `Dual Audio`, `Multilingual`) —
 *     a pack that serves several languages is Multi, not any one of them;
 *  3. an explicit 2-letter tag (`[HI]`, `(EN)`);
 *  4. a language name / 3-letter code as a whole word in the name;
 *  5. the site host's country TLD (`.br` → Portuguese, …) — only TLDs that
 *     name one language are listed;
 *  6. Other.
 */
object SourceLangs {

    const val MULTI = "multi"
    const val OTHER = "other"

    data class Lang(
        val code: String,
        val display: String,
        val emoji: String?,
        val aliases: Set<String>,
    )

    private val langs: List<Lang> = listOf(
        Lang("en", "English", "🇬🇧", setOf("en", "eng", "english")),
        Lang("hi", "हिन्दी", "🇮🇳", setOf("hi", "hin", "hindi")),
        Lang("bn", "বাংলা", "🇮🇳", setOf("bn", "ben", "bengali", "bangla")),
        Lang("ta", "தமிழ்", "🇮🇳", setOf("ta", "tam", "tamil")),
        Lang("te", "తెలుగు", "🇮🇳", setOf("te", "tel", "telugu")),
        Lang("ml", "മലയാളം", "🇮🇳", setOf("ml", "mal", "malayalam")),
        Lang("mr", "मराठी", "🇮🇳", setOf("mr", "mar", "marathi")),
        Lang("kn", "ಕನ್ನಡ", "🇮🇳", setOf("kn", "kan", "kannada")),
        Lang("gu", "ગુજરાતી", "🇮🇳", setOf("gu", "guj", "gujarati")),
        Lang("pa", "ਪੰਜਾਬੀ", "🇮🇳", setOf("pa", "pan", "punjabi")),
        Lang("ur", "اردو", "🇵🇰", setOf("ur", "urd", "urdu")),
        Lang("ne", "नेपाली", "🇳🇵", setOf("ne", "nep", "nepali")),
        Lang("si", "සිංහල", "🇱🇰", setOf("si", "sin", "sinhala", "sinhalese")),
        Lang("ar", "العربية", "🇸🇦", setOf("ar", "ara", "arabic")),
        Lang("es", "Español", "🇪🇸", setOf("es", "spa", "spanish", "espanol", "español", "castellano", "latino")),
        Lang("fr", "Français", "🇫🇷", setOf("fr", "fre", "fra", "french", "francais", "français")),
        Lang("de", "Deutsch", "🇩🇪", setOf("de", "ger", "deu", "german", "deutsch")),
        Lang("pt", "Português (Brasil)", "🇧🇷", setOf("pt", "por", "portuguese", "portugues", "brasileiro", "brazil")),
        Lang("it", "Italiano", "🇮🇹", setOf("it", "ita", "italian", "italiano")),
        Lang("ru", "Русский", "🇷🇺", setOf("ru", "rus", "russian")),
        Lang("uk", "Українська", "🇺🇦", setOf("uk", "ukr", "ukrainian")),
        Lang("zh", "中文", "🇨🇳", setOf("zh", "zho", "chi", "chinese", "mandarin", "cantonese")),
        Lang("ja", "日本語", "🇯🇵", setOf("ja", "jpn", "japanese")),
        Lang("ko", "한국어", "🇰🇷", setOf("ko", "kor", "korean")),
        Lang("tr", "Türkçe", "🇹🇷", setOf("tr", "tur", "turkish", "turkce", "türkçe")),
        Lang("id", "Indonesia", "🇮🇩", setOf("id", "ind", "indonesian", "indonesia")),
        Lang("ms", "Melayu", "🇲🇾", setOf("ms", "may", "msa", "malay", "melayu")),
        Lang("jv", "Jawa", "🇮🇩", setOf("jv", "jav", "javanese", "jawa")),
        Lang("th", "ไทย", "🇹🇭", setOf("th", "tha", "thai")),
        Lang("vi", "Tiếng Việt", "🇻🇳", setOf("vi", "vie", "vietnamese", "vietnam")),
        Lang("fil", "Tagalog", "🇵🇭", setOf("fil", "filipino", "tagalog", "tl", "tlg")),
        Lang("ceb", "Binisaya", "🇵🇭", setOf("ceb", "cebuano", "binisaya", "bisaya", "visayan")),
        Lang("nl", "Nederlands", "🇳🇱", setOf("nl", "dut", "nld", "dutch", "nederlands")),
        Lang("pl", "Polski", "🇵🇱", setOf("pl", "pol", "polish", "polski")),
        Lang("hu", "Magyar", "🇭🇺", setOf("hu", "hun", "hungarian", "magyar")),
        Lang("el", "Ελληνικά", "🇬🇷", setOf("el", "gre", "ell", "greek")),
        Lang("bg", "Български", "🇧🇬", setOf("bg", "bul", "bulgarian")),
        Lang("ro", "Română", "🇷🇴", setOf("ro", "rum", "ron", "romanian")),
        Lang("cs", "Čeština", "🇨🇿", setOf("cs", "cze", "ces", "czech")),
        Lang("sk", "Slovenčina", "🇸🇰", setOf("sk", "slo", "slk", "slovak")),
        Lang("sr", "Српски", "🇷🇸", setOf("sr", "srp", "serbian")),
        Lang("hr", "Hrvatski", "🇭🇷", setOf("hr", "hrv", "croatian")),
        Lang("fi", "Suomi", "🇫🇮", setOf("fi", "fin", "finnish", "suomi")),
        Lang("sv", "Svenska", "🇸🇪", setOf("sv", "swe", "swedish")),
        Lang("no", "Norsk", "🇳🇴", setOf("no", "nor", "norwegian", "bokmal", "bokmål")),
        Lang("da", "Dansk", "🇩🇰", setOf("da", "dan", "danish")),
        Lang("ca", "Català", "🇪🇸", setOf("ca", "cat", "catalan")),
        Lang("eu", "Euskara", "🇪🇸", setOf("eu", "eus", "basque")),
        Lang("gl", "Galego", "🇪🇸", setOf("gl", "glg", "galician")),
        Lang("he", "עברית", "🇮🇱", setOf("he", "heb", "hebrew")),
        Lang("fa", "فارسی", "🇮🇷", setOf("fa", "per", "fas", "persian", "farsi")),
        Lang("mn", "Монгол", "🇲🇳", setOf("mn", "mon", "mongolian")),
        Lang("my", "မြန်မာ", "🇲🇲", setOf("my", "bur", "mya", "burmese", "myanmar")),
        Lang("km", "ខ្មែរ", "🇰🇭", setOf("km", "khm", "khmer", "cambodian")),
        Lang("lo", "ລາວ", "🇱🇦", setOf("lo", "lao", "laotian")),
        Lang("ka", "ქართული", "🇬🇪", setOf("ka", "kat", "geo", "georgian")),
        Lang("am", "አማርኛ", "🇪🇹", setOf("am", "amh", "amharic")),
        Lang("eo", "Esperanto", "🌐", setOf("eo", "epo", "esperanto")),
        Lang("la", "Latin", "🏛️", setOf("la", "lat", "latin")),
        Lang("sw", "Kiswahili", "🇰🇪", setOf("sw", "swa", "swahili")),
        Lang("af", "Afrikaans", "🇿🇦", setOf("af", "afr", "afrikaans")),
    )

    /** Display order: Multi first, Other last, everything else by display name. */
    private val order: List<String> =
        listOf(MULTI) +
            langs.map { it.code }.sortedBy { code -> byCode(code)?.display ?: code } +
            listOf(OTHER)

    private fun byCode(code: String): Lang? = langs.firstOrNull { it.code == code }

    private fun byAliasExact(token: String): Lang? =
        langs.firstOrNull { token in it.aliases }

    /** Explicit 2-letter tag (`[HI]`, `(EN)`, `· FR ·`) — the only place a bare
     *  2-letter code is trusted inside a longer text. */
    private val bracketCode = Regex("""[\[\(（]\s*([A-Za-z]{2})\s*[\]\)）]""")

    /** Multi markers: a pack that serves several languages is Multi. */
    private val multiRe = Regex(
        """(?<![a-z])(multi|multilingual|multi-language|multi-lang|multi-audio|multi audio|dual-audio|dual audio|all language|every language)(?![a-z])"""
    )

    /** Country TLDs that name exactly one language. */
    private val tldLang: Map<String, String> = mapOf(
        "br" to "pt", "pt" to "pt", "mx" to "es", "ar" to "es",
        "fr" to "fr", "de" to "de", "es" to "es", "it" to "it",
        "ru" to "ru", "ua" to "uk", "cn" to "zh", "tw" to "zh", "hk" to "zh",
        "jp" to "ja", "kr" to "ko", "th" to "th", "vn" to "vi",
        "id" to "id", "nl" to "nl", "pl" to "pl", "gr" to "el",
        "tr" to "tr", "sa" to "ar", "ae" to "ar", "se" to "sv",
        "no" to "no", "dk" to "da", "fi" to "fi", "cz" to "cs",
        "sk" to "sk", "ro" to "ro", "hu" to "hu", "rs" to "sr",
        "hr" to "hr", "bg" to "bg", "il" to "he", "ir" to "fa",
        "mn" to "mn", "ph" to "fil", "pk" to "ur", "bd" to "bn",
        "lk" to "si", "np" to "ne", "my" to "ms",
    )

    /** The group key for one installed source: a language code, [MULTI], or [OTHER]. */
    fun codeOf(p: ContentProvider): String {
        // The plugin's own declared language — exact, e.g. `hi`, `pt-BR`.
        val declared = p.config.lang?.trim()?.lowercase()
            ?.substringBefore('-')?.substringBefore('_').orEmpty()
        if (declared.isNotBlank()) {
            if (declared == MULTI || declared == "all") return MULTI
            byAliasExact(declared)?.let { return it.code }
        }
        val hay = (p.config.name + " " + p.config.url).lowercase()
        if (multiRe.containsMatchIn(hay)) return MULTI
        bracketCode.findAll(hay).forEach { m ->
            byAliasExact(m.groupValues[1].lowercase())?.let { return it.code }
        }
        // Language names / 3-letter codes as whole words, earliest wins.
        var best: Lang? = null
        var bestAt = Int.MAX_VALUE
        for (lang in langs) {
            for (alias in lang.aliases) {
                if (alias.length < 3) continue
                val at = Regex("""(?<![a-z])${Regex.escape(alias)}(?![a-z])""").find(hay)?.range?.first
                    ?: continue
                if (at < bestAt) {
                    best = lang
                    bestAt = at
                }
            }
        }
        if (best != null) return best.code
        // Site host's country TLD, when it names one language.
        runCatching {
            val host = p.config.url.lowercase().substringAfter("://").substringBefore('/').substringBefore(':')
            val tld = host.substringAfterLast('.')
            if (host.contains('.') && tld.length in 2..3) tldLang[tld]?.let { return it }
        }
        return OTHER
    }

    /** Display name for a group key (`multi` → `Multi`, `other` → `Other`). */
    fun displayOf(code: String): String = when (code) {
        MULTI -> "Multi"
        OTHER -> "Other"
        else -> byCode(code)?.display ?: code
    }

    /** Flag emoji for a group key, or null for Multi/Other. */
    fun emojiOf(code: String): String? = when (code) {
        MULTI, OTHER -> null
        else -> byCode(code)?.emoji
    }

    /** One drawn group: its key plus its sources, sorted by name. */
    data class LangGroup(
        val code: String,
        val display: String,
        val emoji: String?,
        val members: List<ContentProvider>,
    )

    /** [providers] folded into language groups in display order. Only groups
     *  that have members are returned. */
    fun groupsOf(providers: List<ContentProvider>): List<LangGroup> {
        val buckets = LinkedHashMap<String, MutableList<ContentProvider>>()
        for (p in providers) {
            buckets.getOrPut(codeOf(p)) { ArrayList() } += p
        }
        return order.filter { it in buckets }.map { code ->
            val members = buckets[code].orEmpty().sortedBy { it.config.name.lowercase() }
            LangGroup(code, displayOf(code), emojiOf(code), members)
        }
    }
}
