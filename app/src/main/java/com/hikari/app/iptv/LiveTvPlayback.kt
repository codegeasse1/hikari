package com.hikari.app.iptv

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays Live TV outside the provider contract: the guide, On Demand and the
 * recordings list hand the player a ready server list, the same JSON shape
 * the detail screen builds (see `playerPayload` in DetailScreen).
 */
object LiveTvPlayback {

    fun payloadJson(streams: List<StreamSource>): String = JSONArray().apply {
        streams.forEach { s ->
            put(
                JSONObject()
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("headers", JSONObject(s.headers))
                    .put("isM3u8", s.isM3u8)
                    .put("isMpd", s.isMpd)
                    .put("isTorrent", s.isTorrent)
                    .put("provider", s.provider)
                    .put("providerId", s.providerId)
                    .put("providerName", s.providerName)
                    .put("details", s.details)
                    .put("infoHash", s.infoHash ?: "")
                    .put("fileIdx", s.fileIdx ?: -1)
                    .put("torrentFileHint", s.torrentFileHint ?: "")
                    .put("torrentFileSize", s.torrentFileSize)
                    .put("trackers", JSONArray().apply { s.trackers.forEach { put(it) } })
                    .put(
                        "subtitles",
                        JSONArray().apply {
                            s.subtitles.forEach {
                                put(JSONObject().put("lang", it.lang).put("url", it.url))
                            }
                        }
                    )
                    .put(
                        "drm",
                        s.drm?.let { d ->
                            JSONObject()
                                .put("kid", d.kid ?: "")
                                .put("key", d.key ?: "")
                                .put("uuid", d.uuid ?: "")
                                .put("kty", d.kty ?: "")
                                .put("licenseUrl", d.licenseUrl ?: "")
                                .put("keyRequestParameters", JSONObject(d.keyRequestParameters))
                        } ?: JSONObject.NULL
                    )
            )
        }
    }.toString()

