package com.hikari.app.music

import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Playlist link import: paste a public YouTube or Spotify playlist link, the
 * track list comes into Hikari, and every song plays through the existing
 * YouTube audio pipeline. No login, no keys, no quota.
 */
sealed class ImportLink {
    data class YouTube(val listId: String) : ImportLink()
    data class Spotify(val playlistId: String) : ImportLink()
}

/** Accepts youtube.com / youtu.be / music.youtube.com playlist links and open.spotify.com links. */
fun parsePlaylistLink(raw: String): ImportLink? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    Regex("[?&]list=([A-Za-z0-9_-]+)").find(t)?.let {
        val id = it.groupValues[1]
        if (id.length >= 2) return ImportLink.YouTube(id)
    }
    Regex("youtu\\.be/playlist\\?list=([A-Za-z0-9_-]+)").find(t)?.let {
        return ImportLink.YouTube(it.groupValues[1])
    }
    Regex("open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?playlist/([A-Za-z0-9]{22})").find(t)?.let {
        return ImportLink.Spotify(it.groupValues[1])
    }
    return null
}

data class SpTrack(val title: String, val artist: String, val durationMs: Long)

data class ImportTrack(val title: String, val artist: String, val durationSec: Int, val song: MSong?)

data class ImportPreview(val name: String, val source: String, val tracks: List<ImportTrack>)

/**
 * Reads the link and returns the track list. Spotify tracks are matched to
 * the same song on YouTube one by one; [onProgress] gets (done, total)
 * during matching. Throws with a human message when the link is unreadable.
 */
suspend fun fetchPlaylistPreview(link: ImportLink, onProgress: (Int, Int) -> Unit): ImportPreview =
    withContext(Dispatchers.IO) {
        when (link) {
            is ImportLink.YouTube -> {
                val got = OpenStreamApi.playlistBlocking(link.listId)
                    ?: throw IllegalStateException("That YouTube playlist could not be read (private, deleted, or a radio mix link).")
                val tracks = got.second.map { v ->
                    ImportTrack(
                        v.title,
                        v.artist,
                        v.durationSec,
                        MSong(v.id, v.title, v.artist, "", v.durationSec, v.thumb),
                    )
                }
                if (tracks.isEmpty()) throw IllegalStateException("That YouTube playlist is empty.")
                ImportPreview(got.first, "YouTube", tracks)
            }
            is ImportLink.Spotify -> {
                val sp = readSpotifyPlaylist(link.playlistId)
                    ?: throw IllegalStateException("That Spotify playlist could not be read (private, deleted, or the link is wrong).")
                if (sp.second.isEmpty()) throw IllegalStateException("That Spotify playlist is empty.")
                val out = ArrayList<ImportTrack>()
                val total = sp.second.size
                for ((i, t) in sp.second.withIndex()) {
                    val song = matchOnYouTube(t)
                    out.add(ImportTrack(t.title, t.artist, (t.durationMs / 1000L).toInt(), song))
                    onProgress(i + 1, total)
                }
                ImportPreview(sp.first, "Spotify", out)
            }
        }
    }

/** Public Spotify playlist (name + tracks) scraped from its own share/embed page. */
private fun readSpotifyPlaylist(id: String): Pair<String, List<SpTrack>>? {
    val html = Http.getStringQuiet(
        "https://open.spotify.com/embed/playlist/$id",
        mapOf("User-Agent" to Http.UA),
    ) ?: return null
    return parseSpotifyEmbed(html)
}

/** Pure parse of an embed page: playlist name + (title, first artist, duration ms). */
fun parseSpotifyEmbed(html: String): Pair<String, List<SpTrack>>? {
    val marker = html.indexOf("__NEXT_DATA__")
    if (marker < 0) return null
    val start = html.indexOf('>', marker)
    val end = html.indexOf("</script>", start)
    if (start < 0 || end < 0) return null
    val entity = runCatching {
        JSONObject(html.substring(start + 1, end))
            .getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data")
            .getJSONObject("entity")
    }.getOrNull() ?: return null
    if (entity.optString("type") != "playlist") return null
    val arr = entity.optJSONArray("trackList") ?: return null
    val out = ArrayList<SpTrack>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val title = o.optString("title").trim()
        if (title.isEmpty()) continue
        val artist = o.optString("subtitle").split(",").firstOrNull()?.trim().orEmpty()
        out.add(SpTrack(title, artist, o.optLong("duration")))
    }
    if (out.isEmpty()) return null
    return Pair(entity.optString("name").ifBlank { "Imported playlist" }, out)
}

/** Finds the same song on YouTube Music. Duration gate rejects wrong versions. */
private suspend fun matchOnYouTube(t: SpTrack): MSong? {
    val expected = (t.durationMs / 1000L).toInt()
    suspend fun candidates(query: String): List<MSong> {
        val shelves = runCatching { MusicApi.search(query, MusicApi.FILTER_SONG) }
            .getOrNull() ?: return emptyList()
        return shelves.asSequence().flatMap { it.items.asSequence() }
            .filter { it.kind == MKind.SONG && it.song != null }
            .map { it.song!! }.take(8).toList()
    }
    var cands = candidates(t.title + " " + t.artist)
    if (cands.isEmpty()) {
        val clean = t.title.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").trim()
        if (clean.isNotEmpty() && clean != t.title) cands = candidates(clean + " " + t.artist)
    }
    if (cands.isEmpty()) return null
    if (expected > 0) {
        val tol = max(15, (expected * 0.25).toInt())
        for (s in cands) {
            if (s.durationSec > 0 && abs(s.durationSec - expected) <= tol) return s
        }
        // Durations disagree everywhere: only trust the top hit when YouTube
        // gave no duration to compare against.
        val top = cands.first()
        return if (top.durationSec <= 0) top else null
    }
    return cands.first()
}
