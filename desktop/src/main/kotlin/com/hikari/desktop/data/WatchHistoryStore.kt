package com.hikari.desktop.data

import com.google.gson.Gson
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.prefs.Preferences

internal data class WatchHistoryEntry(
    val title: AddonTitle,
    val episode: AddonEpisode?,
    val launchedAtMillis: Long,
) {
    val uniqueKey: String
        get() = WatchHistoryStore.storageKey(title, episode)
}

internal class WatchHistoryStore(
    preferences: Preferences = Preferences.userNodeForPackage(WatchHistoryStore::class.java),
) {
    private val historyPreferences = preferences.node(HISTORY_NODE)

    @Synchronized
    fun all(): List<WatchHistoryEntry> = historyPreferences.childrenNames()
        .mapNotNull { childName ->
            val entryPreferences = historyPreferences.node(childName)
            val order = entryPreferences.getLong(ORDER_KEY, NO_ORDER)
            val entry = decode(entryPreferences.get(ENTRY_KEY, EMPTY_JSON))
            if (order == NO_ORDER || entry == null) null else OrderedEntry(order, entry)
        }
        .sortedByDescending(OrderedEntry::order)
        .map(OrderedEntry::entry)

    @Synchronized
    fun record(
        title: AddonTitle,
        episode: AddonEpisode? = null,
        launchedAtMillis: Long = System.currentTimeMillis(),
    ): List<WatchHistoryEntry> {
        val entry = WatchHistoryEntry(title.toHistorySnapshot(), episode, launchedAtMillis)
        val key = storageKey(entry.title, entry.episode)
        val order = historyPreferences.getLong(NEXT_ORDER_KEY, 0L) + 1L
        val entryPreferences = historyPreferences.node(key)
        entryPreferences.put(ENTRY_KEY, gson.toJson(entry))
        entryPreferences.putLong(ORDER_KEY, order)
        entryPreferences.flush()
        historyPreferences.putLong(NEXT_ORDER_KEY, order)
        trimOldEntries()
        historyPreferences.flush()
        return all()
    }

    @Synchronized
    fun remove(entry: WatchHistoryEntry): List<WatchHistoryEntry> {
        val key = storageKey(entry.title, entry.episode)
        if (historyPreferences.nodeExists(key)) historyPreferences.node(key).removeNode()
        historyPreferences.flush()
        return all()
    }

    @Synchronized
    fun clear(): List<WatchHistoryEntry> {
        historyPreferences.childrenNames().forEach { childName ->
            historyPreferences.node(childName).removeNode()
        }
        historyPreferences.remove(NEXT_ORDER_KEY)
        historyPreferences.flush()
        return emptyList()
    }

    private fun trimOldEntries() {
        val oldest = historyPreferences.childrenNames()
            .mapNotNull { childName ->
                val order = historyPreferences.node(childName).getLong(ORDER_KEY, NO_ORDER)
                if (order == NO_ORDER) null else childName to order
            }
            .sortedBy { it.second }
            .take((historyPreferences.childrenNames().size - MAX_ENTRIES).coerceAtLeast(0))
        oldest.forEach { (childName, _) -> historyPreferences.node(childName).removeNode() }
    }

    private fun decode(encoded: String): WatchHistoryEntry? =
        runCatching { gson.fromJson(encoded, WatchHistoryEntry::class.java) }.getOrNull()

    private fun AddonTitle.toHistorySnapshot(): AddonTitle = copy(
        backgroundUrl = null,
        description = null,
        genres = emptyList(),
        year = null,
        rating = null,
    )

    private data class OrderedEntry(val order: Long, val entry: WatchHistoryEntry)

    companion object {
        private const val HISTORY_NODE = "watchHistory"
        private const val NEXT_ORDER_KEY = "nextOrder"
        private const val ORDER_KEY = "order"
        private const val ENTRY_KEY = "entry"
        private const val NO_ORDER = Long.MIN_VALUE
        private const val EMPTY_JSON = "{}"
        private const val MAX_ENTRIES = 200
        private val gson = Gson()

        internal fun storageKey(title: AddonTitle, episode: AddonEpisode?): String {
            val identity = WatchHistoryIdentity(
                addonUrl = title.addonUrl,
                type = title.type.lowercase(Locale.ROOT),
                mediaId = title.id,
                episodeId = episode?.id.orEmpty(),
            )
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(gson.toJson(identity).toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

private data class WatchHistoryIdentity(
    val addonUrl: String,
    val type: String,
    val mediaId: String,
    val episodeId: String,
)
