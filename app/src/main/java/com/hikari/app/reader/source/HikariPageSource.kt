package com.hikari.app.reader.source

import com.hikari.app.net.Http
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's [MangaSource] over one manga extension's pages.
 *
 * A page is downloaded THROUGH THE EXTENSION — `HttpSource.getImage(page)` — and
 * not through any other HTTP client. This is Nekoread's own page path
 * (`TachiyomiHttpSourceAdapter.downloadPageImage`) copied here with nothing
 * changed but the names: the same `Page(0, url = page.pageUrl, imageUrl =
 * page.imageUrl)`, the same `ext.getImage(spage)`, the same `isSuccessful`
 * check, the same body-to-file copy and the same `finally { body.close() }`.
 *
 * That is the whole difference between a page that loads and a page that does
 * not on these sites. `getImage` builds its request from the extension's own
 * `imageRequest(page)` — which is where a source puts its Referer/Origin and the
 * rest of the headers its CDN insists on, and where a source with scrambled
 * pages installs its own interceptor — and runs it through the extension's own
 * client, with its per-host limits, cookie handling and 404 fallback. Fetching
 * the bare image URL through the app's shared client skipped every one of those,
 * which is why pages came back refused (403 on a hotlink-protected CDN) or
 * scrambled (an extension's descrambler never ran).
 *
 * One instance serves the whole reader session, because the ported viewers hold
 * on to their source for the life of the screen and may be streaming more than
 * one chapter at a time; [httpSource] is set by the reader whenever it fetches a
 * chapter's page list.
 */
class HikariPageSource(
    override val id: String,
    override val name: String,
) : MangaSource {

    /**
     * The extension's own source for the chapter being read — the object every
     * page of it is fetched through. Set by the reader on each page-list fetch
     * (see [com.hikari.app.ui.screens.MangaReaderScreen]), because a chapter can
     * belong to a different engine than the one this source was created for
     * (cross-engine chapter jumps are not a thing, but a re-resolved engine is).
     */
    @Volatile
    var httpSource: HttpSource? = null

    /**
     * Downloads one page through the extension's own client and
     * `imageRequest(page)` headers — Nekoread's `downloadPageImage`, verbatim.
     *
     * A source that is not an [HttpSource] (there is no such thing among the
     * manga engines this app installs, but the interface allows one) has no
     * `getImage` to call, so its page is fetched plainly with whatever headers
     * the source handed over — the only case where the app's own client is used
     * at all, and one that cannot silently bypass an extension's request.
     */
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

    /**
     * A cover of this source, loaded through the extension's own client —
     * Nekoread's `coverImageModel`, which its own card asks for on every cover it
     * draws. The [HttpSource] behind this source is what makes it possible (its
     * `headers` are the Referer/User-Agent a manga CDN insists on); with no source
     * behind it yet — the reader sets one on each page-list fetch — the URL is
     * returned unchanged, which is what a source that serves its covers to anyone
     * needs anyway.
     */
    override fun coverImageModel(coverUrl: String): Any =
        httpSource?.let { ExtensionCoverImage(coverUrl, it) } ?: coverUrl
}
