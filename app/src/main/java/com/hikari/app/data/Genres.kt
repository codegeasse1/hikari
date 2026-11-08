package com.hikari.app.data

object Genres {

    /** TMDB's film genres by name (case-insensitive). */
    private val MOVIE_IDS: Map<String, Int> =
        TmdbGenres.MOVIE.associate { it.name.lowercase() to it.id }

    /** TMDB's television genres by name (case-insensitive). */
    private val TV_IDS: Map<String, Int> =
        TmdbGenres.TV.associate { it.name.lowercase() to it.id }

    private val TV_FOR_MOVIE: Map<String, List<Int>> = mapOf(
        "action" to listOf(10759),
        "adventure" to listOf(10759),
        "fantasy" to listOf(10765),
        "science fiction" to listOf(10765),
        "war" to listOf(10768),
    )

    private val MOVIE_FOR_TV: Map<String, List<Int>> = mapOf(
        "action & adventure" to listOf(28, 12),
        "sci-fi & fantasy" to listOf(878, 14),
        "war & politics" to listOf(10752),
    )

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

    val ALL: List<String> = run {
        val names = LinkedHashSet<String>()
        TmdbGenres.MOVIE.forEach { names += it.name }
        TmdbGenres.TV.forEach { names += it.name }
        names += ANIME_TAGS
        names.sorted()
    }

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

    fun keywordCandidates(name: String): List<String> {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        return ANIME_KEYWORDS[key] ?: listOf(key)
    }

    private val ALIASES: Map<String, String> = mapOf(
        "accion" to "action",
        "avventura" to "adventure",
        "aventura" to "adventure",
        "abenteuer" to "adventure",
        "animacion" to "animation",
        "animazione" to "animation",
        "comedia" to "comedy",
        "commedia" to "comedy",
        "comedie" to "comedy",
        "krimi" to "crime",
        "crimen" to "crime",
        "terror" to "horror",
        "horreur" to "horror",
        "fantasia" to "fantasy",
        "fantasie" to "fantasy",
        "fantastique" to "fantasy",
        "ciencia ficcion" to "science fiction",
        "sciencefiction" to "science fiction",
        "scifi" to "science fiction",
        "sci fi" to "science fiction",
        "misterio" to "mystery",
        "mystere" to "mystery",
        "romance" to "romance",
        "drame" to "drama",
        "thriller" to "thriller",
        "suspenso" to "thriller",
        "guerra" to "war",
        "western" to "western",
        "oestern" to "western",
    )

    fun normalize(s: String): String {
        var t = s.trim().lowercase()
        t = runCatching {
            java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
        }.getOrDefault(t)
        t = t.replace("&", " and ").replace("+", " plus ")
        t = t.replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (t == "sci fi and fantasy" || t == "sci fi fantasy") return "science fiction fantasy"
        if (t == "action and adventure") return "action adventure"
        if (t == "war and politics") return "war politics"
        return ALIASES[t] ?: t
    }

    fun matches(tag: String, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        val t = normalize(tag)
        if (t.isEmpty()) return false
        if (t.contains(q) || q.contains(t)) return true
        val qt = q.split(" ").filter { it.length > 2 }.toSet()
        val tt = t.split(" ").filter { it.length > 2 }.toSet()
        if (qt.isNotEmpty() && tt.isNotEmpty() && qt.intersect(tt).isNotEmpty()) return true
        return false
    }
}
