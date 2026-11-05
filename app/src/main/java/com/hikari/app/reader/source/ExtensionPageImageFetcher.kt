package com.hikari.app.reader.source

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.FetchResult
import coil.fetch.SourceResult
import coil.request.Options
import coil.key.Keyer
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Coil model for a reader page served by a Tachiyomi extension.
 *
 * The dedicated [Fetcher] loads the image through the extension's own client and its
 * [HttpSource.getImage] path — which builds the request via the source's `imageRequest(page)`
 * (carrying the source's Referer/Origin/custom headers) and runs it through the source's client
 * (including source-specific interceptors like Comix's Descrambler and 404-fallback). This is
 * exactly how Tadami/Mihon's reader loads online pages; loading the bare URL through a generic
 * client omits those headers, which is why hotlink-protected CDNs returned blank/black pages.
 */
data class ExtensionPageImage(
    val pageUrl: String,
    val imageUrl: String,
    val source: HttpSource,
)


/** Memory-cache pages by their unique image URL so scrolling the reader doesn't re-fetch them. */
class ExtensionPageImageKeyer : Keyer<ExtensionPageImage> {
    override fun key(data: ExtensionPageImage, options: Options): String = data.imageUrl
}

/** Network fetcher for reader page models: loads the image through the extension's OWN client and
 *  its [HttpSource.getImage] path (carrying the source's Referer/Origin/custom headers), so
 *  hotlink-protected CDNs accept it. This is the same client/headers the extension itself uses.
 *  No on-device page cache: pages always fetch live from the source.
 */
class ExtensionPageImageFetcherFactory : Fetcher.Factory<ExtensionPageImage> {
    override fun create(
        data: ExtensionPageImage,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                val page = Page(0, url = data.pageUrl, imageUrl = data.imageUrl)
                val response = try {
                    data.source.getImage(page)
                } catch (e: Exception) {
                    throw IOException("${data.source.name} page load failed (${data.imageUrl.take(80)}): ${e.message}", e)
                }
                val body = response.body ?: throw IOException("Null response body")
                return SourceResult(
                    source = ImageSource(
                        source = body.source(),
                        context = options.context,
                    ),
                    mimeType = body.contentType()?.toString() ?: "image/*",
                    dataSource = DataSource.NETWORK,
                )
            }
        }
    }
}

/** Coil model for a catalog/library cover served by a Tachiyomi extension.
 *
 * The dedicated [Fetcher] loads the cover through the extension's OWN client and headers
 * ([HttpSource.headers] = the source's User-Agent + Referer/Origin etc.) so hotlink-protected
 * CDNs accept it — the same reason reader pages go through [ExtensionPageImage]. Loading the bare
 * URL through the generic client omitted the source's Referer, which is why covers on sources like
 * 18 Porn Comic / TheBlank came back as blank gray tiles even after their API worked.
 */
data class ExtensionCoverImage(
    val imageUrl: String,
    val source: HttpSource,
)

/** Stable memory-cache key for covers (source + url), so a cover loads once and is reused. */
class ExtensionCoverImageKeyer : Keyer<ExtensionCoverImage> {
    override fun key(data: ExtensionCoverImage, options: Options): String =
        "cover:" + data.source.toString() + "|" + data.imageUrl
}

class ExtensionCoverImageFetcherFactory : Fetcher.Factory<ExtensionCoverImage> {

    // Covers must never hang for minutes behind a burst of reader page requests on the same host
    // (per-host request cap + long call timeouts on the shared client) — a stuck cover just shows
    // a blank tile until restart. Use a per-source clone with short timeouts so a slow/failing
    // cover fails fast, lets Coil show the placeholder, and retries cleanly on next composition.
    private val coverClients = ConcurrentHashMap<HttpSource, OkHttpClient>()

    private fun coverClient(source: HttpSource): OkHttpClient =
        coverClients.getOrPut(source) {
            source.client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        }

    override fun create(
        data: ExtensionCoverImage,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                // Some extensions return relative cover paths (e.g. "/uploads/x.jpg"); resolve them
                // against the source's own base URL or the request will fail outright.
                val coverUrl = if (data.imageUrl.startsWith("http")) data.imageUrl
                    else data.source.baseUrl.trimEnd('/') + "/" + data.imageUrl.trimStart('/')
                // Referer fallback: many CDNs only serve hotlinked images when the request carries a
                // Referer. HttpSource.headers usually includes one, but when it doesn't, use the
                // source's own homepage so the CDN sees a legit referrer.
                val headers = data.source.headers.newBuilder()
                    .apply {
                        if (get("Referer").isNullOrBlank() && data.source.baseUrl.isNotBlank()) {
                            set("Referer", data.source.baseUrl)
                        }
                    }
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(coverUrl)
                    .headers(headers)
                    .build()
                val response = try {
                    coverClient(data.source).newCall(request).execute()
                } catch (e: Exception) {
                    throw IOException("${data.source.name} cover load failed (${data.imageUrl.take(80)}): ${e.message}", e)
                }
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("HTTP ${response.code} for ${data.imageUrl.take(80)}")
                }
                val body = response.body ?: throw IOException("Null cover body")
                return SourceResult(
                    source = ImageSource(
                        source = body.source(),
                        context = options.context,
                    ),
                    mimeType = body.contentType()?.toString() ?: "image/*",
                    dataSource = DataSource.NETWORK,
                )
            }
        }
    }
}

