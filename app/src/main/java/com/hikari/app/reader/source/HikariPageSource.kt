package com.hikari.app.reader.source

import com.hikari.app.net.Http
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's [MangaSource] over one manga extension's pages.
 *
 * A chapter's pages arrive from the engine as `StreamSource`s: `url` is the page
 * IMAGE and `headers` are the headers that source's own site expects (the manga
 * provider builds them from the extension — see
 * [com.hikari.app.manga.MangaProvider.sourceHeaders]). Nekoread's readers fetch a
 * page through the source instead of through a shared image loader precisely so
 * those headers are used, so this is where they are kept: the map is keyed by the
 * image URL the reader sees and is refreshed whenever the streamed chapters
 * change (see [setHeaders]).
 *
 * One instance serves the whole reader session, because the ported viewers hold
 * on to their source for the life of the screen and may be streaming more than
 * one chapter at a time.
 */
class HikariPageSource(
    override val id: String,
    override val name: String,
) : MangaSource {

    /** image URL -> the headers the extension asked for that page. */
    @Volatile
    private var headers: Map<String, Map<String, String>> = emptyMap()

    /** Replaces the known page headers — called when the reader's chapters change. */
    fun setHeaders(byImageUrl: Map<String, Map<String, String>>) {
        headers = byImageUrl
    }

    override suspend fun downloadPageImage(page: MangaSource.PageDescriptor, target: File): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val pageHeaders = headers[page.imageUrl].orEmpty()
            // `Http.downloadTo` sends the app's browser User-Agent and any headers
            // given here, and reports a non-2xx answer as false — which becomes the
            // page's failure below rather than a zero-byte "success".
            val ok = Http.downloadTo(page.imageUrl, target, pageHeaders)
            if (!ok) {
                target.delete()
                throw IOException("Couldn't fetch the page image (${page.imageUrl.take(80)})")
            }
            target
        }
}
