package com.hikari.app.dub

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Turns one piece of speech into a "voice fingerprint" (speaker embedding). */
internal interface Embedder {
    fun embed(pcm: FloatArray): FloatArray?
    fun close()
}

internal class ScanOutcome(val result: VoiceScanResult?, val note: String)

/**
 * Collects the 16 kHz sound as it arrives and measures every subtitle line as
 * soon as all of its sound is there (pitch, and the voice fingerprint when a
 * model is loaded). Sound that no line needs any more is thrown away, so a
 * whole film never sits in memory.
 */
internal class CueWindow(
    startsMs: LongArray,
    endsMs: LongArray,
    offsetMs: Long,
    private val embedder: Embedder?,
    private val onStep: (done: Int, total: Int) -> Unit,
) : PcmSink {

    private val total = startsMs.size

    /** Where each line is in the sound (sample numbers). A line is looked at for 12 s at most. */
    private val startS = LongArray(total) { max(0L, (startsMs[it] + offsetMs) * 16L) }
    private val endS = LongArray(total) {
        min(max(startS[it], (endsMs[it] + offsetMs) * 16L), startS[it] + MAX_LINE_SAMPLES)
    }

    /** Line numbers in the order the sound reaches them. */
    private val order: IntArray = (0 until total).sortedBy { startS[it] }.toIntArray()

    val f0 = FloatArray(total)
    val weight = IntArray(total)
    val emb: Array<FloatArray?> = arrayOfNulls<FloatArray>(total)

    var done = 0
        private set

    private var buf = ShortArray(16000 * 20)
    private var bufStart = 0L
    private var bufLen = 0
    private var next = 0

    override fun put(at: Long, pcm: ShortArray, n: Int) {
        if (n <= 0) return
        var from = 0
        var count = n
        val end = bufStart + bufLen
        if (at < end) {
            val skip = end - at
            if (skip >= count) return
            from = skip.toInt()
            count -= from
        } else if (at > end) {
            val gap = at - end
            if (gap <= 32000L) {
                // a short hole: fill it with silence so the timeline stays right
                val g = gap.toInt()
                ensure(bufLen + g + count)
                java.util.Arrays.fill(buf, bufLen, bufLen + g, 0.toShort())
                bufLen += g
            } else {
                // a long jump (pieces that no line needed were skipped)
                drain(at)
                bufStart = at
                bufLen = 0
            }
        }
        ensure(bufLen + count)
        System.arraycopy(pcm, from, buf, bufLen, count)
        bufLen += count
        drain(bufStart + bufLen)
        trim()
    }

    /** Called once when all sound has been delivered. */
    fun finish() {
        drain(Long.MAX_VALUE)
    }

    private fun ensure(cap: Int) {
        if (buf.size < cap) buf = buf.copyOf(max(cap, buf.size * 2))
    }

    private fun drain(upTo: Long) {
        while (next < total) {
            val i = order[next]
            if (endS[i] > upTo) break
            analyze(i)
            next++
            done++
            onStep(done, total)
        }
    }

    /** Keeps only the sound that a line which is still waiting can need. */
    private fun trim() {
        val end = bufStart + bufLen
        val keepFrom = if (next < total) startS[order[next]] else end
        val drop = keepFrom - bufStart
        if (drop <= 0L) return
        if (drop >= bufLen) {
            bufStart = end
            bufLen = 0
        } else {
            val d = drop.toInt()
            System.arraycopy(buf, d, buf, 0, bufLen - d)
            bufStart += d
            bufLen -= d
        }
    }

    private fun analyze(i: Int) {
        val from = max(startS[i], bufStart)
        val to = min(endS[i], bufStart + bufLen)
        val len = (to - from).toInt()
        if (len < MIN_SAMPLES) return
        val off = (from - bufStart).toInt()

        // only music / silence under this line: nothing to measure
        var sum = 0.0
        var cnt = 0
        var k = 0
        while (k < len) {
            val v = buf[off + k].toDouble()
            sum += v * v
            cnt++
            k += 4
        }
        val rms = sqrt(sum / max(1, cnt)) / 32768.0
        if (rms < 0.003) return

        val p = PitchTrack.analyze(buf, off, len)
        if (p.hz > 0f) {
            f0[i] = p.hz
            weight[i] = p.voiced
        }
        val e = embedder
        if (e != null && len >= MIN_EMBED_SAMPLES) {
            val m = min(len, MAX_EMBED_SAMPLES)
            val x = FloatArray(m)
            for (q in 0 until m) x[q] = buf[off + q] / 32768f
            val v = try {
                e.embed(x)
            } catch (t: Throwable) {
                null
            }
            if (v != null) emb[i] = normalised(v)
        }
    }

    private fun normalised(v: FloatArray): FloatArray? {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n.isNaN() || n < 1e-6f) return null
        return FloatArray(v.size) { v[it] / n }
    }

    private companion object {
        const val MIN_SAMPLES = 8000            // 0.5 s: less than that cannot be measured
        const val MIN_EMBED_SAMPLES = 12800     // 0.8 s
        const val MAX_EMBED_SAMPLES = 128000    // 8 s
        const val MAX_LINE_SAMPLES = 192000L    // 12 s
    }
}

