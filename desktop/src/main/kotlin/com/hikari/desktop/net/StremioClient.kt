package com.hikari.desktop.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonDetail
import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonManifest
import com.hikari.desktop.data.AddonResource
import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.StreamKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

class AddonClientException(message: String, cause: Throwable? = null) : Exception(message, cause)

class StremioClient(
    private val fetchText: suspend (String) -> String = ::fetchTextOverHttp,
) {
    suspend fun loadManifest(addonUrl: String): AddonManifest {
        val normalizedUrl = AddonUrls.manifestUrl(addonUrl)
        val json = fetchJson(normalizedUrl)
        val catalogs = json.array("catalogs")?.mapNotNull { element ->
            val catalog = element.asObjectOrNull() ?: return@mapNotNull null
            val type = catalog.string("type")
            val id = catalog.string("id")
            if (type.isBlank() || id.isBlank()) return@mapNotNull null
            val extras = catalog.array("extra")?.mapNotNull { it.asObjectOrNull()?.string("name") }
                ?.filter(String::isNotBlank)
                ?.map(String::lowercase)
                ?.toSet()
                .orEmpty()
            AddonCatalog(
                type = type,
                id = id,
                name = catalog.string("name").ifBlank { id },
                extraNames = extras,
            )
        }.orEmpty()
        val globalTypes = json.stringList("types").toSet()
        val globalIdPrefixes = json.array("idPrefixes")?.let { json.stringList("idPrefixes") }
        val resources = json.array("resources")?.mapNotNull { element ->
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    element.asString.takeIf(String::isNotBlank)?.let { resourceName ->
                        AddonResource(resourceName, globalTypes, globalIdPrefixes)
                    }
                }
                element.isJsonObject -> element.asJsonObject.let { resource ->
                    val resourceName = resource.string("name")
                    if (resourceName.isBlank()) {
                        null
                    } else {
                        AddonResource(
                            name = resourceName,
                            types = if (resource.array("types") != null) {
                                resource.stringList("types").toSet()
                            } else {
                                globalTypes
                            },
                            idPrefixes = if (resource.array("idPrefixes") != null) {
                                resource.stringList("idPrefixes")
                            } else {
                                globalIdPrefixes
                            },
                        )
                    }
                }
                else -> null
            }
        }.orEmpty()
        val name = json.string("name").ifBlank { URI(normalizedUrl).host ?: "Stremio add-on" }
        return AddonManifest(
            addonUrl = normalizedUrl,
            name = name,
            description = json.stringOrNull("description"),
            catalogs = catalogs,
            resources = resources,
        )
    }

    suspend fun catalog(
        addonUrl: String,
        catalog: AddonCatalog,
        page: Int = 1,
        search: String? = null,
    ): List<AddonTitle> = catalogItems(
        addonUrl,
        catalog,
        search = search?.trim()?.takeIf(String::isNotEmpty),
        page = page,
    )

    suspend fun search(addonUrl: String, query: String, page: Int = 1): List<AddonTitle> {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(page > 0) { "page must be greater than zero" }
        val manifest = loadManifest(addonUrl)
        val searchable = manifest.catalogs.filter { catalog ->
            catalog.extraNames.isEmpty() || "search" in catalog.extraNames
        }
        val results = coroutineScope {
            searchable.map { catalog ->
                async { catalogItems(manifest.addonUrl, catalog, search = query, page = page) }
            }.awaitAll().flatten()
        }
        return results.distinctBy { "${it.type}:${it.id}" }
    }

    suspend fun detail(title: AddonTitle): AddonDetail? {
        val json = fetchJson(AddonUrls.metaUrl(title.addonUrl, title.type, title.id))
        val meta = json.get("meta").asObjectOrFirstArrayObject() ?: return null
        val fullTitle = titleFromJson(title.addonUrl, meta, title.type) ?: title.copy(
            title = meta.string("name").ifBlank { title.title },
            description = meta.stringOrNull("description") ?: title.description,
            posterUrl = meta.stringOrNull("poster") ?: title.posterUrl,
            backgroundUrl = meta.stringOrNull("background") ?: meta.stringOrNull("backdrop") ?: title.backgroundUrl,
            releaseInfo = meta.stringOrNull("releaseInfo") ?: title.releaseInfo,
            genres = meta.stringList("genres").ifEmpty { title.genres },
            year = meta.string("year").toIntOrNull() ?: releaseYear(meta.stringOrNull("releaseInfo")) ?: title.year,
            rating = meta.doubleOrNull("imdbRating") ?: meta.doubleOrNull("rating") ?: title.rating,
        )
        val episodes = meta.array("videos")?.mapNotNull { element ->
            val video = element.asObjectOrNull() ?: return@mapNotNull null
            val number = video.int("episode", -1)
            if (number < 0) return@mapNotNull null
            val season = video.int("season", 1).coerceAtLeast(1)
            AddonEpisode(
                id = video.string("id").ifBlank { "${title.id}:$season:$number" },
                title = video.string("title").ifBlank { "Episode $number" },
                season = season,
                number = number,
            )
        }.orEmpty().distinctBy { "${it.season}:${it.number}" }.sortedWith(compareBy({ it.season }, { it.number }))
        return AddonDetail(fullTitle, episodes)
    }

    suspend fun streams(addonUrl: String, type: String, videoId: String): List<AddonStream> {
        val json = fetchJson(AddonUrls.streamUrl(addonUrl, type, videoId))
        return json.array("streams")?.mapNotNull { element ->
            val stream = element.asObjectOrNull() ?: return@mapNotNull null
            val name = stream.string("name")
                .ifBlank { stream.string("title") }
                .ifBlank { stream.string("description") }
            val directUrl = stream.string("url")
            val externalUrl = stream.string("externalUrl")
            when {
                isHttpUrl(directUrl) -> AddonStream(
                    name = name.ifBlank { if (directUrl.contains(".m3u8", ignoreCase = true)) "HLS" else "Direct" },
                    url = directUrl,
                    kind = StreamKind.DIRECT,
                )
                isHttpUrl(externalUrl) -> AddonStream(
                    name = name.ifBlank { "Website" },
                    url = externalUrl,
                    kind = StreamKind.BROWSER,
                )
                stream.string("ytId").isNotBlank() -> {
                    val video = stream.string("ytId")
                    AddonStream(
                        name = name.ifBlank { "YouTube" },
                        url = "https://www.youtube.com/watch?v=$video",
                        kind = StreamKind.BROWSER,
                    )
                }
                else -> null
            }
        }.orEmpty().distinctBy { it.url }
    }

    suspend fun streamsForTitle(
        title: AddonTitle,
        videoId: String,
        installedAddons: List<AddonManifest>,
    ): List<AddonStream> {
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        val providerUrls = streamProviderUrls(title, videoId, installedAddons)
        if (providerUrls.isEmpty()) return emptyList()

        val results = coroutineScope {
            providerUrls.map { addonUrl ->
                async {
                    try {
                        Result.success(streams(addonUrl, title.type, videoId))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }
        val streams = results.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.url }
        if (streams.isNotEmpty()) return streams
        results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
        return emptyList()
    }

    private suspend fun catalogItems(
        addonUrl: String,
        catalog: AddonCatalog,
        search: String?,
        page: Int,
    ): List<AddonTitle> {
        require(page > 0) { "page must be greater than zero" }
        val skip = (page - 1) * STREMIO_PAGE_SIZE
        val url = AddonUrls.catalogUrl(
            manifestUrl = addonUrl,
            type = catalog.type,
            catalogId = catalog.id,
            search = search,
            skip = skip,
        )
        val json = fetchJson(url)
        return json.array("metas")?.mapNotNull { element ->
            element.asObjectOrNull()?.let { titleFromJson(AddonUrls.manifestUrl(addonUrl), it, catalog.type) }
        }.orEmpty()
    }

    private fun streamProviderUrls(
        title: AddonTitle,
        videoId: String,
        installedAddons: List<AddonManifest>,
    ): List<String> {
        val origin = installedAddons.firstOrNull { it.addonUrl == title.addonUrl }
        val compatibleProviders = installedAddons.filter { manifest ->
            manifest.resources.any { resource ->
                resource.name.equals("stream", ignoreCase = true) && resource.supports(title.type, videoId)
            }
        }
        val candidates = buildList {
            if (origin?.resources?.isEmpty() == true) add(origin.addonUrl)
            compatibleProviders.forEach { add(it.addonUrl) }
        }.distinct()
        if (candidates.isNotEmpty()) return candidates
        return if (origin == null) listOf(AddonUrls.manifestUrl(title.addonUrl)) else emptyList()
    }

    private suspend fun fetchJson(url: String): JsonObject {
        val body = try {
            fetchText(url)
        } catch (error: AddonClientException) {
            throw error
        } catch (error: Exception) {
            throw AddonClientException("Could not reach the add-on. Check the URL and your connection.", error)
        }
        val element = try {
            JsonParser.parseString(body)
        } catch (error: Exception) {
            throw AddonClientException("The add-on returned invalid JSON.", error)
        }
        return element.asObjectOrNull()
            ?: throw AddonClientException("The add-on response was not a JSON object.")
    }

    private fun titleFromJson(addonUrl: String, json: JsonObject, fallbackType: String): AddonTitle? {
        val id = json.string("id")
        val title = json.string("name").ifBlank { json.string("title") }
        if (id.isBlank() || title.isBlank()) return null
        return AddonTitle(
            addonUrl = AddonUrls.manifestUrl(addonUrl),
            id = id,
            type = json.string("type").ifBlank { fallbackType },
            title = title,
            posterUrl = json.stringOrNull("poster"),
            backgroundUrl = json.stringOrNull("background") ?: json.stringOrNull("backdrop"),
            description = json.stringOrNull("description"),
            releaseInfo = json.stringOrNull("releaseInfo"),
            genres = json.stringList("genres"),
            year = json.string("year").toIntOrNull() ?: releaseYear(json.stringOrNull("releaseInfo")),
            rating = json.doubleOrNull("imdbRating") ?: json.doubleOrNull("rating"),
        )
    }

    private fun releaseYear(value: String?): Int? =
        Regex("(19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null
    } catch (_: Exception) {
        false
    }

    private fun JsonElement?.asObjectOrFirstArrayObject(): JsonObject? = when {
        this == null || isJsonNull -> null
        isJsonObject -> asJsonObject
        isJsonArray -> asJsonArray.firstOrNull()?.asObjectOrNull()
        else -> null
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.stringOrNull(name: String): String? =
        string(name).ifBlank { null }

    private fun JsonObject.stringList(name: String): List<String> =
        array(name)?.mapNotNull { element ->
            if (element.isJsonNull) null else runCatching { element.asString }.getOrNull()?.ifBlank { null }
        }.orEmpty()

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() } ?: default

    private fun JsonObject.doubleOrNull(name: String): Double? =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
            ?.takeIf { it.isFinite() && it in 0.0..10.0 }

}

internal const val STREMIO_PAGE_SIZE = 100

private val defaultHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private suspend fun fetchTextOverHttp(url: String): String = withContext(Dispatchers.IO) {
    try {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Hikari Desktop/0.12.162")
            .GET()
            .build()
        val response = defaultHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw AddonClientException("The add-on returned HTTP ${response.statusCode()}.")
        }
        response.body()
    } catch (error: AddonClientException) {
        throw error
    } catch (error: Exception) {
        throw AddonClientException("Could not reach the add-on. Check the URL and your connection.", error)
    }
}
