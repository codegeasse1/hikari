package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.data.TmdbBrowse
import com.hikari.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A full implementation of the Stremio Addon Protocol
 * (https://github.com/Stremio/stremio-addon-sdk) — the same protocol the real
 * Stremio client uses, so ANY Stremio addon works here:
 *
 *   /manifest.json                       addon metadata (catalogs, resources, config)
 *   /catalog/{type}/{id}.json            content feeds  (+ optional extra args in path:
 *                                        e.g. `/catalog/movie/top/search=foo.json`,
 *                                        `skip=100.json` for paging)
 *   /meta/{type}/{id}.json               full metadata (series episodes, movie details)
 *   /stream/{type}/{videoId}.json        playable streams (http URLs, torrent infoHash,
 *                                        YouTube ytId, externalUrl)
 *   /subtitles/{type}/{id}.json          subtitles
 *
 * Addon hosts get a browser-like fingerprint (User-Agent + Accept), https→http
 * fallback, the optional `/manifest.json` suffix and query params (addon
 * config keys) are preserved, and torrent streams carry their infoHash +
 * fileIdx + trackers so the player's TorrServer engine can actually play them.
 *
 * Type handling: the protocol's {type} URL segment is the addon's OWN literal
 * type string ("movie", "series", but also "tv", "anime", "channel", or any
 * custom type), NOT a normalized name. We keep the raw string end-to-end and
 * only normalize to MediaType for the UI, treating anything unknown as SERIES
 * so catalogs are never dropped as "no usable catalogs".
 */
/**
 * One subtitle lookup's outcome: the tracks, the ids the addon was asked with,
 * and — when it answered nothing at all — why. See
 * [StremioAddon.subtitlesForDetailed]; the ids are what the player's "no
 * subtitles found" line reports so the failure is diagnosable from the device.
 */
data class SubtitleLookup(
    val tracks: List<SubtitleSource> = emptyList(),
    val idsTried: List<String> = emptyList(),
    val note: String = "",
)

class StremioAddon(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider reason why its home catalog failed (empty = it works). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        /** Set when a manifest loaded fine but declares NO catalogs — a normal
         *  stream-only addon (Torrentio, Comet, Novastream…). Not an error; it
         *  just contributes playback sources, never home rows. */
        val streamOnlyAddons = ConcurrentHashMap<String, Boolean>()

        /** What a loaded manifest says it can do ("streams, metadata"), for the
         *  addon's info dialog — an addon has no settings screen to open, so the
         *  gear shows this instead (see [Extras]). */
        val resourceSummary = ConcurrentHashMap<String, String>()

        /** Per-provider reason why source lookup came back empty. Displayed in
         *  the playback sheet so "no playable sources found" is explainable. */
        val streamErrors = ConcurrentHashMap<String, String>()

        /**
         * The words a `streams[]` row uses when it is a message rather than a
         * video — see [isPlaceholderRow]. Deliberately about *refusals* ("you
         * must sign in", "subscription required", "no sources") and not about
         * quality words, which real rows are full of.
         */
        private val PLACEHOLDER_WORDS = listOf(
            "must sign", "sign in", "sign-in", "signin", "sign up", "signup",
            "log in", "login", "logged out",
            "subscribe", "subscription", "premium", "upgrade", "membership",
            "no sources", "no source", "no streams", "no server", "no playable",
            "not available", "unavailable", "not found", "not supported",
            "unauthorized", "not authorized", "expired", "invalid api", "invalid key",
            "debrid key", "daily limit", "rate limit", "quota",
        )

        /** URL fragments that only ever appear on a "you cannot have this" link. */
        private val PLACEHOLDER_URL_PARTS = listOf(
            "signin", "sign-in", "sign_in", "/login", "login.mp4", "subscribe", "upgrade.mp4",
        )

        /** How long a caller waits for a manifest before giving up on it (the
         *  fetch itself keeps running and still fills the cache if it lands). */
        private const val MANIFEST_TIMEOUT_MS = 15_000L

        /** How long a subtitle lookup may spend resolving a title to an
         *  IMDb/TMDB id before it gives up on that id route (the direct ids are
         *  tried first and usually answer).
         *
         *  Raised from 8s: resolution by NAME walks several stripped variants of
         *  the title across TMDB's movie and tv namespaces and then IMDb's
         *  suggestion endpoint, and on a slow connection 8s ran out mid-walk —
         *  which left ONLY a `tmdb:` id in hand, and OpenSubtitles v3 answers
         *  that with an empty list. The result was "no subtitles found" for
         *  every title on that connection. The caller's own budget is longer
         *  than this (see PlayerActivity's ADDON_SUBTITLE_MS) so the walk is
         *  always allowed to finish. */
        private const val RESOLVE_TIMEOUT_MS = 20_000L

        /** How long a FAILED manifest fetch is remembered, so a dead host is not
         *  re-probed by every search/meta/episode/stream call in between. */
        private const val MANIFEST_RETRY_MS = 45_000L

        /** "3 catalogs, streams, metadata" — the manifest's `resources` list in
         *  plain words, for the addon's info dialog. */
        private fun summarize(m: JSONObject, catalogCount: Int): String {
            val parts = ArrayList<String>()
            if (catalogCount > 0) {
                parts += "$catalogCount catalog" + (if (catalogCount == 1) "" else "s")
            }
            val arr = m.optJSONArray("resources")
            if (arr == null) {
                // A manifest that declares no resources at all is assumed to do
                // the usual addon job (that is the protocol's default).
                parts += "streams"
            } else {
                for (i in 0 until arr.length()) {
                    val r = arr.opt(i)
                    val name = when (r) {
                        is String -> r
                        is JSONObject -> r.optString("name")
                        else -> null
                    }
                    val label = when (name?.lowercase()) {
                        "stream" -> "streams"
                        "meta" -> "metadata"
                        "subtitles" -> "subtitles"
                        "catalog" -> "catalogs"
                        null -> null
                        else -> name
                    }
                    if (label != null && !parts.contains(label)) parts += label
                }
            }
            return parts.joinToString(", ").ifBlank { "nothing declared" }
        }
    }

    // ------------------------------------------------------------------
    //  URL handling — tolerate /manifest.json suffix and keep query params
    //  (some addons bake config like `?apiKey=...` into their install URL).
    // ------------------------------------------------------------------
    private val baseAndQuery: Pair<String, String> by lazy {
        val u = config.url.trim().trimEnd('/')
        val qi = u.indexOf('?')
        val path = if (qi >= 0) u.substring(0, qi) else u
        val query = if (qi >= 0) u.substring(qi + 1) else ""
        val clean = if (path.lowercase().endsWith("/manifest.json")) {
            path.dropLast("/manifest.json".length)
        } else path
        clean.trimEnd('/') to query
    }

    private val base: String get() = baseAndQuery.first
    private val query: String get() = baseAndQuery.second

    /** Builds a resource URL, optionally appending the extra-args segment
     *  (`search=...&skip=...`) that the protocol puts in the path. */
    private fun resUrl(resource: String, type: String, id: String, extra: String? = null): String {
        val e = extra?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""
        val s = "$base/$resource/$type/$id$e.json"
        return if (query.isBlank()) s else "$s?$query"
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    @Volatile
    private var manifest: JSONObject? = null

    /** When a manifest fetch last failed — see [loadManifest]. */
    @Volatile
    private var manifestFailedAt = 0L

    /** The manifest fetch currently in flight, shared by parallel callers. */
    @Volatile
    private var manifestFetch: kotlinx.coroutines.Deferred<JSONObject?>? = null

    /** `/meta/{type}/{id}` responses keyed by request URL. The detail screen
     *  asks for meta (backdrop/overview/type correction) and then for episodes,
     *  which hit the SAME document — caching it halves the network calls on
     *  every detail open. Cleared entries simply refetch. */
    private val metaCache = ConcurrentHashMap<String, JSONObject>()

    private suspend fun getMetaJson(url: String): JSONObject? {
        metaCache[url]?.let { return it }
        val json = getJson(url) ?: return null
        metaCache[url] = json
        return json
    }

    /** Fetches JSON with a browser-like fingerprint. The https→http fallback
     *  matters for addons served from IPFS/NAT boxes and for hosts whose
     *  http:// mirror behaves differently. Never throws. */
    private suspend fun getJson(url: String): JSONObject? {
        val headers = mapOf("Accept" to "application/json, text/plain, */*")
        for (u in listOf(url, url.replaceFirst("https://", "http://")).distinct()) {
            for (attempt in 0 until 2) {
                val body = Http.getString(u, headers)
                val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json != null) return json
                if (attempt == 0) {
                    // transient failures are common on first hit (cold serverless
                    // containers wake up with a 502/504) — retry once
                    try {
                        Thread.sleep(400L)
                    } catch (e: InterruptedException) {
                        return null
                    }
                }
            }
        }
        return null
    }

    /**
     * The manifest, fetched at most once per addon (and never re-fetched while
     * one fetch is already in flight or has just failed).
     *
     * A fetch here can cost a long time: the shared client connects with a 20s
     * timeout and reads with a 30s one, and [getJson] tries two URL spellings
     * twice each — so a dead or blocking host is up to two minutes per attempt,
     * and this call sits on the path of EVERY search, meta, episode and stream
     * lookup (`usesTmdbBrowse`). Re-fetching a manifest that already failed is
     * what made a dead addon read as a search that never finishes; the failure
     * is remembered for [MANIFEST_RETRY_MS] instead. The fetch itself is moved
     * off the caller's thread and only WAITED on for [MANIFEST_TIMEOUT_MS], so
     * one slow host costs a pass at most that — a late answer still lands in
     * [manifest] for every later call.
     */
    private suspend fun loadManifest(): JSONObject? {
        manifest?.let { return it }
        if (System.currentTimeMillis() - manifestFailedAt < MANIFEST_RETRY_MS) return null
        // One fetch in flight at a time: a search pass fans out over several
        // addons and can ask this addon for meta, episodes and streams at the
        // same moment.
        val flight = manifestFetch?.takeIf { it.isActive } ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .async {
                runCatching { getJson("$base/manifest.json") }.getOrNull().also {
                    if (it != null) manifest = it
                }
            }
            .also { manifestFetch = it }
        val m = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { flight.await() }
        if (m == null) manifestFailedAt = System.currentTimeMillis()
        return m
    }

    /** Normalizes any addon type string to a MediaType. The Stremio client
     *  accepts arbitrary type strings; we map obvious movies to MOVIE and
     *  EVERYTHING else to SERIES so no catalog is ever dropped. */
    private fun typeOf(t: String): MediaType = when (t.lowercase()) {
        "movie", "movies", "film", "feature-film", "feature" -> MediaType.MOVIE
        else -> MediaType.SERIES
    }

    private fun catalogsOf(m: JSONObject): List<JSONObject> {
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** The literal type segment to use in URLs — the addon's own string. */
    private fun typeSegment(rawType: String, type: MediaType): String =
        rawType.ifBlank { type.name.lowercase() }

    /** Whether the manifest declares the given resource. Addons that only list
     *  catalog/meta are metadata-only and will never produce streams. */
    private fun hasResource(m: JSONObject, name: String): Boolean {
        val arr = m.optJSONArray("resources") ?: return true // unknown → assume yes
        for (i in 0 until arr.length()) {
            val r = arr.opt(i)
            val n = when (r) {
                is String -> r
                is JSONObject -> r.optString("name")
                else -> null
            }
            if (n != null && n.equals(name, ignoreCase = true)) return true
        }
        return false
    }

    /** The TMDB rows this addon browses (see [TmdbBrowse]). Computed once per
     *  instance, like NuvioScraper's rows — the offset needs one read of the
     *  provider list, not one per catalog call. */
    @Volatile
    private var tmdbRefsCache: List<CatalogRef>? = null

    private suspend fun tmdbRefs(): List<CatalogRef> {
        tmdbRefsCache?.let { return it }
        val refs = TmdbBrowse.catalogRefs(config.name, config.id, tmdbOffset())
        tmdbRefsCache = refs
        return refs
    }

    override suspend fun catalogs(): List<CatalogRef> {
        val m = loadManifest() ?: run {
            catalogErrors[config.id] =
                "Could not load manifest from $base/manifest.json — the host may be down, " +
                    "or blocking non-browser requests."
            streamOnlyAddons.remove(config.id)
            resourceSummary.remove(config.id)
            return emptyList()
        }
        val out = LinkedHashMap<String, CatalogRef>()
        for (c in catalogsOf(m)) {
            val t = typeOf(c.optString("type"))
            val id = c.optString("id")
            val name = c.optString("name").ifBlank { id }
            if (id.isBlank()) continue
            val raw = c.optString("type").lowercase()
            out["$t|$id"] = CatalogRef(config.id, t, id, name, raw)
        }
        resourceSummary[config.id] = summarize(m, out.size)
        if (out.isEmpty()) {
            // A SUBTITLES-ONLY addon (OpenSubtitles v3, the official SubDL one)
            // has no catalogs on purpose and answers none. Giving it TMDB's rows
            // to browse — the fallback below, which exists for stream-only
            // addons — would put a full page of content on Home that this addon
            // can never play, which is exactly the "I added it and it shows a
            // useless catalog" report. It gets nothing on Home; the player asks
            // it for subtitles instead (see [subtitlesFor]).
            if (isSubtitleOnly()) {
                catalogErrors.remove(config.id)
                streamOnlyAddons.remove(config.id)
                return emptyList()
            }
            // Zero catalogs is NOT an error — stream-only addons (Torrentio,
            // Comet, Novastream, HdHub…) are valid and common. Instead of
            // leaving Home empty for one (and hiding it from the picker), it
            // browses TMDB the same way a Nuvio provider does: the rows below
            // are real, and the addon's own /stream answers for every title
            // opened from them. That is what lets a user with ONLY stream
            // addons browse and play without also installing a catalog addon
            // (Cinemeta-style) just to have something to look at.
            catalogErrors.remove(config.id)
            streamOnlyAddons[config.id] = true
            return tmdbRefs().ifEmpty {
                // No TMDB rows to offer (not configured): keep the old, honest
                // "streams only" behaviour rather than inventing a catalog.
                streamOnlyAddons[config.id] = true
                emptyList()
            }
        } else {
            catalogErrors.remove(config.id)
            streamOnlyAddons.remove(config.id)
        }
        return out.values.toList()
    }

    /**
     * Which slice of the TMDB row pool this addon shows. The addon's ordinal
     * among the installed Stremio addons, so two stream-only addons never present
     * the same Home screen (the same rule NuvioScraper uses per niche).
     */
    private suspend fun tmdbOffset(): Int {
        val here = runCatching {
            com.hikari.app.HikariApp.instance.store.providers()
                .filter { it.type == com.hikari.app.data.ProviderType.STREMIO }
                .sortedBy { it.name.lowercase() }
                .indexOfFirst { it.id == config.id }
        }.getOrDefault(-1)
        return if (here >= 0) here else kotlin.math.abs(config.id.hashCode())
    }

    /** True when this addon's browsing/search is served by TMDB (its manifest
     *  declares no catalogs of its own). */
    private suspend fun usesTmdbBrowse(): Boolean {
        val m = loadManifest() ?: return false
        return catalogsOf(m).isEmpty()
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        if (TmdbBrowse.isOurCatalog(ref.id)) {
            val items = TmdbBrowse.items(config.id, ref.id, page)
            if (items.isEmpty()) {
                catalogErrors[config.id] = "TMDB returned nothing for '${ref.name}' — check your connection."
            } else {
                catalogErrors.remove(config.id)
            }
            return items
        }
        val extra = if (page > 1) "skip=${(page - 1) * 100}" else null
        val url = resUrl("catalog", typeSegment(ref.rawType, ref.type), ref.id, extra)
        val body = getJson(url)
        val items = parseMetas(body, ref.rawType)
        if (items.isEmpty()) {
            // WHAT the addon said, when it said something. A catalogue host that
            // is rate-limited or whose upstream list is down answers HTTP 200
            // with `{"error":"…"}` (StremioLabAR's Trakt/MDBList lists do exactly
            // this), and its own words are the whole explanation — they used to be
            // thrown away, leaving "returned no items" over a catalogue the user
            // could see working a minute earlier.
            val stated = body?.optString("error")?.takeIf { it.isNotBlank() }
            val total = body?.optInt("totalItems", -1) ?: -1
            catalogErrors[config.id] = when {
                stated != null ->
                    "Catalog '${ref.name}' — the addon says: ${stated.take(200)}"
                body == null ->
                    "Catalog '${ref.name}' — the addon did not answer with JSON " +
                        "(a page, a block, or a timeout): ${url.brief()}"
                total == 0 ->
                    "Catalog '${ref.name}' is empty right now (the addon reports 0 items)."
                else ->
                    "Catalog '${ref.name}' returned no items from ${url.brief()}"
            }
        } else {
            catalogErrors.remove(config.id)
        }
        return items
    }

    /** The head and the tail of a request URL: its middle is an addon's config
     *  blob (StremioLabAR's `/stremio/<base64>` is hundreds of characters), and
     *  the part that identifies the CATALOGUE is what follows it. */
    private fun String.brief(): String =
        if (length <= 150) this else take(90) + "…" + takeLast(50)

    /**
     * The catalogues to draw as Home rows: the manifest's own list, MINUS the
     * ones that can only be answered with a query.
     *
     * The protocol lets a catalogue require an extra (StremioLabAR ships
     * `{"id":"stremiolabar-search","extra":[{"name":"search","isRequired":true}]}`),
     * and such a catalogue answers an empty list to a plain page request — so
     * showing it as a Home row produced a row that was empty by construction, and
     * tapping "Show all" on it opened a page that could only ever say "Nothing
     * here right now". They stay in [catalogs] (the global search asks them WITH
     * the query they need — see [search]) and are only held back from the feed.
     */
    override suspend fun homeCatalogs(): List<CatalogRef> {
        val all = catalogs()
        val m = manifest ?: return all
        val searchOnly = catalogsOf(m).filter { c ->
            val extras = c.optJSONArray("extra") ?: return@filter false
            (0 until extras.length()).any { i ->
                val e = extras.optJSONObject(i) ?: return@any false
                e.optBoolean("isRequired", false) &&
                    e.optString("name").equals("search", ignoreCase = true)
            }
        }.mapNotNull { c -> c.optString("id").takeIf { it.isNotBlank() } }.toSet()
        return if (searchOnly.isEmpty()) all else all.filterNot { it.id in searchOnly }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (c in catalogs().distinctBy { it.id }) {
            if (TmdbBrowse.isOurCatalog(c.id)) continue
            // A catalogue that declares its extras and does NOT declare `search`
            // is not searchable — the protocol says so, and the real client does
            // not send it a query. Asking anyway returned the catalogue's whole
            // unfiltered list as if it were search results (or an empty list),
            // which is worse than not asking. A catalogue that declares NO extras
            // at all keeps the old assumption that it can search.
            if (!supportsSearch(c.id)) continue
            val extra = "search=${encode(query)}" + if (page > 1) "&skip=${(page - 1) * 100}" else ""
            out += parseMetas(
                getJson(resUrl("catalog", typeSegment(c.rawType, c.type), c.id, extra)),
                c.rawType,
            )
        }
        // An addon with no catalog of its own is searched through TMDB, so
        // "find this title in my Stremio addon" works from the global search and
        // from the cross-extension pass (the addon then supplies the streams).
        if (usesTmdbBrowse()) out += TmdbBrowse.search(config.id, query, page)
        return out.distinctBy { it.uniqueId }
    }

    /** Whether the manifest's catalogue [id] accepts a `search` extra: it declares
     *  one, or it declares no extras at all (see [search]). */
    private fun supportsSearch(id: String): Boolean {
        val m = manifest ?: return true
        val c = catalogsOf(m).firstOrNull { it.optString("id") == id } ?: return true
        val extras = c.optJSONArray("extra") ?: return true
        if (extras.length() == 0) return true
        for (i in 0 until extras.length()) {
            val e = extras.optJSONObject(i) ?: continue
            if (e.optString("name").equals("search", ignoreCase = true)) return true
        }
        return false
    }

    private fun parseMetas(json: JSONObject?, catalogRawType: String = ""): List<MediaItem> {
        json ?: return emptyList()
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val title = m.optString("name")
            if (id.isBlank() || title.isBlank()) continue
            // Items usually carry their own type; fall back to the catalog's.
            val raw = m.optString("type").ifBlank { catalogRawType }
            val type = typeOf(raw)
            out += metaToItem(m, id, title, type, raw)
        }
        return out
    }

    private fun metaToItem(
        m: JSONObject,
        id: String,
        title: String,
        type: MediaType,
        rawType: String,
    ): MediaItem = MediaItem(
        providerId = config.id,
        id = id,
        title = title,
        type = type,
        posterUrl = m.optString("poster").ifBlank { null },
        backdropUrl = m.optString("background").ifBlank { m.optString("backdrop").ifBlank { null } },
        year = yearFromRelease(m.optString("releaseInfo")),
        overview = m.optString("description").ifBlank { null },
        genres = stringArray(m, "genres"),
        rawType = rawType,
    )

    private fun yearFromRelease(releaseInfo: String): Int? =
        Regex("""(19|20)\d{2}""").find(releaseInfo)?.value?.toIntOrNull()

    private fun stringArray(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList()

    override suspend fun getMeta(item: MediaItem): MediaItem {
        // An item from our own TMDB rows/searches: its id is a TMDB id, so the
        // addon's /meta would answer nothing. TMDB is asked instead.
        if (usesTmdbBrowse() && isTmdbId(item.id)) return TmdbBrowse.meta(item)
        val url = resUrl("meta", typeSegment(item.rawType, item.type), item.id)
        val json = getMetaJson(url) ?: return tmdbFallbackMeta(item)
        val m = json.optJSONObject("meta") ?: json.optJSONArray("meta")?.optJSONObject(0)
            ?: return tmdbFallbackMeta(item)
        // A series' /meta document frequently declares a different type than
        // the catalog row that produced the item (e.g. an addon exposed it via
        // a "movie"-typed catalog). Trust any explicit "type" the meta carries
        // so the detail screen stops showing only Play for a real series.
        val correctedRaw = m.optString("type").ifBlank { item.rawType }
        val correctedType = if (correctedRaw.isBlank()) item.type else typeOf(correctedRaw)
        return item.copy(
            type = correctedType,
            rawType = correctedRaw,
            overview = m.optString("description").ifBlank { item.overview },
            genres = stringArray(m, "genres").ifEmpty { item.genres },
            year = yearFromRelease(m.optString("releaseInfo")) ?: item.year,
            backdropUrl = m.optString("background").ifBlank { item.backdropUrl },
            posterUrl = m.optString("poster").ifBlank { item.posterUrl },
        )
    }

    /**
     * TMDB's own metadata for an addon-catalogue item whose id is a `tmdb:…`
     * id, with the item's OWN id kept — that id is what the addon's stream
     * lookup, the history entry and every later call are keyed on.
     *
     * An addon's own catalogue rows often carry a `tmdb:` id (StremioLabAR's
     * metas are `tmdb:1137844`) and the same manifest frequently declares no
     * `/meta` for that namespace, so the detail page was left with only what the
     * catalogue row itself said. Ids that are not TMDB's (a `tt…`, a slug) come
     * back unchanged, and any failure is silent.
     */
    private suspend fun tmdbFallbackMeta(item: MediaItem): MediaItem {
        val tmdb = tmdbIdOf(item.id) ?: return item
        if (tmdb == item.id) return item
        val full = runCatching { TmdbBrowse.meta(item.copy(id = tmdb)) }.getOrNull() ?: return item
        return full.copy(id = item.id, rawType = item.rawType)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        // A TMDB item's episode list comes from TMDB (the addon has no catalog
        // and would answer nothing for a TMDB id).
        if (usesTmdbBrowse() && isTmdbId(item.id)) return TmdbBrowse.episodes(item)
        // No hard type gate: a series is often served from a catalog the addon
        // typed as "movie" (or an unknown custom type), and the meta document
        // is the ground truth. getMetaJson shares the cache with getMeta, so
        // for a genuine movie this costs no extra request and simply finds no
        // videos array. A non-empty result also lets the UI reclassify the item
        // as a series.
        val candidates = buildList {
            add(typeSegment(item.rawType, item.type))
            // If the addon mislabelled the row, the videos usually live under
            // the canonical "series"/"tv" segment — try those too.
            if (item.type != MediaType.SERIES) {
                add("series")
                add("tv")
            }
        }.distinct()
        for (seg in candidates) {
            val json = getMetaJson(resUrl("meta", seg, item.id)) ?: continue
            val meta = json.optJSONObject("meta") ?: json.optJSONArray("meta")?.optJSONObject(0) ?: continue
            val videos = meta.optJSONArray("videos") ?: continue
            val out = mutableListOf<Episode>()
            val seen = HashSet<String>()
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val ep = v.optInt("episode", -1)
                val season = v.optInt("season", 1)
                if (ep < 0) continue
                val key = "$season:$ep"
                if (!seen.add(key)) continue // never drop later-season episodes
                out += Episode(
                    number = ep,
                    id = v.optString("id").ifBlank { "${item.id}:$season:$ep" },
                    name = (v.optString("title").ifBlank { "Episode $ep" })
                        .let { if (season > 1) "S$season E$ep · $it" else it },
                    image = v.optString("thumbnail").ifBlank { null },
                    season = season,
                )
            }
            if (out.isNotEmpty()) return out.sortedWith(compareBy({ it.season }, { it.number }))
        }
        // An addon's OWN catalogue row can carry a `tmdb:` id (StremioLabAR's
        // metas are `tmdb:1137844`) and the addon may declare no /meta for that
        // namespace at all. The number inside the id IS a TMDB id, and TMDB is the
        // one source that can always list a series' episodes — so it is asked for
        // the same item under the bare numeric id. Without this a series opened
        // from such a catalogue had NO episode list: every Play searched for
        // season 1 episode 1 whatever the user tapped, and the addon was asked
        // for `tmdb:<id>:1:1`.
        tmdbIdOf(item.id)?.let { tmdb ->
            if (tmdb != item.id) return TmdbBrowse.episodes(item.copy(id = tmdb))
        }
        return null
    }

    /** True if this addon claims to know this video id (via manifest
     *  idPrefixes). The real client only queries addons whose prefix matches,
     *  so e.g. Torrentio isn't asked about a non-`tt` id. Addons without
     *  idPrefixes (or not yet loaded) accept everything. */
    fun acceptsId(id: String): Boolean {
        val m = manifest ?: return true
        val arr = m.optJSONArray("idPrefixes") ?: return true
        if (arr.length() == 0) return true
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNotEmpty() && id.startsWith(p)) return true
        }
        return false
    }

    /**
     * Whether this addon declares it answers ids in [prefix]'s namespace.
     *
     * The protocol lets a manifest name the namespaces it knows twice over — at
     * the top level (`idPrefixes`) and per resource (`resources[].idPrefixes`) —
     * and both are checked. PenguPlay, for instance, declares `tt` AND `tmdb:`,
     * which is what lets an id the app already holds be handed straight over
     * instead of being resolved to an IMDb id that may not exist.
     */
    private fun acceptsPrefix(prefix: String): Boolean {
        val m = manifest ?: return false
        m.optJSONArray("idPrefixes")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (arr.optString(i).equals(prefix, ignoreCase = true)) return true
            }
        }
        val resources = m.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val r = resources.optJSONObject(i) ?: continue
            if (!r.optString("name").equals("stream", ignoreCase = true)) continue
            val arr = r.optJSONArray("idPrefixes") ?: continue
            for (j in 0 until arr.length()) {
                if (arr.optString(j).equals(prefix, ignoreCase = true)) return true
            }
        }
        return false
    }

    /** The `type` strings this manifest answers a STREAM call for [id] with,
     *  lower-cased. Empty when the manifest declares none that apply.
     *
     *  The protocol names a stream resource's id namespaces TWICE — at the top
     *  level (`idPrefixes`) and per resource (`resources[].idPrefixes`) — and the
     *  two belong together. PenguPlay declares both of these:
     *
     *      {"name":"stream","types":["movie","series"],
     *       "idPrefixes":["tt","tmdb:","tvdb:",…]}
     *      {"name":"stream","types":["tv"],"idPrefixes":["pp-live:"]}
     *
     *  i.e. its `tv` segment is for LIVE CHANNELS only. Flattening the resource
     *  list into one set of types (what this used to do) therefore invented `tv`
     *  as a valid segment for a `tmdb:`/`tt` VIDEO id — a request the addon can
     *  only answer with its live/catch-all handler — and, because the item's own
     *  `rawType` was TMDB's `tv`, that invented segment was tried FIRST. A
     *  series' whole fan-out could then be spent on requests the addon does not
     *  serve videos from. Movies hid it (their spelling carries no episode
     *  suffix and the walk still reached a working segment), series reported
     *  "Found 1 link … none could be turned into a video".
     */
    private fun declaredStreamTypes(m: JSONObject?, id: String): Set<String> {
        val resources = m?.optJSONArray("resources") ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until resources.length()) {
            val r = resources.optJSONObject(i) ?: continue
            if (!r.optString("name").equals("stream", ignoreCase = true)) continue
            // A resource that names id prefixes answers ONLY those; one that
            // names none is asked about anything (the protocol's default).
            val prefixes = r.optJSONArray("idPrefixes")
            if (prefixes != null && prefixes.length() > 0) {
                var matches = false
                for (j in 0 until prefixes.length()) {
                    val p = prefixes.optString(j)
                    if (p.isNotEmpty() && id.startsWith(p, ignoreCase = true)) {
                        matches = true
                        break
                    }
                }
                if (!matches) continue
            }
            val types = r.optJSONArray("types") ?: continue
            for (j in 0 until types.length()) {
                types.optString(j).takeIf { it.isNotBlank() }?.let { out += it.lowercase() }
            }
        }
        return out
    }

    /**
     * The `/stream/{type}/` segments to try, best first — for the id in hand.
     *
     * The types the manifest declares FOR THIS ID come first, ordered by the
     * item's kind, so a series asks `series` before `movie` and vice versa. The
     * protocol's canonical spellings follow as a fallback, so an addon whose
     * manifest we could not read (or that declares nothing that matches this id)
     * keeps the old behaviour of trying everything.
     */
    private fun streamTypeOrder(
        m: JSONObject?,
        rawType: String,
        type: MediaType,
        id: String,
    ): List<String> {
        val wanted = if (type == MediaType.SERIES) "series" else "movie"
        val declared = declaredStreamTypes(m, id)
        val out = LinkedHashSet<String>(8)
        if (declared.isEmpty()) {
            out += rawType
        } else {
            out += declared.sortedBy { if (it == wanted) 0 else 1 }
        }
        out += listOf(wanted, "movie", "series", "tv", "anime", "channel")
        return out.filter { it.isNotBlank() }.distinct()
    }

    /** The numeric TMDB id inside [id], or "": `tmdb:1137844`, `tmdb-1137844`
     *  and a bare `1137844:1:2` all name TMDB row 1137844. An id in another
     *  namespace (`tt0417299`, `kitsu:…`) yields "" — it must never be read as a
     *  TMDB number. */
    private fun tmdbDigitsOf(id: String): String {
        val s = when {
            id.startsWith("tmdb:", true) -> id.substringAfter(':')
            id.startsWith("tmdb-", true) -> id.substringAfter('-')
            else -> id
        }
        val digits = s.takeWhile { it.isDigit() }
        return if (digits.isNotEmpty() && s.startsWith(digits)) digits else ""
    }

    /** The TMDB id behind an item id, or null — see [tmdbDigitsOf]. */
    private fun tmdbIdOf(id: String): String? = tmdbDigitsOf(id).ifBlank { null }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val m = loadManifest()
        val typeRaw = typeSegment(item.rawType, item.type)
        // A SERIES is always asked for as a VIDEO id, never as the bare show id:
        // `/stream/{type}/{id}` for a series means `tt…:season:episode`, and an
        // addon handed the show id alone answers an empty stream list. When the
        // page has no episode in hand — the episode list could not be loaded,
        // which is the normal case for a stream-only addon (it has no /meta at
        // all) when no other extension is installed to borrow a list from — season
        // 1 episode 1 stands in for "play this series". Without that, movies
        // played and every series in the same addon reported "no playable source
        // found".
        val noEpisode = episode == null && item.type == MediaType.SERIES
        val epSuffix = when {
            episode != null -> ":${episode.season}:${episode.number}"
            noEpisode -> ":1:1"
            else -> ""
        }
        val idPart = episode?.id ?: (item.id + epSuffix)

        // Resolve the id this addon can actually answer. The real client only
        // ever asks an addon about an id namespace the addon DECLARED (its
        // `idPrefixes`), so two ordinary cases have to be translated first:
        //   (a) an item from our own TMDB browse carries a TMDB id (see
        //       TmdbBrowse) — and the addon may declare either `tmdb:` (hand it
        //       over as it is) or `tt` (resolve the IMDb id behind it);
        //   (b) a title opened from a CloudStream/Aniyomi/etc. extension carries
        //       THAT extension's id, which this addon has never heard of — the
        //       title resolves to a TMDB id, and then to one of the two above.
        // Without this the addon was asked about ids it does not recognise and
        // returned nothing, which is why a stream addon installed next to other
        // extensions never added a single server to the list.
        //
        // BOTH spellings are handed over when the addon declares both, and the
        // `tmdb:` one comes first because it is the id the item ALREADY carries
        // and therefore cannot fail to resolve. That ordering is the fix for
        // "movies play, but series say no playable source found": TMDB's
        // `/tv/{id}/external_ids` has no imdb_id at all for a large share of
        // shows (most anime, most non-English series), while a movie's nearly
        // always has one — so a series fell back to being asked with the bare
        // numeric TMDB id, which an addon like PenguPlay (declaring `tt` and
        // `tmdb:`, and no bare-number namespace) answers with nothing.
        val videoIds = LinkedHashSet<String>()
        if (m == null) {
            // The manifest has not landed yet, so nothing is known about the
            // namespaces this addon accepts: the item's own id is all there is.
            videoIds += idPart
        } else {
            // The numbers of the id in the addon's own namespace: an id that
            // arrived as `tmdb:1137844` (what an addon's OWN catalogue rows
            // carry — StremioLabAR's metas are `tmdb:…`) or as a bare
            // `1137844:1:2` (TMDB browse) both name TMDB row 1137844, and the
            // `tmdb:` and `tt` spellings below are built from it. Reading it with
            // `takeWhile { it.isDigit() }` on the raw id missed the `tmdb:`
            // prefix, so an addon declaring only `tt` (Torrentio, Cinemeta) was
            // never handed the IMDb id behind a `tmdb:` id — it was asked about
            // a namespace it does not know and answered nothing.
            val digits = tmdbDigitsOf(idPart)
            val kind = if (item.type == MediaType.SERIES) "tv" else "movie"
            if (digits.isNotEmpty() && acceptsPrefix("tmdb:")) {
                videoIds += "tmdb:$digits" + epSuffix
            }
            val imdb = when {
                digits.isNotEmpty() && (usesTmdbBrowse() || !acceptsId(idPart)) ->
                    TmdbBrowse.imdbId(digits, kind)
                digits.isEmpty() && !acceptsId(idPart) &&
                    com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item) ->
                    com.hikari.app.nuvio.TmdbResolver.resolve(item)?.let { resolved ->
                        // The resolved TMDB id is offered in the addon's own
                        // `tmdb:` namespace too, so a title that came from a site
                        // scraper gets the same two routes a TMDB item does.
                        if (acceptsPrefix("tmdb:")) videoIds += "tmdb:${resolved.tmdbId}" + epSuffix
                        TmdbBrowse.imdbId(resolved.tmdbId, resolved.mediaType)
                    }
                else -> null
            }
            if (!imdb.isNullOrBlank()) videoIds += imdb + epSuffix
            // Nothing the addon named could be built: send the id as it stands,
            // exactly what every version before this one did.
            if (videoIds.isEmpty()) videoIds += idPart
        }

        // Metadata-only addons (Cinemeta/Streaming-Catalogs style: catalogs +
        // meta but no stream resource) never answer /stream — exactly like the
        // real Stremio client, which only asks addons that declare the stream
        // resource. Sources for these titles come from the other installed
        // playback addons (Torrentio, Comet, Novastream…).
        if (m != null && !hasResource(m, "stream")) {
            streamErrors[config.id] =
                "This addon provides no streams (catalog/metadata only) — " +
                    "sources are fetched from your other playback addons."
            return emptyList()
        }

        // Try the exact URL first, then progressively looser variants so addons
        // with stricter matching still resolve: the same id under every type
        // segment the addon might answer for (its own declared ones first), and
        // each id again without the :season:episode suffix. Every spelling is
        // cheap — an addon that does not recognise one answers an empty stream
        // list in one round trip.
        //
        // The walk is ROUND-ROBIN across the id spellings, not per-spelling: a
        // series usually has two (`tmdb:…:s:e` and `tt…:s:e`) and the app cannot
        // know which one an addon can resolve, so each spelling gets its most
        // likely type segment before any spelling gets its second one. Walking
        // one spelling to exhaustion first is how a series that the addon could
        // only answer in its `tt` namespace never had that spelling tried at all
        // — the whole fan-out was spent on `tmdb:`, and the addon answered
        // nothing for every one of them. (Movies hid this, because a movie has no
        // episode suffix and its `tmdb:` spelling usually IS the one that works.)
        val types = streamTypeOrder(m, typeRaw, item.type, videoIds.first())
        // The spellings an addon DECLARED come first: a request in an id
        // namespace it never hears about is a wasted round trip at the FRONT of
        // the walk, and on a series (two spellings × one episode suffix) the walk
        // only has a handful of slots before the attempt cap.
        val spellings = videoIds.sortedByDescending { acceptsId(it) }
            .map { id -> id to stripVideoSuffix(id) }
        val allAttempts = linkedSetOf<String>()
        for (t in types) {
            for ((id, baseId) in spellings) {
                allAttempts += resUrl("stream", t, id)
                if (baseId != id) allAttempts += resUrl("stream", t, baseId)
            }
        }
        // A hard bound on that fan-out: the first requests are the ones that
        // matter, and a host that answers nothing must not be probed twenty times
        // over on every pass.
        val attempts = allAttempts.take(16)

        val reasons = mutableListOf<String>()
        var placeholder: String? = null
        // The best LINK-ONLY answer seen on the way: rows that are a page ("watch
        // it on the site") or a YouTube id rather than a video file. Such a row
        // parses as an ordinary stream and used to END the walk on the first
        // spelling that produced it, so the spelling that carried the addon's
        // real servers was never asked. A movie hid this; a series did not — it
        // is what "Found 1 link in your extensions, but none could be turned
        // into a video" over a title Stremio plays dozens of servers for was.
        var linkOnly: List<StreamSource>? = null
        for (u in attempts) {
            val json = getJson(u) ?: run {
                reasons += "no response from ${u.take(120)}"
                continue
            }
            val streams = parseStreams(json)
            if (streams.isNotEmpty()) {
                // A NON-EMPTY stream list is not automatically an answer. A row
                // that is a page is not a server (see [linkOnly] above), so the
                // walk goes on; a row that CAN be played ends it.
                if (streams.any { !isLinkRow(it) }) {
                    streamErrors.remove(config.id)
                    return streams
                }
                if (linkOnly == null) linkOnly = streams
                reasons += "addon answered only ${streams.size} link row(s) from ${u.take(120)}"
                continue
            }
            val n = json.optJSONArray("streams")?.length() ?: -1
            // A NON-EMPTY stream list is not automatically an answer. Addons that
            // gate on an account (PenguPlay answers `{"name":"PenguPlay",
            // "title":"You must sign in","url":"…/signin.mp4"}` to a request it
            // will not serve — signed in or not, for an id it cannot resolve)
            // return a single row that looks exactly like a server, and this
            // loop used to stop there: its `parseStreams` came back non-empty, so
            // the search ended on a fake server and reported it as the only
            // result. A row like that is filtered out now ([isPlaceholderRow]),
            // remembered as the reason, and the walk goes on — the next spelling
            // or type segment is often the one that answers for real.
            val note = placeholderText(json)
            if (note != null) {
                placeholder = note
                reasons += "addon answered \"$note\" instead of a video, from ${u.take(120)}"
            } else {
                reasons += if (n >= 0) "addon returned $n empty stream rows from ${u.take(120)}"
                else "no 'streams' field in ${u.take(120)}"
            }
        }

        // Nothing produced a video, but something DID answer with link rows: that
        // is still the addon's answer for this title, and it must reach the list
        // (the detail screen's note says exactly what those rows are — see
        // DetailScreen's linksOnly wording). The ids and segments that were tried
        // are named so a report from a device says what the addon was asked.
        linkOnly?.let { rows ->
            streamErrors[config.id] =
                "This addon has only link row(s) for this video, not a file — tried " +
                    spellings.joinToString(", ") { it.first }.take(120) +
                    " as " + types.take(3).joinToString("/") +
                    " (a row that points at a page has to be opened in a browser)."
            return rows
        }

        // A placeholder explains the emptiness far better than "no streams", so
        // it goes first when one was seen.
        val note = placeholder
        streamErrors[config.id] = if (note != null) {
            "This addon answered \"$note\" rather than returning servers — it does not " +
                "serve this title for this account (a sign-in, subscription or " +
                "coverage problem on the addon's side)."
        } else {
            reasons.take(2).joinToString(" • ").ifBlank { "Addon returned no playable streams." }
        }
        return emptyList()
    }

    /** True when [id] is a TMDB id (all digits) rather than an addon id (a
     *  Stremio id is normally `tt…`, `kitsu:…`, a slug, …). */
    private fun isTmdbId(id: String): Boolean = id.isNotBlank() && id.all { it.isDigit() }

    /** True when a row is a LINK rather than a video: something the player has to
     *  resolve (a YouTube id) or hand to a browser (an addon's `externalUrl`).
     *  Such a row is a legitimate protocol answer — and it is NOT a reason to stop
     *  walking the addon's other id spellings, because those are where its real
     *  servers are (see [getStreams]). */
    private fun isLinkRow(s: StreamSource): Boolean = s.ytId != null || s.externalUrl

    /** Strips a trailing season:episode (or season-episode) suffix from a video
     *  id so we can also try the bare movie/base id. */
    private fun stripVideoSuffix(id: String): String = id
        .replace(Regex(":\\d+:\\d+$"), "")
        .replace(Regex("-\\d+-\\d+$"), "")
        .replace(Regex(":\\d+$"), "")

    private fun parseStreams(json: JSONObject): List<StreamSource> {
        val arr = json.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<StreamSource>()
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            if (isPlaceholderRow(st)) continue
            val name = st.optString("name").ifBlank {
                st.optString("title").ifBlank { st.optString("description") }
            }
            val infoHash = st.optString("infoHash").ifBlank { null }
            val streamUrl = st.optString("url").ifBlank { null }
            // Stremio's protocol names a torrent EITHER by `infoHash` or by a
            // `magnet:` (or `torrent:`) URL in `url`, and addons use both. The
            // magnet form read as a plain direct link, so ExoPlayer was handed
            // "magnet:…" and every row of such an addon failed — a whole
            // addon's worth of servers that play in Stremio and not here.
            val magnet = streamUrl?.takeIf {
                it.startsWith("magnet:", ignoreCase = true) ||
                    it.startsWith("torrent:", ignoreCase = true)
            }
            val ytId = st.optString("ytId").ifBlank { null }
            val externalUrl = st.optString("externalUrl").ifBlank { null }
            val subs = parseSubs(st.optJSONArray("subtitles"))
            // Headers some addons require the stream to be fetched with
            // (behaviorHints.proxyHeaders.request, e.g. an auth header).
            val proxyHeaders = st.optJSONObject("behaviorHints")
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
                ?.let { h ->
                    val map = LinkedHashMap<String, String>()
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = h.optString(k)
                    }
                    map
                } ?: emptyMap()

            when {
                infoHash != null -> out += StreamSource(
                    name = name.ifBlank { "Torrent" },
                    url = "",
                    subtitles = subs,
                    isTorrent = true,
                    infoHash = infoHash,
                    fileIdx = if (st.has("fileIdx") && !st.isNull("fileIdx")) st.optInt("fileIdx") else null,
                    trackers = stringArray(st, "sources"),
                )
                ytId != null -> out += StreamSource(
                    name = name.ifBlank { "YouTube" },
                    url = "",
                    subtitles = subs,
                    ytId = ytId,
                )
                externalUrl != null -> out += StreamSource(
                    name = name.ifBlank { "External" },
                    url = externalUrl,
                    subtitles = subs,
                    externalUrl = true,
                )
                magnet != null -> out += StreamSource(
                    name = name.ifBlank { "Torrent" },
                    url = magnet,
                    subtitles = subs,
                    isTorrent = true,
                    infoHash = magnetHash(magnet),
                    fileIdx = magnetIndex(magnet),
                    trackers = magnetTrackers(magnet),
                )
                streamUrl != null -> out += StreamSource(
                    name = name.ifBlank { if (streamUrl.contains(".m3u8", true)) "HLS" else "Direct" },
                    url = streamUrl,
                    headers = proxyHeaders,
                    subtitles = subs,
                    isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains("master.txt", true),
                    isMpd = streamUrl.contains(".mpd", true),
                )
            }
        }
        return out
    }

    /** The BTIH of a magnet/torrent URL (hex, as written), or null. */
    private fun magnetHash(magnet: String): String? =
        Regex("[&?]xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(magnet)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** The `index=` a magnet carries — which file inside the torrent — or null. */
    private fun magnetIndex(magnet: String): Int? =
        Regex("[&?]index=(\\d+)").find(magnet)?.groupValues?.get(1)?.toIntOrNull()

    /** The `tr=` trackers a magnet carries, URL-decoded. */
    private fun magnetTrackers(magnet: String): List<String> =
        Regex("[&?]tr=([^&\\s]+)").findAll(magnet).mapNotNull { m ->
            val raw = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }.distinct().toList()

    /**
     * True when a `streams[]` row is a MESSAGE rather than a video.
     *
     * Stremio's protocol has no way to say "I will not serve this": an addon
     * answers with a row, and a gated addon answers with a row that describes the
     * problem — `{"name":"PenguPlay","title":"You must sign in","url":"…/signin.mp4"}`.
     * That row parses as a perfectly ordinary direct stream, so it reached the
     * server list as if it were one (and, worse, ended the search: see
     * [getStreams]). Nothing here is guesswork about a specific addon — the test
     * is the row's own words and the URL it points at, and it is narrow on
     * purpose: a row with an `infoHash` or a `ytId` is always a real stream, and a
     * row with no URL and no hash is already unusable.
     */
    private fun isPlaceholderRow(st: JSONObject): Boolean {
        if (st.optString("infoHash").isNotBlank() || st.optString("ytId").isNotBlank()) return false
        val url = st.optString("url")
        if (url.isBlank()) return false
        val hay = (st.optString("name") + " " + st.optString("title") + " " +
            st.optString("description")).lowercase()
        if (PLACEHOLDER_WORDS.any { hay.contains(it) }) return true
        val lowerUrl = url.lowercase()
        return PLACEHOLDER_URL_PARTS.any { lowerUrl.contains(it) }
    }

    /** The first placeholder row's own words, for the "why is this empty" note. */
    private fun placeholderText(json: JSONObject): String? {
        val arr = json.optJSONArray("streams") ?: return null
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            if (!isPlaceholderRow(st)) continue
            return st.optString("title").ifBlank { st.optString("name") }
                .ifBlank { st.optString("description") }
                .trim()
                .take(80)
                .ifBlank { "no video" }
        }
        return null
    }

    private fun parseSubs(arr: JSONArray?): List<SubtitleSource> {
        arr ?: return emptyList()
        val out = mutableListOf<SubtitleSource>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val u = s.optString("url")
            if (u.isBlank()) continue
            out += SubtitleSource(s.optString("lang").ifBlank { "Subtitle" }, u)
        }
        return out
    }

    // ---- Subtitle-only addons ------------------------------------------------

    /**
     * True when this addon exists to supply SUBTITLES and nothing else — a
     * manifest whose `resources` list is `["subtitles"]` and which declares no
     * catalogs. OpenSubtitles v3 (`opensubtitles-v3.strem.io`) and the official
     * SubDL addon (`api3.subdl.com`) are the two everyone meets: both answer
     * `/subtitles/{type}/{id}.json` and neither has a catalog or a stream.
     *
     * They are not content providers, and treating them as one is why adding
     * them looked broken: with no catalogs the addon was given TMDB's rows to
     * browse (the stream-only fallback) and then produced no servers for
     * anything, so it read as "this repo is useless". Hikari now recognises
     * them for what they are (see [subtitlesFor]) and asks them for subtitles.
     */
    suspend fun isSubtitleOnly(): Boolean {
        val m = loadManifest() ?: return false
        if (catalogsOf(m).isNotEmpty()) return false
        if (hasResource(m, "stream")) return false
        return hasResource(m, "subtitles")
    }

    /**
     * The subtitle tracks this addon has for [item] (and [episode], for a
     * series), in the order the addon returned them.
     *
     * A subtitle addon is asked with an id it recognises, and the two protocol
     * families differ: OpenSubtitles v3 declares `idPrefixes: ["tt"]` and only
     * answers IMDb ids, while SubDL declares `tt`, `tmdb:`, `kitsu:`, `mal:`,
     * `anilist:` and `anidb:` and is happiest with `tmdb:`. So this tries, in
     * order: the id the item already carries (an addon-sourced item may already
     * be `tt…`), then the IMDb id TMDB knows behind a TMDB id, then the bare
     * `tmdb:<id>` form. The first URL that returns a non-empty `subtitles`
     * array wins, and an episode id gets the protocol's `:season:episode`
     * suffix.
     */
    suspend fun subtitlesFor(item: MediaItem, episode: Episode?): List<SubtitleSource> =
        subtitlesForDetailed(item, episode).tracks

    /**
     * [subtitlesFor], plus WHY it came back empty.
     *
     * The ids the addon was actually asked with are returned alongside the
     * tracks ([SubtitleLookup.idsTried]) so the player's "no subtitles found"
     * line can say what was tried instead of leaving the user (and whoever they
     * report it to) guessing. The protocol is unforgiving about this:
     * OpenSubtitles v3 declares `idPrefixes: ["tt"]` and answers an empty
     * `subtitles` array — with HTTP 200 — to a `tmdb:550` id or to a bare name,
     * so "nothing found" is what EVERY title looks like when the `tt` id could
     * not be resolved ("in all movie it saying same not found").
     */
    suspend fun subtitlesForDetailed(item: MediaItem, episode: Episode?): SubtitleLookup {
        val m = loadManifest() ?: return SubtitleLookup(
            emptyList(), emptyList(), "manifest could not be read",
        )
        if (!hasResource(m, "subtitles")) return SubtitleLookup(
            emptyList(), emptyList(), "no subtitle resource",
        )
        // What this track should be CALLED in the player's subtitle list: with
        // several subtitle addons installed (OpenSubtitles v3 AND SubDL, say)
        // two "English" rows are indistinguishable without it.
        val addonName = m.optString("name").trim().ifBlank { config.name }
        val epSuffix = if (episode != null) ":${episode.season}:${episode.number}" else ""
        val kind = if (item.type == MediaType.SERIES) "tv" else "movie"
        val digits = item.id.takeWhile { it.isDigit() }
        val candidates = linkedSetOf<String>()
        if (item.id.isNotBlank()) candidates += item.id + epSuffix
        if (digits.isNotEmpty()) {
            TmdbBrowse.imdbId(digits, kind)?.let { candidates += it + epSuffix }
            candidates += "tmdb:$digits"
        }
        // …and the ids the TITLE resolves to, which is the case that matters for
        // an item that came from a SITE SCRAPER: a CloudStream item's id is a URL
        // path on the site, an Aniyomi item's is a slug, and neither is anything a
        // subtitle addon can answer for. The old code tried those ids (plus the
        // digits of a TMDB id, which such an item does not have) and gave up, so
        // an installed subtitle addon returned nothing at all — reported as "i
        // added opensub but in player the subtitle from the added subtitle
        // extension not showing in player".
        resolvedCandidates(item, episode, kind).forEach { candidates += it }
        // Nothing has produced a `tt` id yet — and a `tt` id is the ONLY thing
        // OpenSubtitles v3 answers. IMDb's own suggestion endpoint needs no key
        // and returns the tt-id for a name TMDB could not place (or could not be
        // reached about in time), so ask it before giving up: this is the route
        // that turns the blanket "no subtitles found for any movie" into a real
        // track list.
        if (candidates.none { it.startsWith("tt") }) {
            imdbSuggestedId(item)?.let { candidates += it + epSuffix }
        }
        val segments = linkedSetOf(
            typeSegment(item.rawType, item.type),
            if (item.type == MediaType.SERIES) "series" else "movie",
            "movie", "series", "tv",
        )
        val tried = ArrayList<String>(candidates.size)
        for (id in candidates) {
            tried += id
            for (seg in segments) {
                val json = getJson(resUrl("subtitles", seg, id)) ?: continue
                val subs = parseSubs(json.optJSONArray("subtitles"))
                if (subs.isNotEmpty()) {
                    return SubtitleLookup(subs.map { it.copy(name = addonName) }, tried, "")
                }
            }
        }
        return SubtitleLookup(emptyList(), tried, "")
    }

    /**
     * An IMDb id for [item] straight from IMDb's suggestion endpoint, which
     * needs no API key: `/suggestion/h/<query>.json` answers
     * `d:[{ l: title, y: year, q: "feature"|"TV series", id: "tt…" }]`.
     *
     * This is the subtitle path's last resort for an id and the first one that
     * always works without a database round-trip: the addon that matters most
     * here (OpenSubtitles v3) answers ONLY `tt…` ids — a `tmdb:` id and a bare
     * title both come back as an empty list with HTTP 200 (verified against the
     * live service) — so a title TMDB could not resolve used to look like "this
     * addon has no subtitles for anything".
     */
    private suspend fun imdbSuggestedId(item: MediaItem): String? {
        val title = item.searchTitle.trim()
        if (title.isBlank()) return null
        val q = runCatching {
            java.net.URLEncoder.encode(title.lowercase(), "UTF-8")
        }.getOrNull() ?: return null
        val text = Http.getString(
            "https://v3.sg.media-imdb.com/suggestion/h/$q.json",
            mapOf("Accept" to "application/json"),
        ) ?: return null
        val arr = runCatching { JSONObject(text).optJSONArray("d") }.getOrNull() ?: return null
        val wanted = title.lowercase().trim()
        val wantTv = item.type == MediaType.SERIES
        var best: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (!id.startsWith("tt")) continue
            val label = o.optString("l").lowercase().trim()
            if (label.isBlank()) continue
            var score = 0
            if (label == wanted) score += 50
            else if (wanted.length >= 5 && (label.startsWith(wanted) || wanted.startsWith(label))) score += 20
            else continue
            if (item.year != null && o.optString("y") == item.year.toString()) score += 30
            val isTv = o.optString("q").contains("TV", ignoreCase = true)
            if (isTv == wantTv) score += 5
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
    }

    /**
     * The IMDb/TMDB ids for [item] resolved from its NAME, as
     * `/subtitles/{type}/{id}` ids (episode suffix included for a series).
     *
     * A site-scraper item has no id any addon recognises — its id belongs to the
     * site it came from, not to a database — so the title is resolved through
     * TMDB ([com.hikari.app.nuvio.TmdbResolver], the same resolver the rest of
     * the app uses, cached and single-flight): the IMDb id first, because
     * OpenSubtitles v3 declares `idPrefixes: ["tt"]` and answers nothing else,
     * then the `tmdb:<id>` form SubDL is happiest with.
     */
    private suspend fun resolvedCandidates(
        item: MediaItem,
        episode: Episode?,
        kind: String,
    ): List<String> = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        val resolved = runCatching {
            com.hikari.app.nuvio.TmdbResolver.resolve(item)
        }.getOrNull() ?: return@withTimeoutOrNull emptyList()
        val epSuffix = if (episode != null) ":${episode.season}:${episode.number}" else ""
        val media = resolved.mediaType.ifBlank { kind }
        val out = ArrayList<String>(2)
        val imdb = runCatching { TmdbBrowse.imdbId(resolved.tmdbId, media) }.getOrNull()
        if (!imdb.isNullOrBlank()) out += imdb + epSuffix
        out += "tmdb:" + resolved.tmdbId + epSuffix
        out
    }.orEmpty()
}
