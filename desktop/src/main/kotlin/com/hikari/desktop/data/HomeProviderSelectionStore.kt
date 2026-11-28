package com.hikari.desktop.data

import com.hikari.desktop.net.AddonUrls
import java.util.prefs.Preferences

class HomeProviderSelectionStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(HomeProviderSelectionStore::class.java),
) {
    @Synchronized
    fun selectedUrl(): String? = preferences.get(SELECTED_URL_KEY, "")
        .trim()
        .takeIf(String::isNotBlank)

    @Synchronized
    fun setSelectedUrl(addonUrl: String?) {
        if (addonUrl.isNullOrBlank()) {
            preferences.remove(SELECTED_URL_KEY)
        } else {
            preferences.put(SELECTED_URL_KEY, AddonUrls.manifestUrl(addonUrl))
        }
        preferences.flush()
    }

    @Synchronized
    fun reconcileInstalledSources(installedAddonUrls: Collection<String>): String? {
        val selected = selectedUrl() ?: return null
        if (selected in installedAddonUrls) return selected
        setSelectedUrl(null)
        return null
    }

    @Synchronized
    fun multiFeedEnabled(): Boolean = preferences.getBoolean(MULTI_FEED_KEY, false)

    @Synchronized
    fun setMultiFeedEnabled(enabled: Boolean) {
        preferences.putBoolean(MULTI_FEED_KEY, enabled)
        preferences.flush()
    }

    /** Ordered active feed (manifest URLs). Empty = all installed when multi is on. */
    @Synchronized
    fun activeFeedUrls(): List<String> = preferences.get(ACTIVE_FEED_KEY, "")
        .split("|")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    @Synchronized
    fun setActiveFeedUrls(urls: List<String>) {
        preferences.put(ACTIVE_FEED_KEY, urls.map { it.trim() }.filter { it.isNotBlank() }.joinToString("|"))
        preferences.flush()
    }

    @Synchronized
    fun addToFeed(addonUrl: String) {
        val current = activeFeedUrls().toMutableList()
        if (current.none { it == addonUrl }) current.add(addonUrl)
        setActiveFeedUrls(current)
    }

    @Synchronized
    fun removeFromFeed(addonUrl: String) {
        setActiveFeedUrls(activeFeedUrls().filter { it != addonUrl })
    }

    @Synchronized
    fun moveInFeed(from: Int, to: Int) {
        val current = activeFeedUrls().toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from)
        current.add(to, item)
        setActiveFeedUrls(current)
    }

    private companion object {
        const val SELECTED_URL_KEY = "homeSelectedStremioAddonManifestUrl"
        const val MULTI_FEED_KEY = "homeMultiProviderFeedEnabled"
        const val ACTIVE_FEED_KEY = "homeActiveProviderFeedUrls"
    }
}
