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

data class ExtensionPageImage(
    val pageUrl: String,
    val imageUrl: String,
    val source: HttpSource,
)


/** Memory-cache pages by their unique image URL so scrolling the reader doesn't re-fetch them. */
class ExtensionPageImageKeyer : Keyer<ExtensionPageImage> {
    override fun key(data: ExtensionPageImage, options: Options): String = data.imageUrl
}

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

    private suspend fun resolve(providerId: String): HttpSource {
        val ctx = com.hikari.app.HikariApp.instance
        val config = ctx.store.providers().firstOrNull { it.id == providerId }
            ?: throw IOException("No provider config for $providerId")
        return com.hikari.app.manga.MangaExtensionManager.sourceOf(ctx, config) as? HttpSource
            ?: throw IOException("Extension for $providerId is not an HTTP source")
    }
}
