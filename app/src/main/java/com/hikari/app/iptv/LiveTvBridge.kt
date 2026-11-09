package com.hikari.app.iptv

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.DrmSpec
import com.hikari.app.data.Episode
import com.hikari.app.data.IptvChannel
import com.hikari.app.data.IptvPlaylist
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.IptvProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Live TV source behind the provider contract, so Home, search, detail and
 * the player treat it like any installed extension. Wired in through
 * [com.hikari.app.providers.IptvProvider] delegation (a `livetv://` url).
 */
class LiveTvBridge(override val config: ProviderConfig) : ContentProvider {

    private val sourceId: String = LiveTvEngine.sourceIdOfUrl(config.url) ?: ""

    companion object {
        fun forConfig(config: ProviderConfig): LiveTvBridge? =
            if (config.type == ProviderType.IPTV && LiveTvEngine.isBridgeUrl(config.url)) LiveTvBridge(config) else null

        private const val PAGE = 240
        const val CATALOG_ALL = "livetv-all"
        const val GROUP_PREFIX = "livetv-group:"
        const val FAV_PREFIX = "livetv-fav"
        const val RECENT_PREFIX = "livetv-recent"

        /** The same channel as the tab's own [IptvChannel]: group tiles, the
         *  group-by switch and search read this shape (see `readCard`). */
        fun toIptvChannel(c: LiveChannel): IptvChannel = IptvChannel(
            name = c.name,
            url = c.url,
            logo = c.logo,
            group = c.group,
            tvgId = c.tvgId,
            language = c.language,
            country = c.country,
            headers = c.headers,
        )
    }

    private fun src(): LiveTvSource? = LiveTvEngine.sourceOf(sourceId)

    private fun channels(): List<LiveChannel> = LiveTvEngine.visibleChannels(sourceId)

    /** Engine channels as the tab's own shape (group tiles, search, cards). */
    fun engineChannels(): List<IptvChannel> = channels().map { toIptvChannel(it) }

    /** Tab path (readCard/readGroups): refresh once when empty, then map. */
    suspend fun tabChannels(): List<IptvChannel> = withContext(Dispatchers.IO) {
        if (channels().isEmpty()) {
            runCatching { LiveTvEngine.refreshSource(sourceId) }
        }
        engineChannels()
    }

