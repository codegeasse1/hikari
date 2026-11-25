package com.hikari.app.data

import com.hikari.app.music.ImportLink
import com.hikari.app.music.parsePlaylistLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guards for playlist link import (no Android, no network):
 * link detection must accept every share-link shape and reject the rest.
 * (parseSpotifyEmbed stays untested here — org.json is a stub on JVM.)
 */
class PlaylistImportTest {

    @Test
    fun youtube_watchAndShareLinks() {
        val a = parsePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef")
        assertTrue(a is ImportLink.YouTube)
        assertEquals("PL1234567890abcdef", (a as ImportLink.YouTube).listId)
        val b = parsePlaylistLink("https://youtu.be/dQw4w9WgXcQ?list=PLxyz_ABC-123")
        assertEquals("PLxyz_ABC-123", (b as ImportLink.YouTube).listId)
        val c = parsePlaylistLink("https://music.youtube.com/playlist?list=OLAK5uy_test12")
        assertEquals("OLAK5uy_test12", (c as ImportLink.YouTube).listId)
    }

    @Test
    fun spotify_openLinks() {
        val a = parsePlaylistLink("https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF?si=abc")
        assertTrue(a is ImportLink.Spotify)
        assertEquals("37i9dQZEVXbMDoHDwVN2tF", (a as ImportLink.Spotify).playlistId)
        val b = parsePlaylistLink("https://open.spotify.com/intl-de/playlist/37i9dQZEVXbMDoHDwVN2tF")
        assertEquals("37i9dQZEVXbMDoHDwVN2tF", (b as ImportLink.Spotify).playlistId)
    }

    @Test
    fun rejectsNonPlaylistLinks() {
        assertNull(parsePlaylistLink(""))
        assertNull(parsePlaylistLink("hello world"))
        assertNull(parsePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parsePlaylistLink("https://open.spotify.com/track/4HCOHRCSQelz2GPX8iTamO"))
        assertNull(parsePlaylistLink("https://open.spotify.com/playlist/short"))
    }
}
