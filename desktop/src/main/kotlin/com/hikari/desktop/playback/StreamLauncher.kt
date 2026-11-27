package com.hikari.desktop.playback

import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.StreamKind
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI

object StreamLauncher {
    fun open(stream: AddonStream): String {
        if (stream.kind == StreamKind.DIRECT) {
            val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
            val player = findPlayer(searchDirectories(isWindows), isWindows)
            if (player != null) {
                runCatching { ProcessBuilder(player.absolutePath, stream.url).start() }
                    .onSuccess { return player.nameWithoutExtension.uppercase() }
            }
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw IOException("This computer cannot open a browser from Hikari.")
        }
        Desktop.getDesktop().browse(URI(stream.url))
        return "browser"
    }

    internal fun findPlayer(directories: Iterable<File>, windows: Boolean): File? {
        val names = if (windows) listOf("vlc.exe", "mpv.exe") else listOf("vlc", "mpv")
        for (name in names) {
            for (directory in directories) {
                val candidate = File(directory, name)
                if (candidate.isFile && (windows || candidate.canExecute())) return candidate.absoluteFile
            }
        }
        return null
    }

    private fun searchDirectories(windows: Boolean): List<File> = buildList {
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .mapTo(this, ::File)
        if (windows) {
            System.getenv("ProgramFiles")?.let { add(File(it, "VideoLAN/VLC")) }
            System.getenv("ProgramFiles(x86)")?.let { add(File(it, "VideoLAN/VLC")) }
            System.getenv("LOCALAPPDATA")?.let { local ->
                add(File(local, "Programs/mpv"))
                add(File(local, "Programs/VLC"))
            }
        }
    }
}
