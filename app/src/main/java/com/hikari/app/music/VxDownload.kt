package com.hikari.app.music

import android.content.Context
import android.os.Environment
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * Offline downloads (VxMusic-style, login-free).
 * Saves to Music/HikariMusic with safe file names + .lrc sidecar when available.
 */
object VxDownload {
    private const val TAG = "HikariVxDownload"

    fun safeName(s: String): String {
        var n = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (n.length > 80) n = n.take(80).trim()
        return n.ifBlank { "track" }
    }

    suspend fun downloadSong(ctx: Context, song: MSong): Result<File> = withContext(Dispatchers.IO) {
        try {
            val ctxApp = ctx.applicationContext
            val q = VxQuality.downloadQuality(ctxApp)
            // 1. Try lossless first (unless user forced YouTube).
            var url: String? = null
            var ext = "m4a"
            if (q != VxQuality.YOUTUBE) {
                val vx = runCatching {
                    VxLosslessApi.resolveStreamBlocking(song.title, song.artist, song.durationSec.takeIf { it > 0 }, song.album.takeIf { it.isNotBlank() }, q)
                }.getOrNull()
                if (vx != null) {
                    url = vx.url
                    ext = if (vx.mimeType.contains("mp3", true)) "mp3" else "flac"
                }
            }
            // 2. Fallback: existing YouTube audio (keeps remix/slowed playable).
            if (url.isNullOrBlank()) {
                url = runCatching { MusicApi.resolveStreamBlocking(song.id, song) }.getOrNull()
                ext = "m4a"
            }
            val finalUrl = url ?: return@withContext Result.failure(Exception("No stream for this song"))
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "HikariMusic")
            dir.mkdirs()
            val file = File(dir, safeName(song.artist + " - " + song.title) + "." + ext)
            val ok = Http.downloadTo(finalUrl, file, mapOf("User-Agent" to MusicApi.userAgentFor(finalUrl)))
            if (!ok || !file.exists()) return@withContext Result.failure(Exception("Download failed"))
            // Lyrics sidecar.
            runCatching {
                val lyrics = MusicApi.lyrics(song)
                val lrc = lyrics?.synced
                if (!lrc.isNullOrEmpty()) {
                    val sb = StringBuilder()
                    for (l in lrc) {
                        val m = l.ms / 60000
                        val s = (l.ms % 60000) / 1000.0
                        sb.append("[%02d:%05.2f]%s\n".format(m, s, l.text))
                    }
                    File(dir, file.nameWithoutExtension + ".lrc").writeText(sb.toString())
                } else if (!lyrics?.plain.isNullOrBlank()) {
                    File(dir, file.nameWithoutExtension + ".lrc").writeText(lyrics!!.plain)
                }
            }
            Logs.log(TAG, "saved " + file.name)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
