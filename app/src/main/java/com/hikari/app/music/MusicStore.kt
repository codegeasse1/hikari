package com.hikari.app.music

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The music library kept on the phone: liked songs, play history, playlists, saved
 * albums / artists and recent searches. Plain SharedPreferences + JSON, so it can never
 * touch the rest of Hikari's data.
 */
object MusicStore {
    private var prefs: SharedPreferences? = null

    private val _liked = MutableStateFlow<List<MSong>>(emptyList())
    val liked: StateFlow<List<MSong>> = _liked

    private val _plays = MutableStateFlow<List<MPlayStat>>(emptyList())
    val plays: StateFlow<List<MPlayStat>> = _plays

    private val _playlists = MutableStateFlow<List<MPlaylist>>(emptyList())
    val playlists: StateFlow<List<MPlaylist>> = _playlists

    private val _saved = MutableStateFlow<List<MItem>>(emptyList())
    val saved: StateFlow<List<MItem>> = _saved

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> = _searches

    fun ensure(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("hikari_music", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            _liked.value = readSongs(p.getString("liked", null))
            _searches.value = readStrings(p.getString("searches", null))
            _plays.value = readPlays(p.getString("plays", null))
            _playlists.value = readPlaylists(p.getString("playlists", null))
            _saved.value = readItems(p.getString("saved", null))
        }
    }

    // ------------------------------------------------------------------ json

    private fun MSong.toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", title).put("a", artist).put("al", album).put("d", durationSec).put("th", thumb)

    private fun JSONObject.toSong(): MSong =
        MSong(optString("id"), optString("t"), optString("a"), optString("al"), optInt("d"), optString("th"))

    private fun songsJson(list: List<MSong>): JSONArray {
        val arr = JSONArray()
        for (s in list) arr.put(s.toJson())
        return arr
    }

    private fun readSongs(text: String?): List<MSong> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<MSong>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.toSong()?.let { if (it.id.isNotBlank()) out.add(it) }
        return out
    }

    private fun readStrings(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun readPlays(text: String?): List<MPlayStat> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<MPlayStat>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val song = o.optJSONObject("s")?.toSong() ?: continue
            if (song.id.isNotBlank()) out.add(MPlayStat(song, o.optInt("c", 1), o.optLong("l")))
        }
        return out
    }

    private fun readPlaylists(text: String?): List<MPlaylist> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<MPlaylist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(MPlaylist(o.optString("id"), o.optString("n"), readSongs(o.optJSONArray("s")?.toString())))
        }
        return out
    }

    private fun readItems(text: String?): List<MItem> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<MItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = runCatching { MKind.valueOf(o.optString("k")) }.getOrNull() ?: continue
            out.add(MItem(kind, o.optString("id"), o.optString("t"), o.optString("st"), o.optString("th")))
        }
        return out
    }

    private fun save(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    // ------------------------------------------------------------------ liked

    fun isLiked(id: String): Boolean = _liked.value.any { it.id == id }

    fun toggleLike(song: MSong) {
        val now = _liked.value
        _liked.value = if (now.any { it.id == song.id }) now.filter { it.id != song.id } else listOf(song) + now
        save("liked", songsJson(_liked.value).toString())
    }

    // ------------------------------------------------------------------ history / top

    fun recordPlay(song: MSong) {
        val now = System.currentTimeMillis()
        val list = _plays.value.toMutableList()
        val at = list.indexOfFirst { it.song.id == song.id }
        if (at >= 0) list[at] = MPlayStat(song, list[at].count + 1, now) else list.add(MPlayStat(song, 1, now))
        val trimmed = if (list.size > 400) list.sortedByDescending { it.last }.take(400) else list
        _plays.value = trimmed
        val arr = JSONArray()
        for (p in trimmed) arr.put(JSONObject().put("s", p.song.toJson()).put("c", p.count).put("l", p.last))
        save("plays", arr.toString())
    }

    fun clearHistory() {
        _plays.value = emptyList()
        save("plays", "[]")
    }

    // ------------------------------------------------------------------ playlists

    private fun savePlaylists() {
        val arr = JSONArray()
        for (p in _playlists.value) {
            arr.put(JSONObject().put("id", p.id).put("n", p.name).put("s", songsJson(p.songs)))
        }
        save("playlists", arr.toString())
    }

    fun createPlaylist(name: String): String {
        val id = "pl" + System.currentTimeMillis()
        _playlists.value = _playlists.value + MPlaylist(id, name.trim().ifBlank { "New playlist" }, emptyList())
        savePlaylists()
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        savePlaylists()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        savePlaylists()
    }

    fun addToPlaylist(id: String, song: MSong) {
        _playlists.value = _playlists.value.map {
            if (it.id == id && it.songs.none { s -> s.id == song.id }) it.copy(songs = it.songs + song) else it
        }
        savePlaylists()
    }

    fun removeFromPlaylist(id: String, songId: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(songs = it.songs.filter { s -> s.id != songId }) else it
        }
        savePlaylists()
    }

    // ------------------------------------------------------------------ saved albums / artists / playlists

    fun isSaved(id: String): Boolean = _saved.value.any { it.id == id }

    fun toggleSaved(item: MItem) {
        val now = _saved.value
        _saved.value = if (now.any { it.id == item.id }) now.filter { it.id != item.id } else listOf(item) + now
        val arr = JSONArray()
        for (i in _saved.value) {
            arr.put(JSONObject().put("k", i.kind.name).put("id", i.id).put("t", i.title).put("st", i.subtitle).put("th", i.thumb))
        }
        save("saved", arr.toString())
    }

    // ------------------------------------------------------------------ searches

    private fun saveSearches() {
        val arr = JSONArray()
        for (s in _searches.value) arr.put(s)
        save("searches", arr.toString())
    }

    fun addSearch(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        _searches.value = (listOf(text) + _searches.value.filter { !it.equals(text, ignoreCase = true) }).take(15)
        saveSearches()
    }

    fun removeSearch(q: String) {
        _searches.value = _searches.value.filter { it != q }
        saveSearches()
    }
}
