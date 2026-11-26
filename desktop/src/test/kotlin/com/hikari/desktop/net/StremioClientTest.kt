package com.hikari.desktop.net

import com.hikari.desktop.data.AddonCatalog
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
