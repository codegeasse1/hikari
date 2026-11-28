package com.hikari.desktop.playback

import com.hikari.desktop.engine.EngineStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePlayerEngineTest {

    private val stream = EngineStream("p", "HD", "https://cdn.example.com/v.mp4", quality = "1080p")

    @Test
    fun openPlayPauseSeekFlow() {
        val engine = FakePlayerEngine()
        engine.open(stream, "Dune")
        var state = engine.state().value
        assertEquals("Dune", state.title)
        assertEquals(3_600_000L, state.durationMs)
        assertFalse(state.playing)

        engine.play()
        assertTrue(engine.state().value.playing)
        engine.pause()
        assertFalse(engine.state().value.playing)
        engine.toggle()
        assertTrue(engine.state().value.playing)

        engine.seekTo(60_000)
        assertEquals(60_000L, engine.state().value.positionMs)
        engine.seekBy(-10_000)
        assertEquals(50_000L, engine.state().value.positionMs)

        engine.setRate(1.5f)
        assertEquals(1.5f, engine.state().value.rate)
        engine.setVolume(0.5)
        assertEquals(0.5, engine.state().value.volume, 0.0)

        engine.close()
        state = engine.state().value
        assertEquals(0L, state.positionMs)
        assertFalse(state.playing)
    }
}
