package com.hikari.app.dub

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Keeps the dub clip folder from growing without limit (oldest files go first). */
internal object DubCache {
    fun trim(dir: File, maxBytes: Long) {
        val files = dir.listFiles() ?: return
        var total = 0L
        for (f in files) total += f.length()
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= maxBytes * 3 / 4) break
        }
    }
}

/**
 * Edits a spoken clip after the voice engine made it, entirely on the phone:
 *
 *  1. decode the clip (MP3 from Google, WAV from the phone voice) to samples,
 *  2. cut the silence the engine leaves at both ends,
 *  3. give the voice its character: pitch shift (deeper for a man, higher for
 *     a woman or child) + speaking speed + the mood of the line. The mood
 *     (anger, sadness, fear, joy, surprise, whisper ...) is acted with a
 *     moving pitch line (questions rise, sadness sinks, fear trembles),
 *     speed, loudness, breath, roughness and a real whisper voice,
 *  4. when the line is too long for the time it has, speed it up WITHOUT
 *     changing its pitch (WSOLA time-stretch),
 *  5. even out the loudness of all characters, remove rumble, add tiny fades,
 *  6. save as a WAV that the normal player can play.
 *
 * Pitch shift = time-stretch + resample. Resampling moves the formants along
 * with the pitch, so a lowered voice really sounds like a bigger speaker.
 *
 * FAITHFUL clips (cloned voices, see [process]) skip steps 3 entirely: the voice
 * reaches the listener with exactly the timbre, pitch and speed the cloning
 * model gave it. Only 2, 4 (speed-up without pitch change, and only when the
 * line is too long for its slot), 5 and 6 are done.
 */
internal object DubFx {

    private class Pcm(val samples: FloatArray, val rate: Int)

    /**
     * How one feeling is acted.
     *  - [semitones] / [tempo] / [gainDb]: overall pitch, speed and loudness
     *  - [drive]    roughness (soft clipping): angry and shouting voices
     *  - [breath]   breath noise mixed in (0..1): sad and frightened voices
     *  - [tremorHz] / [tremorSt]  a trembling voice (rate, depth in semitones)
     *  - [lilt]     how much the pitch bounces up and down while speaking
     *  - [slope]    pitch change over the whole line (negative = sinks)
     *  - [attack]   pitch jump at the very start that fades away (surprise)
     *  - [whisper]  true = real whisper (no voice pitch, only breath shaped
     *               like the speech)
     */
    private class Mood(
        val semitones: Double,
        val tempo: Double,
        val gainDb: Double,
        val drive: Double = 0.0,
        val breath: Double = 0.0,
        val tremorHz: Double = 0.0,
        val tremorSt: Double = 0.0,
        val lilt: Double = 0.0,
        val slope: Double = 0.0,
        val attack: Double = 0.0,
        val whisper: Boolean = false,
    )

    /** No acting at all: used for faithful (cloned) clips. */
    private val PLAIN_MOOD = Mood(0.0, 1.0, 0.0)

    private const val TARGET_RMS = 0.11f
    private const val MAX_FIT_SPEEDUP = 1.45
    /** Syllable-like rate of the small pitch bounce, in Hz. */
    private const val LILT_HZ = 2.3
    /** How far (semitones) the end of a question rises. */
    private const val QUESTION_RISE = 2.6

