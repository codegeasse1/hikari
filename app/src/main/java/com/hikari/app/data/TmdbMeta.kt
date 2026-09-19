package com.hikari.app.data

import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import org.json.JSONObject

/**
 * The parts of TMDB the app needs besides stream resolution: alternative
 * artwork for titles an extension left blank, and the "Similar"/"Related"
 * shelves on a detail page.
 *
 * Artwork lookup is deliberately two-stage, because neither source covers
 * everything:
 *
 *  1. TMDB (the app's existing resolver turns a title/year, a numeric id or an
 *     IMDb `tt` id into a tmdbId, and `/movie/{id}` / `/tv/{id}` carries the
 *     poster and backdrop paths). This is the good art when it exists.
 *  2. IMDb's public suggestion endpoint — no key, keyed by title — which
 *     returns both the `tt` id and a poster image URL. This is what fills in
 *     the titles TMDB has no image for (regional/Indian catalogs especially).
 *
 * A title that neither source has art for is a genuine miss and is cached as
 * such, so a blank cell costs one lookup, not one per recomposition.
 */
object TmdbMeta {

    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private const val IMG_WIDE = "https://image.tmdb.org/t/p/w780"
    /** Cast headshots: TMDB's small profile size renders well in a circle. */
    private const val IMG_PROFILE = "https://image.tmdb.org/t/p/w185"

    /** TMDB endpoint segment ("movie" | "tv") for a resolved media type. */
    private fun segment(mediaType: String): String =
        if (mediaType.equals("movie", true)) "movie" else "tv"