    /** Scoped search inside one group tile (the playlist screen's search). */
    suspend fun searchInCatalog(refId: String, query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) return@withContext emptyList()
            val hit = channelsIn(refId).filter {
                it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true)
            }
            if (hit.isEmpty()) return@withContext emptyList()
            val from = (page.coerceAtLeast(1) - 1) * PAGE
            if (from >= hit.size) return@withContext emptyList()
            hit.subList(from, minOf(from + PAGE, hit.size)).map { toItem(it) }
        }

    /** The Live TV source's name (the tile title). */
    fun displayName(): String = src()?.name?.takeIf { it.isNotBlank() }
        ?: config.name.ifBlank { "Live TV" }

    private fun channelsIn(refId: String): List<LiveChannel> {
        val all = channels()
        // The playlist screen builds its group tiles from IptvProvider's own
        // catalog ids ("iptv-all", "iptv-group:<name>", "iptv-lang:…", …) via
        // channels() below, so those ids resolve here by group NAME.
        if (refId == IptvProvider.CATALOG_ALL) return all
        IptvProvider.groupOfCatalogId(refId)?.let { name ->
            val group = LiveTvEngine.groupsOf(sourceId).firstOrNull {
                it.title.equals(name, ignoreCase = true)
            } ?: return emptyList()
            return LiveTvEngine.channelsInGroup(group, sourceId)
        }
        // NOTE: the "iptv-lang:"/"iptv-cat:"/"iptv-country:" literals below
        // mirror IptvProvider's (private) catalogIdForLanguage/Category/Country
        // ids on purpose — see [com.hikari.app.providers.IptvProvider].
        if (refId.startsWith("iptv-lang:")) {
            val want = refId.removePrefix("iptv-lang:")
            return all.filter { IptvPlaylist.languageOf(toIptvChannel(it)) == want }
        }
        if (refId.startsWith("iptv-cat:")) {
            val want = refId.removePrefix("iptv-cat:")
            return all.filter { IptvPlaylist.categoryOf(toIptvChannel(it)) == want }
        }
        if (refId.startsWith("iptv-country:")) {
            val want = refId.removePrefix("iptv-country:")
            return all.filter { IptvPlaylist.countryOf(toIptvChannel(it)) == want }
        }
        return when {
            refId == CATALOG_ALL -> all
            refId == FAV_PREFIX -> {
                val fav = LiveTvEngine.store.userState().favorites.toSet()
                all.filter { it.key in fav }
            }
            refId == RECENT_PREFIX -> {
                val order = LiveTvEngine.store.userState().recent
                val pos = order.withIndex().associate { it.value to it.index }
                all.filter { it.key in pos }.sortedBy { pos[it.key] }
            }
            refId.startsWith(GROUP_PREFIX) -> {
                val gid = refId.removePrefix(GROUP_PREFIX)
                val group = LiveTvEngine.groupsOf(sourceId).firstOrNull { it.id == gid } ?: return emptyList()
                LiveTvEngine.channelsInGroup(group, sourceId)
            }
            else -> emptyList()
        }
    }

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val src = src() ?: return@withContext emptyList()
        val out = ArrayList<CatalogRef>()
        val groups = LiveTvEngine.groupsOf(sourceId)
        if (LiveTvEngine.channelsOf(sourceId).isEmpty()) {
            // Not loaded yet — load now so the first Home pass has rows.
            runCatching { LiveTvEngine.refreshSource(sourceId) }
        }
        groups.forEach { g ->
            val id = when (g.id) {
                LiveChannelGroup.ALL -> CATALOG_ALL
                LiveChannelGroup.FAVORITES -> FAV_PREFIX
                LiveChannelGroup.RECENT -> RECENT_PREFIX
                else -> GROUP_PREFIX + g.id
            }
            out += CatalogRef(config.id, MediaType.MOVIE, id, g.title, "channel")
        }
        if (out.isEmpty()) {
            out += CatalogRef(config.id, MediaType.MOVIE, CATALOG_ALL, src.name, "channel")
        }
        out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val list = channelsIn(ref.id)
        if (list.isEmpty()) return@withContext emptyList()
        val from = (page.coerceAtLeast(1) - 1) * PAGE
        if (from >= list.size) return@withContext emptyList()
        list.subList(from, minOf(from + PAGE, list.size)).map { toItem(it, ref.name) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val hit = LiveTvEngine.search(query, sourceId)
        if (hit.isEmpty()) return@withContext emptyList()
        val from = (page.coerceAtLeast(1) - 1) * PAGE
        if (from >= hit.size) return@withContext emptyList()
        hit.subList(from, minOf(from + PAGE, hit.size)).map { toItem(it) }
    }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val ch = LiveTvEngine.channelOf(item.id.removePrefix("ch:")) ?: LiveTvEngine.allChannels().firstOrNull {
            it.name.equals(item.title, ignoreCase = true) && (sourceId.isBlank() || it.sourceId == sourceId)
        } ?: return@withContext item
        val (now, next) = runCatching { LiveTvEngine.nowNext(ch) }.getOrDefault(null to null)
        val overview = buildString {
            if (now != null) {
                append("Now: ").append(now.title)
                if (next != null) append("\nNext: ").append(next.title)
            } else {
                append(ch.group)
            }
        }
        item.copy(
            title = ch.name,
            posterUrl = ch.logo ?: item.posterUrl,
            overview = overview,
            rawType = "channel",
        )
    }

    /** Today's catch-up programmes as episodes (null when the channel has no archive). */
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        val ch = LiveTvEngine.channelOf(item.id.removePrefix("ch:")) ?: return@withContext null
        if (ch.catchup == null) return@withContext null
        val now = System.currentTimeMillis()
        val list = runCatching { LiveTvEngine.guideWindow(ch, now - 24 * 3_600_000L, now) }.getOrDefault(emptyList())
        val replays = list.filter { p ->
            p.stopMs <= now && CatchupUrlBuilder.isAvailable(ch.catchup, p.startMs, now) &&
                runCatching { LiveTvEngine.catchupUrl(ch, p, now) }.getOrNull() != null
        }.takeLast(40)
        if (replays.isEmpty()) return@withContext null
        replays.mapIndexed { i, p ->
            Episode(
                number = i + 1,
                id = "catchup|" + p.startMs + "|" + p.stopMs,
                name = p.title,
                season = 1,
            )
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val ch = LiveTvEngine.channelOf(item.id.removePrefix("ch:"))
                ?: LiveTvEngine.allChannels().firstOrNull {
                    it.name.equals(item.title, ignoreCase = true) && (sourceId.isBlank() || it.sourceId == sourceId)
                }
                ?: run {
                    if (item.id.startsWith("http")) {
                        return@withContext listOf(
                            StreamSource(
                                name = item.title, url = item.id,
                                isM3u8 = item.id.substringBefore('?').lowercase().contains(".m3u8"),
                                provider = ProviderType.IPTV.groupLabel,
                                providerId = config.id, providerName = config.name,
                            )
                        )
                    }
                    return@withContext emptyList()
                }
            // A catch-up episode: the replay plus its fallback forms.
            if (episode != null && episode.id.startsWith("catchup|")) {
                val parts = episode.id.split('|')
                val start = parts.getOrNull(1)?.toLongOrNull() ?: return@withContext emptyList()
                val stop = parts.getOrNull(2)?.toLongOrNull() ?: return@withContext emptyList()
                val now = System.currentTimeMillis()
                val progList = runCatching { LiveTvEngine.guideWindow(ch, start - 60_000L, stop + 60_000L) }.getOrDefault(emptyList())
                val prog = progList.firstOrNull { it.startMs == start } ?: EpgProgram(start, stop, episode.name ?: ch.name)
                val primary = runCatching { LiveTvEngine.catchupUrl(ch, prog, now) }.getOrNull()
                    ?: return@withContext emptyList()
                val alts = runCatching { LiveTvEngine.catchupAlternatives(primary, ch) }.getOrDefault(emptyList())
                return@withContext (listOf(primary) + alts).distinct().mapIndexed { i, url ->
                    StreamSource(
                        name = (if (i == 0) "Replay" else "Replay ${i + 1}") + " · " + prog.title,
                        url = url,
                        headers = liveHeaders(ch),
                        isM3u8 = url.substringBefore('?').lowercase().contains(".m3u8"),
                        provider = ProviderType.IPTV.groupLabel,
                        providerId = config.id, providerName = config.name,
                    )
                }
            }
            val url = LiveTvEngine.playUrl(ch)
            val out = ArrayList<StreamSource>()
            out += StreamSource(
                name = ch.name,
                url = url,
                headers = liveHeaders(ch),
                isM3u8 = url.substringBefore('?').lowercase().contains(".m3u8"),
                isMpd = url.substringBefore('?').lowercase().contains(".mpd"),
                drm = if (!ch.drmScheme.isNullOrBlank()) DrmSpec(licenseUrl = ch.drmLicense) else null,
                provider = ProviderType.IPTV.groupLabel,
                providerId = config.id, providerName = config.name,
            )
            // Same-name channels from other groups/playlists: the player's server
            // list shows them, which is the backup-channel switch.
            if (LiveTvEngine.store.settings().backupChannel) {
                LiveTvEngine.backupsOf(ch).take(4).forEachIndexed { i, b ->
                    out += StreamSource(
                        name = b.name + " · " + b.group,
                        url = b.url,
                        headers = liveHeaders(b),
                        isM3u8 = b.url.substringBefore('?').lowercase().contains(".m3u8"),
                        provider = "IPTV backup",
                        providerId = config.id, providerName = config.name,
                    )
                }
            }
            LiveTvEngine.markRecent(ch.key)
            out
        }

    private fun liveHeaders(ch: LiveChannel): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ch.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) out[k] = v }
        if (out.keys.none { it.equals("User-Agent", true) }) out["User-Agent"] = Http.UA
        return out
    }

    private fun toItem(c: LiveChannel, group: String = ""): MediaItem = MediaItem(
        providerId = config.id,
        id = "ch:" + c.key,
        title = c.name,
        type = MediaType.MOVIE,
        posterUrl = c.logo,
        overview = group.ifBlank { c.group }.takeIf { it != "Ungrouped" },
        rawType = "channel",
    )
}
