package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource

interface ContentProvider {
    val config: ProviderConfig

    suspend fun catalogs(): List<CatalogRef>
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
