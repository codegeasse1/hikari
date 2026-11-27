package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSourceStoreTest {
    @Test
    fun normalizesDeduplicatesAndRemovesSources() {
        val node = Preferences.userRoot().node("com/hikari/desktop/test/${UUID.randomUUID()}")
        try {
            val store = AddonSourceStore(node)

            assertTrue(store.add("addons.example"))
            assertFalse(store.add("https://addons.example/manifest.json"))
            assertEquals(listOf("https://addons.example/manifest.json"), store.all())

            store.remove("https://addons.example/")

            assertTrue(store.all().isEmpty())
        } finally {
            node.removeNode()
        }
    }
}
