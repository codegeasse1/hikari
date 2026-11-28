package com.hikari.desktop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineModelsTest {

    @Test
    fun addonUrlPrefixesNeverCollideWithStremio() {
        val p = EngineProvider("nuvio|abc123", EngineKind.NUVIO, "Demo", "http://x/y.js")
        val url = engineAddonUrl(p)
        assertTrue(url.startsWith("engine:nuvio:"))
        val (kind, id) = parseEngineRef(url) ?: error("should parse")
        assertEquals(EngineKind.NUVIO, kind)
        assertEquals("nuvio|abc123", id)
        assertNull(parseEngineRef("https://example.com/manifest.json"))
    }

    @Test
    fun conversionsCarryCoreFields() {
        val p = EngineProvider("stremio|1", EngineKind.STREMIO, "Demo", "https://x/manifest.json")
        val media = EngineMedia(p.id, "tt123", "Dune", EngineMediaKind.MOVIE, year = 2021, rating = 8.0)
        val title = media.toAddonTitle(p)
        assertEquals("Dune", title.title)
        assertEquals("tt123", title.id)
        assertEquals("movie", title.type)
        assertEquals(2021, title.year)
        assertEquals(engineAddonUrl(p), title.addonUrl)

        val ep = EngineEpisode(p.id, "tt123", "ep1", 1, 2, "Part Two")
        val addonEp = ep.toAddonEpisode()
        assertEquals(1, addonEp.season)
        assertEquals(2, addonEp.number)
        assertEquals("Part Two", addonEp.title)
    }

    @Test
    fun playableUrlClassifier() {
        assertTrue(isDirectPlayUrl("https://cdn.example.com/video.mp4"))
        assertTrue(isDirectPlayUrl("https://cdn.example.com/live.m3u8?token=abc"))
        assertTrue(isInternalPlayerUrl("https://cdn.example.com/live.m3u8"))
        assertFalse(isInternalPlayerUrl("https://cdn.example.com/video.mpd"))
        assertTrue(isDirectPlayUrl("https://cdn.example.com/video.mpd"))
        assertFalse(isDirectPlayUrl("magnet:?xt=urn:btih:abc"))
        assertFalse(isDirectPlayUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(isDirectPlayUrl(""))
    }

    @Test
    fun streamConversionNamesAndKinds() {
        val playable = EngineStream("p", "HD 1080p", "https://cdn.example.com/v.mp4", quality = "1080p")
        val addon = playable.toAddonStream()
        assertEquals("https://cdn.example.com/v.mp4", addon.url)
        assertTrue(addon.name.contains("1080p"))
        val page = EngineStream("p", "Site", "https://example.com/embed/123")
        assertFalse(isDirectPlayUrl(page.url))
    }
}
