package com.hikari.app.data

import com.hikari.app.HikariApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Last-visit page state for the detail screen: paint instantly on reopen,
 *  then the normal load overwrites each section as fresher data lands
 *  (stale-while-revalidate). Episodes are NOT stored here — the repository's
 *  own episode cache already publishes those first.
 *
 *  Bounded (a dozen titles in memory, 30 on disk, text only — posters stay
 *  in Coil's disk cache), 6-hour TTL, and keyed with the metadata
 *  fingerprint, so a settings toggle or a new addon install re-resolves
 *  instead of serving the old text. The disk copy survives process death,
 *  so revisits across restarts still paint in a blink. */
object DetailSnapshotCache {

    data class Snapshot(
        val meta: MediaItem,
        val extras: TitleExtras?,
        val related: List<MediaItem>,
        val similar: List<MediaItem>,
        val recommended: List<MediaItem>,
        val addonLogo: String?,
        val fingerprint: String,
        val atMs: Long,
    )

    private const val MAX_TITLES = 12
    private const val DISK_MAX = 30
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val FILE_NAME = "detail-snapshots-v1.json"

    private val map = object : LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) =
            size > MAX_TITLES
    }

    @Volatile
    private var diskLoaded = false

    private fun file(): File = File(HikariApp.instance.filesDir, FILE_NAME)

    @Synchronized
    fun get(key: String, fingerprint: String): Snapshot? {
        if (!diskLoaded) {
            diskLoaded = true
            runCatching { loadDisk() }
        }
        val s = map[key] ?: return null
        if (System.currentTimeMillis() - s.atMs > TTL_MS) {
            map.remove(key)
            runCatching { saveDisk() }
            return null
        }
        if (s.fingerprint != fingerprint) {
            map.remove(key)
            return null
        }
        return s
    }

    @Synchronized
    fun put(key: String, snap: Snapshot) {
        map[key] = snap
        runCatching { saveDisk() }
    }

    @Synchronized
    fun evict(key: String) {
        map.remove(key)
    }

    // ---- disk persistence (plain org.json, no new dependencies) ----

    private fun loadDisk() {
        val f = runCatching { file() }.getOrNull() ?: return
        if (!f.exists()) return
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("snapshots") ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            if (map.size >= DISK_MAX) break
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            val snap = runCatching { readSnapshot(o) }.getOrNull() ?: continue
            if (now - snap.atMs > TTL_MS) continue
            map[key] = snap
        }
        // Enforce the memory bound after a bulk load.
        while (map.size > MAX_TITLES) map.remove(map.keys.first())
    }

    private fun saveDisk() {
        val arr = JSONArray()
        // Newest last-wins: LinkedHashMap is access-ordered, eldest first.
        val all = ArrayList(map.entries)
        val start = (all.size - DISK_MAX).coerceAtLeast(0)
        for (i in start until all.size) {
            val (key, snap) = all[i]
            runCatching {
                val o = writeSnapshot(snap)
                o.put("key", key)
                arr.put(o)
            }
        }
        runCatching {
            val root = JSONObject().put("snapshots", arr)
            val f = file()
            f.parentFile?.mkdirs()
            // Atomic-ish: tmp + rename so a killed write never corrupts.
            val tmp = File(f.parent, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) f.writeText(root.toString())
        }
    }

    private fun readSnapshot(o: JSONObject): Snapshot = Snapshot(
        meta = readItem(o.getJSONObject("meta")),
        extras = if (o.has("extras") && !o.isNull("extras")) readExtras(o.getJSONObject("extras")) else null,
        related = readItems(o.optJSONArray("related")),
        similar = readItems(o.optJSONArray("similar")),
        recommended = readItems(o.optJSONArray("recommended")),
        addonLogo = o.optString("addonLogo").takeIf { it.isNotBlank() },
        fingerprint = o.optString("fingerprint"),
        atMs = o.optLong("atMs"),
    )

    private fun writeSnapshot(s: Snapshot): JSONObject = JSONObject()
        .put("meta", writeItem(s.meta))
        .put("extras", s.extras?.let { writeExtras(it) } ?: JSONObject.NULL)
        .put("related", writeItems(s.related))
        .put("similar", writeItems(s.similar))
        .put("recommended", writeItems(s.recommended))
        .put("addonLogo", s.addonLogo ?: JSONObject.NULL)
        .put("fingerprint", s.fingerprint)
        .put("atMs", s.atMs)

    private fun readItems(arr: JSONArray?): List<MediaItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { readItem(arr.getJSONObject(i)) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun writeItems(list: List<MediaItem>): JSONArray {
        val arr = JSONArray()
        for (m in list) runCatching { arr.put(writeItem(m)) }
        return arr
    }

    private fun readItem(o: JSONObject): MediaItem = MediaItem(
        providerId = o.optString("providerId"),
        id = o.optString("id"),
        title = o.optString("title"),
        type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrDefault(MediaType.UNKNOWN),
        posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() },
        year = o.optInt("year", -1).takeIf { it > 0 },
        overview = o.optString("overview").takeIf { it.isNotBlank() },
        genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
        backdropUrl = o.optString("backdropUrl").takeIf { it.isNotBlank() },
        rawType = o.optString("rawType"),
        rating = o.optDouble("rating", Double.NaN).takeIf { !it.isNaN() },
        originalTitle = o.optString("originalTitle"),
        nsfw = o.optBoolean("nsfw"),
        quality = o.optString("quality").takeIf { it.isNotBlank() },
        nextEpisodeDate = o.optString("nextEpisodeDate").takeIf { it.isNotBlank() },
        metadataSource = o.optString("metadataSource").takeIf { it.isNotBlank() },
    )

    private fun writeItem(m: MediaItem): JSONObject = JSONObject()
        .put("providerId", m.providerId)
        .put("id", m.id)
        .put("title", m.title)
        .put("type", m.type.name)
        .put("posterUrl", m.posterUrl ?: JSONObject.NULL)
        .put("year", m.year ?: -1)
        .put("overview", m.overview ?: JSONObject.NULL)
        .put("genres", JSONArray(m.genres))
        .put("backdropUrl", m.backdropUrl ?: JSONObject.NULL)
        .put("rawType", m.rawType)
        .put("rating", m.rating ?: JSONObject.NULL)
        .put("originalTitle", m.originalTitle)
        .put("nsfw", m.nsfw)
        .put("quality", m.quality ?: JSONObject.NULL)
        .put("nextEpisodeDate", m.nextEpisodeDate ?: JSONObject.NULL)
        .put("metadataSource", m.metadataSource ?: JSONObject.NULL)

    private fun readExtras(o: JSONObject): TitleExtras = TitleExtras(
        details = if (o.has("details") && !o.isNull("details")) readDetails(o.getJSONObject("details")) else null,
        cast = (0 until (o.optJSONArray("cast")?.length() ?: 0)).mapNotNull { i ->
            runCatching {
                val c = o.getJSONArray("cast").getJSONObject(i)
                CastMember(
                    name = c.optString("name"),
                    character = c.optString("character").takeIf { it.isNotBlank() },
                    profileUrl = c.optString("profileUrl").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        },
        trailers = (0 until (o.optJSONArray("trailers")?.length() ?: 0)).mapNotNull { i ->
            runCatching {
                val t = o.getJSONArray("trailers").getJSONObject(i)
                Trailer(
                    youtubeKey = t.optString("youtubeKey"),
                    name = t.optString("name"),
                    type = t.optString("type").takeIf { it.isNotBlank() } ?: "Trailer",
                    thumbnailUrl = t.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        },
        castIsCharacters = o.optBoolean("castIsCharacters"),
        localizedTitle = o.optString("localizedTitle").takeIf { it.isNotBlank() },
        originalTitle = o.optString("originalTitle").takeIf { it.isNotBlank() },
        overview = o.optString("overview").takeIf { it.isNotBlank() },
        companies = (0 until (o.optJSONArray("companies")?.length() ?: 0)).mapNotNull { i ->
            runCatching {
                val c = o.getJSONArray("companies").getJSONObject(i)
                CompanyRef(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    logoUrl = c.optString("logoUrl").takeIf { it.isNotBlank() },
                    isNetwork = c.optBoolean("isNetwork"),
                )
            }.getOrNull()
        },
        collection = if (o.has("collection") && !o.isNull("collection")) runCatching {
            val c = o.getJSONObject("collection")
            TitleCollection(
                id = c.optString("id"),
                name = c.optString("name"),
                items = readItems(c.optJSONArray("items")),
            )
        }.getOrNull() else null,
    )

    private fun writeExtras(e: TitleExtras): JSONObject {
        val cast = JSONArray()
        for (m in e.cast) runCatching {
            cast.put(
                JSONObject().put("name", m.name)
                    .put("character", m.character ?: JSONObject.NULL)
                    .put("profileUrl", m.profileUrl ?: JSONObject.NULL),
            )
        }
        val trailers = JSONArray()
        for (t in e.trailers) runCatching {
            trailers.put(
                JSONObject().put("youtubeKey", t.youtubeKey)
                    .put("name", t.name)
                    .put("type", t.type)
                    .put("thumbnailUrl", t.thumbnailUrl ?: JSONObject.NULL),
            )
        }
        val companies = JSONArray()
        for (c in e.companies) runCatching {
            companies.put(
                JSONObject().put("id", c.id)
                    .put("name", c.name)
                    .put("logoUrl", c.logoUrl ?: JSONObject.NULL)
                    .put("isNetwork", c.isNetwork),
            )
        }
        return JSONObject()
            .put("details", e.details?.let { writeDetails(it) } ?: JSONObject.NULL)
            .put("cast", cast)
            .put("trailers", trailers)
            .put("castIsCharacters", e.castIsCharacters)
            .put("localizedTitle", e.localizedTitle ?: JSONObject.NULL)
            .put("originalTitle", e.originalTitle ?: JSONObject.NULL)
            .put("overview", e.overview ?: JSONObject.NULL)
            .put("companies", companies)
            .put(
                "collection",
                e.collection?.let { col ->
                    JSONObject().put("id", col.id).put("name", col.name)
                        .put("items", writeItems(col.items))
                } ?: JSONObject.NULL,
            )
    }

    private fun readDetails(o: JSONObject): TitleDetails = TitleDetails(
        status = o.optString("status").takeIf { it.isNotBlank() },
        runtimeMinutes = o.optInt("runtimeMinutes", -1).takeIf { it > 0 },
        year = o.optInt("year", -1).takeIf { it > 0 },
        rating = o.optDouble("rating", Double.NaN).takeIf { !it.isNaN() },
        voteCount = o.optInt("voteCount", -1).takeIf { it >= 0 },
        certification = o.optString("certification").takeIf { it.isNotBlank() },
        country = o.optString("country").takeIf { it.isNotBlank() },
        language = o.optString("language").takeIf { it.isNotBlank() },
        releaseDate = o.optString("releaseDate").takeIf { it.isNotBlank() },
        director = o.optString("director").takeIf { it.isNotBlank() },
        writers = o.optJSONArray("writers")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
        imdbId = o.optString("imdbId").takeIf { it.isNotBlank() },
        genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
    )

    private fun writeDetails(d: TitleDetails): JSONObject = JSONObject()
        .put("status", d.status ?: JSONObject.NULL)
        .put("runtimeMinutes", d.runtimeMinutes ?: -1)
        .put("year", d.year ?: -1)
        .put("rating", d.rating ?: JSONObject.NULL)
        .put("voteCount", d.voteCount ?: -1)
        .put("certification", d.certification ?: JSONObject.NULL)
        .put("country", d.country ?: JSONObject.NULL)
        .put("language", d.language ?: JSONObject.NULL)
        .put("releaseDate", d.releaseDate ?: JSONObject.NULL)
        .put("director", d.director ?: JSONObject.NULL)
        .put("writers", JSONArray(d.writers))
        .put("imdbId", d.imdbId ?: JSONObject.NULL)
        .put("genres", JSONArray(d.genres))
}
