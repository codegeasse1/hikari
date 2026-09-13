package com.hikari.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hikari.app.net.Http
import com.hikari.app.net.PlayerHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when the user pauses/cancels — the partially downloaded work dir is
 *  left intact so a later resume can continue it. */
class DownloadCancelledException : IOException("Download cancelled")

data class DlProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val durationMs: Long,
    val doneDurationMs: Long,
)

data class DlResult(
    /** On-disk path used for in-app offline playback (null for EXPORT-only). */
    val localPath: String?,
    /** Where an exported copy landed (content:// URI on API 29+, else a path). */
    val savedUri: String?,
)

/**
 * Fetches a stream to local storage.
 *
 * HLS is rebuilt as a *local* bundle rather than fetched piecemeal at playback
 * time: every segment is downloaded (AES-128 segments decrypted with their own
 * key, `EXT-X-BYTERANGE` segments fetched with a Range header, fMP4 init
 * segments via `EXT-X-MAP`), then a VOD media playlist and a master playlist
 * referencing them by local file name are written next to the files. media3
 * then plays `index.m3u8` off local storage — no network, no CDN, no expiry.
 *
 * Single-file sources are streamed straight to disk with a Range-resumed
 * append, so interrupting a big file and resuming never re-downloads the part
 * already on disk.
 *
 * EXPORT additionally concatenates the downloaded parts into one playable file
 * (`.ts` / `.mp4`) and copies it into the phone's Downloads/Hikari folder via
 * MediaStore; an fMP4 stream with a separate audio rendition (which cannot be
 * concatenated into a valid single file) is instead exported as its bundle
 * folder so the local playlist still resolves.
 */
object DownloadEngine {

    private const val BUFFER = 1 shl 16
    private const val MAX_MEM_SEGMENT = 96L * 1024 * 1024

    suspend fun run(
        ctx: Context,
        task: DownloadTask,
        workDir: File,
        onProgress: suspend (DlProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): DlResult = withContext(Dispatchers.IO) {
        if (!task.resumePartial) runCatching { workDir.deleteRecursively() }
        workDir.mkdirs()
        val ua = task.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
        val headers = task.headers
        val isHls = task.isM3u8 || task.url.substringBefore('?').lowercase().contains(".m3u8")

        if (isHls) {
            val res = downloadHls(task.url, headers, ua, workDir, Reporter(onProgress), isCancelled)
            if (task.kind == DownloadKind.EXPORT) {
                val saved = exportHls(ctx, task, workDir, res)
                runCatching { workDir.deleteRecursively() }
                DlResult(null, saved)
            } else {
                DlResult(res.entry.absolutePath, null)
            }
        } else {
            val file = downloadDirect(task, headers, ua, workDir, Reporter(onProgress), isCancelled)
            if (task.kind == DownloadKind.EXPORT) {
                val name = task.fileBaseName() + "." + file.extension.ifBlank { "mp4" }
                val saved = writeToDownloads(ctx, file, name, mimeForName(name), "")
                runCatching { workDir.deleteRecursively() }
                DlResult(null, saved)
            } else {
                DlResult(file.absolutePath, null)
            }
        }
    }

    /** Aggregates the video + (optional) audio rendition into one task-wide
     *  progress reading. */
    private class Reporter(private val onProgress: suspend (DlProgress) -> Unit) {
        private var vBytes = 0L
        private var vTotal = -1L
        private var vDurMs = 0L
        private var vTotalDurMs = 0L
        private var aBytes = 0L
        private var aTotal = -1L
        private var aDurMs = 0L
        private var aTotalDurMs = 0L
        private var hasAudio = false

        /** Byte callbacks fire every 64 KiB; without this the UI (and the
         *  DataStore-backed task list) would be rewritten hundreds of times per
         *  segment. 4 updates/second is plenty for a progress bar. */
        private var lastEmit = 0L

        suspend fun video(bytes: Long, total: Long, durMs: Long, totalDurMs: Long) {
            vBytes = bytes
            if (total > 0) vTotal = total
            vDurMs = durMs
            vTotalDurMs = totalDurMs
            emit()
        }

        suspend fun audio(bytes: Long, total: Long, durMs: Long, totalDurMs: Long) {
            hasAudio = true
            aBytes = bytes
            if (total > 0) aTotal = total
            aDurMs = durMs
            aTotalDurMs = totalDurMs
            emit()
        }

        private suspend fun emit() {
            val now = System.currentTimeMillis()
            if (now - lastEmit < 250L) return
            lastEmit = now
            val done = vBytes + aBytes
            val total = when {
                hasAudio && aTotal > 0 -> vTotal + aTotal
                hasAudio -> -1L
                else -> vTotal
            }
            onProgress(DlProgress(done, total, vTotalDurMs + aTotalDurMs, vDurMs + aDurMs))
        }
    }

    // ------------------------------------------------------------------ HLS --

    private class Rendition(
        val playlistName: String,
        val durationMs: Long,
        val fmp4: Boolean,
        val parts: List<File>,
    )

    private class HlsResult(
        val entry: File,
        val videoParts: List<File>,
        val audioParts: List<File>,
        val initPart: File?,
        val fmp4: Boolean,
        val durationMs: Long,
    )

    private suspend fun downloadHls(
        masterUrl: String,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        isCancelled: () -> Boolean,
    ): HlsResult {
        val (text, finalUrl) = fetchText(masterUrl, headers, ua)
        val top = parsePlaylist(text, finalUrl)

        val videoUrl: String
        var audioUrl: String? = null
        if (top.isMaster) {
            val variants = top.variants.ifEmpty { throw IOException("HLS master has no variants") }
            val best = variants.maxByOrNull { it.bandwidth } ?: variants.first()
            videoUrl = best.url
            best.audioGroup
                ?.let { g -> top.audios.firstOrNull { it.group == g } }
                ?.let { audioUrl = it.url }
        } else {
            videoUrl = finalUrl
        }

        val video = downloadRendition("v", videoUrl, headers, ua, workDir, reporter, true, isCancelled)

        var audio: Rendition? = null
        if (audioUrl != null) {
            if (isCancelled()) throw DownloadCancelledException()
            audio = downloadRendition("a", audioUrl, headers, ua, workDir, reporter, false, isCancelled)
        }

        // Master playlist referencing the local media playlists. Written even
        // for a single rendition: the local index.m3u8 is the stable entry point
        // that both the offline copy and any bundle export point at.
        val bandwidth = top.variants.maxByOrNull { it.bandwidth }?.bandwidth ?: 0L
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        if (audio != null) {
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",")
                .append("DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(audio.playlistName).append("\"\n")
        }
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(if (bandwidth > 0) bandwidth else 2_000_000L)
        if (audio != null) sb.append(",AUDIO=\"aud\"")
        sb.append('\n').append(video.playlistName).append('\n')
        val entry = File(workDir, "index.m3u8")
        entry.writeText(sb.toString())

        val initPart = workDir.listFiles()?.firstOrNull { it.name == "init_v" + extFor(video.fmp4) }
        return HlsResult(
            entry = entry,
            videoParts = video.parts,
            audioParts = audio?.parts.orEmpty(),
            initPart = initPart,
            fmp4 = video.fmp4,
            durationMs = maxOf(video.durationMs, audio?.durationMs ?: 0L),
        )
    }

