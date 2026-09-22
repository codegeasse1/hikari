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
 * This interface is Nekoread's own (`com.example.data.source.MangaSource`),
 * trimmed to the members the ported reader actually calls, so the ported files
 * are the same code with one import path changed. [HikariPageSource] is this
 * app's implementation of it.
 */
interface MangaSource {

    /** The installed extension's id — used only to tell two sources apart. */
    val id: String

    /** The extension's display name, for log lines and error rows. */
    val name: String

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
}
