package com.hikari.app.ui

import com.hikari.app.providers.ContentProvider

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
        Lang("or", "\u0B13\u0B21\u0BC0\u0B06", "\uD83C\uDDEE\uD83C\uDDF3", setOf("or", "ori", "odia", "oriya")),
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
        Lang("zh", "中文", "🇨🇳", setOf("zh", "zho", "chi", "cn", "chinese", "mandarin", "cantonese")),
        Lang("ja", "日本語", "🇯🇵", setOf("ja", "jpn", "japanese")),
        Lang("ko", "한국어", "🇰🇷", setOf("ko", "kor", "kr", "korean")),
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


    private fun hasHangul(s: String): Boolean = s.any { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' }
    private fun hasKana(s: String): Boolean = s.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
    private fun hasCjk(s: String): Boolean = s.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
    private fun hasDevanagari(s: String): Boolean = s.any { it in '\u0900'..'\u097F' }
    private fun hasBengali(s: String): Boolean = s.any { it in '\u0980'..'\u09FF' }
    private fun hasTamil(s: String): Boolean = s.any { it in '\u0B80'..'\u0BFF' }
    private fun hasTelugu(s: String): Boolean = s.any { it in '\u0C00'..'\u0C7F' }
    private fun hasMalayalam(s: String): Boolean = s.any { it in '\u0D00'..'\u0D7F' }
    private fun hasGujarati(s: String): Boolean = s.any { it in '\u0A80'..'\u0AFF' }
    private fun hasGurmukhi(s: String): Boolean = s.any { it in '\u0A00'..'\u0A7F' }
    private fun hasKannada(s: String): Boolean = s.any { it in '\u0C80'..'\u0CFF' }
    private fun hasOriya(s: String): Boolean = s.any { it in '\u0B00'..'\u0B7F' }
    private fun hasSinhala(s: String): Boolean = s.any { it in '\u0D80'..'\u0DFF' }
    private fun hasMyanmar(s: String): Boolean = s.any { it in '\u1000'..'\u109F' }
    private fun hasKhmer(s: String): Boolean = s.any { it in '\u1780'..'\u17FF' }
    private fun hasLao(s: String): Boolean = s.any { it in '\u0E80'..'\u0EFF' }
    private fun hasThai(s: String): Boolean = s.any { it in '\u0E00'..'\u0E7F' }
    private fun hasArabic(s: String): Boolean = s.any { it in '\u0600'..'\u06FF' }
    private fun hasHebrew(s: String): Boolean = s.any { it in '\u0590'..'\u05FF' }

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
        // Script detection: the name/URL itself is written in the language
        // (360\u8d44\u6e90, \u4f11\u307f\u306a\u3057, \ub204\uc758 \uce5c\uad6c...).
        // Hangul and kana name their language exactly; CJK ideographs alone
        // read as Chinese (Japanese without a single kana is rare, and the
        // declared language still wins whenever the plugin states one).
        if (hasHangul(hay)) return "ko"
        if (hasKana(hay)) return "ja"
        if (hasThai(hay)) return "th"
        if (hasLao(hay)) return "lo"
        if (hasKhmer(hay)) return "km"
        if (hasMyanmar(hay)) return "my"
        if (hasSinhala(hay)) return "si"
        if (hasDevanagari(hay)) return "hi"
        if (hasBengali(hay)) return "bn"
        if (hasTamil(hay)) return "ta"
        if (hasTelugu(hay)) return "te"
        if (hasMalayalam(hay)) return "ml"
        if (hasGujarati(hay)) return "gu"
        if (hasGurmukhi(hay)) return "pa"
        if (hasKannada(hay)) return "kn"
        if (hasOriya(hay)) return "or"
        if (hasArabic(hay)) return "ar"
        if (hasHebrew(hay)) return "he"
        if (hasCjk(hay)) return "zh"
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
