package com.hikari.app.dub

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Receives the original sound of the video as 16 kHz mono, in order. */
internal interface PcmSink {
    /** [n] samples (16 kHz, mono) that belong at sample number [at] of the video's timeline. */
    fun put(at: Long, pcm: ShortArray, n: Int)
}

/**
 * Plain HTTP for the voice scan. It runs in the helper process, which starts
 * none of the app's own network code, so it only uses what Android has.
 */
internal object ScanHttp {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /** Opens [url] (following redirects by hand); null when the server does not answer 200 / 206. */
    fun open(url: String, headers: Map<String, String>): HttpURLConnection? {
        var u = url
        var hops = 0
        while (hops < 6) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            var hasUa = false
            for ((k, v) in headers) {
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                conn.setRequestProperty(k, v)
            }
            if (!hasUa) conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) return null
                u = URL(URL(u), next).toString()
                hops++
                continue
            }
            if (code == 200 || code == 206) return conn
            conn.disconnect()
            return null
        }
        return null
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        input.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** A small file as bytes; null when it fails or is bigger than [max] bytes. */
    fun bytes(url: String, headers: Map<String, String>, max: Int): ByteArray? {
        return try {
            val conn = open(url, headers) ?: return null
            try {
                readCapped(conn.inputStream, max)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** A small text file (a playlist); gzip is unpacked when the server sent it without saying so. */
    fun text(url: String, headers: Map<String, String>, max: Int = 2_000_000): String? {
        val b = bytes(url, headers, max) ?: return null
        return try {
            val plain = if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
                GZIPInputStream(b.inputStream()).readBytes()
            } else {
                b
            }
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /** Saves [url] into [out], after the bytes in [prefix] when given. False when it fails. */
    fun toFile(
        url: String,
        headers: Map<String, String>,
        prefix: ByteArray?,
        out: File,
        stop: () -> Boolean,
    ): Boolean {
        return try {
            val conn = open(url, headers) ?: return false
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fos ->
                        if (prefix != null) fos.write(prefix)
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (stop()) return false
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * Decodes the first audio track of whatever [open] points the extractor at
 * (a file, or a web address) and hands it on as 16 kHz mono, block by block.
 * Works much faster than the video plays: nothing is shown or heard.
 */
internal object AudioDecode {

    /** True when some sound was delivered. */
    fun run(
        open: (MediaExtractor) -> Unit,
        baseSample: Long,
        sink: PcmSink,
        stop: () -> Boolean,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var produced = 0L
        try {
            open(extractor)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return false
            val mimeType = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            extractor.selectTrack(track)

            val dec = MediaCodec.createDecoderByType(mimeType)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            var rate = intOf(fmt, MediaFormat.KEY_SAMPLE_RATE, 48000)
            var channels = intOf(fmt, MediaFormat.KEY_CHANNEL_COUNT, 2)
            if (channels < 1) channels = 1
            var floatPcm = false
            var resampler = Resampler16k(rate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPts = -1L
            var mono = FloatArray(8192)
            var pcm16 = ShortArray(4096)
            var idle = 0

            while (!outputDone) {
                if (stop()) break
                var progressed = false
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        val n = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    progressed = true
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                        if (frames > 0) {
                            if (mono.size < frames) mono = FloatArray(frames + 4096)
                            readMono(ob, floatPcm, channels, mono, frames)
                            val cap = resampler.maxOut(frames)
                            if (pcm16.size < cap) pcm16 = ShortArray(cap + 4096)
                            val got = resampler.process(mono, frames, pcm16)
                            val pts = info.presentationTimeUs
                            if (firstPts < 0L) firstPts = pts
                            var at = baseSample + produced
                            val want = baseSample + (pts - firstPts) * 16L / 1000L
                            // a hole of more than half a second in the sound: keep the timeline right
                            if (want - at > 8000L) at = want
                            if (got > 0) sink.put(at, pcm16, got)
                            produced = (at - baseSample) + got
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    val nf = dec.outputFormat
                    rate = intOf(nf, MediaFormat.KEY_SAMPLE_RATE, rate)
                    channels = intOf(nf, MediaFormat.KEY_CHANNEL_COUNT, channels)
                    if (channels < 1) channels = 1
                    floatPcm = intOf(nf, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                        AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler16k(rate)
                }
                if (progressed) {
                    idle = 0
                } else {
                    idle++
                    if (idle > 2000) break
                }
            }
            return produced > 0L
        } catch (t: Throwable) {
            return produced > 0L
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun intOf(f: MediaFormat, key: String, def: Int): Int =
        if (f.containsKey(key)) f.getInteger(key) else def

    private fun readMono(ob: ByteBuffer, floatPcm: Boolean, channels: Int, mono: FloatArray, frames: Int) {
        val tmp = FloatArray(channels)
        if (floatPcm) {
            val fb = ob.asFloatBuffer()
            for (f in 0 until frames) {
                for (c in 0 until channels) tmp[c] = fb.get()
                mono[f] = mixDown(tmp, channels)
            }
        } else {
            val sb = ob.asShortBuffer()
            for (f in 0 until frames) {
                for (c in 0 until channels) tmp[c] = sb.get() * (1f / 32768f)
                mono[f] = mixDown(tmp, channels)
            }
        }
    }

    /** One mono value from one frame: the centre channel carries the dialogue in 5.1 / 7.1. */
    private fun mixDown(t: FloatArray, ch: Int): Float {
        if (ch == 1) return t[0]
        if (ch == 2) return (t[0] + t[1]) * 0.5f
        if (ch >= 6) return 0.6f * t[2] + 0.2f * (t[0] + t[1])
        var s = 0f
        for (i in 0 until ch) s += t[i]
        return s / ch
    }
}

internal class HlsSeg(val url: String, val startSec: Double, val durSec: Double, val init: String?)

internal class HlsPlan(val segments: List<HlsSeg>)

/**
 * Reads the sound of an HLS (.m3u8) video: takes the audio-only list when the
 * video has one, otherwise the smallest picture quality (the sound is the
 * same), downloads only the pieces that hold subtitle lines (four at a time)
 * and decodes each piece on its own. Encrypted lists are not supported.
 */
internal object Hls {

    private val BANDWIDTH = Regex("BANDWIDTH=(\\d+)")

    private fun attr(line: String, key: String): String? {
        val m = Regex("(?:^|[,:])" + key + "=(\"[^\"]*\"|[^,]*)").find(line) ?: return null
        return m.groupValues[1].trim().trim('"')
    }

    private fun resolve(base: String, ref: String): String =
        try {
            URL(URL(base), ref).toString()
        } catch (t: Throwable) {
            ref
        }

    /** The playlist of the pieces, or null (not a playlist, encrypted, or unreachable). */
    fun plan(url: String, headers: Map<String, String>): HlsPlan? {
        var cur = url
        for (attempt in 0 until 3) {
            val text = ScanHttp.text(cur, headers) ?: return null
            if (!text.trimStart().startsWith("#EXTM3U")) return null
            if (text.contains("#EXT-X-STREAM-INF")) {
                cur = pickFromMaster(text, cur) ?: return null
            } else {
                return media(text, cur)
            }
        }
        return null
    }

    private fun pickFromMaster(text: String, base: String): String? {
        val lines = text.lines()
        var audioUri: String? = null
        var audioDefault = false
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-MEDIA") && attr(l, "TYPE") == "AUDIO") {
                val u = attr(l, "URI") ?: continue
                val def = attr(l, "DEFAULT") == "YES"
                if (audioUri == null || (def && !audioDefault)) {
                    audioUri = u
                    audioDefault = def
                }
            }
        }
        if (audioUri != null) return resolve(base, audioUri)

        var bestBw = Long.MAX_VALUE
        var bestUri: String? = null
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = BANDWIDTH.find(l)?.groupValues?.get(1)?.toLongOrNull() ?: (Long.MAX_VALUE - 1)
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
            if (j < lines.size && bw < bestBw) {
                bestBw = bw
                bestUri = lines[j].trim()
            }
        }
        return bestUri?.let { resolve(base, it) }
    }

    private fun media(text: String, base: String): HlsPlan? {
        var seconds = 0.0
        var dur = 0.0
        var initUrl: String? = null
        val segs = ArrayList<HlsSeg>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                if (l.startsWith("#EXTINF:")) {
                    dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                } else if (l.startsWith("#EXT-X-MAP:")) {
                    if (attr(l, "BYTERANGE") != null) return null
                    initUrl = attr(l, "URI")?.let { resolve(base, it) }
                } else if (l.startsWith("#EXT-X-KEY:")) {
                    val method = attr(l, "METHOD")
                    if (method != null && method != "NONE") return null
                } else if (l.startsWith("#EXT-X-BYTERANGE")) {
                    return null
                }
                continue
            }
            segs.add(HlsSeg(resolve(base, l), seconds, dur, initUrl))
            seconds += dur
            dur = 0.0
        }
        if (segs.isEmpty()) return null
        return HlsPlan(segs)
    }

    private fun fetch(
        seg: HlsSeg,
        idx: Int,
        headers: Map<String, String>,
        dir: File,
        initCache: ConcurrentHashMap<String, ByteArray>,
        stop: () -> Boolean,
    ): File? {
        if (stop()) return null
        var prefix: ByteArray? = null
        val initUrl = seg.init
        if (initUrl != null) {
            prefix = initCache[initUrl]
            if (prefix == null) {
                prefix = ScanHttp.bytes(initUrl, headers, 4_000_000)
                if (prefix != null) initCache[initUrl] = prefix
            }
            if (prefix == null) return null
        }
        val f = File(dir, "seg$idx.bin")
        for (attempt in 0 until 2) {
            if (stop()) return null
            if (ScanHttp.toFile(seg.url, headers, prefix, f, stop)) return f
        }
        f.delete()
        return null
    }

    /**
     * Downloads and decodes the pieces for which [needed] (start second, end
     * second) says yes. True when at least one piece gave sound.
     */
    fun run(
        plan: HlsPlan,
        headers: Map<String, String>,
        needed: (Double, Double) -> Boolean,
        sink: PcmSink,
        tmpDir: File,
        stop: () -> Boolean,
    ): Boolean {
        val segs = plan.segments.filter { needed(it.startSec, it.startSec + it.durSec) }
        if (segs.isEmpty()) return false
        tmpDir.mkdirs()
        val pool = Executors.newFixedThreadPool(4)
        val initCache = ConcurrentHashMap<String, ByteArray>()
        val futures = ArrayList<Future<File?>>()
        var ok = 0
        try {
            var submitted = 0
            for (i in segs.indices) {
                if (stop()) break
                while (submitted < segs.size && submitted < i + 6) {
                    val idx = submitted
                    val s = segs[idx]
                    futures.add(pool.submit(Callable<File?> { fetch(s, idx, headers, tmpDir, initCache, stop) }))
                    submitted++
                }
                val file: File? = try {
                    futures[i].get(120, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    null
                }
                if (file == null) continue
                try {
                    val base = (segs[i].startSec * 16000.0).toLong()
                    val got = AudioDecode.run({ ex -> ex.setDataSource(file.absolutePath) }, base, sink, stop)
                    if (got) ok++
                } finally {
                    file.delete()
                }
            }
        } finally {
            pool.shutdownNow()
            tmpDir.listFiles()?.forEach { it.delete() }
        }
        return ok > 0
    }
}
