package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource

interface ContentProvider {
    val config: ProviderConfig

    /**
     * True when this provider exposes its own settings screen that Hikari can
     * open (CloudStream plugins do this via `Plugin.openSettings`). The
     * Extensions UI only shows a settings button for providers that return
     * true here — exactly like CloudStream.
     */
    val settingsAvailable: Boolean get() = false

    /**
     * Opens the provider's own settings UI. [activity] is the host activity the
     * settings screen should attach its dialogs/fragments to (null = let the
     * provider resolve the current one). Returns false when unsupported or when
     * opening failed.
     */
    fun openSettings(activity: android.app.Activity?): Boolean = false

    suspend fun catalogs(): List<CatalogRef>
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