    /** Reads a TMDB image path, treating JSON null / "" / "null" as absent.
     *  org.json's `optString` returns the literal string "null" for a JSON
     *  null, which used to be accepted as a path and produced URLs like
     *  "…/w500null" (HTTP 404 → blank cell) while also short-circuiting the
     *  IMDb fallback below, since the "path" looked present. */
    private fun JSONObject.tmdbPath(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun yearOf(o: JSONObject): Int? {
        val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
        return raw.take(4).takeIf { it.length == 4 }?.toIntOrNull()
    }

    /**
     * (poster, backdrop) for [item] from TMDB, falling back to IMDb when TMDB
     * has no usable image. Either element of the pair may be null; null as a
     * whole means neither source knew this title.
     */
    suspend fun artwork(item: MediaItem): Pair<String?, String?>? {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull()
        if (resolved != null) {
            val seg = segment(resolved.mediaType)
            val d = TmdbResolver.apiGet("/$seg/${resolved.tmdbId}", emptyMap())
            if (d != null) {
                val p = d.tmdbPath("poster_path")?.let { IMG + it }
                val b = d.tmdbPath("backdrop_path")?.let { IMG_WIDE + it }
                if (p != null || b != null) return p to b
            }
        }
        return imdbArtwork(item)
    }

    /** Title shape for matching across sources: lowercase, punctuation dropped,
     *  word/digit forms unified. IMDb spells a sequel "Ramayana Part 2" while the
     *  catalog says "Ramayana: Part Two"; without this the suggestion result was
     *  rejected by the `startsWith` test below and the cell kept its placeholder
     *  icon forever. */
    private val NUMERALS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    /** Lower-cased, punctuation-stripped, numeral-normalised title — the form
     *  used to match a title across sources that punctuate it differently
     *  ("Ramayana: Part Two" vs "Ramayana Part 2"). Also used by the episode
     *  fallback to pick the right hit out of an extension's search results. */
    fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { NUMERALS[it] ?: it }

    /**
     * How well [candidate] names the same title as [query]: 60 for a match, 40
     * for one being the other's prefix ("Sword of Coming" / "Sword of Coming
     * Season 2"), 25 for a containment, 0 otherwise. Both the raw lower-cased
     * forms and the punctuation/numeral-normalised forms are tried, because
     * [normalizeTitle] drops CJK entirely — a Chinese title has to be compared
     * as-is while a decorated Latin one needs normalising.
     */
    fun titleScore(query: String, candidate: String): Int {
        val q = query.trim().lowercase()
        val n = candidate.trim().lowercase()
        if (q.isBlank() || n.isBlank()) return 0
        var best = scorePair(q, n)
        val qn = normalizeTitle(q)
        val nn = normalizeTitle(n)
        if (qn.isNotBlank() && nn.isNotBlank()) best = maxOf(best, scorePair(qn, nn))
        return best
    }

    private fun scorePair(q: String, n: String): Int = when {
        n == q -> 60
        (n.length >= 5 && q.startsWith(n)) || (q.length >= 5 && n.startsWith(q)) -> 40
        (q.length >= 6 && n.contains(q)) || (n.length >= 6 && q.contains(n)) -> 25
        else -> 0
    }

    /** The season/part ordinal a site title names ("Sword of Coming Season 2",
     *  "斗破苍穹 第二季" → 2), or null. Extensions commonly split a donghua's run
     *  into per-season items, which restart their episode numbering at 1 — the
     *  episode enricher needs to know so it does not map season 2's first
     *  episode onto the season-1 title. */
    fun seasonHint(raw: String): Int? {
        SEASON_NUM.find(raw)?.let { m ->
            val digits = m.groupValues[1].ifBlank { m.groupValues[2] }
            digits.toIntOrNull()?.let { return it }
        }
        SEASON_CN.find(raw)?.let { return cnNumber(it.groupValues[1]) }
        return null
    }

    /** Strings that mark season/part [n] inside a name, for matching against a
     *  database entry that titles its seasons its own way ("第二季", "Season 2",
     *  "Part 2"). */
    fun seasonMarkers(n: Int): List<String> {
        val cn = CN_NUMERALS[n] ?: n.toString()
        val ordinal = when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
        return listOf(
            "season $n", "season$n", "$ordinal season",
            "part $n", "part$n",
            "第${cn}季", "第${n}季", "第${cn}部", "第${n}部", "第${cn}篇", "第${n}篇",
        )
    }

    /** Chinese numeral ("二", "十二", "21") to Int. */
    fun cnNumber(raw: String): Int? {
        val t = raw.trim()
        t.toIntOrNull()?.let { return it }
        if (t.isEmpty()) return null
        if (t == "十") return 10
        if (t.length == 1) return CN_DIGITS[t[0]]
        if (t[0] == '十' && t.length == 2) return CN_DIGITS[t[1]]?.let { 10 + it }
        if (t.length >= 2 && t[1] == '十') {
            val tens = CN_DIGITS[t[0]] ?: return null
            if (t.length == 2) return tens * 10
            if (t.length == 3) return CN_DIGITS[t[2]]?.let { tens * 10 + it }
        }
        return null
    }

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val CN_NUMERALS = mapOf(
        1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "七",
        8 to "八", 9 to "九", 10 to "十", 11 to "十一", 12 to "十二", 13 to "十三",
        14 to "十四", 15 to "十五", 16 to "十六", 17 to "十七", 18 to "十八",
        19 to "十九", 20 to "二十", 21 to "二十一", 22 to "二十二", 23 to "二十三",
    )

    private val SEASON_NUM = Regex(
        "(?i)(?:\\bseason|\\bseries|\\bpart|\\bcour|\\bbook|\\bvolume)\\s*(\\d+)\\b|\\b(\\d+)\\s*(?:st|nd|rd|th)\\s+season\\b"
    )
    private val SEASON_CN = Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*[季部篇]")
    private val SEASON_MARKER = Regex(
        "(?i)\\b(?:season|series|part|cour|book|volume)\\s*\\d+\\b" +
            "|\\b\\d+\\s*(?:st|nd|rd|th)\\s+season\\b" +
            "|第\\s*[0-9一二三四五六七八九十]+\\s*[季部篇]"
    )
    private val BRACKETED = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|（[^）]*）|【[^】]*】")
    private val SPACES = Regex("\\s+")

    /**
     * Progressively simpler TMDB search queries for a title that carries
     * decorations the TMDB index does not match — site metas are full of them:
     *
     *   "Sword of Coming Season 2"        → "Sword of Coming"
     *   "Battle Through The Heavens: Origin" → "Battle Through The Heavens"
     *   "One Piece (2023)"                → "One Piece"
     *   "剑来 第二季"                        → "剑来"
     *
     * The full title is always first, so an exact hit still wins; the stripped
     * forms only get used when it returns nothing.
     *
     * The minimum length for a stripped form is TWO characters, not three:
     * Chinese titles are routinely that short, and a threshold of three
     * silently dropped "剑来" — so a site item called "剑来 第二季" never resolved
     * to anything at all, losing its cast/related/similar rows along with its
     * episode titles. A rejected query merely costs one wasted search.
     */
    fun queryVariants(raw: String): List<String> {
        val t = raw.trim()
        if (t.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(t)
        val flat = t.replace(BRACKETED, " ").replace(SPACES, " ").trim()
        if (flat.isNotBlank()) out.add(flat)
        for (base in listOf(t, flat)) {
            val noSeason = base.replace(SEASON_MARKER, " ").replace(SPACES, " ")
                .trim().trim('-', '–', '—', ':', '：', '|', '.').trim()
            if (noSeason.length >= 2) out.add(noSeason)
            val head = base.substringBefore("：").substringBefore(":")
                .substringBefore(" - ").trim()
            if (head.length >= 2 && head.length < base.length) out.add(head)
        }
        return out.filter { it.length >= 2 }
    }

    /**
     * IMDb's suggestion endpoint (`/suggestion/h/<query>.json`) needs no API
     * key and answers with `d: [{ l: title, y: year, i: { imageUrl } }]`. The
     * image URLs are on m.media-amazon.com and load like any other poster.
     */
    private suspend fun imdbArtwork(item: MediaItem): Pair<String?, String?>? {
        val title = item.title.trim()
        if (title.isBlank()) return null
        val q = runCatching {
            java.net.URLEncoder.encode(title.lowercase(), "UTF-8")
        }.getOrNull() ?: return null
        val text = Http.getString(
            "https://v3.sg.media-imdb.com/suggestion/h/$q.json",
            mapOf("Accept" to "application/json")
        ) ?: return null
        val arr = runCatching {
            JSONObject(text).optJSONArray("d")
        }.getOrNull() ?: return null
        val wanted = normalizeTitle(title)
        var best: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = normalizeTitle(o.optString("l"))
            if (label.isBlank()) continue
            var score = 0
            if (label == wanted) score += 50
            else if (wanted.length >= 5 && (label.startsWith(wanted) || wanted.startsWith(label))) score += 20
            else continue
            if (item.year != null && o.optString("y") == item.year.toString()) score += 30
            val img = o.optJSONObject("i")?.optString("imageUrl").orEmpty()
            if (img.isBlank()) continue
            if (score > bestScore) {
                bestScore = score
                best = img
            }
        }
        return best?.let { it to null }
    }

    /** TMDB's "similar" titles for [item] (same genre/vibe). */
    suspend fun similar(item: MediaItem, limit: Int = 18): List<MediaItem> =
        shelf(item, "similar", limit)

    /** TMDB's "recommendations" for [item] (what people watched next). */
    suspend fun related(item: MediaItem, limit: Int = 18): List<MediaItem> =
        shelf(item, "recommendations", limit)

    private suspend fun shelf(item: MediaItem, kind: String, limit: Int): List<MediaItem> {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return emptyList()
        val seg = segment(resolved.mediaType)
        val data = TmdbResolver.apiGet("/$seg/${resolved.tmdbId}/$kind", emptyMap())
            ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MediaItem>(limit)
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == resolved.tmdbId) continue
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (title.isBlank()) continue
            val type = if (seg == "movie") MediaType.MOVIE else MediaType.SERIES
            val poster = o.tmdbPath("poster_path")?.let { IMG + it }
            val backdrop = o.tmdbPath("backdrop_path")?.let { IMG_WIDE + it }
            // A shelf cell with no art at all reads as a hole in the row, so
            // leave those out rather than padding the shelf with blanks.
            if (poster == null && backdrop == null) continue
            out.add(
                MediaItem(
                    providerId = "tmdb",
                    id = id,
                    title = title,
                    type = type,
                    posterUrl = poster,
                    year = yearOf(o),
                    overview = o.optString("overview").takeIf { it.isNotBlank() },
                    backdropUrl = backdrop,
                    rawType = "tmdb",
                    rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                    // The name the extensions index this title under (a shelf is
                    // built from TMDB, so a non-English language renames it).
                    originalTitle = o.optString("original_title")
                        .ifBlank { o.optString("original_name") }
                        .trim()
                        .takeIf { it.isNotBlank() && it != "null" }
                        .orEmpty(),
                )
            )
        }
        return out
    }

    /** A trailer with its sort rank, so the ranking logic stays readable. */
    private data class ScoredTrailer(val score: Int, val trailer: Trailer)

    /**
     * The detail page's extra sections — the "Show Details" metadata block, the
     * Cast row and the Trailers row — in ONE TMDB call. `append_to_response`
     * bundles `credits`, `videos` and the per-region certification list
     * (`release_dates` for movies, `content_ratings` for series) into the
     * details response, so opening a page costs one extra request, not three.
     *
     * Like [artwork] and [shelf] this is a bonus that runs in the background:
     * a slow or failed lookup simply leaves the sections out, never blocking
     * the page or playback. Null when the title can't be resolved to a TMDB id.
     */
    suspend fun extras(item: MediaItem): TitleExtras? {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return null
        val seg = segment(resolved.mediaType)
        val certKey = if (seg == "movie") "release_dates" else "content_ratings"
        val d = TmdbResolver.apiGet(
            "/$seg/${resolved.tmdbId}",
            mapOf("append_to_response" to "credits,videos,$certKey,external_ids"),
        ) ?: return null

        val isMovie = seg == "movie"
        // Movie runtime is one number; a series carries a per-episode list and,
        // as a fallback, the runtime of its most recent episode.
        val runtime = if (isMovie) {
            d.optInt("runtime").takeIf { it > 0 }
        } else {
            d.optJSONArray("episode_run_time")
                ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.firstOrNull { it > 0 } }
                ?: d.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 }
        }

        val credits = d.optJSONObject("credits")
        val crew = credits?.optJSONArray("crew")
        val directors = crewNames(crew, setOf("Director"), limit = 2).ifEmpty {
            // Series list their creators separately instead of as crew.
            val cb = d.optJSONArray("created_by")
            (0 until (cb?.length() ?: 0)).mapNotNull { i ->
                cb?.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        val writers = crewNames(crew, setOf("Writer", "Screenplay", "Story"), limit = 3)

        val details = TitleDetails(
            status = d.optString("status").trim().takeIf { it.isNotBlank() },
            runtimeMinutes = runtime,
            year = yearOf(d),
            rating = d.optDouble("vote_average").takeIf { it > 0.0 },
            voteCount = d.optInt("vote_count").takeIf { it > 0 },
            certification = certificationOf(d, seg),
            country = originCountryOf(d),
            language = d.optString("original_language").trim()
                .takeIf { it.isNotBlank() }?.uppercase(),
            director = directors.takeIf { it.isNotEmpty() }?.joinToString(", "),
            writers = writers,
            imdbId = d.optJSONObject("external_ids")
                ?.optString("imdb_id")?.trim()
                ?.takeIf { it.startsWith("tt") && it.length >= 8 },
        )

        val cast = ArrayList<CastMember>(20)
        val castArr = credits?.optJSONArray("cast")
        for (i in 0 until (castArr?.length() ?: 0)) {
            if (cast.size >= 20) break
            val o = castArr?.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isBlank()) continue
            cast.add(
                CastMember(
                    name = name,
                    character = o.optString("character").trim().takeIf { it.isNotBlank() },
                    profileUrl = o.tmdbPath("profile_path")?.let { IMG_PROFILE + it },
                )
            )
        }

        // Anime: the credits list voice actors, whose faces mean nothing to
        // someone who knows the show — the characters do. AniList is asked for
        // them (see [AnimeCast]), and only a confident title+year match is
        // trusted; anything else keeps TMDB's list, so the row can be wrong in
        // the direction of "still correct, just the actors".
        var castIsCharacters = false
        val displayCast = if (isAnimeTitle(d) && item.title.isNotBlank()) {
            val characters = AnimeCast.characters(
                item.title,
                yearOf(d),
                cacheKey = "tmdb:$seg:${resolved.tmdbId}",
            )
            if (characters.isNotEmpty()) {
                castIsCharacters = true
                characters
            } else {
                cast
            }
        } else {
            cast
        }

        // Trailers before teasers, official before unofficial — the order the
        // reference clients show them in. `videos` mixes everything together.
        val ranked = ArrayList<ScoredTrailer>(12)
        val vids = d.optJSONObject("videos")?.optJSONArray("results")
        for (i in 0 until (vids?.length() ?: 0)) {
            val o = vids?.optJSONObject(i) ?: continue
            if (!o.optString("site").equals("YouTube", true)) continue
            val key = o.optString("key").trim()
            if (key.isBlank()) continue
            val type = o.optString("type").trim().ifBlank { "Video" }
            val name = o.optString("name").trim().ifBlank { type }
            var score = when {
                type.equals("Trailer", true) -> 30
                type.equals("Teaser", true) -> 20
                else -> 10
            }
            if (o.optBoolean("official")) score += 5
            ranked.add(
                ScoredTrailer(
                    score,
                    Trailer(
                        youtubeKey = key,
                        name = name,
                        type = type,
                        thumbnailUrl = "https://img.youtube.com/vi/$key/hqdefault.jpg",
                    )
                )
            )
        }
        ranked.sortByDescending { it.score }
        val trailers = ranked.take(12).map { it.trailer }

        return TitleExtras(
            details = details,
            cast = displayCast,
            trailers = trailers,
            castIsCharacters = castIsCharacters,
            // The same response that was localized for the page also carries the
            // original name — so the two names a provider lookup needs arrive
            // together, for free, in the call the page already makes.
            localizedTitle = d.optString("title").ifBlank { d.optString("name") }
                .trim().takeIf { it.isNotBlank() && it != "null" },
            originalTitle = d.optString("original_title").ifBlank { d.optString("original_name") }
                .trim().takeIf { it.isNotBlank() && it != "null" },
            // The plot summary, in the very same response and therefore in the
            // very same language as [localizedTitle] — see [TitleExtras.overview].
            overview = d.optString("overview").trim()
                .takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /**
     * The (localized, original) pair of names for an item, from TMDB alone.
     *
     * Used where the two matter and [extras] is not being fetched — the play
     * intents (so the player's artwork card can print the title in the chosen
     * language) and a detail page whose origin provider is gone (so a provider
     * lookup has the original name to search with). One request, cached per
     * title, and null whenever TMDB cannot resolve the item at all.
     */
    suspend fun titles(item: MediaItem): Pair<String, String>? {
        val key = item.originalTitle + "\u0001" + item.title + "\u0001" + item.type.name
        synchronized(titleCache) { titleCache[key] }?.let { return it }
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return null
        val seg = segment(resolved.mediaType)
        val d = TmdbResolver.apiGet("/$seg/${resolved.tmdbId}", emptyMap()) ?: return null
        val localized = d.optString("title").ifBlank { d.optString("name") }
            .trim().takeIf { it.isNotBlank() && it != "null" }
        val original = d.optString("original_title").ifBlank { d.optString("original_name") }
            .trim().takeIf { it.isNotBlank() && it != "null" }
        if (localized == null && original == null) return null
        val out = (localized ?: original!!) to (original ?: localized!!)
        synchronized(titleCache) { titleCache[key] = out }
        return out
    }

    /** Bounded per-session memo for [titles] — a title is opened over and over. */
    private val titleCache = HashMap<String, Pair<String, String>>()

    /**
     * The (localized, original) pair for a bare TMDB id — the case a detail page
     * arrives in, where all it has is `providerId = "tmdb"` and a numeric id
     * (the row's own localized name came through the route, and a stored item
     * from an older build may carry no `originalTitle` at all). Probes both
     * namespaces when [type] does not say which one it is.
     */
    suspend fun titlesForId(id: String, type: MediaType): Pair<String, String>? {
        val numeric = id.trim()
        if (numeric.isBlank() || !numeric.all { it.isDigit() }) return null
        val segs = when (type) {
            MediaType.MOVIE -> listOf("movie")
            MediaType.SERIES -> listOf("tv")
            else -> listOf("movie", "tv")
        }
        for (seg in segs) {
            val d = TmdbResolver.apiGet("/$seg/$numeric", emptyMap()) ?: continue
            val localized = d.optString("title").ifBlank { d.optString("name") }
                .trim().takeIf { it.isNotBlank() && it != "null" } ?: continue
            val original = d.optString("original_title").ifBlank { d.optString("original_name") }
                .trim().takeIf { it.isNotBlank() && it != "null" }
            return localized to (original ?: localized)
        }
        return null
    }

    /**
     * True when TMDB's own detail response describes an ANIME title: an animated
     * work whose original language is Japanese (or which is from Japan). The
     * distinction matters because only for those does a character list exist
     * somewhere that can be paired with the title (see [AnimeCast]); Western
     * animation's "cast" is already its voice cast, which TMDB lists with the
     * character each actor plays, so those rows need no substitution.
     */
    private fun isAnimeTitle(d: JSONObject): Boolean {
        val animated = d.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).any {
                arr.optJSONObject(it)?.optString("name")?.equals("Animation", true) == true
            }
        } ?: false
        if (!animated) return false
        if (d.optString("original_language").trim().equals("ja", true)) return true
        return d.optJSONArray("origin_country")?.let { arr ->
            (0 until arr.length()).any { arr.optString(it).equals("JP", true) }
        } ?: false
    }

    /** Names of the crew members whose `job` is in [jobs], in listing order. */
    private fun crewNames(crew: org.json.JSONArray?, jobs: Set<String>, limit: Int): List<String> {
        if (crew == null) return emptyList()
        val out = ArrayList<String>(limit)
        for (i in 0 until crew.length()) {
            if (out.size >= limit) break
            val o = crew.optJSONObject(i) ?: continue
            if (o.optString("job").trim() !in jobs) continue
            val n = o.optString("name").trim()
            if (n.isNotBlank() && n !in out) out.add(n)
        }
        return out
    }

    /** Age rating, preferred the way a viewer recognises it: the US rating
     *  first (R / PG-13 / TV-MA), then the other English-speaking boards, and
     *  only then whatever region TMDB happens to list first. Every title that
     *  has *any* rating gets one, which is what puts a certification on nearly
     *  every detail page instead of only the ones TMDB rated for the US. */
    private fun certificationOf(d: JSONObject, seg: String): String? {
        val arr = d.optJSONObject(if (seg == "movie") "release_dates" else "content_ratings")
            ?.optJSONArray("results") ?: return null
        val preferred = listOf("US", "GB", "AU", "CA", "IE", "NZ")
        var fallback: String? = null
        var preferredHit: String? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val iso = r.optString("iso_3166_1")
            var cert: String? = null
            if (seg == "movie") {
                val dates = r.optJSONArray("release_dates") ?: continue
                for (j in 0 until dates.length()) {
                    val c = dates.optJSONObject(j)?.optString("certification")?.trim().orEmpty()
                    if (c.isNotBlank()) { cert = c; break }
                }
            } else {
                cert = r.optString("rating").trim().takeIf { it.isNotBlank() }
            }
            if (cert == null) continue
            if (iso == "US") return cert
            if (iso in preferred && preferredHit == null) preferredHit = cert
            if (fallback == null) fallback = cert
        }
        return preferredHit ?: fallback
    }

    private fun originCountryOf(d: JSONObject): String? {
        d.optJSONArray("origin_country")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optString(i).trim()
                if (c.isNotBlank()) return c
            }
        }
        val pc = d.optJSONArray("production_countries")
        for (i in 0 until (pc?.length() ?: 0)) {
            val c = pc?.optJSONObject(i)?.optString("iso_3166_1")?.trim()
            if (!c.isNullOrBlank()) return c
        }
        return null
    }
}
