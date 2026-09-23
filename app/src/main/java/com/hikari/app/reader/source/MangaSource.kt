package com.hikari.app.reader.source

import java.io.File

/**
 * What the reader's page loaders need from a manga source, and nothing else.
 *
 * The reader is Nekoread's, ported whole: its webtoon viewer, its pager viewer
 * and the page cache they share all download a page through the SOURCE's own
 * client, with the source's own request headers (Referer, Origin, whatever the
 * site insists on), instead of asking a generic image loader for the URL. That
 * is the one thing a page image needs that a poster does not: a manga CDN will
 * serve the same bytes to a request that says where it came from and refuse the
 * one that does not, which is why a page fetched through the app's shared Coil
 * client came back 403 on half these sites.
 *
 * This interface is Nekoread's own (`com.example.data.source.MangaSource`). The
 * members are the ones its ported readers and fetchers call: the page descriptor
 * pair the viewers work from, the cover/image models that go through Coil, and
 * the user agent the Cloudflare WebView has to mirror. Its implementation here is
 * [HikariPageSource].
 */
interface MangaSource {

    /** The installed extension's id — used only to tell two sources apart. */
    val id: String

    /** The extension's display name, for log lines and error rows. */
    val name: String

    /**
     * The User-Agent this source's requests are made with, when it is known.
     * Present for the same reason it is in Nekoread: the Cloudflare-verification
     * WebView has to claim the SAME user agent as the requests it is solving a
     * challenge for, or the cf_clearance cookie it earns is bound to the wrong
     * one and every subsequent request is refused.
     */
    val userAgent: String get() = ""

    /**
     * One page of a chapter, Nekoread's shape: the request page's URL (the page
     * on the site, empty when the source has none) and the image URL to fetch.
     * The image URL is the identity every cache key and diff is built from, so it
     * must be stable for a page.
     */
    data class PageDescriptor(
        val pageUrl: String,
        val imageUrl: String,
    )

    /**
     * Downloads one page's bytes into [target] (creating or overwriting it) and
     * returns it, through the source's own HTTP client and headers. Throwing is
     * how a failure is reported: the page cache turns that into the page's Retry
     * row, and the prewarm loop backs off the URL for a few seconds.
     */
    suspend fun downloadPageImage(page: PageDescriptor, target: File): File

    /**
     * Coil models for a chapter's pages, for the reader paths that hand an image
     * straight to Coil instead of downloading it to the cache first.
     *
     * Nekoread's interface carries this beside [downloadPageImage] because its
     * reader uses BOTH: the webtoon/pager viewers work from cached page FILES
     * (see [downloadPageImage]), while anything that renders a page as an
     * ordinary Coil image takes a model instead — and for an extension source
     * that model has to carry the source, so the request goes out with the
     * extension's own headers (see
     * [com.hikari.app.reader.source.ExtensionPageImageFetcher]).
     *
     * The default is deliberately empty rather than "the plain URLs": a page
     * fetched through a generic client is exactly the request these CDNs refuse,
     * so guessing here would silently undo the point of the method. A source
     * that can answer returns models; a source that cannot says so.
     */
    suspend fun getPageImageModels(rawChapterId: String): List<Any> = emptyList()

    /**
     * Coil model for a cover of this source, for the cells that draw one.
     *
     * Present for the same reason as [getPageImageModels]: a manga CDN refuses a
     * bare request, so a cover has to be fetched through the extension's client
     * (Nekoread returns an `ExtensionCoverImage` here, and its card asks for it
     * for every cover it draws). The default returns the URL unchanged, which is
     * right for any source whose covers are served to anyone.
     */
    fun coverImageModel(coverUrl: String): Any = coverUrl
}
