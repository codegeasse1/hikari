package com.hikari.desktop.playback

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamLauncherTest {
    @Test
    fun prefersVlcWhenBothPlayersAreOnPath() {
        val directory = Files.createTempDirectory("hikari-player path ").toFile()
        try {
            val vlc = directory.resolve("vlc.exe").apply { writeText("") }
            directory.resolve("mpv.exe").writeText("")

            assertEquals(vlc.absoluteFile, StreamLauncher.findPlayer(listOf(directory), windows = true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToMpvWhenVlcIsMissing() {
        val directory = Files.createTempDirectory("hikari-player").toFile()
        try {
            val mpv = directory.resolve("mpv.exe").apply { writeText("") }

            assertEquals(mpv.absoluteFile, StreamLauncher.findPlayer(listOf(directory), windows = true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun returnsNoPlayerWhenNeitherIsInstalled() {
        val directory = Files.createTempDirectory("hikari-player").toFile()
        try {
            assertNull(StreamLauncher.findPlayer(listOf(directory), windows = true))
        } finally {
            directory.deleteRecursively()
        }
    }
}