/**
 * Listens to the ORIGINAL sound of a video and works out, for every subtitle
 * line, who speaks it and whether that is a man or a woman. Runs in the voice
 * helper process (see [NeuralTtsService]). Time budget: [BUDGET_MS]; what is
 * measured by then is used and the rest is left to the old random guess.
 */
internal object VoiceScanner {

    private const val BUDGET_MS = 8L * 60_000L

    fun run(
        ctx: Context,
        url: String,
        headers: Map<String, String>,
        hlsHint: Boolean,
        startsMs: LongArray,
        endsMs: LongArray,
        offsetMs: Long,
        embedder: Embedder?,
        cancelled: () -> Boolean,
        progress: (Int, String) -> Unit,
    ): ScanOutcome {
        val total = startsMs.size
        if (total == 0 || endsMs.size != total) return ScanOutcome(null, "there are no subtitle lines")

        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000L
        val stop: () -> Boolean = { cancelled() || System.nanoTime() > deadline }

        var lastReport = 0L
        val window = CueWindow(startsMs, endsMs, offsetMs, embedder) { done, all ->
            val now = System.nanoTime()
            if (now - lastReport > 2_000_000_000L) {
                lastReport = now
                progress(done * 100 / max(1, all), "")
            }
        }

        val lower = url.lowercase()
        val isFile = lower.startsWith("file:") || url.startsWith("/")
        val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
        if (!isFile && !isHttp) return ScanOutcome(null, "this kind of video link cannot be read")

        var gotSound: Boolean
        if (isFile) {
            val path = if (lower.startsWith("file:")) (Uri.parse(url).path ?: "") else url
            gotSound = AudioDecode.run({ ex -> ex.setDataSource(path) }, 0L, window, stop)
        } else {
            val cover = coverage(startsMs, endsMs, offsetMs)
            val needed: (Double, Double) -> Boolean = { a, b ->
                val from = max(0, a.toInt())
                val to = min(cover.size - 1, b.toInt() + 1)
                var hit = false
                var s = from
                while (s <= to) {
                    if (cover[s]) {
                        hit = true
                        break
                    }
                    s++
                }
                hit
            }
            val tmp = File(ctx.cacheDir, "dub/scan_tmp")

            fun viaHls(): Boolean {
                val plan = Hls.plan(url, headers) ?: return false
                return Hls.run(plan, headers, needed, window, tmp, stop)
            }

            fun viaFile(): Boolean =
                AudioDecode.run({ ex -> ex.setDataSource(url, headers) }, 0L, window, stop)

            val looksHls = hlsHint || lower.contains(".m3u8")
            if (looksHls) {
                gotSound = viaHls()
                if (!gotSound && !stop()) gotSound = viaFile()
            } else {
                gotSound = viaFile()
                if (!gotSound && !stop()) gotSound = viaHls()
            }
        }

        window.finish()
        if (cancelled()) return ScanOutcome(null, "cancelled")
        if (!gotSound) return ScanOutcome(null, "the sound of this video could not be read")

        val result = SpeakerGrouping.group(window.emb, window.f0, window.weight)
        if (result.known() == 0) return ScanOutcome(null, "no clear voice was heard in this video")
        return ScanOutcome(result, "")
    }

    /** One flag per second of the video: true when a subtitle line is (nearly) there. */
    private fun coverage(startsMs: LongArray, endsMs: LongArray, offsetMs: Long): BooleanArray {
        var maxSec = 0
        for (i in startsMs.indices) {
            maxSec = max(maxSec, ((endsMs[i] + offsetMs) / 1000L).toInt() + 3)
        }
        val c = BooleanArray(maxSec + 2)
        for (i in startsMs.indices) {
            val a = max(0, ((startsMs[i] + offsetMs) / 1000L).toInt() - 1)
            val endCapped = min(endsMs[i], startsMs[i] + 12_000L)
            val b = min(c.size - 1, ((endCapped + offsetMs) / 1000L).toInt() + 1)
            for (s in a..b) c[s] = true
        }
        return c
    }
}
