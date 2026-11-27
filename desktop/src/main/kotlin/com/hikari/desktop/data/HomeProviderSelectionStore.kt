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

    private companion object {
        const val SELECTED_URL_KEY = "homeSelectedStremioAddonManifestUrl"
    }
}
