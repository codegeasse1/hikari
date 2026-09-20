package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.IptvChannel
import com.hikari.app.data.IptvPlaylist
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * An IPTV playlist as a Hikari provider.
 *
 * A playlist (`http://host/get.php?username=…&password=…&type=m3u_plus`, any
 * other M3U/M3U8 link, or a file the user picked from storage) is a flat list of
 * channels in groups. Those two things — the groups and the channels — are
 * exactly what a provider's `catalogs()` and `getCatalog()` already describe, so
 * IPTV needs no special case anywhere else in the app: it appears in the Home
 * provider picker like any extension, its channels are found by the global
 * search, it can be marked as an exception extension, and its channels land in
 * the player's server list under an "IPTV" heading — because every one of those
 * places reads the provider contract, and a channel's stream is simply… its
 * own URL.
 *
 * The playlist is parsed ONCE and kept for [TTL_MS]: a 20 000-channel list is
 * a few hundred kilobytes of names, and re-downloading it for every Home row,
 * search box keystroke and stream lookup would be worse than useless. A failed
 * download is remembered too (see [iptvErrors]) so a dead host is not retried
 * by every call in a pass.
 */
class IptvProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider failure text, shown in the row/notification that
         *  explains an empty result — same contract as the other engines. */
        val iptvErrors = ConcurrentHashMap<String, String>()

        /** How many channels each playlist currently holds, for the Extensions
         *  row ("1 240 channels") without re-parsing anything. */
        val channelCounts = ConcurrentHashMap<String, Int>()

        /** When each playlist was last read successfully (epoch ms). */
        private val loadedAt = ConcurrentHashMap<String, Long>()

        /** How long a parsed playlist is trusted before it is re-read. */
        const val TTL_MS = 30 * 60 * 1000L

        /** A playlist body is capped at this size before parsing — an Xtream
         *  panel's full m3u_plus export is a few MB; anything far past that is
         *  not a playlist a phone should hold in memory. */
        private const val MAX_BYTES = 16 * 1024 * 1024

        /** One Home row / catalog page. */
        private const val PAGE = 240

        /** Home rows offered per playlist: "All channels" plus this many groups,
         *  the biggest groups first. A playlist with 900 groups would otherwise
         *  bury every other extension on Home. */
        private const val MAX_GROUPS = 24

        private const val ALL = "iptv-all"
        private const val GROUP_PREFIX = "iptv-group:"

        private val HEADERS = mapOf(
            "Accept" to "application/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*",
        )

        /** How long a caller waits for the playlist before giving up on it. */
        private const val FETCH_TIMEOUT_MS = 45_000L

        /** True for a provider that is a playlist (its `url` is the M3U link or
         *  the local copy of one). */
        fun isPlaylistSource(url: String): Boolean {
            val u = url.trim()
            return u.startsWith("http://") || u.startsWith("https://") || File(u).exists()
        }

        /** Channels of [url] without needing a provider instance — used by the
         *  Add dialog to say how many channels a link holds before saving it. */
        suspend fun preview(url: String): Result<Int> = withContext(Dispatchers.IO) {
            val cached = previewCache[url]
            if (cached != null) return@withContext Result.success(cached)
            val text = readSource(url) ?: return@withContext Result.failure(
                Exception("Could not download that playlist — check the link."),
            )
            val list = IptvPlaylist.parse(text)
            val n = if (list.isEmpty() && url.startsWith("http")) 1 else list.size
            if (n == 0) return@withContext Result.failure(Exception("No channels found in that playlist."))
            previewCache[url] = n
            Result.success(n)
        }

        private val previewCache = ConcurrentHashMap<String, Int>()

        /** Reads the playlist body from a URL or a file. Blocking — call from IO. */
        private fun readSource(url: String): String? {
            val u = url.trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                val body = runCatching {
                    Http.fetchStringRobust(u, HEADERS).getOrNull()
                }.getOrNull() ?: return null
                return if (body.length > MAX_BYTES) body.take(MAX_BYTES) else body
            }
            val f = File(u)
            if (!f.exists() || !f.isFile) return null
            return runCatching {
                f.inputStream().use { input ->
                    val buf = ByteArray(MAX_BYTES)
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                }
            }.getOrNull()
        }
    }

    private val loadLock = Mutex()

    @Volatile
    private var channelsCache: List<IptvChannel>? = null

    private val selfCheckUrl = config.url.trim()

    /** The name the user gave the playlist, else the host it came from. */
    val displayName: String
        get() = config.name.ifBlank {
            config.url.substringAfter("://").substringBefore('/').ifBlank { "IPTV" }
        }

    /**
     * The playlist's channels, downloaded and parsed at most once per [TTL_MS].
     * [force] re-reads it (the folder's Refresh action). A failed read leaves any
     * previously parsed list in place, so a dead panel does not empty Home.
     */
    suspend fun channels(force: Boolean = false): List<IptvChannel> = loadLock.withLock {
        val cached = channelsCache
        if (!force && cached != null && System.currentTimeMillis() - (loadedAt[config.id] ?: 0L) < TTL_MS) {
            return@withLock cached
        }
        // A link with no `#EXTINF` at all is a bare m3u8/m3u stream rather than a
        // list: the URL itself is the one channel. That is what makes "just paste
        // my m3u8 link" work.
        val list = runCatching {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val text = readSource(selfCheckUrl)
                    if (text == null) null else IptvPlaylist.parse(text)
                }
            }
        }.getOrNull()
        if (list == null) {
            iptvErrors[config.id] = if (selfCheckUrl.startsWith("http")) {
                "Could not download this playlist — the host may be down, or the link may have expired."
            } else {
                "This playlist file is missing from the app's storage — re-add it."
            }
            return@withLock cached ?: emptyList()
        }
        val resolved = if (list.isEmpty()) singleStreamChannel().let { if (it == null) emptyList() else listOf(it) } else list
        if (resolved.isEmpty()) {
            iptvErrors[config.id] = "No channels found in this playlist."
            return@withLock cached ?: emptyList()
        }
        iptvErrors.remove(config.id)
        channelsCache = resolved
        loadedAt[config.id] = System.currentTimeMillis()
        channelCounts[config.id] = resolved.size
        resolved
    }

    /** When [config.url] is a stream rather than a playlist (an m3u8/m3u link
     *  pasted straight in), that stream is the playlist's only channel. */
    private fun singleStreamChannel(): IptvChannel? {
        val u = selfCheckUrl
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val name = config.name.ifBlank {
            u.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
                .ifBlank { u.substringAfter("://").substringBefore('/') }
        }
        return IptvChannel(name = name, url = u, group = "")
    }

    /** Channels after [group] filtering, shared by catalogs and streams. */
    private fun groupChannels(all: List<IptvChannel>, group: String): List<IptvChannel> =
        all.filter { IptvPlaylist.groupOf(it) == group }

    override suspend fun catalogs(): List<CatalogRef> {
        val all = channels()
        if (all.isEmpty()) return emptyList()
        val out = ArrayList<CatalogRef>()
        out += CatalogRef(config.id, MediaType.MOVIE, ALL, "All channels", "channel")
        val groups = all.groupBy { IptvPlaylist.groupOf(it) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<IptvChannel>>> { it.value.size }
                    .thenBy { it.key.lowercase() },
            )
        for (g in groups.take(MAX_GROUPS)) {
            out += CatalogRef(config.id, MediaType.MOVIE, GROUP_PREFIX + g.key, g.key, "channel")
        }
        return out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val all = channels()
        val list = when {
            ref.id == ALL -> all
            ref.id.startsWith(GROUP_PREFIX) -> groupChannels(all, ref.id.removePrefix(GROUP_PREFIX))
            else -> emptyList()
        }
        if (list.isEmpty()) return emptyList()
        val from = (page.coerceAtLeast(1) - 1) * PAGE
        if (from >= list.size) return emptyList()
        return list.subList(from, minOf(from + PAGE, list.size)).map { toItem(it) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val all = channels()
        val hit = all.filter { it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true) }
        if (hit.isEmpty()) return emptyList()
        val from = (page.coerceAtLeast(1) - 1) * PAGE
        if (from >= hit.size) return emptyList()
        return hit.subList(from, minOf(from + PAGE, hit.size)).map { toItem(it) }
    }

    /**
     * A channel's stream IS its URL — there is nothing to extract, which is why
     * an IPTV playlist is the fastest source in the app: the server list gets
     * the exact link the playlist declared, with the playlist's own group as its
     * secondary line.
     */
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val all = channels()
        val channel = all.firstOrNull { it.url == item.id }
            ?: all.firstOrNull { it.name.equals(item.title, ignoreCase = true) }
            ?: if (item.id.startsWith("http")) {
                IptvChannel(
                    name = item.title,
                    url = item.id,
                    logo = item.posterUrl,
                    group = item.overview.orEmpty(),
                )
            } else {
                null
            }
            ?: run {
                iptvErrors[config.id] = "That channel is no longer in this playlist."
                return emptyList()
            }
        val url = channel.url
        return listOf(
            StreamSource(
                name = channel.name,
                url = url,
                isM3u8 = url.substringBefore('?').contains(".m3u8", ignoreCase = true),
                provider = ProviderType.IPTV.groupLabel,
                providerId = config.id,
                providerName = displayName,
            ),
        )
    }

    override suspend fun getMeta(item: MediaItem): MediaItem = item

    /** A channel has no episodes — it is one stream, playing continuously. */
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = null

    private fun toItem(c: IptvChannel): MediaItem = MediaItem(
        providerId = config.id,
        id = c.url,
        title = c.name,
        type = MediaType.MOVIE,
        posterUrl = c.logo,
        overview = IptvPlaylist.groupOf(c).takeIf { it != "Ungrouped" },
        rawType = "channel",
    )

    /** Re-reads the playlist on the next call. */
    fun invalidate() {
        loadedAt.remove(config.id)
        channelsCache = null
    }
}
