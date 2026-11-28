package com.hikari.desktop.engine

import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Nuvio JS engine (exact port of the Android NuvioScraper + NuvioRuntime
 * streams path): TMDB-backed catalogs/search/meta/episodes, provider .js for
 * stream extraction via the shared V8 host.
 */
class NuvioEngine(
    private val host: JavetJsHost = JavetJsHost(),
    private val tmdbKey: () -> String = { "" },
) : Engine {
    override val kind: EngineKind = EngineKind.NUVIO

    // ---- install layout ----

    fun dir(): File = File(homeDir(), "engines/nuvio").apply { mkdirs() }

    fun fileFor(providerId: String): File =
        File(dir(), providerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".js")

    // ---- Niches / rows (port of NuvioScraper.RowDef tables) ----

    private enum class Niche { ANIME, HINDI, KDRAMA, FRENCH, PERSIAN, GENERAL }

    private data class RowDef(
        val id: String,
        val kind: EngineMediaKind,
        val path: String,
        val params: Map<String, String>,
        val title: String,
    )

    private val generalRows = listOf(
        RowDef("g0", EngineMediaKind.SERIES, "/trending/all/week", emptyMap(), "Trending"),
        RowDef("g1", EngineMediaKind.MOVIE, "/movie/popular", emptyMap(), "Popular Movies"),
        RowDef("g2", EngineMediaKind.MOVIE, "/movie/top_rated", emptyMap(), "Top Rated Movies"),
        RowDef("g3", EngineMediaKind.MOVIE, "/movie/now_playing", emptyMap(), "In Cinemas"),
        RowDef("g4", EngineMediaKind.MOVIE, "/movie/upcoming", emptyMap(), "Coming Soon"),
        RowDef("g5", EngineMediaKind.SERIES, "/tv/popular", emptyMap(), "Popular Series"),
        RowDef("g6", EngineMediaKind.SERIES, "/tv/top_rated", emptyMap(), "Top Rated Series"),
        RowDef("g7", EngineMediaKind.SERIES, "/tv/airing_today", emptyMap(), "Airing Today"),
        RowDef("g8", EngineMediaKind.SERIES, "/tv/on_the_air", emptyMap(), "On TV"),
    )

    private val animeRows = listOf(
        RowDef("a0", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime"),
        RowDef("a1", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime"),
        RowDef("a2", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "first_air_date.desc"), "New Anime"),
        RowDef("a3", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime Movies"),
        RowDef("a4", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime Movies"),
        RowDef("a5", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "primary_release_date.desc"), "New Anime Movies"),
        RowDef("a6", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Movies"),
        RowDef("a7", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Series"),
        RowDef("a8", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_count.desc"), "Most Discussed Anime"),
    )

    private val hindiRows = listOf(
        RowDef("h0", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Movies"),
        RowDef("h1", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Series"),
        RowDef("h2", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Movies"),
        RowDef("h3", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Series"),
        RowDef("h4", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "primary_release_date.desc"), "New Hindi Movies"),
    )

    private val kdramaRows = listOf(
        RowDef("k0", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Dramas"),
        RowDef("k1", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Korean Dramas"),
        RowDef("k2", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "first_air_date.desc"), "New Korean Dramas"),
        RowDef("k3", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Movies"),
    )

    private val frenchRows = listOf(
        RowDef("f0", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Movies"),
        RowDef("f1", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Series"),
        RowDef("f2", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Movies"),
        RowDef("f3", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Series"),
    )

    private val persianRows = listOf(
        RowDef("p0", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Movies"),
        RowDef("p1", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Series"),
        RowDef("p2", EngineMediaKind.MOVIE, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Persian Movies"),
        RowDef("p3", EngineMediaKind.SERIES, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Persian Series"),
    )

    private fun nicheOf(name: String): Niche {
        val n = name.lowercase()
        return when {
            listOf("anime", "hianime", "anidb", "anikoto", "kurage", "cartoon").any { n.contains(it) } -> Niche.ANIME
            listOf("desi", "hindi", "hindmoviez", "einthusan", "gramcinema", "moonflix", "ctgmovies", "hdghar", "hdhub", "moviebox", "movieblast", "movieshunt", "movies4u", "vegamovies", "zinkmovie", "bollywood").any { n.contains(it) } -> Niche.HINDI
            listOf("kdrama", "kisskh").any { n.contains(it) } -> Niche.KDRAMA
            listOf("movix", "nakios", "purstream", "wiflix", "vostfr", "frenchstream", "streamingvf", "papadustream").any { n.contains(it) } -> Niche.FRENCH
            n.contains("persian") -> Niche.PERSIAN
            else -> Niche.GENERAL
        }
    }

    private fun rowsFor(provider: EngineProvider): List<RowDef> {
        val pool = when (nicheOf(provider.name)) {
            Niche.ANIME -> animeRows
            Niche.HINDI -> hindiRows
            Niche.KDRAMA -> kdramaRows
            Niche.FRENCH -> frenchRows
            Niche.PERSIAN -> persianRows
            Niche.GENERAL -> generalRows
        }
        // Stable rotation per provider so installs don't all show identical rows.
        val offset = (provider.id.hashCode() and 0x7fffffff) % pool.size
        val take = minOf(5, pool.size)
        return List(take) { i -> pool[(offset + i) % pool.size] }
    }

    // ---- Engine API ----

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> =
        rowsFor(provider).map { EngineCatalog(provider.id, it.id, it.title, it.kind) }

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> = withContext(Dispatchers.IO) {
        val def = rowsFor(provider).firstOrNull { it.id == catalog.id } ?: return@withContext emptyList()
        val params = LinkedHashMap(def.params)
        params["page"] = page.coerceAtLeast(1).toString()
        val data = TmdbClient.apiGet(def.path, params, tmdbKey()) ?: return@withContext emptyList()
        val arr = data.getAsJsonArray("results") ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            tmdbMedia(el.asJsonObject, provider.id, def.kind)
        }
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val data = TmdbClient.apiGet(
                "/search/multi",
                mapOf("query" to query, "page" to page.coerceAtLeast(1).toString()),
                tmdbKey(),
            ) ?: return@withContext emptyList()
            val arr = data.getAsJsonArray("results") ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val mt = o.get("media_type")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                if (mt != "movie" && mt != "tv") return@mapNotNull null
                tmdbMedia(o, provider.id, if (mt == "movie") EngineMediaKind.MOVIE else EngineMediaKind.SERIES)
            }
        }

    override suspend fun meta(provider: EngineProvider, media: EngineMedia): EngineMedia =
        withContext(Dispatchers.IO) {
            if (media.id.isBlank() || !media.id.all { it.isDigit() }) return@withContext media
            val path = if (media.kind == EngineMediaKind.SERIES) "/tv/${media.id}" else "/movie/${media.id}"
            val d = TmdbClient.apiGet(path, emptyMap(), tmdbKey()) ?: return@withContext media
            val year = listOf("release_date", "first_air_date").firstNotNullOfOrNull { k ->
                d.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            }
            media.copy(
                title = pickFirstString(d.titleOf("title", "name")) ?: media.title,
                overview = pickFirstString(d.titleOf("overview")) ?: media.overview,
                posterUrl = d.titleOf("poster_path")?.let { TmdbClient.img(it) } ?: media.posterUrl,
                backdropUrl = d.titleOf("backdrop_path")?.let { TmdbClient.img(it, large = true) } ?: media.backdropUrl,
                year = year ?: media.year,
                genres = d.getAsJsonArray("genres")?.mapNotNull {
                    it.asJsonObject?.get("name")?.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf { s -> s.isNotBlank() }
                } ?: media.genres,
            )
        }

    override suspend fun episodes(provider: EngineProvider, media: EngineMedia): List<EngineEpisode> =
        withContext(Dispatchers.IO) {
            if (media.kind != EngineMediaKind.SERIES) return@withContext emptyList()
            if (media.id.isBlank() || !media.id.all { it.isDigit() }) return@withContext emptyList()
            val tv = TmdbClient.apiGet("/tv/${media.id}", emptyMap(), tmdbKey()) ?: return@withContext emptyList()
            val seasons = tv.getAsJsonArray("seasons") ?: return@withContext emptyList()
            val nums = (0 until seasons.size()).mapNotNull { i ->
                val s = seasons[i].takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val n = s.get("season_number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
                if (n > 0) n else null
            }.take(24)
            val key = tmdbKey()
            val rows = coroutineScope {
                nums.map { sn ->
                    async(Dispatchers.IO) {
                        val sd = TmdbClient.apiGet("/tv/${media.id}/season/$sn", emptyMap(), key)
                            ?: return@async emptyList<EngineEpisode>()
                        val eps = sd.getAsJsonArray("episodes") ?: return@async emptyList<EngineEpisode>()
                        buildList {
                            for (i in 0 until eps.size()) {
                                val e = eps[i].takeIf { it.isJsonObject }?.asJsonObject ?: continue
                                val en = e.get("episode_number")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                                if (en <= 0) continue
                                add(
                                    EngineEpisode(
                                        providerId = provider.id,
                                        mediaId = media.id,
                                        id = "${media.id}:$sn:$en",
                                        season = sn,
                                        number = en,
                                        title = e.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                                            ?.takeIf { it.isNotBlank() && it != "null" } ?: "Episode $en",
                                        overview = e.get("overview")?.takeIf { it.isJsonPrimitive }?.asString
                                            ?.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                    }
                }.map { runCatching { it.await() }.getOrDefault(emptyList()) }.flatten()
            }
            rows.sortedWith(compareBy({ it.season }, { it.number }))
        }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> = withContext(Dispatchers.IO) {
        val resolved = if (media.id.all { it.isDigit() } && media.id.isNotBlank()) {
            TmdbClient.Resolved(media.id, if (media.kind == EngineMediaKind.SERIES) "tv" else "movie")
        } else {
            TmdbClient.resolve(media.title, media.year, media.kind, tmdbKey())
        } ?: return@withContext emptyList()
        val source = runCatching { fileFor(provider.id).readText() }.getOrNull()
        if (source.isNullOrBlank()) return@withContext emptyList()
        val mediaType = if (resolved.mediaType.equals("movie", true)) "movie" else "tv"
        val season: Int? = if (episode == null) {
            if (media.kind == EngineMediaKind.SERIES) 1 else null
        } else episode.season
        val epNum: Int? = episode?.number
        val cid = UUID.randomUUID().toString()
        val result = host.execute(
            JsRun(
                scripts = nuvioScripts(),
                startEval = nuvioStreamsCall(cid, source, provider.id, settingsJson(provider), resolved.tmdbId, mediaType, season, epNum),
                pumpEval = null,
                timeoutMs = 75_000,
            ),
            pluginId = provider.id,
        )
        if (!result.ok || result.payload.isNullOrBlank()) return@withContext emptyList()
        val (ok, data) = unwrapData(result.payload)
        if (!ok || data == null) return@withContext emptyList()
        streamsFromData(data, provider.id, provider.name).distinctBy { it.url }
    }

    override suspend fun validate(provider: EngineProvider): String? = withContext(Dispatchers.IO) {
        val source = runCatching { fileFor(provider.id).readText() }.getOrNull()
        if (source.isNullOrBlank()) return@withContext "script file is missing — reinstall this extension"
        val result = host.execute(
            JsRun(
                scripts = nuvioScripts(),
                startEval = "globalThis.__hikariDone(" + nuvioValidateCall(source) + ");",
                pumpEval = null,
                timeoutMs = 30_000,
            ),
            pluginId = provider.id,
        )
        val verdict = result.payload?.trim()?.trim('"')
        if (verdict == "OK") null else "provider has no usable getStreams export"
    }

    private fun settingsJson(provider: EngineProvider): String = "{}"

    private fun nuvioScripts(): List<JsScript> = listOf(
        jsAsset("nuvio/boot.js"),
        jsInline("cheerio-head.js", CHEERIO_HEAD),
        jsAsset("nuvio/cheerio.js"),
        jsInline("cheerio-tail.js", CHEERIO_TAIL),
        jsAsset("nuvio/harness.js"),
        // Best-effort: some providers require('crypto-js'); a dud eval must not fail the run.
        JsScript("nuvio/crypto-js.js", "(function(){try{\n" + EngineAssets.read("nuvio/crypto-js.js") + "\n}catch(e){} })();"),
        jsInline("register.js", nuvioRegisterGlue()),
    )

    private fun tmdbMedia(o: JsonObject, providerId: String, kind: EngineMediaKind): EngineMedia? {
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val title = pickFirstString(
            o.get("title")?.takeIf { it.isJsonPrimitive }?.asString,
            o.get("name")?.takeIf { it.isJsonPrimitive }?.asString,
        ) ?: return null
        return EngineMedia(
            providerId = providerId,
            id = id,
            title = title,
            kind = kind,
            posterUrl = o.get("poster_path")?.takeIf { it.isJsonPrimitive }?.asString?.let { TmdbClient.img(it) },
            backdropUrl = o.get("backdrop_path")?.takeIf { it.isJsonPrimitive }?.asString?.let { TmdbClient.img(it, large = true) },
            overview = o.get("overview")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            year = listOf("release_date", "first_air_date").firstNotNullOfOrNull { k ->
                o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            },
            rating = o.get("vote_average")?.takeIf { it.isJsonPrimitive }?.asDouble,
        )
    }

    private fun JsonObject.titleOf(vararg keys: String): String? {
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isJsonPrimitive) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    companion object {
        fun homeDir(): File {
            val base = System.getProperty("user.home") ?: "."
            return java.io.File(base, ".hikari-desktop")
        }

        val DEFAULT_REPOS = listOf(
            Triple(
                "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
                "Yoru's Nuvio Repo",
                "English nuvio providers (vixsrc, moviebox, showbox, …)",
            ),
            Triple(
                "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
                "Gowaru's Nuvio Repo",
                "Nuvio providers by Gowaru (French-oriented)",
            ),
            Triple(
                "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/manifest.json",
                "Phisher's Nuvio Repo",
                "Nuvio providers by Phisher98",
            ),
            Triple(
                "https://plugin.eclipsia.dpdns.org/manifest.json",
                "Eclipsia",
                "Eclipsia nuvio providers (HDHub4u, VegaMovies, AnimeWorld, …)",
            ),
            Triple(
                "https://raw.githubusercontent.com/D3adlyRocket/Anime-Nuvio/refs/heads/main/manifest.json",
                "All-in-One-Anime",
                "Anime nuvio providers (AllAnime, AniKai, AnimePahe, Anime-Sama, …)",
            ),
            Triple(
                "https://raw.githubusercontent.com/D3adlyRocket/Hindi-Nuvio/refs/heads/main/manifest.json",
                "Hindi-Nuvio",
                "Hindi/Indian nuvio providers (4KHDHub, VegaMovies, HDMovie2, …)",
            ),
        )
    }
}
