package com.hikari.desktop.data

import com.google.gson.Gson
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.prefs.Preferences

class SavedTitlesStore(
    preferences: Preferences = Preferences.userNodeForPackage(SavedTitlesStore::class.java),
) {
    private val libraryPreferences = preferences.node(SAVED_TITLES_NODE)

    @Synchronized
    fun all(): List<AddonTitle> = libraryPreferences.childrenNames()
        .mapNotNull { childName ->
            val titlePreferences = libraryPreferences.node(childName)
            val order = titlePreferences.getLong(ORDER_KEY, NO_ORDER)
            val title = decode(titlePreferences.get(TITLE_KEY, EMPTY_JSON))
            if (order == NO_ORDER || title == null) null else SavedTitleEntry(order, title)
        }
        .sortedByDescending(SavedTitleEntry::order)
        .map(SavedTitleEntry::title)

    @Synchronized
    fun save(title: AddonTitle): List<AddonTitle> {
        val order = libraryPreferences.getLong(NEXT_ORDER_KEY, 0L) + 1L
        val titlePreferences = libraryPreferences.node(title.storageKey())
        titlePreferences.put(TITLE_KEY, gson.toJson(title.toLibrarySnapshot()))
        titlePreferences.putLong(ORDER_KEY, order)
        titlePreferences.flush()
        libraryPreferences.putLong(NEXT_ORDER_KEY, order)
        libraryPreferences.flush()
        return all()
    }

    @Synchronized
    fun remove(title: AddonTitle): List<AddonTitle> {
        val childName = title.storageKey()
        if (libraryPreferences.nodeExists(childName)) {
            libraryPreferences.node(childName).removeNode()
        }
        libraryPreferences.flush()
        return all()
    }

    @Synchronized
    fun isSaved(title: AddonTitle): Boolean = all().any {
        it.savedTitleIdentity() == title.savedTitleIdentity()
    }

    private fun decode(encoded: String): AddonTitle? =
        runCatching { gson.fromJson(encoded, AddonTitle::class.java) }.getOrNull()

    private fun AddonTitle.toLibrarySnapshot(): AddonTitle = copy(
        backgroundUrl = null,
        description = null,
        genres = emptyList(),
    )

    private fun AddonTitle.storageKey(): String {
        val identity = gson.toJson(savedTitleIdentity()).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private data class SavedTitleEntry(val order: Long, val title: AddonTitle)

    private companion object {
        const val SAVED_TITLES_NODE = "savedTitles"
        const val NEXT_ORDER_KEY = "nextOrder"
        const val ORDER_KEY = "order"
        const val TITLE_KEY = "title"
        const val NO_ORDER = Long.MIN_VALUE
        const val EMPTY_JSON = "{}"
        val gson = Gson()
    }
}

internal data class SavedTitleIdentity(
    val addonUrl: String,
    val type: String,
    val id: String,
)

internal fun AddonTitle.savedTitleIdentity(): SavedTitleIdentity =
    SavedTitleIdentity(addonUrl = addonUrl, type = type.lowercase(Locale.ROOT), id = id)
