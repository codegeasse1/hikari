package com.hikari.desktop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LenientParserTest {

    @Test
    fun unwrapsNuvioEnvelope() {
        val (ok, data) = unwrapData("""{"ok":true,"data":[{"url":"https://x/v.mp4","name":"HD"}]}""")
        assertTrue(ok)
        val streams = streamsFromData(data, "p", "Demo")
        assertEquals(1, streams.size)
        assertEquals("https://x/v.mp4", streams[0].url)
        assertEquals("HD", streams[0].name)
    }

    @Test
    fun unwrapsSkyEnvelopeAndNestedShapes() {
        val (ok, data) = unwrapData("""{"success":true,"data":{"streams":[{"file":"https://x/a.m3u8","label":"Auto"}]}}""")
        assertTrue(ok)
        val streams = streamsFromData(data, "p", "Demo")
        assertEquals(1, streams.size)
        assertEquals("https://x/a.m3u8", streams[0].url)
        assertEquals("Auto", streams[0].name)
    }

    @Test
    fun surfacesProviderErrors() {
        val (ok, data) = unwrapData("""{"ok":false,"error":"timed out after 60s"}""")
        assertFalse(ok)
        assertEquals("timed out after 60s", envelopeError("""{"ok":false,"error":"timed out after 60s"}"""))
        assertTrue(streamsFromData(data, "p", "Demo").isEmpty())
    }

    @Test
    fun parsesBareArraysAndStrings() {
        val (_, data) = unwrapData("""["https://x/a.mp4", {"url":"https://x/b.mp4","quality":"720p"}]""")
        val streams = streamsFromData(data, "p", "Demo")
        assertEquals(2, streams.size)
        assertEquals("720p", streams[1].quality)
    }

    @Test
    fun parsesMediaAndEpisodesLeniently() {
        val (_, data) = unwrapData(
            """[{"url":"https://site/t/1","title":"Dune","poster":"https://img/p.jpg","year":2021}]""",
        )
        val media = mediaListFromData(data, "p", EngineMediaKind.MOVIE)
        assertEquals(1, media.size)
        assertEquals("Dune", media[0].title)
        assertEquals("https://site/t/1", media[0].id)
        assertEquals(2021, media[0].year)

        val (_, eps) = unwrapData("""[{"id":"e1","title":"Pilot","season":1,"episode":1}]""")
        val episodes = episodesFromData(eps, "p", "m1")
        assertEquals(1, episodes.size)
        assertEquals(1, episodes[0].season)
        assertEquals(1, episodes[0].number)
    }
}
