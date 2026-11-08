package com.hikari.app.reader.source

import com.hikari.app.net.Http
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HikariPageSource(
    override val id: String,
    override val name: String,
) : MangaSource {

    @Volatile
    var httpSource: HttpSource? = null

    override suspend fun downloadPageImage(page: MangaSource.PageDescriptor, target: File): File =
        withContext(Dispatchers.IO) {
            val ext = httpSource
            if (ext == null) return@withContext downloadPlain(page, target)
            val spage = Page(0, url = page.pageUrl, imageUrl = page.imageUrl)
            val response = ext.getImage(spage)
            val body = response.body
                ?: throw IOException("Empty image body for ${page.imageUrl.take(80)}")
            try {
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for ${page.imageUrl.take(80)}")
                }
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            } finally {
                body.close()
            }
            target
        }

    /** The fallback for a source with no `HttpSource` behind it: the URL and the
     *  headers the source itself gave us, through the app's own client. */
    private fun downloadPlain(page: MangaSource.PageDescriptor, target: File): File {
        target.parentFile?.mkdirs()
        val ok = Http.downloadTo(page.imageUrl, target, headers[page.imageUrl].orEmpty())
        if (!ok) {
            target.delete()
            throw IOException("Couldn't fetch the page image (${page.imageUrl.take(80)})")
        }
        return target
    }

    /** image URL -> the headers the source asked for that page, used only by
     *  [downloadPlain]. */
    @Volatile
    private var headers: Map<String, Map<String, String>> = emptyMap()

    /** Replaces the known page headers — called when the reader's chapters change. */
    fun setHeaders(byImageUrl: Map<String, Map<String, String>>) {
        headers = byImageUrl
    }

    override fun coverImageModel(coverUrl: String): Any =
        httpSource?.let { ExtensionCoverImage(coverUrl, it) } ?: coverUrl
}
