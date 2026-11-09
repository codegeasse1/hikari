package com.hikari.app.iptv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Live TV engine models. Everything persists through [LiveTvStore] as org.json
 * (no new database dependency — the EPG day buckets are plain files too).
 *
 * Field names follow the NuvioPlusIPTV models this engine is ported from, so a
 * backup exported there can be understood here where the shapes overlap.
 */

// ---------------------------------------------------------------------------
// Sources
// ---------------------------------------------------------------------------

/** A user-added playlist: a plain M3U link/file, or an Xtream Codes login. */
data class LiveTvSource(
    val id: String,
    val name: String,
    val kind: String = KIND_M3U,
    val url: String = "",
    val userAgent: String = "",
    val useEmbeddedEpg: Boolean = true,
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val enabled: Boolean = true,
    /** Xtream live stream format: "auto", "ts" or "m3u8". */
    val streamFormat: String = "auto",
    val importLive: Boolean = true,
    val importVod: Boolean = true,
    /** Where channel logos come from: "playlist" or "epg". */
    val logoSource: String = "playlist",
    val serverTimezone: String = "",
    val accountStatus: String = "",
    val accountExpiresMs: Long = 0L,
    val accountConnections: String = "",
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val channelCount: Int = 0,
    val catchupNoHlsSince: Long = 0L,
) {
    val isXtream: Boolean get() = kind == KIND_XTREAM
    val liveEnabled: Boolean get() = enabled && (!isXtream || importLive)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind).put("url", url)
        .put("userAgent", userAgent).put("useEmbeddedEpg", useEmbeddedEpg)
        .put("xtreamServer", xtreamServer).put("xtreamUsername", xtreamUsername)
        .put("xtreamPassword", xtreamPassword).put("enabled", enabled)
        .put("streamFormat", streamFormat).put("importLive", importLive)
        .put("importVod", importVod).put("logoSource", logoSource)
        .put("serverTimezone", serverTimezone).put("accountStatus", accountStatus)
        .put("accountExpiresMs", accountExpiresMs).put("accountConnections", accountConnections)
        .put("lastUpdatedMs", lastUpdatedMs).put("lastError", lastError.orEmpty())
        .put("channelCount", channelCount).put("catchupNoHlsSince", catchupNoHlsSince)

    companion object {
        const val KIND_M3U = "m3u"
        const val KIND_XTREAM = "xtream"

        fun fromJson(o: JSONObject): LiveTvSource? = runCatching {
            LiveTvSource(
                id = o.optString("id").ifBlank { return null },
                name = o.optString("name"),
                kind = o.optString("kind").ifBlank { KIND_M3U },
                url = o.optString("url"),
                userAgent = o.optString("userAgent"),
                useEmbeddedEpg = o.optBoolean("useEmbeddedEpg", true),
                xtreamServer = o.optString("xtreamServer"),
                xtreamUsername = o.optString("xtreamUsername"),
                xtreamPassword = o.optString("xtreamPassword"),
                enabled = o.optBoolean("enabled", true),
                streamFormat = o.optString("streamFormat").ifBlank { "auto" },
                importLive = o.optBoolean("importLive", true),
                importVod = o.optBoolean("importVod", true),
                logoSource = o.optString("logoSource").ifBlank { "playlist" },
                serverTimezone = o.optString("serverTimezone"),
                accountStatus = o.optString("accountStatus"),
                accountExpiresMs = o.optLong("accountExpiresMs"),
                accountConnections = o.optString("accountConnections"),
                lastUpdatedMs = o.optLong("lastUpdatedMs"),
                lastError = o.optString("lastError").ifBlank { null },
                channelCount = o.optInt("channelCount"),
                catchupNoHlsSince = o.optLong("catchupNoHlsSince"),
            )
        }.getOrNull()
    }
}