/**
 * A cover that names its extension instead of holding it: `(imageUrl, providerId)`.
 *
 * This is the one addition to the port, and it exists because of a difference between the two
 * apps rather than a difference of opinion. Nekoread keeps every installed extension in a live
 * registry built at start-up, so a cell can hand Coil an [ExtensionCoverImage] directly — it
 * already holds the [HttpSource]. Here the extension's classes are loaded on demand (a dex load:
 * see [com.hikari.app.manga.MangaExtensionManager]), which must never happen while a grid is
 * composing on the main thread.
 *
 * So the model carries only the id, and the [Fetcher] below — which Coil always calls on a
 * background dispatcher — resolves the source, then does exactly what
 * [ExtensionCoverImageFetcherFactory] does with it: the extension's own headers, plus a Referer
 * fallback, through a short-timeout clone of the extension's client.
 */
data class ExtensionCoverRef(
    val imageUrl: String,
    val providerId: String,
)

/** Cache key for [ExtensionCoverRef]: stable per (provider, url). */
class ExtensionCoverRefKeyer : Keyer<ExtensionCoverRef> {
    override fun key(data: ExtensionCoverRef, options: Options): String =
        "extcover:" + data.providerId + "|" + data.imageUrl
}

class ExtensionCoverRefFetcherFactory : Fetcher.Factory<ExtensionCoverRef> {

    // Same reasoning as the cover fetcher above: a cover must never queue behind a burst of page
    // requests on the extension's shared client.
    private val coverClients = ConcurrentHashMap<HttpSource, OkHttpClient>()

    private fun coverClient(source: HttpSource): OkHttpClient =
        coverClients.getOrPut(source) {
            source.client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        }

    override fun create(
        data: ExtensionCoverRef,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                // The extension is normally there — the item came from it — but a
                // load can fail (a half-installed or just-uninstalled extension, a
                // dex that will not verify). That must not leave the cell blank
                // where the plain URL would have worked, so the fallback below
                // fetches the bare URL through the app's own client, which is what
                // every non-manga cover does.
                val source = runCatching { resolve(data.providerId) }.getOrNull()
                if (source == null) return fetchPlain(data.imageUrl)
                val coverUrl = if (data.imageUrl.startsWith("http")) data.imageUrl
                    else source.baseUrl.trimEnd('/') + "/" + data.imageUrl.trimStart('/')
                val headers = source.headers.newBuilder()
                    .apply {
                        if (get("Referer").isNullOrBlank() && source.baseUrl.isNotBlank()) {
                            set("Referer", source.baseUrl)
                        }
                    }
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(coverUrl)
                    .headers(headers)
                    .build()
                val response = try {
                    coverClient(source).newCall(request).execute()
                } catch (e: Exception) {
                    throw IOException("${source.name} cover load failed (${data.imageUrl.take(80)}): ${e.message}", e)
                }
                if (!response.isSuccessful) {
                    response.close()
                    return fetchPlain(data.imageUrl)
                }
                val body = response.body ?: throw IOException("Null cover body")
                return SourceResult(
                    source = ImageSource(
                        source = body.source(),
                        context = options.context,
                    ),
                    mimeType = body.contentType()?.toString() ?: "image/*",
                    dataSource = DataSource.NETWORK,
                )
            }

            /** The plain-URL path: the app's own client, which is what a cover had
             *  before this model existed. Blocking on purpose — a Coil fetcher is
             *  always called off the main thread. */
            private fun fetchPlain(url: String): FetchResult {
                val absolute = if (url.startsWith("http")) url else "https://$url"
                val response = com.hikari.app.net.Http.get(
                    absolute,
                    mapOf("Referer" to (android.net.Uri.parse(absolute).let { u ->
                        u.scheme + "://" + u.host + "/"
                    })),
                )
                val body = response.body ?: throw IOException("Null cover body")
                return SourceResult(
                    source = ImageSource(
                        source = body.source(),
                        context = options.context,
                    ),
                    mimeType = body.contentType()?.toString() ?: "image/*",
                    dataSource = DataSource.NETWORK,
                )
            }
        }
    }

    /**
     * The extension behind [providerId], resolved HERE so the dex load happens on Coil's
     * dispatcher and never in composition. Throws when the extension is not installed or cannot
     * be loaded, which Coil reports as a failed request — the cell then shows its placeholder
     * rather than the wrong picture.
     */
    private suspend fun resolve(providerId: String): HttpSource {
        val ctx = com.hikari.app.HikariApp.instance
        val config = ctx.store.providers().firstOrNull { it.id == providerId }
            ?: throw IOException("No provider config for $providerId")
        return com.hikari.app.manga.MangaExtensionManager.sourceOf(ctx, config) as? HttpSource
            ?: throw IOException("Extension for $providerId is not an HTTP source")
    }
}
