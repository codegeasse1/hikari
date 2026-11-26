package com.hikari.desktop.ui

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkImageTest {
    @Test
    fun acceptsAbsoluteHttpAndHttpsArtworkUrls() {
        val https = URI("https://images.example/poster.jpg?size=large")
        val http = URI("http://cdn.example/poster.webp")

        assertEquals(https, safeArtworkUri(https.toString()))
        assertEquals(http, safeArtworkUri(http.toString()))
    }

    @Test
    fun rejectsNonHttpAndMalformedArtworkUrls() {
        assertNull(safeArtworkUri("file:///tmp/poster.png"))
        assertNull(safeArtworkUri("data:image/png;base64,abc"))
        assertNull(safeArtworkUri("https:///poster.png"))
        assertNull(safeArtworkUri("not a url"))
        assertNull(safeArtworkUri(null))
    }

    @Test
    fun rejectsCredentialsAndLocalNetworkArtworkHosts() {
        assertNull(safeArtworkUri("https://user:password@images.example/poster.jpg"))
        assertNull(safeArtworkUri("http://localhost/poster.jpg"))
        assertNull(safeArtworkUri("http://127.0.0.1/poster.jpg"))
        assertNull(safeArtworkUri("http://192.168.1.8/poster.jpg"))
        assertNull(safeArtworkUri("http://media.local/poster.jpg"))
    }

    @Test
    fun rejectsInvalidPorts() {
        assertNull(safeArtworkUri("https://images.example:99999/poster.jpg"))
        assertNull(safeArtworkUri("https://images.example:0/poster.jpg"))
    }
}