/** A user-added XMLTV guide (plain or .gz). */
data class LiveTvEpgSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val programCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("enabled", enabled)
        .put("lastUpdatedMs", lastUpdatedMs).put("lastError", lastError.orEmpty())
        .put("programCount", programCount)

    companion object {
        fun fromJson(o: JSONObject): LiveTvEpgSource? = runCatching {
            LiveTvEpgSource(
                id = o.optString("id").ifBlank { return null },
                name = o.optString("name"),
                url = o.optString("url"),
                enabled = o.optBoolean("enabled", true),
                lastUpdatedMs = o.optLong("lastUpdatedMs"),
                lastError = o.optString("lastError").ifBlank { null },
                programCount = o.optInt("programCount"),
            )
        }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Channels and guide
// ---------------------------------------------------------------------------

/** Catch-up details parsed from a playlist's EXTINF for one channel. */
data class CatchupInfo(
    val type: String,
    val source: String? = null,
    val days: Int = 1,
    val correctionHours: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("source", source.orEmpty())
        .put("days", days).put("correctionHours", correctionHours)

    companion object {
        fun fromJson(o: JSONObject): CatchupInfo = CatchupInfo(
            type = o.optString("type"),
            source = o.optString("source").ifBlank { null },
            days = o.optInt("days", 1).coerceAtLeast(1),
            correctionHours = o.optDouble("correctionHours"),
        )
    }
}

/** One playable channel, unified across M3U and Xtream sources. */
data class LiveChannel(
    /** Stable key for favorites/hidden/recent: "<sourceId>|<url>". */
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val groupId: String,
    val group: String,
    val number: Int = 0,
    val url: String,
    /** Playlist-declared language / country (for the group-by switch). */
    val language: String = "",
    val country: String = "",
    val headers: Map<String, String> = emptyMap(),
    val catchup: CatchupInfo? = null,
    val drmScheme: String? = null,
    val drmLicense: String? = null,
    /** Xtream stream id (for VOD-adjacent features); blank for M3U. */
    val streamId: String = "",
)

/** One show in the guide (an XMLTV programme). */
data class EpgProgram(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val icon: String? = null,
    val year: Int? = null,
    val people: List<String> = emptyList(),
) {
    fun isLive(nowMs: Long): Boolean = nowMs in startMs until stopMs
    fun progress(nowMs: Long): Float {
        val span = (stopMs - startMs).coerceAtLeast(1L)
        return ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("startMs", startMs).put("stopMs", stopMs).put("title", title)
        .put("description", description.orEmpty()).put("category", category.orEmpty())
        .put("episode", episode.orEmpty()).put("icon", icon.orEmpty())
        .put("year", year ?: 0)
        .put("people", JSONArray().also { a -> people.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): EpgProgram = EpgProgram(
            startMs = o.optLong("startMs"),
            stopMs = o.optLong("stopMs"),
            title = o.optString("title"),
            description = o.optString("description").ifBlank { null },
            category = o.optString("category").ifBlank { null },
            episode = o.optString("episode").ifBlank { null },
            icon = o.optString("icon").ifBlank { null },
            year = o.optInt("year").takeIf { it > 0 },
            people = o.optJSONArray("people")?.let { a ->
                List(a.length()) { i -> a.optString(i) }.filter { it.isNotBlank() }
            }.orEmpty(),
        )
    }
}

// ---------------------------------------------------------------------------
// Settings and per-device state
// ---------------------------------------------------------------------------

/** Every Live TV setting (mirrors the settings screens). */
data class LiveTvSettings(
    // Guides
    val daysAhead: Int = 3,
    val guideHoursBack: Int = 24,
    val playlistRefreshHours: Int = 24,
    val epgRefreshHours: Int = 12,
    val epgOffsetMinutes: Int = 0,
    val use24HourClock: Boolean = false,
    // Playback
    val streamFormatDefault: String = "auto",
    val backupChannel: Boolean = true,
    val continueWatching: Boolean = true,
    val rememberAudioTrack: Boolean = true,
    val preferHlsCatchup: Boolean = true,
    val catchupShiftMinutes: Int = 0,
    val autoReconnect: Boolean = true,
    // Channel display (this device only)
    val showLogos: Boolean = true,
    val showNames: Boolean = true,
    val showNumbers: Boolean = true,
    val showNowLine: Boolean = true,
    val showProgramTimes: Boolean = false,
    val showEpisodeNumbers: Boolean = true,
    // On Demand
    val onDemandInStreams: Boolean = true,
    val vodMergeDuplicates: Boolean = true,
    val vodDefaultSort: String = "DEFAULT",
    // Recordings
    val recordPreMinutes: Int = 2,
    val recordPostMinutes: Int = 5,
    val recordSeriesKeep: Int = 0,
    val recordStorage: String = "",
    val recordSpaceRule: String = "STOP",
    // Tabs
    val showLiveTvTab: Boolean = true,
    // Sports
    val calendarSports: Boolean = true,
    // Misc
    val nameRemovals: String = "",
    val sequentialNumbers: Boolean = false,
    val reminderLeadMinutes: Int = 10,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("daysAhead", daysAhead).put("guideHoursBack", guideHoursBack)
        .put("playlistRefreshHours", playlistRefreshHours).put("epgRefreshHours", epgRefreshHours)
        .put("epgOffsetMinutes", epgOffsetMinutes).put("use24HourClock", use24HourClock)
        .put("streamFormatDefault", streamFormatDefault).put("backupChannel", backupChannel)
        .put("continueWatching", continueWatching).put("rememberAudioTrack", rememberAudioTrack)
        .put("preferHlsCatchup", preferHlsCatchup).put("catchupShiftMinutes", catchupShiftMinutes)
        .put("autoReconnect", autoReconnect)
        .put("showLogos", showLogos).put("showNames", showNames).put("showNumbers", showNumbers)
        .put("showNowLine", showNowLine).put("showProgramTimes", showProgramTimes)
        .put("showEpisodeNumbers", showEpisodeNumbers)
        .put("onDemandInStreams", onDemandInStreams).put("vodMergeDuplicates", vodMergeDuplicates)
        .put("vodDefaultSort", vodDefaultSort)
        .put("recordPreMinutes", recordPreMinutes).put("recordPostMinutes", recordPostMinutes)
        .put("recordSeriesKeep", recordSeriesKeep).put("recordStorage", recordStorage)
        .put("recordSpaceRule", recordSpaceRule)
        .put("showLiveTvTab", showLiveTvTab).put("calendarSports", calendarSports)
        .put("nameRemovals", nameRemovals).put("sequentialNumbers", sequentialNumbers)
        .put("reminderLeadMinutes", reminderLeadMinutes)

    companion object {
        fun fromJson(o: JSONObject): LiveTvSettings = LiveTvSettings(
            daysAhead = o.optInt("daysAhead", 3).coerceIn(1, 14),
            guideHoursBack = o.optInt("guideHoursBack", 24).coerceIn(0, 72),
            playlistRefreshHours = o.optInt("playlistRefreshHours", 24).coerceIn(1, 168),
            epgRefreshHours = o.optInt("epgRefreshHours", 12).coerceIn(1, 168),
            epgOffsetMinutes = o.optInt("epgOffsetMinutes"),
            use24HourClock = o.optBoolean("use24HourClock"),
            streamFormatDefault = o.optString("streamFormatDefault").ifBlank { "auto" },
            backupChannel = o.optBoolean("backupChannel", true),
            continueWatching = o.optBoolean("continueWatching", true),
            rememberAudioTrack = o.optBoolean("rememberAudioTrack", true),
            preferHlsCatchup = o.optBoolean("preferHlsCatchup", true),
            catchupShiftMinutes = o.optInt("catchupShiftMinutes"),
            autoReconnect = o.optBoolean("autoReconnect", true),
            showLogos = o.optBoolean("showLogos", true),
            showNames = o.optBoolean("showNames", true),
            showNumbers = o.optBoolean("showNumbers", true),
            showNowLine = o.optBoolean("showNowLine", true),
            showProgramTimes = o.optBoolean("showProgramTimes"),
            showEpisodeNumbers = o.optBoolean("showEpisodeNumbers", true),
            onDemandInStreams = o.optBoolean("onDemandInStreams", true),
            vodMergeDuplicates = o.optBoolean("vodMergeDuplicates", true),
            vodDefaultSort = o.optString("vodDefaultSort").ifBlank { "DEFAULT" },
            recordPreMinutes = o.optInt("recordPreMinutes", 2).coerceIn(0, 60),
            recordPostMinutes = o.optInt("recordPostMinutes", 5).coerceIn(0, 120),
            recordSeriesKeep = o.optInt("recordSeriesKeep").coerceAtLeast(0),
            recordStorage = o.optString("recordStorage"),
            recordSpaceRule = o.optString("recordSpaceRule").ifBlank { "STOP" },
            showLiveTvTab = o.optBoolean("showLiveTvTab", true),
            calendarSports = o.optBoolean("calendarSports", true),
            nameRemovals = o.optString("nameRemovals"),
            sequentialNumbers = o.optBoolean("sequentialNumbers"),
            reminderLeadMinutes = o.optInt("reminderLeadMinutes", 10).coerceIn(0, 120),
        )
    }
}

/** "Remind me" on an upcoming show. */
data class LiveReminder(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("channelKey", channelKey).put("channelName", channelName)
        .put("title", title).put("startMs", startMs)

    companion object {
        fun fromJson(o: JSONObject): LiveReminder? = runCatching {
            LiveReminder(
                id = o.optString("id").ifBlank { return null },
                channelKey = o.optString("channelKey"),
                channelName = o.optString("channelName"),
                title = o.optString("title"),
                startMs = o.optLong("startMs"),
            )
        }.getOrNull()
    }
}

/** A user-made group of channels. */
data class LiveCustomGroup(
    val id: String,
    val name: String,
    val channelKeys: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("channelKeys", JSONArray().also { a -> channelKeys.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): LiveCustomGroup? = runCatching {
            LiveCustomGroup(
                id = o.optString("id").ifBlank { return null },
                name = o.optString("name"),
                channelKeys = o.optJSONArray("channelKeys")?.let { a ->
                    List(a.length()) { i -> a.optString(i) }
                }.orEmpty(),
            )
        }.getOrNull()
    }
}

/** Per-device Live TV state: favorites, hidden rows, pins, reminders, teams. */
data class LiveUserState(
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val lastChannelKey: String? = null,
    val lastGroupId: String? = null,
    val channelNames: Map<String, String> = emptyMap(),
    val channelNumbers: Map<String, Int> = emptyMap(),
    val groupNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val customGroups: List<LiveCustomGroup> = emptyList(),
    val channelOrder: Map<String, List<String>> = emptyMap(),
    val lockedGroups: Set<String> = emptySet(),
    val parentalPin: String = "",
    val lockAdultContent: Boolean = true,
    val reminders: List<LiveReminder> = emptyList(),
    val channelAudio: Map<String, String> = emptyMap(),
    val channelQuality: Map<String, String> = emptyMap(),
    val vodHiddenCategories: Set<String> = emptySet(),
    val vodGroupOrder: List<String> = emptyList(),
    val followedTeams: Set<String> = emptySet(),
    val epgOverrides: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("favorites", jsonStrList(favorites))
        .put("recent", jsonStrList(recent))
        .put("hiddenChannels", jsonStrList(hiddenChannels.toList()))
        .put("hiddenGroups", jsonStrList(hiddenGroups.toList()))
        .put("lastChannelKey", lastChannelKey.orEmpty())
        .put("lastGroupId", lastGroupId.orEmpty())
        .put("channelNames", jsonStrMap(channelNames))
        .put("channelNumbers", JSONObject().also { o -> channelNumbers.forEach { (k, v) -> o.put(k, v) } })
        .put("groupNames", jsonStrMap(groupNames))
        .put("groupOrder", jsonStrList(groupOrder))
        .put("customGroups", JSONArray().also { a -> customGroups.forEach { a.put(it.toJson()) } })
        .put("channelOrder", JSONObject().also { o -> channelOrder.forEach { (k, v) -> o.put(k, jsonStrList(v)) } })
        .put("lockedGroups", jsonStrList(lockedGroups.toList()))
        .put("parentalPin", parentalPin)
        .put("lockAdultContent", lockAdultContent)
        .put("reminders", JSONArray().also { a -> reminders.forEach { a.put(it.toJson()) } })
        .put("channelAudio", jsonStrMap(channelAudio))
        .put("channelQuality", jsonStrMap(channelQuality))
        .put("vodHiddenCategories", jsonStrList(vodHiddenCategories.toList()))
        .put("vodGroupOrder", jsonStrList(vodGroupOrder))
        .put("followedTeams", jsonStrList(followedTeams.toList()))
        .put("epgOverrides", jsonStrMap(epgOverrides))

    companion object {
        fun fromJson(o: JSONObject): LiveUserState = LiveUserState(
            favorites = o.strList("favorites"),
            recent = o.strList("recent"),
            hiddenChannels = o.strList("hiddenChannels").toSet(),
            hiddenGroups = o.strList("hiddenGroups").toSet(),
            lastChannelKey = o.optString("lastChannelKey").ifBlank { null },
            lastGroupId = o.optString("lastGroupId").ifBlank { null },
            channelNames = o.strMap("channelNames"),
            channelNumbers = o.optJSONObject("channelNumbers")?.let { jo ->
                jo.keys().asSequence().associateWith { jo.optInt(it) }
            }.orEmpty(),
            groupNames = o.strMap("groupNames"),
            groupOrder = o.strList("groupOrder"),
            customGroups = o.optJSONArray("customGroups")?.let { a ->
                List(a.length()) { i -> a.optJSONObject(i)?.let { LiveCustomGroup.fromJson(it) } }.filterNotNull()
            }.orEmpty(),
            channelOrder = o.optJSONObject("channelOrder")?.let { jo ->
                jo.keys().asSequence().associateWith { k ->
                    jo.optJSONArray(k)?.let { a -> List(a.length()) { i -> a.optString(i) } }.orEmpty()
                }
            }.orEmpty(),
            lockedGroups = o.strList("lockedGroups").toSet(),
            parentalPin = o.optString("parentalPin"),
            lockAdultContent = o.optBoolean("lockAdultContent", true),
            reminders = o.optJSONArray("reminders")?.let { a ->
                List(a.length()) { i -> a.optJSONObject(i)?.let { LiveReminder.fromJson(it) } }.filterNotNull()
            }.orEmpty(),
            channelAudio = o.strMap("channelAudio"),
            channelQuality = o.strMap("channelQuality"),
            vodHiddenCategories = o.strList("vodHiddenCategories").toSet(),
            vodGroupOrder = o.strList("vodGroupOrder"),
            followedTeams = o.strList("followedTeams").toSet(),
            epgOverrides = o.strMap("epgOverrides"),
        )
    }
}

/** A row in the group panel. */
data class LiveChannelGroup(
    val id: String,
    val title: String,
    val count: Int,
    val special: Boolean = false,
    val sourceId: String? = null,
    val locked: Boolean = false,
) {
    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val RECENT = "__recent__"
        const val CUSTOM_PREFIX = "custom:"
    }
}

// ---------------------------------------------------------------------------
// On Demand (Xtream movies & series)
// ---------------------------------------------------------------------------

/** One Xtream movie or series. */
data class VodItem(
    /** "<sourceId>|movie|<id>" or "<sourceId>|series|<id>". */
    val uid: String,
    val sourceId: String,
    val sourceName: String,
    val isSeries: Boolean,
    val id: String,
    val name: String,
    val icon: String? = null,
    val categoryId: String = "",
    val categoryName: String = "",
    val containerExtension: String? = null,
    val tmdbId: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val addedSec: Long = 0L,
    val genre: String? = null,
) {
    val displayTitle: String get() = name
}

/** One series episode (from get_series_info). */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val streamId: String,
    val containerExtension: String? = null,
    val info: String? = null,
)

