package com.hikari.desktop.data

import com.hikari.desktop.net.AddonUrls
import java.util.prefs.Preferences

class AddonSourceStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(AddonSourceStore::class.java),
) {
    @Synchronized
    fun all(): List<String> = preferences.get(SOURCES_KEY, "")
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    @Synchronized
    fun add(addonUrl: String): Boolean {
        val normalized = AddonUrls.manifestUrl(addonUrl)
        val current = all()
        if (normalized in current) return false
        save(current + normalized)
        return true
    }

    @Synchronized
    fun remove(addonUrl: String) {
        val normalized = AddonUrls.manifestUrl(addonUrl)
        save(all().filterNot { it == normalized })
    }

    private fun save(urls: List<String>) {
        preferences.put(SOURCES_KEY, urls.joinToString("\n"))
        preferences.flush()
    }

    private companion object {
        const val SOURCES_KEY = "stremioAddonManifestUrls"
    }
}