    /**
     * Returns true when [dst] was written. On any problem it returns false and
     * the caller plays the untouched clip instead.
     *
     * [question] = the spoken text ends with a question mark: the pitch rises
     * at the end, as a person's voice does.
     *
     * [faithful] = the clip is a CLONED voice and must keep its own timbre: no pitch
     * change, no speed change, no mood effects. The silence is trimmed, a line that is
     * too long for its slot is sped up WITHOUT moving the pitch, the loudness is evened
     * out and the edges are faded. Nothing else.
     */
    fun process(
        src: File,
        dst: File,
        profile: VoiceProfile,
        emotion: Emotion,
        fitMs: Long,
        question: Boolean = false,
        faithful: Boolean = false,
    ): Boolean {
        return try {
            val pcm = decode(src) ?: return false
            val rate = pcm.rate
            if (rate < 8000 || pcm.samples.size < rate / 20) return false

            var x = trimSilence(pcm.samples, rate)

            val mood: Mood
            if (faithful) {
                mood = PLAIN_MOOD
                val clipMs = x.size * 1000.0 / rate
                if (fitMs > 0L && clipMs > fitMs) {
                    val speedUp = min(clipMs / fitMs, MAX_FIT_SPEEDUP)
                    if (speedUp > 1.02) x = wsola(x, speedUp, rate)
                }
                if (x.size < rate / 20) return false
            } else {
                mood = moodOf(emotion)
                val semis = (profile.semitones + mood.semitones).coerceIn(-9.0, 8.0)
                val ratio = 2.0.pow(semis / 12.0)

                // Speed: character speed x mood, then whatever is needed to fit the time slot.
                var tempo = profile.tempo.toDouble() * mood.tempo
                val clipMs = x.size * 1000.0 / rate
                val afterMs = clipMs / tempo
                if (fitMs > 0L && afterMs > fitMs) {
                    tempo *= min(afterMs / fitMs, MAX_FIT_SPEEDUP)
                }
                tempo = tempo.coerceIn(0.7, 2.0)

                // pitch ratio r is applied by resampling (which also changes the length
                // by 1/r), so the stretch step has to make up for it.
                val stretch = (tempo / ratio).coerceIn(0.5, 2.5)
                if (abs(stretch - 1.0) > 0.02) x = wsola(x, stretch, rate)

                // Pitch: the character's base shift plus the moving "acting" line.
                // The moving line is centred on zero, so the length stays about the same.
                x = resampleMoving(x, rate, semis, mood, question)

                if (x.size < rate / 20) return false
            }

            // The voice of the feeling.
            if (mood.whisper) {
                val w = whisperize(x, rate)
                if (w.size == x.size) x = w
            }
            if (mood.drive > 0.0) saturate(x, mood.drive)
            if (mood.breath > 0.0) addBreath(x, rate, mood.breath)

            highPass(x, rate, 60f)
            val gain = 10.0.pow(mood.gainDb / 20.0).toFloat()
            normalize(x, TARGET_RMS * gain)
            fade(x, rate)

            // never hand a broken clip to the player
            for (v in x) if (v.isNaN() || v.isInfinite()) return false

            val tmp = File(dst.parentFile, dst.name + ".part")
            writeWav(tmp, x, rate)
            if (dst.exists()) dst.delete()
            if (tmp.renameTo(dst)) {
                dst.parentFile?.let { DubCache.trim(it, 120L * 1024 * 1024) }
                true
            } else {
                tmp.delete()
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun moodOf(e: Emotion): Mood = when (e) {
        // plain speech still gets a tiny natural fall and bounce, so it is not flat
        Emotion.NORMAL -> Mood(0.0, 1.0, 0.0, lilt = 0.25, slope = -0.8)
        Emotion.SHOUT -> Mood(1.0, 1.06, 4.0, drive = 1.8, lilt = 0.3, slope = -1.5, attack = 1.0)
        Emotion.WHISPER -> Mood(0.0, 0.95, -6.0, whisper = true)
        Emotion.SAD -> Mood(
            -1.2, 0.90, -3.0,
            breath = 0.10, tremorHz = 5.5, tremorSt = 0.18, lilt = 0.15, slope = -2.2,
        )
        Emotion.EXCITED -> Mood(0.8, 1.05, 1.5, lilt = 0.8, slope = 0.6, attack = 0.8)
        Emotion.ANGRY -> Mood(-0.8, 1.07, 4.5, drive = 2.4, lilt = 0.4, slope = -1.8, attack = 0.8)
        Emotion.HAPPY -> Mood(1.0, 1.04, 1.0, lilt = 0.9, slope = 0.5, attack = 0.5)
        Emotion.FEAR -> Mood(
            1.8, 1.10, 0.5,
            breath = 0.09, tremorHz = 6.5, tremorSt = 0.45, lilt = 0.5, slope = 0.8, attack = 0.8,
        )
        Emotion.SURPRISE -> Mood(1.2, 1.03, 2.0, lilt = 0.4, slope = 0.6, attack = 2.8)
    }

    /**
     * The moving part of the pitch, in semitones, at progress [u] (0..1 through
     * the clip) and time [t] seconds.
     */
    private fun contourSt(m: Mood, question: Boolean, u: Double, t: Double): Double {
        var c = 0.0
        if (!question && m.slope != 0.0) c += m.slope * (u - 0.5)
        if (m.attack != 0.0) c += m.attack * exp(-u / 0.12)
        if (m.lilt != 0.0) c += m.lilt * sin(2.0 * PI * LILT_HZ * t)
        if (m.tremorSt != 0.0) c += m.tremorSt * sin(2.0 * PI * m.tremorHz * t)
        if (question) {
            val q = ((u - 0.62) / 0.38).coerceIn(0.0, 1.0)
            c += QUESTION_RISE * q * q * (3.0 - 2.0 * q)
        }
        return c
    }

    // ───────────────────────── decoding / writing ─────────────────────────

    private fun decode(file: File): Pcm? {
        if (file.name.endsWith(".wav", ignoreCase = true)) {
            val w = readWav(file)
            if (w != null) return w
        }
        return decodeWithCodec(file)
    }

    private fun tag(b: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > b.size) return false
        for (i in s.indices) if (b[off + i] != s[i].code.toByte()) return false
        return true
    }

    private fun readWav(file: File): Pcm? {
        val b = file.readBytes()
        if (b.size < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        var fmtTag = 1
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                fmtTag = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt() and 0xFFFF
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                dataLen = if (size < 0 || body + size > b.size) b.size - body else size
                break
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || rate <= 0 || channels <= 0 || bits != 16) return null
        if (fmtTag != 1 && fmtTag != 0xFFFE) return null
        val frames = dataLen / (2 * channels)
        if (frames <= 0) return null
        val out = FloatArray(frames)
        var p = dataStart
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += bb.getShort(p).toFloat() / 32768f
                p += 2
            }
            out[i] = acc / channels
        }
        return Pcm(out, rate)
    }