// ---------------------------------------------------------------------------
// Recordings
// ---------------------------------------------------------------------------

enum class RecordingStatus { RECORDING, DONE, FAILED }

/** One recording (mirrors its folder's info.json). */
data class LiveTvRecording(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val channelLogo: String? = null,
    val title: String,
    val episode: String? = null,
    val description: String? = null,
    val programStartMs: Long = 0L,
    val programStopMs: Long = 0L,
    val startedAtMs: Long,
    val stopAtMs: Long,
    val endedAtMs: Long? = null,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    val sizeBytes: Long = 0L,
    val problem: String? = null,
    val dir: String = "",
    val seriesId: String? = null,
    val fromCatchup: Boolean = false,
    val fileName: String = "recording.ts",
) {
    val isRecording: Boolean get() = status == RecordingStatus.RECORDING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("channelKey", channelKey).put("channelName", channelName)
        .put("channelLogo", channelLogo.orEmpty()).put("title", title)
        .put("episode", episode.orEmpty()).put("description", description.orEmpty())
        .put("programStartMs", programStartMs).put("programStopMs", programStopMs)
        .put("startedAtMs", startedAtMs).put("stopAtMs", stopAtMs)
        .put("endedAtMs", endedAtMs ?: 0L).put("status", status.name)
        .put("sizeBytes", sizeBytes).put("problem", problem.orEmpty())
        .put("seriesId", seriesId.orEmpty()).put("fromCatchup", fromCatchup)
        .put("fileName", fileName)

    companion object {
        fun fromJson(o: JSONObject, dir: String): LiveTvRecording? = runCatching {
            LiveTvRecording(
                id = o.optString("id").ifBlank { return null },
                channelKey = o.optString("channelKey"),
                channelName = o.optString("channelName"),
                channelLogo = o.optString("channelLogo").ifBlank { null },
                title = o.optString("title"),
                episode = o.optString("episode").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                programStartMs = o.optLong("programStartMs"),
                programStopMs = o.optLong("programStopMs"),
                startedAtMs = o.optLong("startedAtMs"),
                stopAtMs = o.optLong("stopAtMs"),
                endedAtMs = o.optLong("endedAtMs").takeIf { it > 0 },
                status = runCatching { RecordingStatus.valueOf(o.optString("status")) }.getOrDefault(RecordingStatus.DONE),
                sizeBytes = o.optLong("sizeBytes"),
                problem = o.optString("problem").ifBlank { null },
                dir = dir,
                seriesId = o.optString("seriesId").ifBlank { null },
                fromCatchup = o.optBoolean("fromCatchup"),
                fileName = o.optString("fileName").ifBlank { "recording.ts" },
            )
        }.getOrNull()
    }
}

