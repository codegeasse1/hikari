package com.hikari.app.download

import com.hikari.app.data.SubtitleSource
import org.json.JSONArray
import org.json.JSONObject

/** Where a finished download ends up: kept inside the app for offline playback,
 *  or copied out into the phone's Downloads folder. */
enum class DownloadKind { OFFLINE, EXPORT }

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/**
 * One downloaded (or downloading) video. Persisted as JSON in its own DataStore
 * file (see [DownloadStore]), so the queue survives an app restart.
 *
 * [progress] prefers the DURATION ratio (a segment-downloaded count over the
 * playlist's total duration) because byte totals are usually unknown for HLS
 * playlists — falling back to bytes only for single-file downloads where the
 * server's Content-Length is known.
 */
data class DownloadTask(
    val id: String,
    val title: String,
    val episodeLabel: String = "",
    val poster: String? = null,
    val providerId: String = "",
    val mediaId: String = "",
    val episodeId: String = "",
    val sourceName: String = "",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isM3u8: Boolean = false,
    val subtitles: List<SubtitleSource> = emptyList(),
    /** Preferred HLS quality: the exact variant height to download (0 = the
     *  highest available), plus that variant's declared bandwidth as a
     *  tie-breaker for playlists that don't publish a RESOLUTION. */
    val preferredHeight: Int = 0,
    val preferredBandwidth: Long = 0L,
    val kind: DownloadKind = DownloadKind.OFFLINE,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = -1L,
    val durationMs: Long = 0L,
    val doneDurationMs: Long = 0L,
    val error: String? = null,
    /** Directory of the on-disk copy used for in-app offline playback. */
    val localPath: String? = null,
    /** Where the EXPORT copy landed (a content:// URI on API 29+, else a path). */
    val savedUri: String? = null,
    val createdAt: Long = 0L,
    /** True once the user asked to resume, so the engine keeps (rather than
     *  wipes) whatever segments are already on disk. */
    val resumePartial: Boolean = false,
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.DONE -> 1f
            durationMs > 0L && doneDurationMs > 0L ->
                (doneDurationMs.toFloat() / durationMs).coerceIn(0f, 1f)
            bytesTotal > 0L -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            else -> 0f
        }

    /** True when the task can be played inside the app right now. */
    val playableOffline: Boolean
        get() = status == DownloadStatus.DONE && !localPath.isNullOrBlank()

    /** File name stem for an exported copy (no extension). */
    fun fileBaseName(): String {
        val stem = buildString {
            append(title)
            if (episodeLabel.isNotBlank()) append(" - ").append(episodeLabel)
        }
        return sanitizeFile(stem).ifBlank { "hikari-video" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("episodeLabel", episodeLabel)
        put("poster", poster ?: "")
        put("providerId", providerId)
        put("mediaId", mediaId)
        put("episodeId", episodeId)
        put("sourceName", sourceName)
        put("url", url)
        put("headers", JSONObject(headers))
        put("isM3u8", isM3u8)
        put("preferredHeight", preferredHeight)
        put("preferredBandwidth", preferredBandwidth)
        put(
            "subtitles",
            JSONArray().apply {
                subtitles.forEach {
                    put(JSONObject().put("lang", it.lang).put("url", it.url))
                }
            }
        )
        put("kind", kind.name)
        put("status", status.name)
        put("bytesDone", bytesDone)
        put("bytesTotal", bytesTotal)
        put("durationMs", durationMs)
        put("doneDurationMs", doneDurationMs)
        put("error", error ?: "")
        put("localPath", localPath ?: "")
        put("savedUri", savedUri ?: "")
        put("createdAt", createdAt)
        put("resumePartial", resumePartial)
    }

    companion object {
        fun idFor(
            providerId: String,
            mediaId: String,
            episodeId: String,
            kind: DownloadKind,
        ): String = kind.name.lowercase() + "_" + fnv1a("$providerId|$mediaId|$episodeId")

        fun fromJson(o: JSONObject): DownloadTask {
            val headersObj = o.optJSONObject("headers") ?: JSONObject()
            val headers = HashMap<String, String>()
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = headersObj.optString(k)
            }
            val subsArr = o.optJSONArray("subtitles") ?: JSONArray()
            val subs = (0 until subsArr.length()).mapNotNull { i ->
                val s = subsArr.optJSONObject(i) ?: return@mapNotNull null
                val url = s.optString("url")
                if (url.isBlank()) null else SubtitleSource(s.optString("lang"), url)
            }
            return DownloadTask(
                id = o.optString("id"),
                title = o.optString("title"),
                episodeLabel = o.optString("episodeLabel"),
                poster = o.optString("poster").ifBlank { null },
                providerId = o.optString("providerId"),
                mediaId = o.optString("mediaId"),
                episodeId = o.optString("episodeId"),
                sourceName = o.optString("sourceName"),
                url = o.optString("url"),
                headers = headers,
                isM3u8 = o.optBoolean("isM3u8"),
                subtitles = subs,
                preferredHeight = o.optInt("preferredHeight"),
                preferredBandwidth = o.optLong("preferredBandwidth"),
                kind = runCatching { DownloadKind.valueOf(o.optString("kind")) }
                    .getOrDefault(DownloadKind.OFFLINE),
                status = runCatching { DownloadStatus.valueOf(o.optString("status")) }
                    .getOrDefault(DownloadStatus.PAUSED),
                bytesDone = o.optLong("bytesDone"),
                bytesTotal = o.optLong("bytesTotal", -1L),
                durationMs = o.optLong("durationMs"),
                doneDurationMs = o.optLong("doneDurationMs"),
                error = o.optString("error").ifBlank { null },
                localPath = o.optString("localPath").ifBlank { null },
                savedUri = o.optString("savedUri").ifBlank { null },
                createdAt = o.optLong("createdAt"),
                resumePartial = o.optBoolean("resumePartial"),
            )
        }

        private fun fnv1a(s: String): String {
            var h = 0x811c9dc5.toInt()
            for (b in s.encodeToByteArray()) {
                h = (h xor (b.toInt() and 0xFF))
                h *= 0x01000193
            }
            return (h.toUInt()).toString(16)
        }
    }
}

/** Strips path-hostile characters out of a user-facing title. */
fun sanitizeFile(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