    private fun intOf(f: MediaFormat, key: String, def: Int): Int =
        if (f.containsKey(key)) f.getInteger(key) else def

    private fun decodeWithCodec(file: File): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
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
            if (track < 0 || fmt == null) return null
            val mimeType = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            val dec = MediaCodec.createDecoderByType(mimeType)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            var rate = intOf(fmt, MediaFormat.KEY_SAMPLE_RATE, 24000)
            var channels = intOf(fmt, MediaFormat.KEY_CHANNEL_COUNT, 1)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm = ShortArray(1 shl 16)
            var count = 0
            val deadline = System.nanoTime() + 15_000_000_000L

            while (!outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000L)
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
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining()
                        if (count + n > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, count + n))
                        sb.get(pcm, count, n)
                        count += n
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    rate = intOf(nf, MediaFormat.KEY_SAMPLE_RATE, rate)
                    channels = intOf(nf, MediaFormat.KEY_CHANNEL_COUNT, channels)
                }
            }
            if (count == 0 || channels <= 0) return null
            val frames = count / channels
            val out = FloatArray(frames)
            var p = 0
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += pcm[p].toFloat() / 32768f
                    p++
                }
                out[i] = acc / channels
            }
            return Pcm(out, rate)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeWav(file: File, x: FloatArray, rate: Int) {
        val dataLen = x.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1.toShort())            // PCM
        bb.putShort(1.toShort())            // mono
        bb.putInt(rate)
        bb.putInt(rate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataLen)
        for (v in x) {
            val s = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
            bb.putShort(s.toShort())
        }
        file.writeBytes(bb.array())
    }

    // ───────────────────────── signal processing ─────────────────────────

    /** Cuts the silence at the start / end (keeps a little breathing room). */
    private fun trimSilence(x: FloatArray, rate: Int): FloatArray {
        var peak = 0f
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return x
        val thr = max(0.006f, peak * 0.03f)
        var first = 0
        while (first < x.size && abs(x[first]) < thr) first++
        if (first >= x.size) return x
        var last = x.size - 1
        while (last > first && abs(x[last]) < thr) last--
        val from = max(0, first - rate * 15 / 1000)
        val to = min(x.size, last + 1 + rate * 40 / 1000)
        return if (to - from < 16) x else x.copyOfRange(from, to)
    }

    /**
     * WSOLA time-stretch: changes the speed by [tempo] (>1 = faster, shorter)
     * and keeps the pitch. Overlap-adds 30 ms Hann frames, each one picked
     * (within +-12 ms) where it best continues the previous frame.
     */
    private fun wsola(x: FloatArray, tempo: Double, fs: Int): FloatArray {
        val n = ((0.030 * fs).toInt() / 2) * 2
        val hs = n / 2
        val tol = (0.012 * fs).toInt()
        val total = (x.size / tempo).toInt()
        if (total <= 0 || n < 16) return x
        val outSize = total + n * 2
        val out = FloatArray(outSize)
        val norm = FloatArray(outSize)
        val win = FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / n)).toFloat() }
        val pad = tol + n
        val xp = FloatArray(x.size + 2 * pad + 2 * n)
        System.arraycopy(x, 0, xp, pad, x.size)

        var prev = pad
        var frame = 0
        while (true) {
            val outPos = frame * hs
            if (outPos + n > outSize) break
            val origPos = Math.round(frame.toDouble() * hs * tempo)
            if (origPos >= x.size) break
            val nominal = origPos.toInt() + pad
            var cand = nominal
            if (frame > 0) {
                val tpl = prev + hs
                var best = Float.NEGATIVE_INFINITY
                var c = nominal - tol
                val hi = nominal + tol
                while (c <= hi) {
                    var dot = 0f
                    var en = 0f
                    var i = 0
                    while (i < n) {
                        val a = xp[tpl + i]
                        val b = xp[c + i]
                        dot += a * b
                        en += b * b
                        i += 2
                    }
                    val score = dot / sqrt(en + 1e-9f)
                    if (score > best) {
                        best = score
                        cand = c
                    }
                    c += 2
                }
            }
            for (i in 0 until n) {
                val w = win[i]
                out[outPos + i] += xp[cand + i] * w
                norm[outPos + i] += w
            }
            prev = cand
            frame++
        }
        val res = FloatArray(total)
        for (i in 0 until total) {
            val nv = norm[i]
            if (nv > 1e-3f) res[i] = out[i] / nv
        }
        return res
    }

    /** Cubic resampling: reads the samples [step] at a time (step < 1 = deeper). */
    private fun resample(y: FloatArray, step: Double): FloatArray {
        if (y.size < 4) return y
        val nOut = ((y.size - 1) / step).toInt()
        if (nOut <= 0) return y
        val z = FloatArray(nOut)
        val last = y.size - 1
        for (i in 0 until nOut) {
            val pos = i * step
            val i1 = pos.toInt()
            val f = (pos - i1).toFloat()
            val p0 = y[if (i1 > 0) i1 - 1 else 0]
            val p1 = y[i1]
            val p2 = y[if (i1 + 1 < last) i1 + 1 else last]
            val p3 = y[if (i1 + 2 < last) i1 + 2 else last]
            z[i] = p1 + 0.5f * f * (p2 - p0 + f * (2f * p0 - 5f * p1 + 4f * p2 - p3 + f * (3f * (p1 - p2) + p3 - p0)))
        }
        return z
    }

    /**
     * Cubic resampling with a pitch that changes while the clip plays:
     * the read position moves 2^((base + contour) / 12) samples per output
     * sample. Higher step = higher pitch (and a little shorter).
     */
    private fun resampleMoving(
        y: FloatArray,
        rate: Int,
        baseSemis: Double,
        mood: Mood,
        question: Boolean,
    ): FloatArray {
        if (y.size < 8) return y
        val n = y.size
        val last = n - 1
        val out = FloatArray(n * 2 + 16)
        var pos = 0.0
        var o = 0
        while (pos < last && o < out.size) {
            val u = pos / last
            val st = (baseSemis + contourSt(mood, question, u, pos / rate)).coerceIn(-11.0, 10.0)
            val i1 = pos.toInt()
            val f = (pos - i1).toFloat()
            val p0 = y[if (i1 > 0) i1 - 1 else 0]
            val p1 = y[i1]
            val p2 = y[if (i1 + 1 < last) i1 + 1 else last]
            val p3 = y[if (i1 + 2 < last) i1 + 2 else last]
            out[o] = p1 + 0.5f * f * (p2 - p0 + f * (2f * p0 - 5f * p1 + 4f * p2 - p3 + f * (3f * (p1 - p2) + p3 - p0)))
            o++
            pos += 2.0.pow(st / 12.0)
        }
        return if (o < 16) y else out.copyOf(o)
    }

    /**
     * A real whisper: the clip is analysed in 10 ms steps (linear prediction
     * keeps the "shape" of every sound) and rebuilt from plain breath noise
     * with that shape and the same loudness. No voice pitch is left, only
     * words made of air, which is what a whisper is.
     */
    private fun whisperize(x: FloatArray, rate: Int): FloatArray {
        val n = x.size
        val hop = max(64, rate / 100)
        val win = hop * 3
        val order = 16
        val out = FloatArray(n)
        val rnd = java.util.Random(20240611L)
        val hist = DoubleArray(order)
        val a = DoubleArray(order + 1)
        val tmp = DoubleArray(order + 1)
        val r = DoubleArray(order + 1)
        val frame = DoubleArray(win)
        val w = DoubleArray(win) { i -> 0.5 - 0.5 * cos(2.0 * PI * (i + 0.5) / win) }
        val seg = DoubleArray(hop)
        var prevGain = 0.0
        var prevY = 0.0
        var pos = 0
        while (pos < n) {
            val len = min(hop, n - pos)
            val from = pos + len / 2 - win / 2
            for (i in 0 until win) {
                val idx = from + i
                frame[i] = if (idx >= 0 && idx < n) x[idx].toDouble() * w[i] else 0.0
            }
            for (k in 0..order) {
                var acc = 0.0
                var i = 0
                while (i + k < win) {
                    acc += frame[i] * frame[i + k]
                    i++
                }
                r[k] = acc
            }
            var e = 0.0
            for (i in 0 until len) {
                val v = x[pos + i].toDouble()
                e += v * v
            }
            val targetRms = sqrt(e / len)
            if (r[0] < 1e-9 || targetRms < 1e-6) {
                for (i in 0 until len) out[pos + i] = 0f
                prevGain = 0.0
                pos += len
                continue
            }

            // Levinson-Durbin: the filter that describes the shape of this sound
            r[0] = r[0] * 1.0001 + 1e-9
            a.fill(0.0)
            a[0] = 1.0
            var err = r[0]
            for (i in 1..order) {
                var acc = r[i]
                for (j in 1 until i) acc += a[j] * r[i - j]
                val k = -acc / err
                if (!(abs(k) < 0.9999)) break
                for (j in 1 until i) tmp[j] = a[j] + k * a[i - j]
                for (j in 1 until i) a[j] = tmp[j]
                a[i] = k
                err *= (1.0 - k * k)
                if (err <= 1e-12) break
            }
            // keep the filter safely stable
            var g = 1.0
            for (k in 1..order) {
                g *= 0.96
                a[k] *= g
            }

            // breath noise through that filter
            var se = 0.0
            for (i in 0 until len) {
                var y = rnd.nextGaussian()
                for (k in 1..order) y -= a[k] * hist[k - 1]
                if (!(abs(y) < 1e6)) {
                    y = 0.0
                    hist.fill(0.0)
                }
                for (k in order - 1 downTo 1) hist[k] = hist[k - 1]
                hist[0] = y
                val bright = y - 0.5 * prevY      // a little extra "air"
                prevY = y
                seg[i] = bright
                se += bright * bright
            }
            val synRms = sqrt(se / len)
            val gain = if (synRms > 1e-9) targetRms / synRms else 0.0
            for (i in 0 until len) {
                val f = (i + 1).toDouble() / len
                out[pos + i] = (seg[i] * (prevGain + (gain - prevGain) * f)).toFloat()
            }
            prevGain = gain
            pos += len
        }
        return out
    }

    /** Roughness for angry / shouting voices: soft clipping that adds harmonics. */
    private fun saturate(x: FloatArray, drive: Double) {
        var peak = 0f
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return
        val pre = 0.9f / peak
        val d = drive.toFloat()
        val norm = tanh(d)
        for (i in x.indices) x[i] = tanh(d * x[i] * pre) / norm
    }

    /** Breath noise that follows the loudness of the speech (sad / frightened voices). */
    private fun addBreath(x: FloatArray, rate: Int, amount: Double) {
        val rnd = java.util.Random(77L)
        val attack = (1.0 - exp(-1.0 / (0.004 * rate))).toFloat()
        val release = (1.0 - exp(-1.0 / (0.040 * rate))).toFloat()
        val amt = amount.toFloat()
        var env = 0f
        var prev = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            env += (a - env) * (if (a > env) attack else release)
            val noise = rnd.nextFloat() * 2f - 1f
            val air = (noise - prev) * 0.7f      // first difference = high-passed noise
            prev = noise
            x[i] = x[i] + air * env * amt
        }
    }

    /** One-pole high-pass: removes DC / rumble that the pitch shift can leave. */
    private fun highPass(x: FloatArray, fs: Int, fc: Float) {
        if (x.isEmpty()) return
        val rc = 1.0f / (2f * PI.toFloat() * fc)
        val dt = 1.0f / fs
        val a = rc / (rc + dt)
        var prevX = x[0]
        var prevY = 0f
        for (i in x.indices) {
            val cur = x[i]
            val y = a * (prevY + cur - prevX)
            prevX = cur
            prevY = y
            x[i] = y
        }
    }

    /** Same loudness for every character, with a soft limiter against clipping. */
    private fun normalize(x: FloatArray, targetRms: Float) {
        var peak = 0f
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return
        val thr = peak * 0.05f
        var sum = 0.0
        var cnt = 0
        for (v in x) {
            if (abs(v) >= thr) {
                sum += (v * v).toDouble()
                cnt++
            }
        }
        if (cnt == 0) return
        val rms = sqrt(sum / cnt).toFloat()
        if (rms < 1e-5f) return
        val gain = (targetRms / rms).coerceIn(0.25f, 8f)
        for (i in x.indices) x[i] = softLimit(x[i] * gain)
    }

    private fun softLimit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.85f) return v
        val s = 0.85f + 0.15f * tanh((a - 0.85f) / 0.15f)
        return if (v < 0f) -s else s
    }

    private fun fade(x: FloatArray, rate: Int) {
        val fi = min(x.size / 2, rate * 8 / 1000)
        for (i in 0 until fi) x[i] = x[i] * (i.toFloat() / fi)
        val fo = min(x.size / 2, rate * 25 / 1000)
        for (i in 0 until fo) x[x.size - 1 - i] = x[x.size - 1 - i] * (i.toFloat() / fo)
    }
}