/** A scheduled recording (guide show, manual, or series rule fire). */
data class RecordingSchedule(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val seriesId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("channelKey", channelKey).put("channelName", channelName)
        .put("title", title).put("startMs", startMs).put("stopMs", stopMs)
        .put("seriesId", seriesId.orEmpty())

    companion object {
        fun fromJson(o: JSONObject): RecordingSchedule? = runCatching {
            RecordingSchedule(
                id = o.optString("id").ifBlank { return null },
                channelKey = o.optString("channelKey"),
                channelName = o.optString("channelName"),
                title = o.optString("title"),
                startMs = o.optLong("startMs"),
                stopMs = o.optLong("stopMs"),
                seriesId = o.optString("seriesId").ifBlank { null },
            )
        }.getOrNull()
    }
}

/** A series recording rule ("record every new airing of X"). */
data class SeriesRule(
    val id: String,
    val channelKey: String,
    val title: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("channelKey", channelKey).put("title", title)

    companion object {
        fun fromJson(o: JSONObject): SeriesRule? = runCatching {
            SeriesRule(
                id = o.optString("id").ifBlank { return null },
                channelKey = o.optString("channelKey"),
                title = o.optString("title"),
            )
        }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Sports
// ---------------------------------------------------------------------------

/** One league the Sports hub knows. */
data class SportsLeague(
    val id: String,
    val name: String,
    val short: String,
    val espnPath: String?,
    val guideWords: List<String> = emptyList(),
    val minutes: Int = 120,
    val color: Long = 0xFF444444,
)

/** One team (from ESPN). */
data class SportsTeam(
    val id: String,
    val leagueId: String,
    val name: String,
    val abbr: String,
    val logo: String? = null,
    val color: String? = null,
)

/** One game (from ESPN scoreboard or the guide). */
data class SportsEvent(
    val id: String,
    val leagueId: String,
    val leagueName: String,
    val title: String,
    val startMs: Long,
    val status: String = "",
    val clock: String = "",
    val homeName: String = "",
    val awayName: String = "",
    val homeAbbr: String = "",
    val awayAbbr: String = "",
    val homeScore: String = "",
    val awayScore: String = "",
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val network: String = "",
    val live: Boolean = false,
    val final: Boolean = false,
)

/** A channel showing a game. */
data class ChannelMatch(
    val channelKey: String,
    val channelName: String,
    val how: String,
    val program: EpgProgram? = null,
)

// ---------------------------------------------------------------------------
// Small JSON helpers
// ---------------------------------------------------------------------------

internal fun jsonStrList(list: List<String>): JSONArray =
    JSONArray().also { a -> list.forEach { a.put(it) } }

internal fun jsonStrMap(map: Map<String, String>): JSONObject =
    JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, v) } }

