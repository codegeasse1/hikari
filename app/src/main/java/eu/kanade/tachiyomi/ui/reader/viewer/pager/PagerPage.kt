package eu.kanade.tachiyomi.ui.reader.viewer.pager

import com.hikari.app.reader.source.MangaSource

/**
 * A single page of the paged (chimahon-style) reader: its 1-based [number] within the chapter and
 * the [desc] whose image bytes are downloaded once to the on-device cache and rendered by the
 * subsampling view (see WebtoonPageCache).
 */
data class PagerPage(
    val number: Int,
    val desc: MangaSource.PageDescriptor,
)
