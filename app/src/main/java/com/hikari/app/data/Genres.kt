package com.hikari.app.data

/**
 * The one genre vocabulary both genre strips offer — Home's "Browse by genre"
 * and the Search tab's filter — plus what a tap on a name has to send to TMDB.
 *
 * The two screens used to disagree: Search offered the union of TMDB's film and
 * television names PLUS [ANIME_TAGS] (Isekai, Harem, School Life, …), while
 * Home offered only [TmdbGenres.MOVIE]. So the anime vocabulary — the genre
 * names anime and manga sources actually tag their titles with — was pickable
 * in Search and absent on Home, and the television-only names (Reality, Soap,
 * Talk, Sci-Fi & Fantasy, …) were missing from Home too.
 *
 * A genre name is only worth offering if a tap on it can LEAD somewhere, so the
 * two halves are kept together here:
 *
 *  * [tmdbGenreFilter] answers the `with_genres` OR-list for every name TMDB
 *    has a genre id for — BOTH namespaces, because TMDB keeps films and series
 *    in separate id spaces (film 28 "Action" is series 10759 "Action &
 *    Adventure", film 14 "Fantasy" is series 10765 "Sci-Fi & Fantasy"), and a
 *    genre is browsable in both only when both ids are sent. TMDB ignores the
 *    id that belongs to the other namespace rather than erroring, verified
 *    against the live API.
 *  * [keywordCandidates] answers the TMDB KEYWORD names for a genre TMDB has no
 *    genre id for at all (Isekai, Harem, School Life, …). TMDB's keyword
 *    vocabulary does have those — "isekai", "harem", "high school" — and a
 *    keyword can be turned into the `with_keywords` ids discover needs (see
 *    [com.hikari.app.data.TmdbSources]). Without this an anime genre would be a
 *    chip that opens an empty grid.
 */
object Genres {

    /** TMDB's film genres by name (case-insensitive). */
    private val MOVIE_IDS: Map<String, Int> =
        TmdbGenres.MOVIE.associate { it.name.lowercase() to it.id }

    /** TMDB's television genres by name (case-insensitive). */
    private val TV_IDS: Map<String, Int> =
        TmdbGenres.TV.associate { it.name.lowercase() to it.id }

    /**
     * A FILM genre name → the TELEVISION ids that mean the same thing, for the
     * names that differ between the two namespaces. A name spelled the same in
     * both (Drama, Comedy, Animation…) needs no entry: [tmdbGenreFilter] takes
     * the id from each namespace directly.
     */
    private val TV_FOR_MOVIE: Map<String, List<Int>> = mapOf(
        "action" to listOf(10759),
        "adventure" to listOf(10759),
        "fantasy" to listOf(10765),
        "science fiction" to listOf(10765),
        "war" to listOf(10768),
    )

    /**
     * A TELEVISION genre name → the FILM ids that mean the same thing. The
     * three television-only names that have an obvious film counterpart get one
     * so the Home grid carries both kinds of title; the rest (News, Reality,
     * Soap, Talk) are series-only by nature and are left with their TV id.
     */
    private val MOVIE_FOR_TV: Map<String, List<Int>> = mapOf(
        "action & adventure" to listOf(28, 12),
        "sci-fi & fantasy" to listOf(878, 14),
        "war & politics" to listOf(10752),
    )