internal fun JSONObject.strList(key: String): List<String> =
    optJSONArray(key)?.let { a -> List(a.length()) { i -> a.optString(i) } }.orEmpty()

internal fun JSONObject.strMap(key: String): Map<String, String> =
    optJSONObject(key)?.let { jo -> jo.keys().asSequence().associateWith { jo.optString(it) } }.orEmpty()

/**
 * Channel name editor, TiviMate style: removes listed prefixes/suffixes ("USA",
 * "FHD", "24/7") from the ends of channel names. Ported from NuvioPlusIPTV.
 */
object ChannelNameEditor {
    fun terms(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }

    private class Compiled(val prefix: Regex, val suffix: Regex, val suffixCheck: Regex)

    @Volatile private var compiledFor: List<String> = emptyList()
    @Volatile private var compiled: List<Compiled> = emptyList()
    @Volatile private var lowerFor: List<String> = emptyList()
    @Volatile private var lowerTerms: List<String> = emptyList()

    private fun lowered(terms: List<String>): List<String> {
        if (terms == lowerFor) return lowerTerms
        val l = terms.map { it.lowercase() }
        lowerTerms = l
        lowerFor = terms
        return l
    }

    private fun atEdge(lower: String, terms: List<String>): Boolean {
        val start = lower.trimStart { !it.isLetterOrDigit() }
        val end = lower.trimEnd { !it.isLetterOrDigit() }
        val rawStart = lower.trimStart()
        val rawEnd = lower.trimEnd()
        return terms.any { t ->
            start.startsWith(t) || end.endsWith(t) || rawStart.startsWith(t) || rawEnd.endsWith(t)
        }
    }

