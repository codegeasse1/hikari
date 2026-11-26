package com.hikari.desktop.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.prefs.Preferences

class SearchHistoryStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(SearchHistoryStore::class.java),
) {
    @Synchronized
    fun all(): List<String> = decode(preferences.get(HISTORY_KEY, EMPTY_JSON))

    @Synchronized
    fun record(rawQuery: String): List<String> {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LENGTH) return all()

        val updated = (listOf(query) + all().filterNot { it.equals(query, ignoreCase = true) })
            .take(MAX_HISTORY)
        save(updated)
        return updated
    }

    @Synchronized
    fun remove(query: String): List<String> {
        val updated = all().filterNot { it.equals(query, ignoreCase = true) }
        save(updated)
        return updated
    }

    @Synchronized
    fun clear(): List<String> {
        preferences.remove(HISTORY_KEY)
        preferences.flush()
        return emptyList()
    }

    private fun save(queries: List<String>) {
        if (queries.isEmpty()) {
            preferences.remove(HISTORY_KEY)
        } else {
            preferences.put(HISTORY_KEY, gson.toJson(queries))
        }
        preferences.flush()
    }

    private fun decode(encoded: String): List<String> = runCatching {
        gson.fromJson<List<String>>(encoded, listType)
            .orEmpty()
            .map(String::trim)
            .filter { it.length >= MIN_QUERY_LENGTH }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_HISTORY)
    }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_KEY = "recentSearches"
        const val MIN_QUERY_LENGTH = 2
        const val MAX_HISTORY = 20
        const val EMPTY_JSON = "[]"
        val gson = Gson()
        val listType = object : TypeToken<List<String>>() {}.type
    }
}
