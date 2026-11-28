package com.hikari.desktop.engine

import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {

    private fun freshRegistry(): ProviderRegistry {
        val node = Preferences.userRoot().node("hikari-desktop-test/engines-" + System.nanoTime())
        return ProviderRegistry(node)
    }

    @Test
    fun roundTripsProviders() {
        val registry = freshRegistry()
        assertTrue(registry.all().isEmpty())
        val p = EngineProvider("nuvio|demo", EngineKind.NUVIO, "Demo", "https://example.com/x.js")
        registry.upsert(p)
        assertEquals(1, registry.all().size)
        assertEquals("Demo", registry.all().first().name)
        assertEquals(1, registry.enabled().size)

        registry.setEnabled("nuvio|demo", false)
        assertTrue(registry.enabled().isEmpty())
        assertEquals(1, registry.all().size)

        registry.setNote("nuvio|demo", "boom")
        assertEquals("boom", registry.all().first().note)

        registry.remove("nuvio|demo")
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun tmdbKeyRoundTrips() {
        val registry = freshRegistry()
        assertEquals("", registry.tmdbKey())
        registry.setTmdbKey("  abc123  ")
        assertEquals("abc123", registry.tmdbKey())
    }

    @Test
    fun engineForCoversEveryKind() {
        val registry = freshRegistry()
        for (kind in EngineKind.entries) {
            val engine = registry.engineFor(kind)
            assertTrue("missing engine for $kind", engine != null)
            assertEquals(kind, engine!!.kind)
        }
    }

    @Test
    fun disabledProvidersAreSkippedByAggregation() {
        val registry = freshRegistry()
        registry.upsert(EngineProvider("stremio|off", EngineKind.STREMIO, "Off", "https://127.0.0.1:9/manifest.json", enabled = false))
        assertFalse(registry.enabled().any { it.id == "stremio|off" })
    }
}
