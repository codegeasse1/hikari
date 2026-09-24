package com.hikari.app.telegram

import com.hikari.app.data.AppStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The channels the Telegram tab lists, as the JSON the store keeps:
 * `[{"name":"@netflix","title":"Netflix"}]`.
 *
 * The NAME is the identity (it is what the web preview is fetched by); the title
 * is only what the row prints, so a channel that renames itself does not turn
 * into a second entry.
 */
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
}