    /**
     * The genres TMDB has no name for, because they are ANIME's own vocabulary.
     *
     * A manga/anime extension tags a title with these — "Isekai", "School Life",
     * "Shounen", "Seinen", "Mecha", "Harem" — and a TMDB-sourced item can carry
     * "Animation" and nothing else, so without this list the strip could not be
     * used to narrow to any of them. The names are the ones the extension
     * ecosystems actually write (keiyoushi / MangaDex / AniList tags), and they
     * are matched case-insensitively against a result's own genre strings, so a
     * name no source happens to use simply never matches anything.
     *
     * Genres TMDB DOES name are deliberately not repeated here: the union below
     * already carries them.
     */
    val ANIME_TAGS: List<String> = listOf(
        "Isekai",
        "Reincarnation",
        "Regression",
        "Rebirth",
        "System",
        "Cultivation",
        "Transmigration",
        "Villainess",
        "Otome Game",
        "Reverse Harem",
        "Harem",
        "School Life",
        "School",
        "Slice of Life",
        "Iyashikei",
        "Shounen",
        "Shoujo",
        "Seinen",
        "Josei",
        "Mecha",
        "Ecchi",
        "Smut",
        "Mahou Shoujo",
        "Magical Girl",
        "Martial Arts",
        "Swordplay",
        "Samurai",
        "Ninja",
        "Super Power",
        "Supernatural",
        "Space",
        "Magic",
        "Demons",
        "Yokai",
        "Vampire",
        "Monsters",
        "Kaiju",
        "Zombies",
        "Post-Apocalyptic",
        "Apocalypse",
        "Cyberpunk",
        "Steampunk",
        "Dungeon",
        "Guilds",
        "Adventure Quest",
        "Time Travel",
        "Second Chance",
        "Cooking",
        "Gourmet",
        "Food",
        "Idol",
        "Showbiz",
        "Delinquents",
        "Bullying",
        "Revenge",
        "Survival",
        "Parody",
        "Gore",
        "Tragedy",
        "Psychological",
        "Military",
        "Mythology",
        "Historical",
        "Nobility",
        "Royalty",
        "Emperor's Daughter",
        "Royal Family",
        "Age Gap",
        "Arranged Marriage",
        "Cohabitation",
        "Childhood Friends",
        "Love Triangle",
        "Yandere",
        "Tsundere",
        "Gender Bender",
        "Crossdressing",
        "Boys Love",
        "Girls Love",
        "Detective",
        "Police",
        "Espionage",
        "Game",
        "Gaming",
        "Virtual Reality",
        "Sports",
        "Music",
        "Racing",
        "Kids",
        "Award Winning",
        "Workplace",
        "Office Workers",
        "Otaku Culture",
        "Superhero",
        "Webtoon",
        "Manhwa",
        "Manhua",
        "One Shot",
        "Doujinshi",
        "Anime",
    )

    /**
     * An anime tag → the TMDB keyword names to try for it, best first. Only the
     * tags TMDB has no genre id for need an entry; every lookup falls back to
     * the tag itself ([keywordCandidates]).
     *
     * Verified against the live TMDB API (every entry's candidate list resolves,
     * and every resolved keyword answers a non-empty `/discover` grid in at
     * least one namespace — 97 tags checked), which is why two of them carry a
     * spelling TMDB actually uses: "crossdressing" is TMDB's "cross dressing",
     * and TMDB has no guild keyword at all, so "Guilds" falls back to
     * "adventurer" (136 films / 62 series) rather than to an empty grid. Add a
     * candidate only after checking it the same way — a chip that opens an empty
     * page is worse than one that is not offered.
     */
    private val ANIME_KEYWORDS: Map<String, List<String>> = mapOf(
        "isekai" to listOf("isekai", "another world"),
        "reincarnation" to listOf("reincarnation"),
        "regression" to listOf("regression", "second chance"),
        "rebirth" to listOf("rebirth", "reincarnation"),
        "system" to listOf("system"),
        "cultivation" to listOf("cultivation"),
        "transmigration" to listOf("transmigration", "reincarnation"),
        "villainess" to listOf("villainess", "villain"),
        "otome game" to listOf("otome game", "otome"),
        "reverse harem" to listOf("reverse harem", "harem"),
        "harem" to listOf("harem"),
        "school life" to listOf("school life", "high school", "school"),
        "school" to listOf("school", "high school"),
        "slice of life" to listOf("slice of life"),
        "iyashikei" to listOf("iyashikei", "slice of life"),
        "shounen" to listOf("shounen", "shonen"),
        "shoujo" to listOf("shoujo", "shojo"),
        "seinen" to listOf("seinen"),
        "josei" to listOf("josei"),
        "mecha" to listOf("mecha"),
        "ecchi" to listOf("ecchi"),
        "smut" to listOf("smut"),
        "mahou shoujo" to listOf("magical girl", "mahou shoujo"),
        "magical girl" to listOf("magical girl"),
        "martial arts" to listOf("martial arts"),
        "swordplay" to listOf("swordplay", "sword fight"),
        "samurai" to listOf("samurai"),
        "ninja" to listOf("ninja"),
        "super power" to listOf("super power", "superpower", "superhero"),
        "supernatural" to listOf("supernatural"),
        "space" to listOf("space", "outer space"),
        "magic" to listOf("magic"),
        "demons" to listOf("demon", "demons"),
        "yokai" to listOf("yokai", "youkai"),
        "vampire" to listOf("vampire"),
        "monsters" to listOf("monster", "monsters"),
        "kaiju" to listOf("kaiju", "giant monster"),
        "zombies" to listOf("zombie", "zombies"),
        "post-apocalyptic" to listOf("post-apocalyptic", "post-apocalypse"),
        "apocalypse" to listOf("apocalypse"),
        "cyberpunk" to listOf("cyberpunk"),
        "steampunk" to listOf("steampunk"),
        "dungeon" to listOf("dungeon"),
        "guilds" to listOf("guild", "adventurer"),
        "adventure quest" to listOf("quest"),
        "time travel" to listOf("time travel"),
        "second chance" to listOf("second chance"),
        "cooking" to listOf("cooking", "food"),
        "gourmet" to listOf("gourmet", "food"),
        "food" to listOf("food"),
        "idol" to listOf("idol", "idols"),
        "showbiz" to listOf("showbiz", "entertainment"),
        "delinquents" to listOf("delinquent", "delinquents"),
        "bullying" to listOf("bullying", "bully"),
        "revenge" to listOf("revenge"),
        "survival" to listOf("survival"),
        "parody" to listOf("parody"),
        "gore" to listOf("gore"),
        "tragedy" to listOf("tragedy"),
        "psychological" to listOf("psychological"),
        "military" to listOf("military"),
        "mythology" to listOf("mythology"),
        "historical" to listOf("historical", "history"),
        "nobility" to listOf("nobility", "aristocrat"),
        "royalty" to listOf("royalty", "royal family"),
        "emperor's daughter" to listOf("royal family", "princess"),
        "royal family" to listOf("royal family"),
        "age gap" to listOf("age gap"),
        "arranged marriage" to listOf("arranged marriage"),
        "cohabitation" to listOf("cohabitation", "living together"),
        "childhood friends" to listOf("childhood friend", "childhood friends"),
        "love triangle" to listOf("love triangle"),
        "yandere" to listOf("yandere"),
        "tsundere" to listOf("tsundere"),
        "gender bender" to listOf("gender bender", "gender swap"),
        "crossdressing" to listOf("crossdressing", "cross-dressing", "cross dressing"),
        "boys love" to listOf("boys love", "bl"),
        "girls love" to listOf("girls love", "yuri"),
        "detective" to listOf("detective"),
        "police" to listOf("police"),
        "espionage" to listOf("espionage", "spy"),
        "game" to listOf("game", "video game"),
        "gaming" to listOf("video game", "gaming"),
        "virtual reality" to listOf("virtual reality", "vrmmo"),
        "sports" to listOf("sport", "sports"),
        "music" to listOf("music"),
        "racing" to listOf("racing", "race"),
        "kids" to listOf("kids"),
        "award winning" to listOf("award winning"),
        "workplace" to listOf("workplace"),
        "office workers" to listOf("office worker", "workplace"),
        "otaku culture" to listOf("otaku"),
        "superhero" to listOf("superhero"),
        "webtoon" to listOf("webtoon"),
        "manhwa" to listOf("manhwa"),
        "manhua" to listOf("manhua"),
        "one shot" to listOf("one shot"),
        "doujinshi" to listOf("doujinshi"),
        "anime" to listOf("anime"),
    )