    private fun compile(terms: List<String>): List<Compiled> {
        if (terms == compiledFor) return compiled
        val list = terms.map { t ->
            val q = Regex.escape(t)
            val opts = setOf(RegexOption.IGNORE_CASE)
            val ascii = t.all { it.code < 128 && it.isLetterOrDigit() }
            val before = if (ascii) """(?<![\p{L}\p{N}])""" else ""
            val after = if (ascii) """(?![\p{L}\p{N}])""" else ""
            val prefix = if (t.all { it.isLetter() }) {
                Regex("""^[^\p{L}\p{N}]*$q\s*[^\p{L}\p{N}\s(\[{<][^\p{L}\p{N}(\[{<]*""", opts)
            } else {
                Regex("""^[^\p{L}\p{N}]*$q$after[^\p{L}\p{N}(\[{<]*""", opts)
            }
            Compiled(
                prefix,
                Regex("""[^\p{L}\p{N}+&)\]}]*$before$q[^\p{L}\p{N}]*$""", opts),
                Regex("""$before$q[^\p{L}\p{N}]*$""", opts)
            )
        }
        compiled = list
        compiledFor = terms
        return list
    }

    fun spacePrefixes(names: Collection<String>, terms: List<String>): Set<String> {
        val words = terms.filter { t -> t.all { it.isLetter() } }.map { it.lowercase() }
        if (words.isEmpty()) return emptySet()
        val counts = HashMap<String, Int>()
        for (n in names) {
            val l = n.trimStart { !it.isLetterOrDigit() }.lowercase()
            for (w in words) if (l.startsWith("$w ") && l.length > w.length + 1) counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.filterValues { it >= 5 }.keys
    }

    fun clean(name: String, terms: List<String>, spacePrefixes: Set<String> = emptySet()): String {
        if (terms.isEmpty()) return name
        var start = name.trim()
        if (spacePrefixes.isNotEmpty()) {
            val body = start.trimStart { !it.isLetterOrDigit() }
            val l = body.lowercase()
            spacePrefixes.firstOrNull { l.startsWith("$it ") }?.let { w ->
                val rest = body.substring(w.length).trim()
                if (rest.isNotBlank()) start = rest
            }
        }
        val lowerTerms = lowered(terms)
        if (!atEdge(start.lowercase(), lowerTerms)) return start
        val patterns = compile(terms)
        var out = start
        var changed = true
        var guard = 0
        while (changed && guard++ < 6) {
            if (guard > 1 && !atEdge(out.lowercase(), lowerTerms)) break
            changed = false
            for (c in patterns) {
                val p = c.prefix.replace(out, "").trim()
                if (p != out && p.isNotBlank()) { out = p; changed = true }
                if (c.suffixCheck.containsMatchIn(out)) {
                    val sfx = c.suffix.replace(out, "").trim()
                    if (sfx != out && sfx.isNotBlank()) { out = sfx; changed = true }
                }
            }
        }
        return out
    }

    const val COMMON = "USA, US, UK, CA, AU, 24/7, FHD, HD, SD, UHD, 4K, HEVC, RAW, VIP"
}