    fun play(
        context: Context,
        title: String,
        streams: List<StreamSource>,
        providerId: String,
        mediaId: String,
        posterUrl: String? = null,
    ): Boolean {
        if (streams.isEmpty()) {
            Toast.makeText(context, "Nothing playable here yet", Toast.LENGTH_LONG).show()
            return false
        }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("title", title)
            putExtra("sources", payloadJson(streams))
            putExtra("histTitle", title)
            putExtra("histProviderId", providerId)
            putExtra("histMediaId", mediaId)
            putExtra("histType", "MOVIE")
            putExtra("histPoster", posterUrl.orEmpty())
            putExtra("askServer", streams.size > 1)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "Could not open the player", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun channelStreams(channel: LiveChannel, providerName: String): List<StreamSource> {
        val out = ArrayList<StreamSource>()
        val url = LiveTvEngine.playUrl(channel)
        out += StreamSource(
            name = channel.name,
            url = url,
            headers = liveHeaders(channel),
            isM3u8 = url.substringBefore('?').lowercase().contains(".m3u8"),
            isMpd = url.substringBefore('?').lowercase().contains(".mpd"),
            provider = ProviderType.IPTV.groupLabel,
            providerId = LiveTvEngine.bridgeId(channel.sourceId),
            providerName = providerName,
        )
        if (LiveTvEngine.store.settings().backupChannel) {
            LiveTvEngine.backupsOf(channel).take(4).forEach { b ->
                out += StreamSource(
                    name = b.name + " · " + b.group,
                    url = b.url,
                    headers = liveHeaders(b),
                    isM3u8 = b.url.substringBefore('?').lowercase().contains(".m3u8"),
                    provider = "IPTV backup",
                    providerId = LiveTvEngine.bridgeId(b.sourceId),
                    providerName = providerName,
                )
            }
        }
        return out
    }

    private fun liveHeaders(ch: LiveChannel): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ch.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) out[k] = v }
        if (out.keys.none { it.equals("User-Agent", true) }) {
            out["User-Agent"] = XtreamApi.DEFAULT_UA
        }
        return out
    }

    /** Plays a live channel (primary + backup servers), marking it recent. */
    suspend fun playChannel(context: Context, channel: LiveChannel): Boolean =
        withContext(Dispatchers.IO) {
            val src = LiveTvEngine.sourceOf(channel.sourceId)
            val ok = play(
                context,
                channel.name,
                channelStreams(channel, src?.name.orEmpty()),
                LiveTvEngine.bridgeId(channel.sourceId),
                "ch:" + channel.key,
                channel.logo,
            )
            if (ok) LiveTvEngine.markRecent(channel.key)
            ok
        }

    /** Plays one catch-up replay (primary form + alternates as servers). */
    suspend fun playReplay(context: Context, channel: LiveChannel, program: EpgProgram): Boolean =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val primary = runCatching { LiveTvEngine.catchupUrl(channel, program, now) }.getOrNull()
            if (primary == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "That replay is no longer available", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
            val alts = runCatching { LiveTvEngine.catchupAlternatives(primary, channel) }.getOrDefault(emptyList())
            val streams = ([primary] + alts).distinct().mapIndexed { i, url ->
                StreamSource(
                    name = (if (i == 0) "Replay" else "Replay ${i + 1}") + " · " + program.title,
                    url = url,
                    headers = liveHeaders(channel),
                    isM3u8 = url.substringBefore('?').lowercase().contains(".m3u8"),
                    provider = ProviderType.IPTV.groupLabel,
                    providerId = LiveTvEngine.bridgeId(channel.sourceId),
                    providerName = channel.sourceName,
                )
            }
            play(
                context, program.title, streams,
                LiveTvEngine.bridgeId(channel.sourceId),
                "catchup|" + program.startMs + "|" + program.stopMs, channel.logo,
            )
        }

    /** Plays a movie or one series episode from On Demand. */
    suspend fun playVod(context: Context, item: VodItem, episode: VodEpisode? = null): Boolean =
        withContext(Dispatchers.IO) {
            val pairs = LiveTvEngine.vod.streamUrls(item, episode)
            if (pairs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "That title has no stream yet", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
            val title = if (episode != null) "${item.name} · E${episode.episode}" else item.name
            val streams = pairs.map { (label, url) ->
                StreamSource(
                    name = label,
                    url = url,
                    headers = mapOf("User-Agent" to XtreamApi.DEFAULT_UA),
                    isM3u8 = url.substringBefore('?').lowercase().contains(".m3u8"),
                    provider = ProviderType.IPTV.groupLabel,
                    providerId = LiveTvEngine.bridgeId(item.sourceId),
                    providerName = item.sourceName,
                )
            }
            play(context, title, streams, LiveTvEngine.bridgeId(item.sourceId), item.uid, item.icon)
        }

    /** Plays a finished recording file straight from storage. */
    fun playRecording(context: Context, rec: LiveTvRecording): Boolean {
        val file = java.io.File(rec.dir, rec.fileName)
        if (!file.isFile) {
            val any = java.io.File(rec.dir).listFiles()?.firstOrNull { it.isFile && it.name != "info.json" }
            if (any == null) {
                Toast.makeText(context, "That recording file is gone", Toast.LENGTH_LONG).show()
                return false
            }
            return play(
                context, rec.title,
                listOf(
                    StreamSource(
                        name = rec.title, url = "file://" + any.absolutePath,
                        provider = "Recordings",
                        providerId = "livetv-recordings", providerName = "Recordings",
                    )
                ),
                "livetv-recordings", "rec:" + rec.id, rec.channelLogo,
            )
        }
        return play(
            context, rec.title,
            listOf(
                StreamSource(
                    name = rec.title, url = "file://" + file.absolutePath,
                    provider = "Recordings",
                    providerId = "livetv-recordings", providerName = "Recordings",
                )
            ),
            "livetv-recordings", "rec:" + rec.id, rec.channelLogo,
        )
    }
}