    private suspend fun downloadRendition(
        prefix: String,
        playlistUrl: String,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        isVideo: Boolean,
        isCancelled: () -> Boolean,
    ): Rendition {
        val (text, finalUrl) = fetchText(playlistUrl, headers, ua)
        var pl = parsePlaylist(text, finalUrl)
        if (pl.isMaster) {
            val best = pl.variants.maxByOrNull { it.bandwidth } ?: pl.variants.firstOrNull()
                ?: throw IOException("HLS master has no variants")
            val (t2, u2) = fetchText(best.url, headers, ua)
            pl = parsePlaylist(t2, u2)
        }
        val segs = pl.segments
        if (segs.isEmpty()) throw IOException("HLS playlist has no segments")

        val fmp4 = segs.any { it.mapUrl != null }
        val ext = extFor(fmp4)
        val totalDurMs = (segs.sumOf { it.duration } * 1000.0).toLong()

        // fMP4 init segment (#EXT-X-MAP) — required before any fragment.
        var initFile: File? = null
        segs.firstOrNull { it.mapUrl != null }?.mapUrl?.let { mapUrl ->
            val f = File(workDir, "init_$prefix$ext")
            if (!(f.exists() && f.length() > 0)) {
                downloadToFile(mapUrl, headers, ua, f, null, false, { _, _ -> }, isCancelled)
            }
            initFile = f
        }

        val keyCache = HashMap<String, ByteArray>()
        val names = ArrayList<String>(segs.size)
        val parts = ArrayList<File>(segs.size)
        var doneDur = 0.0
        var doneDurMs = 0L
        var bytes = 0L

        segs.forEachIndexed { i, seg ->
            if (isCancelled()) throw DownloadCancelledException()
            val name = prefix + "_" + i.toString().padStart(5, '0') + ext
            val f = File(workDir, name)
            if (!(f.exists() && f.length() > 0)) {
                val key = seg.key
                val method = key?.method.orEmpty()
                if (method.isBlank() || method.equals("NONE", true)) {
                    downloadToFile(
                        seg.url, headers, ua, f, seg.byteRange, false,
                        onBytes = { done, _ ->
                            bytes = done
                            reportRendition(reporter, isVideo, bytes, doneDurMs, totalDurMs)
                        },
                        isCancelled = isCancelled,
                    )
                } else if (method.equals("AES-128", true)) {
                    val k = key ?: throw IOException("Encrypted segment without a key")
                    val keyBytes = keyCache.getOrPut(k.uri) { fetchBytes(k.uri, headers, ua) }
                    val enc = fetchBytes(seg.url, headers, ua, seg.byteRange)
                    val iv = k.iv ?: ivFor(pl.mediaSequence + i)
                    f.writeBytes(decryptAes128(enc, keyBytes, iv))
                    bytes = f.length()
                    reportRendition(reporter, isVideo, bytes, doneDurMs, totalDurMs)
                } else {
                    throw IOException("Unsupported HLS encryption: $method")
                }
            }
            names.add(name)
            parts.add(f)
            // Progress is driven by the playlist's DURATIONS (byte totals are
            // unknown for HLS) — see DownloadTask.progress.
            doneDur += seg.duration
            doneDurMs = (doneDur * 1000.0).toLong()
            reportRendition(reporter, isVideo, bytes, doneDurMs, totalDurMs)
        }

        val playlistName = prefix + "_0.m3u8"
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(segs, pl.targetDuration)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        initFile?.let { sb.append("#EXT-X-MAP:URI=\"").append(it.name).append("\"\n") }
        segs.forEachIndexed { i, seg ->
            sb.append("#EXTINF:").append(fmtDuration(seg.duration)).append(",\n")
            sb.append(names[i]).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        File(workDir, playlistName).writeText(sb.toString())

        return Rendition(playlistName, totalDurMs, fmp4, parts)
    }

    private suspend fun reportRendition(
        reporter: Reporter,
        isVideo: Boolean,
        bytes: Long,
        doneDurMs: Long,
        totalDurMs: Long,
    ) {
        if (isVideo) reporter.video(bytes, -1L, doneDurMs, totalDurMs)
        else reporter.audio(bytes, -1L, doneDurMs, totalDurMs)
    }

    // --------------------------------------------------------------- Direct --

    private suspend fun downloadDirect(
        task: DownloadTask,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        isCancelled: () -> Boolean,
    ): File {
        val rawPath = task.url.substringBefore('?').substringBefore('#')
        val ext = rawPath.substringAfterLast('.', "")
            .takeIf { it.length in 1..4 && it.all { c -> c.isLetterOrDigit() } }
            ?: "mp4"
        val file = File(workDir, "video.$ext")
        downloadToFile(
            task.url, headers, ua, file, null, resume = true,
            onBytes = { done, total -> reporter.video(done, total, 0L, 0L) },
            isCancelled = isCancelled,
        )
        return file
    }

    private suspend fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        ua: String,
        file: File,
        byteRange: Pair<Long, Long>?,
        resume: Boolean,
        onBytes: suspend (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val startAt = if (resume && byteRange == null && file.exists()) file.length() else 0L
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        if (byteRange != null) {
            req.header("Range", "bytes=${byteRange.first}-${byteRange.second}")
        } else if (startAt > 0L) {
            req.header("Range", "bytes=$startAt-")
        }
        PlayerHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            val append = byteRange == null && startAt > 0L && resp.code == 206
            if (!append && byteRange == null && file.exists()) file.delete()
            val avail = body.contentLength()
            val total = when {
                byteRange != null -> byteRange.second - byteRange.first + 1
                avail > 0 && append -> startAt + avail
                avail > 0 -> avail
                else -> -1L
            }
            val cap = if (byteRange != null) byteRange.second - byteRange.first + 1 else Long.MAX_VALUE
            var written = if (append) startAt else 0L
            FileOutputStream(file, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val room = (cap - written).coerceAtMost(buf.size.toLong()).toInt()
                        if (room <= 0) break
                        val n = input.read(buf, 0, room)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBytes(written, total)
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- Export --

    private fun exportHls(ctx: Context, task: DownloadTask, workDir: File, res: HlsResult): String {
        val base = task.fileBaseName()

        // MPEG-TS (plain HLS): video + audio segments concatenate into one
        // valid transport stream that any player reads.
        if (!res.fmp4) {
            val out = File(workDir, "export.ts")
            FileOutputStream(out).use { os ->
                res.videoParts.forEach { p -> p.inputStream().use { it.copyTo(os, BUFFER) } }
                res.audioParts.forEach { p -> p.inputStream().use { it.copyTo(os, BUFFER) } }
            }
            return writeToDownloads(ctx, out, "$base.ts", "video/mp2t", "")
        }

        // fMP4 with muxed audio: init + fragments concatenate into one .mp4.
        if (res.audioParts.isEmpty() && res.initPart != null) {
            val out = File(workDir, "export.mp4")
            FileOutputStream(out).use { os ->
                res.initPart.inputStream().use { it.copyTo(os, BUFFER) }
                res.videoParts.forEach { p -> p.inputStream().use { it.copyTo(os, BUFFER) } }
            }
            return writeToDownloads(ctx, out, "$base.mp4", "video/mp4", "")
        }

        // fMP4 with a SEPARATE audio track can't be concatenated into one valid
        // file — export the whole local bundle (playlists + parts) as a folder
        // so the local index.m3u8 still resolves.
        val folder = sanitizeFile(base).ifBlank { "hikari-video" }
        var entryUri = ""
        workDir.listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
            if (f.isFile) {
                val uri = writeToDownloads(ctx, f, f.name, mimeForName(f.name), folder)
                if (f.name == "index.m3u8") entryUri = uri
            }
        }
        return entryUri.ifBlank { "Downloads/Hikari/$folder/index.m3u8" }
    }

    private fun writeToDownloads(
        ctx: Context,
        src: File,
        displayName: String,
        mime: String,
        subPath: String,
    ): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = buildString {
                append(Environment.DIRECTORY_DOWNLOADS).append("/Hikari")
                if (subPath.isNotBlank()) append('/').append(subPath)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create Downloads entry")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, BUFFER) }
            } ?: throw IOException("Could not open Downloads entry")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            return uri.toString()
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            if (subPath.isBlank()) "Hikari" else "Hikari/$subPath",
        ).apply { mkdirs() }
        val dest = uniqueFile(dir, displayName)
        src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, BUFFER) } }
        runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null) }
        return dest.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 500) {
            f = File(dir, "$stem ($i)$ext")
            i++
        }
        return f
    }

    private fun mimeForName(name: String): String = when {
        name.endsWith(".m3u8", true) -> "application/vnd.apple.mpegurl"
        name.endsWith(".ts", true) -> "video/mp2t"
        name.endsWith(".mp4", true) || name.endsWith(".m4s", true) -> "video/mp4"
        name.endsWith(".vtt", true) -> "text/vtt"
        else -> "application/octet-stream"
    }

    // -------------------------------------------------------------- Playlist --

    private class Variant(val url: String, val bandwidth: Long, val audioGroup: String?)
    private class AudioRendition(val group: String, val name: String, val url: String)
    private class KeyInfo(val method: String, val uri: String, val iv: ByteArray?)
    private class Segment(
        val url: String,
        val duration: Double,
        val byteRange: Pair<Long, Long>?,
        val key: KeyInfo?,
        val mapUrl: String?,
    )
    private class Playlist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        val audios: List<AudioRendition>,
        val segments: List<Segment>,
        val targetDuration: Int,
        val mediaSequence: Int,
    )

    /**
     * Minimal HLS parser — deliberately our own rather than media3's, so the
     * download path never depends on internal parser APIs. Handles the tags that
     * matter for delivering a byte-exact offline copy: variants, audio
     * renditions, byte ranges, AES-128 keys and fMP4 init maps.
     */
    private fun parsePlaylist(text: String, baseUrl: String): Playlist {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val variants = ArrayList<Variant>()
        val audios = ArrayList<AudioRendition>()
        val segments = ArrayList<Segment>()
        var sawMasterTag = false
        var targetDur = 0
        var mediaSeq = 0
        var pendingStreamInf: Map<String, String>? = null
        var pendingKey: KeyInfo? = null
        var pendingMap: String? = null
        var pendingByteRange: Pair<Long, Long>? = null
        var pendingDur = 0.0
        var byteRangeCursor = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    sawMasterTag = true
                    pendingStreamInf = parseAttrs(line.substringAfter(':', ""))
                }
                // Exact tag name — a bare startsWith("#EXT-X-MEDIA") would also
                // swallow #EXT-X-MEDIA-SEQUENCE (which must be parsed as its own
                // tag, or the AES-128 IV default would be wrong).
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    val uri = a["URI"]
                    if (a["TYPE"].equals("AUDIO", true) && !uri.isNullOrBlank()) {
                        audios.add(AudioRendition(a["GROUP-ID"].orEmpty(), a["NAME"].orEmpty(), resolve(baseUrl, uri)))
                    }
                }
                line.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDur = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSeq = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("#EXTINF") ->
                    pendingDur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE") -> {
                    val v = line.substringAfter(':').trim()
                    val len = v.substringBefore('@').toLongOrNull() ?: 0L
                    // No explicit offset = continue right after the previous
                    // range (RFC 8216).
                    val off = if (v.contains('@')) {
                        v.substringAfter('@').toLongOrNull() ?: byteRangeCursor
                    } else {
                        byteRangeCursor
                    }
                    if (len > 0) {
                        pendingByteRange = off to (off + len - 1)
                        byteRangeCursor = off + len
                    }
                }
                line.startsWith("#EXT-X-KEY") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    val method = a["METHOD"].orEmpty()
                    pendingKey = if (method.isBlank() || method.equals("NONE", true)) {
                        null
                    } else {
                        KeyInfo(method, resolve(baseUrl, a["URI"].orEmpty()), parseIv(a["IV"]))
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    pendingMap = a["URI"]?.let { resolve(baseUrl, it) }
                }
                line.startsWith("#") -> {}
                else -> {
                    val si = pendingStreamInf
                    if (si != null) {
                        val bw = si["BANDWIDTH"]?.toLongOrNull()
                            ?: si["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                        variants.add(Variant(resolve(baseUrl, line), bw, si["AUDIO"]))
                        pendingStreamInf = null
                    } else {
                        segments.add(Segment(resolve(baseUrl, line), pendingDur, pendingByteRange, pendingKey, pendingMap))
                        pendingDur = 0.0
                        pendingByteRange = null
                    }
                }
            }
        }
        return Playlist(
            isMaster = sawMasterTag && segments.isEmpty(),
            variants = variants,
            audios = audios,
            segments = segments,
            targetDuration = targetDur,
            mediaSequence = mediaSeq,
        )
    }

    /** `KEY=VAL,KEY2="quoted, with comma"` → map. */
    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < n && s[j] == '"') {
                val end = s.indexOf('"', j + 1)
                if (end < 0) {
                    value = s.substring(j + 1)
                    i = n
                } else {
                    value = s.substring(j + 1, end)
                    i = end + 1
                }
            } else {
                var end = s.indexOf(',', j)
                if (end < 0) end = n
                value = s.substring(j, end).trim()
                i = end
            }
            if (key.isNotEmpty()) out[key] = value
            if (i < n && s[i] == ',') i++
        }
        return out
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref
    }

    private fun parseIv(s: String?): ByteArray? {
        if (s.isNullOrBlank()) return null
        val hex = s.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) {
                ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }

    /** Default AES-128 IV per RFC 8216: the segment's media sequence number as
     *  a 16-byte big-endian integer. */
    private fun ivFor(seq: Int): ByteArray {
        val iv = ByteArray(16)
        var v = seq.toLong()
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return iv
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ----------------------------------------------------------------- HTTP --

    private fun applyHeaders(req: Request.Builder, headers: Map<String, String>, ua: String) {
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("User-Agent", true)) req.header(k, v)
        }
        req.header("User-Agent", ua)
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        ua: String,
    ): Pair<String, String> {
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        PlayerHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response body")
            return body to resp.request.url.toString()
        }
    }

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        ua: String,
        byteRange: Pair<Long, Long>? = null,
    ): ByteArray {
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        if (byteRange != null) req.header("Range", "bytes=${byteRange.first}-${byteRange.second}")
        PlayerHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            val len = body.contentLength()
            if (len > MAX_MEM_SEGMENT) throw IOException("Segment too large to buffer")
            val bytes = body.bytes()
            return if (byteRange != null && resp.code == 206) {
                val want = (byteRange.second - byteRange.first + 1).toInt()
                if (bytes.size > want) bytes.copyOf(want) else bytes
            } else {
                bytes
            }
        }
    }

    // --------------------------------------------------------------- Helpers --

    private fun extFor(fmp4: Boolean): String = if (fmp4) ".m4s" else ".ts"

    private fun targetDuration(segs: List<Segment>, declared: Int): Int {
        val max = segs.maxOfOrNull { it.duration } ?: 0.0
        return maxOf(declared, kotlin.math.ceil(max).toInt(), 1)
    }

    private fun fmtDuration(d: Double): String {
        val rounded = kotlin.math.round(d * 1000.0).toLong()
        return if (rounded % 1000 == 0L) (rounded / 1000).toString()
        else String.format(java.util.Locale.US, "%.3f", d)
    }
}
