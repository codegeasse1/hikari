package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.providers.StremioAddon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Nuvio-style metadata-addon pipeline: detail text/art comes from the user's
 * installed metadata addons first, TMDB second, extension data last.
 *
 * Order (every leg only runs when its own toggle is on, and a miss falls
 * through to the next leg, so a dead backend can never blank a page):
 *
 *   1. AioMetadata (its own Anime/Movies toggles + endpoint — see
 *      [AioMetadata]; first, like Nuvio's configured AIOMetadata addon).
 *   2. Installed Stremio metadata addons (Cinemeta and friends), in provider
 *      order — only those whose manifest declares `meta` for the id.
 *   3. TMDB (existing modules, gated by the TMDB toggles — see [tmdbOn]).
 *   4. Extension/provider data (existing fallbacks, unchanged).
 *
 * The per-family switch ([familyEnabled]) decides whether a title's engine
 * takes part at all: off means the extension's own site metadata is used
 * untouched. A disabled source is never consulted and never silently fallen
 * back to; changing any toggle changes [fingerprint], which is baked into the
 * detail cache key, so old text can never stick.
 */
object MetaSources {

    /** Engine families external metadata may override. Manga-side engines are
     *  excluded — their metadata path is separate. */
    val EXTERNAL_FAMILIES: List<ProviderType> = listOf(
        ProviderType.STREMIO,
        ProviderType.UNIVERSAL,
        ProviderType.CS3,
        ProviderType.HIKARI,
        ProviderType.NUVIO,
        ProviderType.SKYSTREAM,
        ProviderType.ANIYOMI,
        ProviderType.IPTV,
        ProviderType.VEGA,
        ProviderType.SORA,
        ProviderType.ANYMEX,
        ProviderType.ZANGETSU,
        ProviderType.FORK_VIDEO,
    )

    val EXTERNAL_FAMILY_NAMES: List<String> = EXTERNAL_FAMILIES.map { it.name }

    /** Whether [type]'s engine takes part in the pipeline. Absent choice =
     *  on (today's behaviour); the user can switch each family off so its
     *  titles keep pure extension site metadata. */
    suspend fun familyEnabled(type: ProviderType): Boolean =
        runCatching { HikariApp.instance.store.metaFamilyExternal() }
            .getOrDefault(EXTERNAL_FAMILY_NAMES.toSet())
            .contains(type.name)

    /** Master + per-kind TMDB switch for metadata text (the Movies & series /
     *  Anime TMDB toggles plus the master enrichment toggle). */
    suspend fun tmdbOn(item: MediaItem): Boolean {
        val s = HikariApp.instance.store
        if (!runCatching { s.tmdbEnabled() }.getOrDefault(true)) return false
        val anime = item.rawType.contains("anime", ignoreCase = true)
        return runCatching { if (anime) s.animeTmdb() else s.videoTmdb() }.getOrDefault(true)
    }

    /** Cache fingerprint of every switch that changes what the pipeline may
     *  return. Baked into the detail cache key so toggling a source
     *  re-resolves instead of serving the old source's text. [addonsKey] is
     *  the installed Stremio providers' `id:enabled` list in order — toggling
     *  one addon, or reordering Extensions, must invalidate too. */
    suspend fun fingerprint(item: MediaItem, family: ProviderType?, addonsKey: String = ""): String {
        val s = HikariApp.instance.store
        val bits = StringBuilder()
        bits.append(if (runCatching { s.tmdbEnabled() }.getOrDefault(true)) "t1" else "t0")
        bits.append(if (runCatching { s.videoTmdb() }.getOrDefault(true)) "v1" else "v0")
        bits.append(if (runCatching { s.animeTmdb() }.getOrDefault(true)) "n1" else "n0")
        bits.append(if (runCatching { s.videoAio() }.getOrDefault(true)) "a1" else "a0")
        bits.append(if (runCatching { s.animeAio() }.getOrDefault(true)) "m1" else "m0")
        bits.append(runCatching { s.aioBaseUrl() }.getOrDefault("").hashCode().toString(36))
        bits.append(family?.name ?: "?")
        bits.append(runCatching { s.metaFamilyExternal() }.getOrDefault(emptySet()).hashCode().toString(36))
        bits.append(addonsKey.hashCode().toString(36))
        return "|ms" + bits.toString().hashCode().toString(36)
    }

    data class MetaPatch(
        val overview: String?,
        val genres: List<String>,
        val year: Int?,
        val poster: String?,
        val backdrop: String?,
        val rating: Double?,
        val source: String,
    )

    /**
     * Aio first, then installed metadata addons (first trusted hit wins),
     * applied onto [item]. Never throws; a total miss returns [item] as-is.
     */
    suspend fun applyExternal(item: MediaItem, addons: List<StremioAddon>): MediaItem =
        withContext(Dispatchers.IO) {
            var out = item
            runCatching {
                val doc = withTimeoutOrNull(2_000L) { AioMetadata.meta(out) }
                if (doc != null) {
                    patchFromDoc(doc, "AioMetadata", out)?.let {
                        out = applyPatch(out, it)
                        Logs.log("Meta", "external meta: AioMetadata answered for \"${out.title.take(60)}\"")
                    }
                }
            }
            if (out.overview.isNullOrBlank() || out.backdropUrl.isNullOrBlank()) {
                val base = out
                val hit = runCatching {
                    coroutineScope {
                        val jobs = addons.map { addon ->
                            async {
                                withTimeoutOrNull(2_500L) { trustedPatch(addon, base) }
                            }
                        }
                        var first: Pair<StremioAddon, MetaPatch>? = null
                        for (i in addons.indices) {
                            val p = runCatching { jobs[i].await() }.getOrNull()
                            if (p != null) {
                                first = addons[i] to p
                                break
                            }
                        }
                        jobs.forEach { runCatching { it.cancel() } }
                        first
                    }
                }.getOrNull()
                if (hit != null) {
                    out = applyPatch(out, hit.second)
                    Logs.log("Meta", "external meta: ${hit.second.source} answered for \"${out.title.take(60)}\"")
                }
            }
            out
        }

    /**
     * The winning full `/meta` document for the detail shelves (cast,
     * production, trailers, similar): Aio first, then installed metadata
     * addons in provider order, first trusted hit wins. Returns the source
     * name, the doc, and the winning addon's provider id ("" for Aio).
     * Never throws; null means no addon answered.
     */
    suspend fun winningDoc(
        item: MediaItem,
        addons: List<StremioAddon>,
    ): Triple<String, JSONObject, String>? = withContext(Dispatchers.IO) {
        val aioDoc = runCatching {
            withTimeoutOrNull(2_500L) { AioMetadata.meta(item) }
        }.getOrNull()
        if (aioDoc != null && isTrustedDoc(item, aioDoc)) {
            return@withContext Triple("AioMetadata", aioDoc, "")
        }
        val base = item
        runCatching {
            coroutineScope {
                val jobs = addons.map { addon ->
                    async {
                        withTimeoutOrNull(3_000L) {
                            for (id in metaIdCandidates(base)) {
                                val doc = addon.fetchMetaDoc(typeSegs(base).first(), id)
                                    ?: continue
                                if (isTrustedDoc(base, doc)) return@withTimeoutOrNull doc
                            }
                            null
                        }
                    }
                }
                var win: Triple<String, JSONObject, String>? = null
                for (i in addons.indices) {
                    val doc = runCatching { jobs[i].await() }.getOrNull()
                    if (doc != null) {
                        val a = addons[i]
                        win = Triple(
                            a.config.name.ifBlank { a.config.id },
                            doc,
                            a.config.id,
                        )
                        break
                    }
                }
                jobs.forEach { runCatching { it.cancel() } }
                win
            }
        }.getOrNull()
    }

    /** Detail shelves from the addon legs: cast, director, trailers,
     *  production, runtime, IMDb id — works with TMDB off, because the doc
     *  itself (Aio aggregates TMDB server-side) already carries them. */
    suspend fun addonExtras(item: MediaItem, addons: List<StremioAddon>): TitleExtras? {
        val (_, doc, _) = runCatching { winningDoc(item, addons) }.getOrNull() ?: return null
        return runCatching { AddonMetaDetails.toExtras(AddonMetaDetails.parse(doc)) }.getOrNull()
    }

    /** Recommendation/similar entries carried inside the winning addon doc
     *  (Aio serves these; plain Cinemeta usually does not). */
    suspend fun addonSimilar(
        item: MediaItem,
        addons: List<StremioAddon>,
        limit: Int = 18,
    ): List<MediaItem> {
        val (_, doc, providerId) = runCatching { winningDoc(item, addons) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            AddonMetaDetails.similar(
                doc,
                item,
                providerId.ifBlank { item.providerId },
                limit,
            )
        }.getOrDefault(emptyList())
    }

    /** Title logo from the addon legs (Aio/Cinemeta `logo`), for the detail
     *  hero when TMDB — the usual logo source — is off. */
    suspend fun addonLogo(item: MediaItem, addons: List<StremioAddon>): String? {
        val (_, doc, _) = runCatching { winningDoc(item, addons) }.getOrNull() ?: return null
        return runCatching { AddonMetaDetails.parse(doc).logo }.getOrNull()
    }

    private suspend fun trustedPatch(addon: StremioAddon, item: MediaItem): MetaPatch? {        for (id in metaIdCandidates(item)) {
            val doc = addon.fetchMetaDoc(typeSegs(item).first(), id) ?: continue
            patchFromDoc(
                doc,
                addon.config.name.ifBlank { addon.config.id },
                item,
            )?.let { return it }
        }
        return null
    }

    /** Stremio ids to ask an addon for, best first. A site scraper's own
     *  numeric row id is NOT a TMDB id (same rule as [AioMetadata]) — only
     *  trust it when the provider is TMDB-backed or explicitly tmdb:-prefixed. */
    private suspend fun metaIdCandidates(item: MediaItem): List<String> {
        val out = LinkedHashSet<String>()
        val id = item.id.trim()
        if (id.isNotEmpty()) out += id
        val bare = when {
            id.startsWith("tmdb:", ignoreCase = true) -> id.substringAfter(':').trim()
            id.startsWith("tmdb-", ignoreCase = true) -> id.substringAfter('-').trim()
            id.isNotEmpty() && id.all { it.isDigit() } -> id
            else -> ""
        }
        val digits = bare.takeWhile { it.isDigit() }
        val explicit = id.startsWith("tmdb:", ignoreCase = true) || id.startsWith("tmdb-", ignoreCase = true)
        val backed = item.providerId.equals("tmdb", ignoreCase = true) ||
            item.providerId.startsWith("tmdb|", ignoreCase = true) ||
            item.rawType.equals("tmdb", ignoreCase = true)
        if (digits.isNotEmpty() && (explicit || backed)) {
            out += "tmdb:$digits"
            val kind = if (item.type == MediaType.MOVIE) "movie" else "tv"
            if (runCatching { tmdbOn(item) }.getOrDefault(true)) {
                runCatching { withTimeoutOrNull(2_000L) { TmdbBrowse.imdbId(digits, kind) } }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out.toList().take(3)
    }

    private fun typeSegs(item: MediaItem): List<String> {
        val raw = item.rawType.ifBlank { item.type.name.lowercase() }
        return (listOf(raw) + if (item.type == MediaType.MOVIE) listOf("movie") else listOf("series", "tv"))
            .filter { it.isNotBlank() }.distinct()
    }

    private fun patchFromDoc(doc: JSONObject, source: String, item: MediaItem): MetaPatch? {
        val desc = doc.optString("description").trim().takeIf { it.isNotBlank() && it != "null" }
        val poster = doc.optString("poster").trim().takeIf { it.isNotBlank() && it != "null" }
        val backdrop = doc.optString("background").trim().takeIf { it.isNotBlank() && it != "null" }
            ?: doc.optString("backdrop").trim().takeIf { it.isNotBlank() && it != "null" }
        if (desc == null && poster == null && backdrop == null) return null
        if (!isTrustedDoc(item, doc)) return null
        val year = doc.optString("releaseInfo").take(4).toIntOrNull()
            ?: doc.optString("released").take(4).toIntOrNull()
        val genres = doc.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        val rating = doc.optString("imdbRating").trim().toDoubleOrNull()?.takeIf { it > 0 }
        return MetaPatch(desc, genres, year, poster, backdrop, rating, source)
    }

    /** Trust gate: the row must echo an id we sent or name our title back
     *  (same bar as [AioMetadata]), so a wrong addon row can never overwrite
     *  good extension data. */
    private fun isTrustedDoc(item: MediaItem, doc: JSONObject): Boolean {
        val id = item.id.trim()
        val sent = buildList {
            if (id.isNotEmpty()) add(id.lowercase())
            val digits = id.removePrefix("tmdb:").removePrefix("tmdb-").takeWhile { it.isDigit() }
            if (digits.isNotEmpty() && (id.startsWith("tmdb:", true) || id.startsWith("tmdb-", true))) {
                add(digits)
                add("tmdb:$digits")
            }
        }
        val echoed = listOf(
            doc.optString("id"),
            doc.optString("imdb_id"),
            doc.optString("_tmdbId"),
            doc.optString("_tvdbId"),
        ).map { it.trim().lowercase() }.filter { it.isNotBlank() && it != "null" }
        if (sent.any { s -> echoed.any { it == s } }) return true
        val name = doc.optString("name").trim()
        if (name.isEmpty() || name == "null") return false
        val variants = runCatching {
            item.allTitles.flatMap { TmdbMeta.queryVariants(it) }
        }.getOrDefault(emptyList())
        if (variants.isEmpty()) return false
        var best = 0
        for (v in variants) for (n in listOf(name)) {
            best = maxOf(best, runCatching { TmdbMeta.titleScore(v, n) }.getOrDefault(0))
        }
        if (best < 25) return false
        val y = doc.optString("releaseInfo").take(4).toIntOrNull()
            ?: doc.optString("released").take(4).toIntOrNull()
        if (y != null && item.year != null && item.year > 0 && kotlin.math.abs(y - item.year) > 2) return false
        return true
    }

    private fun applyPatch(item: MediaItem, p: MetaPatch): MediaItem {
        var out = item
        if (out.overview.isNullOrBlank() && !p.overview.isNullOrBlank()) out = out.copy(overview = p.overview)
        if (out.genres.isEmpty() && p.genres.isNotEmpty()) out = out.copy(genres = p.genres)
        if (out.year == null && p.year != null) out = out.copy(year = p.year)
        if (out.posterUrl.isNullOrBlank() && !p.poster.isNullOrBlank()) out = out.copy(posterUrl = p.poster)
        if (out.backdropUrl.isNullOrBlank() && !p.backdrop.isNullOrBlank()) out = out.copy(backdropUrl = p.backdrop)
        if (out.rating == null && p.rating != null) out = out.copy(rating = p.rating)
        if (out.metadataSource.isNullOrBlank()) out = out.copy(metadataSource = p.source)
        return out
    }

    /**
     * Episode thumbnails from the addon legs (Aio first, then installed meta
     * addons' `/meta` videos), keyed by season+number. Feeds the player sheet
     * and detail rows when the image CDN is down: a non-TMDB thumbnail beats
     * a dead TMDB still.
     */
    suspend fun episodeThumbs(item: MediaItem, addons: List<StremioAddon>): Map<Pair<Int, Int>, String> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<Pair<Int, Int>, String>()
            runCatching {
                withTimeoutOrNull(2_000L) { AioMetadata.episodes(item) }
            }.getOrNull().orEmpty().forEach { ep ->
                val t = ep.image?.takeIf { it.isNotBlank() } ?: return@forEach
                out.putIfAbsent(ep.season to ep.number, t)
            }
            if (out.size < 4) {
                runCatching {
                    coroutineScope {
                        addons.map { addon ->
                            async {
                                val got = LinkedHashMap<Pair<Int, Int>, String>()
                                for (id in metaIdCandidates(item)) {
                                    val doc = withTimeoutOrNull(2_500L) {
                                        addon.fetchMetaDoc(typeSegs(item).first(), id)
                                    } ?: continue
                                    val videos = doc.optJSONArray("videos") ?: continue
                                    for (i in 0 until videos.length()) {
                                        val v = videos.optJSONObject(i) ?: continue
                                        val s = v.optInt("season", 1).takeIf { it >= 0 } ?: 1
                                        val e = v.optInt("episode", -1)
                                        if (e <= 0) continue
                                        val t = v.optString("thumbnail").trim()
                                            .takeIf {
                                                it.isNotBlank() && it != "null" &&
                                                    !it.contains("missing_thumbnail") &&
                                                    !it.contains("missing-thumbnail")
                                            } ?: continue
                                        got.putIfAbsent(s to e, t)
                                    }
                                    if (got.isNotEmpty()) break
                                }
                                got
                            }
                        }.awaitAll().forEach { m -> m.forEach { (k, v) -> out.putIfAbsent(k, v) } }
                    }
                }
            }
            out
        }

    /** Fills blank or TMDB-hosted episode images from the addon legs. Ids are
     *  never touched (playback ownership stays with the origin); only the
     *  artwork swaps, and only for a non-TMDB host or a blank. */
    suspend fun fillEpisodeImages(
        item: MediaItem,
        eps: List<Episode>?,
        addons: List<StremioAddon>,
    ): List<Episode>? {
        if (eps.isNullOrEmpty()) return eps
        val need = eps.filter { it.image.isNullOrBlank() || it.image!!.contains("image.tmdb.org") }
        if (need.isEmpty()) return eps
        val thumbs = runCatching { withTimeoutOrNull(6_000L) { episodeThumbs(item, addons) } }
            .getOrNull().orEmpty()
        if (thumbs.isEmpty()) return eps
        var changed = false
        val out = eps.map { ep ->
            val t = thumbs[ep.season to ep.number] ?: return@map ep
            val cur = ep.image
            val better = cur.isNullOrBlank() ||
                (cur.contains("image.tmdb.org") && !t.contains("image.tmdb.org"))
            if (better && t != cur) {
                changed = true
                ep.copy(image = t)
            } else ep
        }
        return if (changed) out else eps
    }
}
