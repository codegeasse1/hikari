package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeProviderSelectionStoreTest {
    @Test
    fun persistsNormalizedSelectionAcrossStoreInstances() = withStore { node, store ->
        store.setSelectedUrl("addons.example")

        assertEquals("https://addons.example/manifest.json", store.selectedUrl())
        assertEquals(
            "https://addons.example/manifest.json",
            HomeProviderSelectionStore(node).selectedUrl(),
        )
    }

    @Test
    fun clearsSelectionWhenAllProvidersIsChosen() = withStore { _, store ->
        store.setSelectedUrl("addons.example")

        store.setSelectedUrl(null)

        assertNull(store.selectedUrl())
    }

    @Test
    fun keepsSelectionWhileTheAddonIsInstalled() = withStore { _, store ->
        store.setSelectedUrl("https://addons.example/manifest.json")

        assertEquals(
            "https://addons.example/manifest.json",
            store.reconcileInstalledSources(listOf("https://addons.example/manifest.json")),
        )
    }

    @Test
    fun clearsSelectionWhenTheAddonIsNoLongerInstalled() = withStore { _, store ->
        store.setSelectedUrl("https://removed.example/manifest.json")

        assertNull(
            store.reconcileInstalledSources(listOf("https://remaining.example/manifest.json")),
        )
        assertNull(store.selectedUrl())
    }

    private fun withStore(block: (Preferences, HomeProviderSelectionStore) -> Unit) {
        val node = Preferences.userRoot().node("com/hikari/desktop/test/${UUID.randomUUID()}")
        try {
            block(node, HomeProviderSelectionStore(node))
        } finally {
            node.removeNode()
        }
    }
}
