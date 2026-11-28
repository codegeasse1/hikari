package com.hikari.desktop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ch1" tvg-logo="http://x/logo.png" group-title="News",Channel One
        #EXTVLCOPT:http-referrer=http://example.com/
        http://example.com/stream1.m3u8
        #EXTINF:-1 group-title="Sports",Sports HD
        http://example.com/sports.ts
        #EXTINF:-1,No Group
        http://example.com/plain.mp4
    """.trimIndent()

    @Test
    fun parsesEntriesWithGroupsAndHeaders() {
        val entries = M3uParser.parse(sample)
        assertEquals(3, entries.size)
        assertEquals("Channel One", entries[0].name)
        assertEquals("http://example.com/stream1.m3u8", entries[0].url)
        assertEquals("News", entries[0].group)
        assertEquals("http://x/logo.png", entries[0].logo)
        assertEquals("http://example.com/", entries[0].headers["Referer"])
        assertEquals("Sports", entries[1].group)
        assertEquals("Sports HD", entries[1].name)
        assertEquals(null, entries[2].group)
        assertEquals("No Group", entries[2].name)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = "#EXTM3U\n\n#EXT-X-VERSION:3\n#EXTINF:-1,Only\nhttp://example.com/a.mp4\n"
        val entries = M3uParser.parse(text)
        assertEquals(1, entries.size)
        assertEquals("Only", entries[0].name)
    }

    @Test
    fun emptyPlaylistParsesEmpty() {
        assertTrue(M3uParser.parse("").isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\n").isEmpty())
    }
}
