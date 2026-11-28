package com.hikari.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:04,000
        Hello <b>world</b>

        2
        00:00:05.500 --> 00:00:07,000
        Second line
        still second

        3
        01:02:03,004 --> 01:02:05,000
        Hour mark
    """.trimIndent()

    @Test
    fun parsesSrtCues() {
        val cues = SubtitleParser.parse(srt)
        assertEquals(3, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
        assertEquals("Second line\nstill second", cues[1].text)
        assertEquals(((1 * 3600 + 2 * 60 + 3) * 1000) + 4L, cues[2].startMs)
    }

    @Test
    fun parsesWebVtt() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\nHi there\n"
        val cues = SubtitleParser.parse(vtt)
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("Hi there", cues[0].text)
    }

    @Test
    fun activeAtFindsCurrentCue() {
        val cues = SubtitleParser.parse(srt)
        assertEquals("Hello world", SubtitleParser.activeAt(cues, 2_000)?.text)
        assertNull(SubtitleParser.activeAt(cues, 4_500))
        assertEquals("Second line\nstill second", SubtitleParser.activeAt(cues, 6_000)?.text)
        assertNull(SubtitleParser.activeAt(emptyList(), 2_000))
    }

    @Test
    fun invalidBlocksAreSkipped() {
        val cues = SubtitleParser.parse("not a subtitle\n\n1\n00:00:01,000 --> 00:00:02,000\nOk\n")
        assertEquals(1, cues.size)
        assertEquals("Ok", cues[0].text)
        assertTrue(SubtitleParser.parse("").isEmpty())
    }
}
