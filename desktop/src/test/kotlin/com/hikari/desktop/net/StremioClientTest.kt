package com.hikari.desktop.net

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonResource
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.StreamKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioClientTest {
    @Test
    fun readsManifestNameAndCatalogDefinitions() = runBlocking {
        val client = StremioClient { url ->
            assertEquals("https://addons.example/manifest.json", url)
            """
                {"name":"Hikari Picks","description":"Curated titles","catalogs":[
                  {"type":"movie","id":"popular","name":"Popular"}
                ]}
            """.trimIndent()
        }

        val manifest = client.loadManifest("addons.example")

        assertEquals("Hikari Picks", manifest.name)
        assertEquals("Curated titles", manifest.description)
        assertEquals(listOf(AddonCatalog("movie", "popular", "Popular")), manifest.catalogs)
    }

    @Test
    fun loadsStremioDeepLinkFromItsNormalizedManifestUrl() = runBlocking {
        var requestedUrl: String? = null
        val client = StremioClient { url ->
            requestedUrl = url
            """{"name":"Imported add-on"}"""
        }

        val manifest = client.loadManifest("stremio://addons.example/stremio/abc?install=1")

        assertEquals("https://addons.example/stremio/abc/manifest.json", requestedUrl)
        assertEquals("https://addons.example/stremio/abc/manifest.json", manifest.addonUrl)
    }

    @Test
    fun mapsCatalogMetaFieldsIntoDesktopTitles() = runBlocking {
        val client = StremioClient {
            """
                {"metas":[{"id":"tt123","type":"movie","name":"Film One",
                  "poster":"https://img.example/poster.jpg","background":"https://img.example/bg.jpg",
                  "description":"A short summary","releaseInfo":"2024"}]}
            """.trimIndent()
        }

        val titles = client.catalog(
            addonUrl = "https://addons.example/",
            catalog = AddonCatalog("movie", "popular", "Popular"),
        )

        assertEquals(1, titles.size)
        assertEquals("Film One", titles.single().title)
        assertEquals("movie", titles.single().type)
        assertEquals("https://img.example/poster.jpg", titles.single().posterUrl)
        assertEquals("A short summary", titles.single().description)
    }

    @Test
    fun mapsYearAndImdbRatingIntoDesktopTitles() = runBlocking {
        val client = StremioClient {
            """
                {"metas":[{"id":"tt456","type":"movie","name":"Rated Film",
                  "releaseInfo":"2021–2023","imdbRating":"8.4"}]}
            """.trimIndent()
        }

        val titles = client.catalog(
            addonUrl = "https://addons.example/",
            catalog = AddonCatalog("movie", "popular", "Popular"),
        )

        assertEquals(2021, titles.single().year)
        assertEquals(8.4, titles.single().rating!!, 0.0)
    }

    @Test
    fun catalogPageTwoRequestsTheStremioSkipOffset() = runBlocking {
        val requestUrls = mutableListOf<String>()
        val expectedUrl = "https://addons.example/catalog/movie/popular/skip=100.json"
        val client = StremioClient { url ->
            requestUrls += url
            if (url == expectedUrl) {
                """{"metas":[{"id":"tt200","type":"movie","name":"Later page"}]}"""
            } else {
                error("Unexpected request: $url")
            }
        }

        val titles = client.catalog(
            addonUrl = "https://addons.example/manifest.json",
            catalog = AddonCatalog("movie", "popular", "Popular"),
            page = 2,
        )

        assertEquals(listOf(expectedUrl), requestUrls)
        assertEquals("Later page", titles.single().title)
    }

    @Test
    fun searchesOnlySearchableCatalogsAndAppliesPageOffset() = runBlocking {
        val responses = mapOf(
            "https://addons.example/manifest.json" to """
                {"name":"Search","catalogs":[
                  {"type":"movie","id":"movies","name":"Movies","extra":[{"name":"search"}]},
                  {"type":"series","id":"shows","name":"Shows","extra":[{"name":"search"}]},
                  {"type":"movie","id":"featured","name":"Featured","extra":[{"name":"genre"}]}
                ]}
            """.trimIndent(),
            "https://addons.example/catalog/movie/movies/search=one%20piece&skip=100.json" to
                """{"metas":[{"id":"m1","type":"movie","name":"One Piece"}]}""",
            "https://addons.example/catalog/series/shows/search=one%20piece&skip=100.json" to
                """{"metas":[{"id":"s1","type":"series","name":"Live Action"}]}""",
        )
        val client = StremioClient { url -> responses[url] ?: error("Unexpected request: $url") }

        val results = client.search("https://addons.example", "one piece", page = 2)

        assertEquals(listOf("One Piece", "Live Action"), results.map { it.title })
        assertTrue(results.all { it.addonUrl == "https://addons.example/manifest.json" })
    }

    @Test
    fun loadsDetailsAndEpisodeIdsFromMetaDocument() = runBlocking {
        val client = StremioClient { url ->
            assertEquals("https://addons.example/meta/series/show1.json", url)
            """
                {"meta":{"id":"show1","type":"series","name":"Show One",
                  "description":"A series","videos":[
                    {"id":"show1:1:2","title":"Second Episode","season":1,"episode":2}
                  ]}}
            """.trimIndent()
        }
        val title = AddonTitle(
            addonUrl = "https://addons.example/manifest.json",
            id = "show1",
            type = "series",
            title = "Show One",
        )

        val detail = client.detail(title)

        assertNotNull(detail)
        assertEquals("A series", detail!!.title.description)
        assertEquals(1, detail.episodes.size)
        assertEquals("show1:1:2", detail.episodes.single().id)
        assertEquals(2, detail.episodes.single().number)
    }

    @Test
    fun parsesStringAndObjectResourcesUsingGlobalDefaults() = runBlocking {
        val client = StremioClient {
            """
                {"name":"Stream only","catalogs":[],"types":["movie","series"],
                 "idPrefixes":["tt"],"resources":[
                   "stream",
                   {"name":"catalog","types":["movie"],"idPrefixes":["tmdb:"]},
                   {"name":"meta"}
                 ]}
            """.trimIndent()
        }

        val manifest = client.loadManifest("stream.example")

        assertEquals(emptyList<AddonCatalog>(), manifest.catalogs)
        assertEquals(
            listOf(
                AddonResource("stream", setOf("movie", "series"), listOf("tt")),
                AddonResource("catalog", setOf("movie"), listOf("tmdb:")),
                AddonResource("meta", setOf("movie", "series"), listOf("tt")),
            ),
            manifest.resources,
        )
    }

    @Test
    fun routesSeriesEpisodeToCompatibleStreamOnlyAddonAndKeepsManifestQuery() = runBlocking {
        val manifestResponses = listOf(
            "https://catalog.example/manifest.json" to """
                {"name":"Catalog","catalogs":[],"resources":["catalog"],
                 "types":["series"],"idPrefixes":["tt"]}
            """.trimIndent(),
            "https://pengu.example/stremio/abc/manifest.json?key=secret" to """
                {"name":"PenguPlay","catalogs":[],"resources":["stream"],
                 "types":["series"],"idPrefixes":["tt"]}
            """.trimIndent(),
            "https://movie-only.example/manifest.json" to """
                {"name":"Movie only","catalogs":[],"resources":[
                  {"name":"stream","types":["movie"],"idPrefixes":["tt"]}
                ]}
            """.trimIndent(),
            "https://wrong-prefix.example/manifest.json" to """
                {"name":"Wrong prefix","catalogs":[],"resources":[
                  {"name":"stream","types":["series"],"idPrefixes":["tmdb:"]}
                ]}
            """.trimIndent(),
        )
        val responsesByUrl = manifestResponses.toMap()
        val requestUrls = mutableListOf<String>()
        val expectedStreamUrl = "https://pengu.example/stremio/abc/stream/series/tt123:1:2.json?key=secret"
        val client = StremioClient { url ->
            requestUrls += url
            responsesByUrl[url] ?: when (url) {
                expectedStreamUrl -> """{"streams":[{"name":"PenguPlay","url":"https://video.example/episode.mp4"}]}"""
                else -> error("Unexpected request: $url")
            }
        }
        val manifests = manifestResponses.map { (url, _) -> client.loadManifest(url) }
        val title = AddonTitle(
            addonUrl = "https://catalog.example/manifest.json",
            id = "tt123",
            type = "series",
            title = "Series One",
        )

        val streams = client.streamsForTitle(title, "tt123:1:2", manifests)

        assertEquals(manifestResponses.map { it.first } + expectedStreamUrl, requestUrls)
        assertEquals("https://video.example/episode.mp4", streams.single().url)
    }

    @Test
    fun keepsUsingTheOriginForLegacyManifestsWithoutResources() = runBlocking {
        val requestUrls = mutableListOf<String>()
        val manifestUrl = "https://legacy.example/manifest.json"
        val streamUrl = "https://legacy.example/stream/movie/tt123.json"
        val client = StremioClient { url ->
            requestUrls += url
            when (url) {
                manifestUrl -> """{"name":"Legacy","catalogs":[{"type":"movie","id":"top"}]}"""
                streamUrl -> """{"streams":[{"url":"https://video.example/legacy.mp4"}]}"""
                else -> error("Unexpected request: $url")
            }
        }
        val manifest = client.loadManifest(manifestUrl)
        val title = AddonTitle(manifestUrl, "tt123", "movie", "Film One")

        val streams = client.streamsForTitle(title, title.id, listOf(manifest))

        assertEquals(listOf(manifestUrl, streamUrl), requestUrls)
        assertEquals("https://video.example/legacy.mp4", streams.single().url)
    }

    @Test
    fun keepsStreamsFromHealthyProvidersWhenAnotherCompatibleAddonFails() = runBlocking {
        val manifestResponses = listOf(
            "https://catalog.example/manifest.json" to """
                {"name":"Catalog","catalogs":[],"resources":["catalog"]}
            """.trimIndent(),
            "https://offline.example/manifest.json" to """
                {"name":"Offline","catalogs":[],"resources":[
                  {"name":"stream","types":["movie"],"idPrefixes":["tt"]}
                ]}
            """.trimIndent(),
            "https://healthy.example/manifest.json" to """
                {"name":"Healthy","catalogs":[],"types":["movie"],
                 "idPrefixes":["tt"],"resources":["stream"]}
            """.trimIndent(),
        )
        val responsesByUrl = manifestResponses.toMap()
        val failedStreamUrl = "https://offline.example/stream/movie/tt123.json"
        val healthyStreamUrl = "https://healthy.example/stream/movie/tt123.json"
        val requestedStreamUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = StremioClient { url ->
            responsesByUrl[url] ?: when (url) {
                failedStreamUrl -> {
                    requestedStreamUrls += url
                    error("Add-on is offline")
                }
                healthyStreamUrl -> {
                    requestedStreamUrls += url
                    """{"streams":[{"url":"https://video.example/available.mp4"}]}"""
                }
                else -> error("Unexpected request: $url")
            }
        }
        val manifests = manifestResponses.map { (url, _) -> client.loadManifest(url) }
        val title = AddonTitle("https://catalog.example/manifest.json", "tt123", "movie", "Film One")

        val streams = client.streamsForTitle(title, title.id, manifests)

        assertEquals(setOf(failedStreamUrl, healthyStreamUrl), requestedStreamUrls.toSet())
        assertEquals(listOf("https://video.example/available.mp4"), streams.map { it.url })
    }

    @Test
    fun returnsDirectAndExternalHttpStreamsButSkipsTorrentRows() = runBlocking {
        val client = StremioClient {
            """
                {"streams":[
                  {"name":"1080p","title":"Direct","url":"https://video.example/file.m3u8"},
                  {"name":"Website","externalUrl":"https://watch.example/title"},
                  {"name":"Torrent","infoHash":"abc123"},
                  {"name":"Unsafe","url":"file:///tmp/video.mp4"}
                ]}
            """.trimIndent()
        }

        val streams = client.streams(
            addonUrl = "https://addons.example/manifest.json",
            type = "movie",
            videoId = "tt123",
        )

        assertEquals(2, streams.size)
        assertEquals(StreamKind.DIRECT, streams[0].kind)
        assertEquals("https://video.example/file.m3u8", streams[0].url)
        assertEquals(StreamKind.BROWSER, streams[1].kind)
        assertEquals("https://watch.example/title", streams[1].url)
    }
}
