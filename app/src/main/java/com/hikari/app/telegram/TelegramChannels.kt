package com.hikari.app.telegram

import com.hikari.app.data.AppStore
import org.json.JSONArray
import org.json.JSONObject

object TelegramChannels {

    fun decode(json: String): List<Pair<String, String>> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isBlank()) null else name to o.optString("title").trim()
            }
        }.getOrDefault(emptyList())
    }

    fun encode(list: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((name, title) in list) {
            arr.put(JSONObject().put("name", name).put("title", title))
        }
        return arr.toString()
    }

    /** [existing] plus one channel, unless it is already there. */
    suspend fun add(
        store: AppStore,
        existing: List<Pair<String, String>>,
        name: String,
        title: String,
    ) {
        if (existing.any { it.first.equals(name, ignoreCase = true) }) return
        store.setTelegramChannels(encode(existing + (name to title)))
    }

    suspend fun remove(
        store: AppStore,
        existing: List<Pair<String, String>>,
        name: String,
    ) {
        store.setTelegramChannels(encode(existing.filterNot { it.first.equals(name, true) }))
    }

    suspend fun setTitle(
        store: AppStore,
        existing: List<Pair<String, String>>,
        name: String,
        title: String,
    ) {
        val clean = title.trim()
        if (clean.isBlank()) return
        val current = existing.firstOrNull { it.first.equals(name, ignoreCase = true) } ?: return
        if (current.second == clean) return
        store.setTelegramChannels(
            encode(
                existing.map { if (it.first.equals(name, true)) it.first to clean else it }
            )
        )
    }
}
