package com.hikari.app.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.hikari.app.HikariApp
import com.hikari.app.data.IptvPlaylist
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The Live TV engine: M3U + Xtream sources, XMLTV guides, unified channels,
 * favorites/recent/hidden, now/next, catch-up, backup channels, reminders,
 * recordings, sports and On Demand data. UI screens read this; [LiveTvBridge]
 * serves the same channels through the provider contract so Home, search,
 * detail and the player work unchanged.
 */
object LiveTvEngine {

    const val PROVIDER_PREFIX = "livetv|"
    const val BRIDGE_SCHEME = "livetv://"

    fun bridgeId(sourceId: String): String = PROVIDER_PREFIX + sourceId
    fun sourceIdOfBridge(providerId: String): String? =
        providerId.removePrefix(PROVIDER_PREFIX).takeIf { providerId.startsWith(PROVIDER_PREFIX) && it.isNotBlank() }

    fun isBridgeUrl(url: String): Boolean = url.startsWith(BRIDGE_SCHEME)
    fun sourceIdOfUrl(url: String): String? =
        url.removePrefix(BRIDGE_SCHEME).substringBefore('?').substringBefore('/')
            .takeIf { url.startsWith(BRIDGE_SCHEME) && it.isNotBlank() }

    private var appCtx: Context? = null
    fun appContext(): Context? = appCtx?.applicationContext ?: appCtx
    private fun hikari(): HikariApp? = appContext() as? HikariApp
    val store: LiveTvStore by lazy { LiveTvStore(appContext() ?: throw IllegalStateException("Live TV not initialised yet")) }
    val epg: EpgStore by lazy { EpgStore(store) }
    val xtream = XtreamApi()
    val vod = LiveTvVod()
    val sports = LiveTvSports()
    val reminders: LiveTvReminders = LiveTvReminders
    val recordings: LiveTvRecordings = LiveTvRecordings

    private val lock = Mutex()

    /** Source id -> its channels (light; posters stay URLs). */
    @Volatile private var channelsBySource: Map<String, List<LiveChannel>> = emptyMap()

    /** Channel key -> channel (rebuilt with the lists). */
    @Volatile private var channelIndex: Map<String, LiveChannel> = emptyMap()