    /**
     * Every genre the strips offer, alphabetically: the union of TMDB's film and
     * television genres — the names a TMDB-sourced item carries and the ones an
     * extension's own tags most often match — plus [ANIME_TAGS].
     *
     * A FIXED list on purpose, exactly like the year strip: the names come from
     * the catalogue's own vocabulary, not from what a search happens to have
     * found, so a genre is pickable before a single result is in.
     */
    val ALL: List<String> = run {
        val names = LinkedHashSet<String>()
        TmdbGenres.MOVIE.forEach { names += it.name }
        TmdbGenres.TV.forEach { names += it.name }
        names += ANIME_TAGS
        names.sorted()
    }

    /**
     * The `with_genres` OR-list for a genre [name], or "" when TMDB has no genre
     * id for it (an anime tag — see [keywordCandidates]).
     *
     * Both namespaces are sent, so one chip covers films AND series: a genre
     * name that exists in both contributes both ids, and the cross-namespace
     * maps above cover the names that differ.
     */
    fun tmdbGenreFilter(name: String): String {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return ""
        val ids = LinkedHashSet<Int>()
        MOVIE_IDS[key]?.let { ids += it }
        TV_IDS[key]?.let { ids += it }
        TV_FOR_MOVIE[key]?.let { ids += it }
        MOVIE_FOR_TV[key]?.let { ids += it }
        return ids.joinToString("|")
    }

    /** True when TMDB has a genre id for [name] (so a grid can be opened by id). */
    fun hasTmdbGenre(name: String): Boolean = tmdbGenreFilter(name).isNotEmpty()

    /**
     * The TMDB keyword names to try for an anime tag, best first. Falls back to
     * the tag itself, so a tag with no curated entry still has a chance of
     * resolving (TMDB's keyword vocabulary is large and keeps growing).
     */
    fun keywordCandidates(name: String): List<String> {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        return ANIME_KEYWORDS[key] ?: listOf(key)
    }
}
