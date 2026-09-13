package com.hikari.app.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/** Its own DataStore file, so a large download queue never bloats the main
 *  `hikari` preferences blob the rest of the app reads on every launch. */
private val Context.downloadDataStore by preferencesDataStore(name = "hikari_downloads")

private val TASKS_KEY = stringPreferencesKey("tasks")

object DownloadStore {

    suspend fun load(ctx: Context): List<DownloadTask> {
        val raw = ctx.downloadDataStore.data.first()[TASKS_KEY] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .map { DownloadTask.fromJson(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun save(ctx: Context, tasks: List<DownloadTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        ctx.downloadDataStore.edit { it[TASKS_KEY] = arr.toString() }
    }
}