    /** Lowercased name -> channels (backup-channel switching). */
    @Volatile private var backupIndex: Map<String, List<LiveChannel>> = emptyMap()

    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        appCtx = context.applicationContext ?: context
        val app = hikari()
        ready = true
        ensureNotificationChannel()
        if (app != null) {
            recordings.init(app)
            reminders.init(app)
            sports.init(app)
        }
    }

    private fun requireReady() {
        if (!ready) runCatching { init(HikariApp.instance) }
        if (!ready) throw IllegalStateException("Live TV not initialised yet")
    }

    // ---- sources ----

    fun sources(): List<LiveTvSource> {
        requireReady()
        return store.sources()
    }

    fun epgSources(): List<LiveTvEpgSource> {
        requireReady()
        return store.epgSources()
    }

    fun sourceOf(id: String): LiveTvSource? = sources().firstOrNull { it.id == id }

    suspend fun addM3u(name: String, url: String, userAgent: String = ""): LiveTvSource {
        requireReady()
        val src = LiveTvSource(id = store.newId(), name = name.ifBlank { "Playlist" }, kind = LiveTvSource.KIND_M3U, url = url.trim(), userAgent = userAgent)
        // Validate before saving (a dead link must not become a provider row).
        val text = downloadPlaylist(src) ?: throw IllegalStateException("Could not download that playlist — check the link.")
        val list = IptvPlaylist.parse(text, base = src.url)
        if (list.isEmpty()) throw IllegalStateException("No channels found in that playlist.")
        store.updateSources { it + src }
        ensureProviderRow(src)
        refreshSource(src.id)
        return src
    }

    suspend fun addXtream(name: String, server: String, username: String, password: String, streamFormat: String = "auto"): LiveTvSource {
        requireReady()
        val src = LiveTvSource(
            id = store.newId(), name = name.ifBlank { "Xtream" }, kind = LiveTvSource.KIND_XTREAM,
            xtreamServer = XtreamApi.cleanServer(server), xtreamUsername = username.trim(),
            xtreamPassword = password.trim(), streamFormat = streamFormat,
        )
        val info = withContext(Dispatchers.IO) { xtream.accountInfo(src) }
        info.loginError()?.let { throw IllegalStateException(it) }
        val withAccount = src.copy(
            accountStatus = info.status, accountExpiresMs = info.expiresAtMs,
            accountConnections = if (info.maxConnections > 0) "${info.activeConnections}/${info.maxConnections}" else "",
            serverTimezone = info.serverTimezone.orEmpty(),
        )
        store.updateSources { it + withAccount }
        ensureProviderRow(withAccount)
        refreshSource(withAccount.id)
        return withAccount
    }

    suspend fun addEpg(name: String, url: String): LiveTvEpgSource {
        requireReady()
        val src = LiveTvEpgSource(id = store.newId(), name = name.ifBlank { "Guide" }, url = url.trim())
        // Validate before saving.
        downloadGuide(src) ?: throw IllegalStateException("Could not download that guide — check the link.")
        store.saveEpgSources(epgSources() + src)
        refreshEpg(src.id)
        return src
    }

    suspend fun removeSource(id: String) {
        requireReady()
        store.updateSources { list -> list.filterNot { it.id == id } }
        epg.clear(id)
        channelsBySource = channelsBySource - id
        rebuildIndex()
        removeProviderRow(id)
    }

    suspend fun removeEpg(id: String) {
        requireReady()
        store.saveEpgSources(epgSources().filterNot { it.id == id })
        epg.clear(id)
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        requireReady()
        store.updateSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        val src = sourceOf(id) ?: return
        val app = hikari() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                app.store.updateProviders { plist ->
                    plist.map { p -> if (p.id == bridgeId(id)) p.copy(enabled = enabled) else p }
                }
                app.providers.refresh()
            }
        }
        if (enabled) refreshSource(id)
    }

    // ---- provider rows (Home / search / detail integration) ----

    private fun providerRow(src: LiveTvSource): ProviderConfig = ProviderConfig(
        id = bridgeId(src.id),
        name = src.name,
        type = ProviderType.IPTV,
        url = BRIDGE_SCHEME + src.id,
        enabled = src.enabled,
    )

    private suspend fun ensureProviderRow(src: LiveTvSource) {
        val row = providerRow(src)
        val app = hikari() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                var added = false
                app.store.updateProviders { list ->
                    if (list.any { it.id == row.id }) {
                        list.map { if (it.id == row.id) row.copy(iconUrl = it.iconUrl) else it }
                    } else {
                        added = true
                        list + row
                    }
                }
                if (added) app.providers.refresh()
            }.onFailure { Logs.log("LiveTv", "provider row failed: ${it.message}") }
        }
    }

    private suspend fun removeProviderRow(sourceId: String) {
        val app = hikari() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                app.store.updateProviders { list -> list.filterNot { it.id == bridgeId(sourceId) } }
                app.providers.refresh()
            }
        }
    }

    /** Adds provider rows for every source (used after an import). */
    suspend fun resyncProviderRows() {
        requireReady()
        sources().forEach { ensureProviderRow(it) }
    }

    // ---- refresh ----

    /** Refreshes every stale source (intervals from settings) — safe to call often. */
    suspend fun ensureFresh() {
        requireReady()
        val s = store.settings()
        val now = System.currentTimeMillis()
        sources().filter { it.enabled }.forEach { src ->
            val staleMs = s.playlistRefreshHours * 3_600_000L
            if (src.lastUpdatedMs <= 0 || now - src.lastUpdatedMs > staleMs) {
                refreshSource(src.id)
            }
        }
        epgSources().filter { it.enabled }.forEach { src ->
            val staleMs = s.epgRefreshHours * 3_600_000L
            if (src.lastUpdatedMs <= 0 || now - src.lastUpdatedMs > staleMs) {
                refreshEpg(src.id)
            }
        }
    }

    /** Re-downloads one source now (the "Update now" button). */
    suspend fun refreshSource(id: String): String? = lock.withLock {
        val src = sourceOf(id) ?: return null
        val failure = runCatching {
            withTimeoutOrNull(120_000L) {
                if (src.isXtream) refreshXtream(src) else refreshM3u(src)
            } ?: throw IllegalStateException("the provider took too long to answer")
        }.exceptionOrNull()
        if (failure != null && failure !is kotlinx.coroutines.CancellationException) {
            val err = prettyError(failure)
            store.updateSources { list -> list.map { if (it.id == id) it.copy(lastError = err) else it } }
            Logs.log("LiveTv", "${src.name}: refresh failed: $err")
        }
        rebuildIndex()
        return sourceOf(id)?.lastError
    }

    private suspend fun refreshM3u(src: LiveTvSource) {
        val text = withContext(Dispatchers.IO) { downloadPlaylist(src) }
            ?: throw IllegalStateException("Could not download this playlist — the host may be down, or the link may have expired.")
        val parsed = IptvPlaylist.parse(text, base = src.url)
        val extra = scanExtinf(text)
        val terms = ChannelNameEditor.terms(store.settings().nameRemovals)
        val space = ChannelNameEditor.spacePrefixes(parsed.map { it.name }, terms)
        val list = parsed.mapIndexed { i, c ->
            val x = extra[c.url]
            val cleanName = ChannelNameEditor.clean(c.name, terms, space)
            LiveChannel(
                key = src.id + "|" + c.url,
                sourceId = src.id, sourceName = src.name,
                name = cleanName.ifBlank { c.name },
                tvgId = x?.tvgId ?: c.tvgId,
                tvgName = c.tvgId?.let { null },
                logo = pickLogo(c.logo, null, src.logoSource),
                groupId = src.id + "|" + IptvPlaylist.groupOf(c),
                group = IptvPlaylist.groupOf(c),
                number = x?.number ?: (i + 1),
                url = c.url, headers = c.headers,
                catchup = x?.catchup,
                language = c.language,
                country = c.country,
            )
        }
        channelsBySource = channelsBySource + (src.id to list)
        store.updateSources { l ->
            l.map { if (it.id == src.id) it.copy(lastUpdatedMs = System.currentTimeMillis(), lastError = null, channelCount = list.size) else it }
        }
        Logs.log("LiveTv", "${src.name}: ${list.size} channel(s)")
        // Embedded guides advertised inside the playlist header.
        if (src.useEmbeddedEpg) {
            embeddedGuideUrls(text).forEach { url ->
                refreshGuideText(src.id, url, src.userAgent, "playlist guide")
            }
        }
        // Xtream-backed M3U link: also pull the provider guide + account.
        if (!src.isXtream) {
            val apiUrl = runCatching { xtream.m3uAccountUrl(src.url) }.getOrNull()
            if (apiUrl != null) {
                runCatching {
                    val info = withContext(Dispatchers.IO) { xtream.accountInfo(apiUrl, src.userAgent) }
                    store.updateSources { l ->
                        l.map {
                            if (it.id == src.id) it.copy(
                                accountStatus = info.status, accountExpiresMs = info.expiresAtMs,
                                accountConnections = if (info.maxConnections > 0) "${info.activeConnections}/${info.maxConnections}" else "",
                                serverTimezone = info.serverTimezone ?: it.serverTimezone,
                            ) else it
                        }
                    }
                }
                runCatching {
                    val base = XtreamApi.cleanServer(src.url.substringBefore("/get.php"))
                    val user = src.url.substringAfter("username=").substringBefore('&')
                    val pass = src.url.substringAfter("password=").substringBefore('&')
                    val epgUrl = "$base/xmltv.php?username=$user&password=$pass"
                    refreshGuideText(src.id, epgUrl, src.userAgent, "provider guide")
                }
            }
        }
    }

    private suspend fun refreshXtream(src: LiveTvSource) {
        val built = withContext(Dispatchers.IO) { xtream.livePlaylistM3u(src) }
        built.account.loginError()?.let { throw XtreamLoginError(it) }
        val parsed = IptvPlaylist.parse(built.m3u, base = XtreamApi.cleanServer(src.xtreamServer))
        val extra = scanExtinf(built.m3u)
        val terms = ChannelNameEditor.terms(store.settings().nameRemovals)
        val space = ChannelNameEditor.spacePrefixes(parsed.map { it.name }, terms)
        val ext = withContext(Dispatchers.IO) {
            runCatching { xtream.streamExtension(src, built.account.allowedFormats) }.getOrDefault("ts")
        }
        val list = parsed.mapIndexed { i, c ->
            val x = extra[c.url]
            val num = x?.tvgChno?.toIntOrNull() ?: (i + 1)
            val streamId = XtreamApi.xtreamLink.find(c.url)?.groupValues?.getOrNull(4).orEmpty()
            LiveChannel(
                key = src.id + "|" + c.url,
                sourceId = src.id, sourceName = src.name,
                name = ChannelNameEditor.clean(c.name, terms, space).ifBlank { c.name },
                tvgId = x?.tvgId ?: c.tvgId,
                tvgName = null,
                logo = pickLogo(c.logo, null, src.logoSource),
                groupId = src.id + "|" + IptvPlaylist.groupOf(c),
                group = IptvPlaylist.groupOf(c),
                number = num,
                url = c.url, headers = c.headers,
                catchup = x?.catchup,
                streamId = streamId,
                language = c.language,
                country = c.country,
            )
        }
        channelsBySource = channelsBySource + (src.id to list)
        store.updateSources { l ->
            l.map {
                if (it.id == src.id) it.copy(
                    lastUpdatedMs = System.currentTimeMillis(), lastError = null,
                    channelCount = list.size, serverTimezone = built.account.serverTimezone ?: it.serverTimezone,
                    accountStatus = built.account.status, accountExpiresMs = built.account.expiresAtMs,
                    accountConnections = if (built.account.maxConnections > 0) "${built.account.activeConnections}/${built.account.maxConnections}" else "",
                ) else it
            }
        }
        Logs.log("LiveTv", "${src.name}: ${list.size} channel(s) via Xtream ($ext)")
        // Provider guide.
        runCatching {
            val text = withContext(Dispatchers.IO) { xtream.epgXml(src) }
            if (!text.isNullOrBlank()) ingestGuideText(src.id, text, "provider guide", list)
        }
        // On Demand import.
        if (src.importVod) {
            runCatching { vod.refreshSource(src) }.onFailure {
                Logs.log("LiveTv", "${src.name}: on-demand failed: ${it.message}")
            }
        }
    }

    /** Re-downloads one guide now (the "Update now" button). Returns its error, if any. */
    suspend fun refreshEpgNow(id: String): String? = lock.withLock {
        val src = epgSources().firstOrNull { it.id == id } ?: return null
        val failure = runCatching {
            withTimeoutOrNull(180_000L) {
                val text = withContext(Dispatchers.IO) { downloadGuide(src) }
                    ?: throw IllegalStateException("Could not download this guide — the host may be down.")
                val (nc, np) = ingestGuideText(id, text, src.name, allChannels())
                store.saveEpgSources(epgSources().map {
                    if (it.id == id) it.copy(lastUpdatedMs = System.currentTimeMillis(), lastError = null, programCount = np) else it
                })
                Logs.log("LiveTv", "${src.name}: guide $nc channel(s), $np show(s)")
            } ?: throw IllegalStateException("the provider took too long to answer")
        }.exceptionOrNull()
        if (failure != null && failure !is kotlinx.coroutines.CancellationException) {
            val err = prettyError(failure)
            store.saveEpgSources(epgSources().map {
                if (it.id == id) it.copy(lastError = err) else it
            })
            return failure.message
        }
        return null
    }

    private suspend fun refreshEpg(id: String) {        val src = epgSources().firstOrNull { it.id == id } ?: return
        val text = withContext(Dispatchers.IO) { downloadGuide(src) }
        if (text == null) {
            store.saveEpgSources(epgSources().map {
                if (it.id == id) it.copy(lastError = "Could not download this guide — the host may be down.") else it
            })
            return
        }
        val channels = allChannels()
        val (nc, np) = ingestGuideText(id, text, src.name, channels)
        store.saveEpgSources(epgSources().map {
            if (it.id == id) it.copy(lastUpdatedMs = System.currentTimeMillis(), lastError = null, programCount = np) else it
        })
        Logs.log("LiveTv", "${src.name}: guide $nc channel(s), $np show(s)")
    }

    /** Parses guide [text] (plain or .gz bytes already decoded) into the windowed store. */
    fun ingestGuideText(sourceId: String, text: String, label: String, channels: List<LiveChannel>): Pair<Int, Int> {
        val s = store.settings()
        val now = System.currentTimeMillis()
        val from = now - s.guideHoursBack * 3_600_000L
        val to = now + s.daysAhead * 86_400_000L
        val epgChannels = epg.channelsOf(sourceId)
        val nameIndex = HashMap<String, MutableSet<String>>()
        epgChannels.forEach { c -> c.displayNames.forEach { n -> nameIndex.getOrPut(normName(n)) { HashSet() } += c.id } }
        val wanted = epg.wantedIds(channels, store.userState().epgOverrides.values.toSet())
        val res = epg.ingest(sourceId, sequenceOf(text), from, to, wanted)
        epg.prune(sourceId, from)
        return res
    }

    private fun refreshGuideText(sourceId: String, url: String, userAgent: String, label: String) {
        val text = downloadGuideUrl(url, userAgent) ?: return
        runCatching { ingestGuideText(sourceId, text, label, allChannels()) }
            .onFailure { Logs.log("LiveTv", "$label failed: ${it.message}") }
    }

    // ---- channels ----

    fun allChannels(): List<LiveChannel> = channelsBySource.values.flatten()

    fun channelsOf(sourceId: String): List<LiveChannel> = channelsBySource[sourceId].orEmpty()

    fun channelOf(key: String): LiveChannel? = channelIndex[key]

    private fun rebuildIndex() {
        val all = allChannels()
        channelIndex = all.associateBy { it.key }
        val byName = HashMap<String, MutableList<LiveChannel>>()
        all.forEach { byName.getOrPut(normName(it.name)) { ArrayList() } += it }
        backupIndex = byName
    }

    /** Channels with the same name from another group or playlist (backup switching). */
    fun backupsOf(channel: LiveChannel): List<LiveChannel> =
        backupIndex[normName(channel.name)].orEmpty().filter { it.key != channel.key }

    /** Applies favorites/hidden/locks/renames/numbers for lists. */
    fun visibleChannels(sourceId: String? = null): List<LiveChannel> {
        val st = store.userState()
        val s = store.settings()
        val terms = ChannelNameEditor.terms(s.nameRemovals)
        return allChannels()
            .filter { sourceId == null || it.sourceId == sourceId }
            .filter { it.key !in st.hiddenChannels && it.groupId !in st.hiddenGroups }
            .filter { !isLockedGroup(it.groupId, it.group) }
            .map { c ->
                val name = st.channelNames[c.key] ?: ChannelNameEditor.clean(c.name, terms)
                val num = st.channelNumbers[c.key] ?: c.number
                if (name == c.name && num == c.number) c else c.copy(name = name, number = num)
            }
    }

    fun groupsOf(sourceId: String? = null): List<LiveChannelGroup> {
        val st = store.userState()
        val chans = visibleChannels(sourceId)
        val byGroup = chans.groupBy { it.groupId }
        val out = ArrayList<LiveChannelGroup>()
        val all = chans.size
        if (all > 0) out += LiveChannelGroup(LiveChannelGroup.ALL, "All channels", all, special = true)
        if (st.favorites.isNotEmpty()) {
            val n = st.favorites.count { k -> channelIndex[k] != null }
            if (n > 0) out += LiveChannelGroup(LiveChannelGroup.FAVORITES, "Favorites", n, special = true)
        }
        val recent = st.recent.filter { channelIndex[it] != null }
        if (recent.isNotEmpty()) out += LiveChannelGroup(LiveChannelGroup.RECENT, "Recently watched", recent.size, special = true)
        val ordered = byGroup.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<LiveChannel>>> { it.value.size }.thenBy { it.value.first().group.lowercase() }
        )
        val order = st.groupOrder
        val sorted = if (order.isNotEmpty()) {
            ordered.sortedBy { e -> order.indexOf(e.key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        } else ordered
        sorted.forEach { (gid, list) ->
            val title = st.groupNames[gid] ?: list.first().group
            val srcId = list.first().sourceId
            out += LiveChannelGroup(gid, title, list.size, sourceId = srcId, locked = isLockedGroup(gid, title))
        }
        st.customGroups.forEach { g ->
            val n = g.channelKeys.count { channelIndex[it] != null }
            out += LiveChannelGroup(LiveChannelGroup.CUSTOM_PREFIX + g.id, g.name, n, special = true)
        }
        return out
    }

    fun channelsInGroup(group: LiveChannelGroup, sourceId: String? = null): List<LiveChannel> {
        val st = store.userState()
        val order = st.channelOrder[group.id]
        fun sort(list: List<LiveChannel>): List<LiveChannel> {
            val base = when {
                store.settings().sequentialNumbers -> list.mapIndexed { i, c -> c.copy(number = i + 1) }
                else -> list.sortedWith(compareBy({ it.number.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            }
            if (order.isNullOrEmpty()) return base
            val pos = order.withIndex().associate { it.value to it.index }
            return base.sortedBy { pos[it.key] ?: Int.MAX_VALUE }
        }
        return when (group.id) {
            LiveChannelGroup.ALL -> sort(visibleChannels(sourceId))
            LiveChannelGroup.FAVORITES -> {
                val fav = st.favorites.mapNotNull { channelIndex[it] }
                    .filter { sourceId == null || it.sourceId == sourceId }
                sort(fav)
            }
            LiveChannelGroup.RECENT -> {
                val rec = st.recent.mapNotNull { channelIndex[it] }
                    .filter { sourceId == null || it.sourceId == sourceId }
                rec // recency order, not sorted
            }
            else -> {
                if (group.id.startsWith(LiveChannelGroup.CUSTOM_PREFIX)) {
                    val g = st.customGroups.firstOrNull { LiveChannelGroup.CUSTOM_PREFIX + it.id == group.id }
                    sort(g?.channelKeys?.mapNotNull { channelIndex[it] }.orEmpty())
                } else {
                    sort(visibleChannels(sourceId).filter { it.groupId == group.id })
                }
            }
        }
    }

    fun search(query: String, sourceId: String? = null): List<LiveChannel> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return visibleChannels(sourceId).filter {
            it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true)
        }.take(300)
    }

    // ---- guide ----

    /** The XMLTV id a channel's guide lives under (tvg-id, or the hand assignment). */
    fun epgIdOf(channel: LiveChannel): String? {
        val override = store.userState().epgOverrides[channel.key]
        if (override != null) return override.ifBlank { null }
        channel.tvgId?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    fun nowNext(channel: LiveChannel, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgram?, EpgProgram?> {
        val id = epgIdOf(channel) ?: return null to null
        val s = store.settings()
        val off = s.epgOffsetMinutes * 60_000L
        val list = epg.programs(channel.sourceId, id, nowMs - 6 * 3_600_000L + off, nowMs + 6 * 3_600_000L + off)
        if (list.isEmpty()) return null to null
        val now = list.firstOrNull { nowMs + off in it.startMs until it.stopMs }
        val next = list.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs + off) } ?: list.firstOrNull { it.startMs > nowMs + off }
        return now to next
    }

    fun guideWindow(channel: LiveChannel, fromMs: Long, toMs: Long): List<EpgProgram> {
        val id = epgIdOf(channel) ?: return emptyList()
        val off = store.settings().epgOffsetMinutes * 60_000L
        return epg.programs(channel.sourceId, id, fromMs + off, toMs + off)
    }

    /** Matches channels to an XMLTV id by name (the "Change EPG" / auto path). */
    fun matchEpgId(channel: LiveChannel, candidates: List<XmltvChannel>): XmltvChannel? {
        val norm = normName(channel.name)
        candidates.firstOrNull { c -> c.id.equals(channel.tvgId, ignoreCase = true) }?.let { return it }
        var best: XmltvChannel? = null
        var bestScore = 0
        candidates.forEach { c ->
            c.displayNames.forEach { n ->
                val score = nameScore(norm, normName(n))
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
        }
        return if (bestScore >= 70) best else null
    }

    // ---- favorites / recent / hidden / locks ----

    fun toggleFavorite(key: String) {
        store.updateUserState { st ->
            st.copy(favorites = if (key in st.favorites) st.favorites - key else st.favorites + key)
        }
    }

    /** One channel by its stable key (the player drawer resolves "ch:<key>"
     *  through this), with the user's rename/number overrides applied — the
     *  same mapping [visibleChannels] uses, so the drawer names match Home. */
    fun channelByKey(key: String): LiveChannel? {
        val raw = channelIndex[key] ?: return null
        return runCatching {
            val st = store.userState()
            val name = st.channelNames[key] ?: raw.name
            val num = st.channelNumbers[key] ?: raw.number
            if (name == raw.name && num == raw.number) raw else raw.copy(name = name, number = num)
        }.getOrDefault(raw)
    }

    fun markRecent(key: String) {
        if (!store.settings().continueWatching) return
        store.updateUserState { st ->
            st.copy(recent = (listOf(key) + st.recent.filterNot { it == key }).take(60))
        }
    }

    fun setHiddenChannel(key: String, hidden: Boolean) {
        store.updateUserState { st ->
            st.copy(hiddenChannels = if (hidden) st.hiddenChannels + key else st.hiddenChannels - key)
        }
    }

    fun setHiddenGroup(groupId: String, hidden: Boolean) {
        store.updateUserState { st ->
            st.copy(hiddenGroups = if (hidden) st.hiddenGroups + groupId else st.hiddenGroups - groupId)
        }
    }

    fun isLockedGroup(groupId: String, groupName: String): Boolean {
        val st = store.userState()
        if (st.parentalPin.length < 4) return false
        if (LiveTvParental.unlocked(groupId)) return false
        return groupId in st.lockedGroups || (st.lockAdultContent && LiveTvParental.isAdult(groupName))
    }

    fun rememberAudio(channelKey: String, label: String) {
        if (!store.settings().rememberAudioTrack) return
        store.updateUserState { st -> st.copy(channelAudio = st.channelAudio + (channelKey to label)) }
    }

    fun audioOf(channelKey: String): String? = store.userState().channelAudio[channelKey]

    // ---- catch-up ----

    /** Builds a replay URL for [program] on [channel] (with the store's HLS/time prefs). */
    fun catchupUrl(channel: LiveChannel, program: EpgProgram, nowMs: Long = System.currentTimeMillis()): String? {
        val cu = channel.catchup ?: return null
        if (!CatchupUrlBuilder.isAvailable(cu, program.startMs, nowMs)) return null
        val s = store.settings()
        val src = sourceOf(channel.sourceId)
        return CatchupUrlBuilder.build(
            liveUrl = channel.url,
            catchup = cu.copy(correctionHours = cu.correctionHours + s.catchupShiftMinutes / 60.0),
            startMs = program.startMs,
            stopMs = program.stopMs,
            nowMs = nowMs,
            preferHls = s.preferHlsCatchup && !hlsRefused(channel.sourceId),
            serverTimezone = src?.serverTimezone?.ifBlank { null },
        )
    }

    /** Other forms of a replay link to try when the first fails (HLS↔TS, clock, shorter). */
    fun catchupAlternatives(url: String, channel: LiveChannel): List<String> {
        val src = sourceOf(channel.sourceId)
        return CatchupUrlBuilder.alternatives(url, src?.serverTimezone?.ifBlank { null }, null, null)
    }

    /** Remembers that HLS failed here (a week of straight-to-TS, like the reference). */
    fun noteHlsRefused(sourceId: String) {
        store.updateSources { list ->
            list.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = System.currentTimeMillis()) else it }
        }
    }

    private fun hlsRefused(sourceId: String): Boolean {
        val since = sourceOf(sourceId)?.catchupNoHlsSince ?: 0L
        return since > 0 && System.currentTimeMillis() - since < 7 * 86_400_000L
    }

    /** HLS that can't be read plays as TS once (remembered for this run). */
    private val tsInstead = HashSet<String>()
    fun tsLinkFor(url: String): String? {
        val m = XtreamApi.xtreamLink.find(url) ?: return null
        if (!url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return null
        return url.substringBefore('?').dropLast(5) + ".ts" + url.substring(url.substringBefore('?').length)
    }

    fun playUrl(channel: LiveChannel): String =
        synchronized(tsInstead) {
            if (channel.key in tsInstead) tsLinkFor(channel.url) ?: channel.url else channel.url
        }

    fun noteTsInstead(channel: LiveChannel) {
        synchronized(tsInstead) { tsInstead += channel.key }
    }

    // ---- downloads ----

    private fun downloadPlaylist(src: LiveTvSource): String? {
        val u = src.url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) {
            val headers = mapOf("Accept" to "application/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*") +
                (src.userAgent.ifBlank { null }?.let { mapOf("User-Agent" to it) }.orEmpty())
            runCatching { Http.fetchStringRobust(u, headers).getOrNull()?.take(16 * 1024 * 1024) }.getOrNull()
        } else {
            val f = File(u)
            if (!f.isFile) return null
            runCatching {
                f.inputStream().use { ins ->
                    val buf = ByteArray(16 * 1024 * 1024)
                    var read = 0
                    while (read < buf.size) {
                        val n = ins.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                }
            }.getOrNull()
        }
    }

    fun downloadGuide(src: LiveTvEpgSource): String? = downloadGuideUrl(src.url, "")

    fun downloadGuideUrl(url: String, userAgent: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return runCatching {
                val req = okhttp3.Request.Builder().url(u)
                    .header("User-Agent", userAgent.ifBlank { XtreamApi.DEFAULT_UA })
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "gzip")
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) return null
                    val body = resp.body ?: return null
                    val enc = resp.header("Content-Encoding").orEmpty().lowercase()
                    val stream = body.byteStream()
                    val input = if ("gzip" in enc || u.endsWith(".gz", true)) {
                        runCatching { java.util.zip.GZIPInputStream(stream) }.getOrNull() ?: stream
                    } else stream
                    val sb = StringBuilder()
                    val buf = ByteArray(256 * 1024)
                    var total = 0
                    input.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > XtreamApi.GUIDE_MAX_BYTES) break
                            sb.append(String(buf, 0, n, Charsets.UTF_8))
                        }
                    }
                    sb.toString().takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
        val f = File(u)
        if (!f.isFile) return null
        return runCatching {
            val raw = f.readBytes().take(256 * 1024 * 1024).toByteArray()
            val stream = if (u.endsWith(".gz", true)) {
                runCatching { java.util.zip.GZIPInputStream(raw.inputStream()) }.getOrNull()?.readBytes()?.inputStream()
                    ?: raw.inputStream()
            } else raw.inputStream()
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    // ---- misc ----

    private fun prettyError(t: Throwable): String {
        val m = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return if (m.length > 220) m.take(220) + "…" else m
    }

    private fun pickLogo(playlist: String?, epg: String?, mode: String): String? {
        val p = playlist?.takeIf { it.isNotBlank() }
        val e = epg?.takeIf { it.isNotBlank() }
        return if (mode == "epg") e ?: p else p ?: e
    }

    fun normName(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun nameScore(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) return 85
        val aw = a.split(' ').filter { it.length > 2 }.toSet()
        val bw = b.split(' ').filter { it.length > 2 }.toSet()
        if (aw.isEmpty() || bw.isEmpty()) return 0
        val inter = aw.intersect(bw).size
        val union = aw.union(bw).size
        return (100 * inter / union).coerceIn(0, 100)
    }

    // ---- EXTINF extras (catch-up, tvg-chno) that the M3U model drops ----

    data class ExtinfExtra(val tvgId: String?, val tvgChno: String?, val number: Int?, val catchup: CatchupInfo?)

    private val attrRe = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    fun scanExtinf(text: String): Map<String, ExtinfExtra> {
        val out = HashMap<String, ExtinfExtra>()
        var pending: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.regionMatches(0, "#EXTINF", 0, 7, ignoreCase = true)) {
                    val head = if (',' in line) line.substring(0, line.lastIndexOf(',')) else line
                    val map = HashMap<String, String>()
                    for (m in attrRe.findAll(head)) map[m.groupValues[1].lowercase()] = m.groupValues[2]
                    pending = map
                }
                continue
            }
            if (line.startsWith("http://") || line.startsWith("https://")) {
                val p = pending
                pending = null
                if (p == null) continue
                val cuType = p["catchup"].orEmpty().lowercase()
                val cuSource = p["catchup-source"]?.takeIf { it.isNotBlank() }
                val cuDays = p["catchup-days"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val catchup = when {
                    cuType in setOf("xc", "xtream") -> CatchupInfo("xc", cuSource, cuDays)
                    cuType.isNotBlank() -> CatchupInfo(
                        type = cuType,
                        source = cuSource,
                        days = cuDays,
                        correctionHours = p["catchup-correction"]?.toDoubleOrNull() ?: 0.0,
                    )
                    // Xtream-style links without an explicit tag still get timeshift links.
                    XtreamApi.xtreamLink.containsMatchIn(line) -> CatchupInfo("xc", null, 1)
                    else -> null
                }
                out[line] = ExtinfExtra(
                    tvgId = p["tvg-id"]?.takeIf { it.isNotBlank() },
                    tvgChno = p["tvg-chno"]?.takeIf { it.isNotBlank() },
                    number = p["tvg-chno"]?.toIntOrNull(),
                    catchup = catchup,
                )
            } else {
                pending = null
            }
        }
        return out
    }

    private fun embeddedGuideUrls(m3uText: String): List<String> {
        val out = LinkedHashSet<String>()
        m3uText.lineSequence().take(60).forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#", ignoreCase = true)) return@forEach
            for (m in attrRe.findAll(line)) {
                val k = m.groupValues[1].lowercase()
                if (k == "url-tvg" || k == "x-tvg-url") {
                    m.groupValues[2].split(',').map { it.trim().trim('"') }
                        .filter { it.startsWith("http") }.forEach { out += it }
                }
            }
        }
        return out.take(3)
    }

    private fun ensureNotificationChannel() {
        val ctx = appContext() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Live TV", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
        }
    }

    const val CHANNEL_ID = "hikari_livetv"
}
