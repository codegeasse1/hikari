package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource

interface ContentProvider {
    val config: ProviderConfig

    fun adultExtension(): Boolean? = null

    val settingsAvailable: Boolean get() = false

    val settingsReady: Boolean get() = settingsAvailable

    fun prepareSettings(): Boolean = settingsAvailable

    fun rebuildSettings(context: android.content.Context): Boolean = prepareSettings()

    fun openSettings(activity: android.app.Activity?): Boolean = false

    suspend fun catalogs(): List<CatalogRef>

    val searchOnly: Boolean get() = false

    suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    suspend fun warmDetail(item: MediaItem) {}

        suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>

    fun partialStreams(): List<StreamSource> = emptyList()
}
