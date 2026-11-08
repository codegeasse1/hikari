package com.hikari.app.dub

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pitch of a human voice (YIN method), used to tell a man from a woman.
 * No model, no internet: pure arithmetic on the samples of one subtitle line.
 *
 * Speech is looked at in short frames (45 ms) at 8 kHz; only the loud frames
 * of the line are measured (at most [MAX_FRAMES]), and the MEDIAN pitch of the
 * frames that really are voiced is the answer. A man is usually 85-155 Hz, a
 * woman 165-255 Hz.
 */
internal object PitchTrack {

    class Result(val hz: Float, val voiced: Int)

    private val NONE = Result(0f, 0)

    private const val RATE = 8000f
    private const val FRAME = 360        // samples per frame (45 ms)
    private const val WIN = 240          // samples compared inside a frame
    private const val MIN_LAG = 20       // 400 Hz
    private const val MAX_LAG = 114      // about 70 Hz  (WIN + MAX_LAG must stay <= FRAME)
    private const val HOP = 200          // 25 ms
    private const val MAX_FRAMES = 60
    private const val THRESHOLD = 0.15f
    private const val MIN_VOICED = 5

    /** [x] = 16 kHz mono samples; the line is x[from until from + len]. */
    fun analyze(x: ShortArray, from: Int, len: Int): Result {
        val n8 = len / 2
        if (n8 < FRAME + HOP) return NONE
        val y = FloatArray(n8)
        for (i in 0 until n8) {
            val a = x[from + 2 * i].toInt() + x[from + 2 * i + 1].toInt()
            y[i] = a * (0.5f / 32768f)
        }

        val frames = (n8 - FRAME) / HOP + 1
        val rms = FloatArray(frames)
        var maxRms = 0f
        for (f in 0 until frames) {
            val s = f * HOP
            var sum = 0f
            for (j in 0 until FRAME) {
                val v = y[s + j]
                sum += v * v
            }
            val r = sqrt(sum / FRAME)
            rms[f] = r
            if (r > maxRms) maxRms = r
        }
        if (maxRms < 0.004f) return NONE
        val gate = max(0.004f, 0.3f * maxRms)

        var candidates = 0
        for (f in 0 until frames) if (rms[f] >= gate) candidates++
        if (candidates == 0) return NONE
        val step = (candidates + MAX_FRAMES - 1) / MAX_FRAMES

        val d = FloatArray(MAX_LAG + 2)
        val cm = FloatArray(MAX_LAG + 2)
        val found = FloatArray(MAX_FRAMES + 1)
        var count = 0
        var seen = 0
        for (f in 0 until frames) {
            if (rms[f] < gate) continue
            val take = seen % step == 0
            seen++
            if (!take) continue
            val hz = yin(y, f * HOP, d, cm)
            if (hz > 0f && count < found.size) {
                found[count] = hz
                count++
            }
        }
        if (count < MIN_VOICED) return Result(0f, count)
        val sorted = found.copyOf(count)
        sorted.sort()
        return Result(sorted[count / 2], count)
    }

    /** Pitch in Hz of the frame that starts at [s], or 0 when it is not a clear voiced sound. */
    private fun yin(y: FloatArray, s: Int, d: FloatArray, cm: FloatArray): Float {
        for (tau in 1..MAX_LAG) {
            var sum = 0f
            for (j in 0 until WIN) {
                val a = y[s + j] - y[s + j + tau]
                sum += a * a
            }
            d[tau] = sum
        }
        var running = 0f
        for (tau in 1..MAX_LAG) {
            running += d[tau]
            cm[tau] = if (running > 0f) d[tau] * tau / running else 1f
        }
        var tau = MIN_LAG
        while (tau < MAX_LAG) {
            if (cm[tau] < THRESHOLD) {
                while (tau + 1 < MAX_LAG && cm[tau + 1] < cm[tau]) tau++
                val a = cm[tau - 1]
                val b = cm[tau]
                val c = cm[tau + 1]
                val denom = a - 2f * b + c
                var shift = if (denom != 0f) 0.5f * (a - c) / denom else 0f
                if (shift > 1f) shift = 1f
                if (shift < -1f) shift = -1f
                val lag = tau + shift
                return if (lag > 0f) RATE / lag else 0f
            }
            tau++
        }
        return 0f
    }
}

/**
 * Turns the sound of any rate / channel count into 16 kHz mono 16-bit, one
 * block after another (so a whole film never has to sit in memory). Each
 * output sample is the average of the input samples it covers, which also
 * acts as a low-pass filter.
 */
internal class Resampler16k(srcRate: Int) {
    private val ratio: Double = (if (srcRate > 0) srcRate else 48000).toDouble() / 16000.0
    private var acc = 0.0
    private var pos = 0.0

    /** Room the output array needs for [n] input samples. */
    fun maxOut(n: Int): Int = (n / ratio).toInt() + 4

    /** Resamples src[0 until n] into [dst] (from index 0); returns how many samples were written. */
    fun process(src: FloatArray, n: Int, dst: ShortArray): Int {
        var o = 0
        for (i in 0 until n) {
            val v = src[i].toDouble()
            var w = 1.0
            while (w > 1e-9) {
                val room = ratio - pos
                if (w < room) {
                    acc += v * w
                    pos += w
                    w = 0.0
                } else {
                    acc += v * room
                    w -= room
                    if (o < dst.size) {
                        val s = (acc / ratio * 32767.0).toInt()
                        dst[o] = (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
                        o++
                    }
                    acc = 0.0
                    pos = 0.0
                }
            }
        }
        return o
    }
}

/**
 * Puts the lines of a video into groups of "the same person" and decides
 * whether each group is a man or a woman.
 *
 *  - Groups come from the voice fingerprints (speaker embeddings) when a
 *    model was available; two lines are the same person when their
 *    fingerprints point the same way (cosine similarity).
 *  - The sex of a group is the median pitch of all its lines (below
 *    [MALE_BELOW] Hz = man). A group whose lines are clearly both low and
 *    high is split in two, so a man and a woman can never share a voice.
 *  - Without a model, the lines are grouped by pitch alone (one man voice,
 *    one woman voice).
 */
internal object SpeakerGrouping {

    /** Fingerprints at least this similar are the same person. Lower = fewer, bigger groups. */
    private const val SAME = 0.45f
    private const val MAX_VOICES = 8
    const val MALE_BELOW = 162f

    fun group(emb: Array<FloatArray?>, f0: FloatArray, weight: IntArray): VoiceScanResult {
        val n = f0.size
        val label = IntArray(n) { -1 }
        val anyEmb = emb.any { it != null }
        if (anyEmb) {
            val l = clusterByEmbedding(emb)
            for (i in 0 until n) label[i] = l[i]
        } else {
            for (i in 0 until n) if (f0[i] > 0f) label[i] = if (f0[i] < MALE_BELOW) 0 else 1
        }

        splitMixed(label, f0)

        // sex of every group
        val ids = label.filter { it >= 0 }.toSortedSet().toList()
        val sexOf = HashMap<Int, Gender>()
        for (id in ids) {
            val vals = ArrayList<Float>()
            val ws = ArrayList<Int>()
            for (i in 0 until n) {
                if (label[i] == id && f0[i] > 0f) {
                    vals.add(f0[i])
                    ws.add(max(1, weight[i]))
                }
            }
            sexOf[id] = if (vals.isEmpty()) Gender.UNKNOWN
            else if (weightedMedian(vals, ws) < MALE_BELOW) Gender.MALE else Gender.FEMALE
        }

        // numbers 0,1,2... in order of first appearance
        val renum = HashMap<Int, Int>()
        val voice = IntArray(n) { -1 }
        val gender = Array(n) { Gender.UNKNOWN }
        for (i in 0 until n) {
            val l = label[i]
            if (l >= 0) {
                val nr = renum.getOrPut(l) { renum.size }
                voice[i] = nr
                gender[i] = sexOf[l] ?: Gender.UNKNOWN
            } else if (f0[i] > 0f) {
                gender[i] = if (f0[i] < MALE_BELOW) Gender.MALE else Gender.FEMALE
            }
        }
        return VoiceScanResult(voice, gender)
    }

    private fun weightedMedian(vals: List<Float>, ws: List<Int>): Float {
        val idx = vals.indices.sortedBy { vals[it] }
        var total = 0L
        for (w in ws) total += w.toLong()
        var seen = 0L
        for (i in idx) {
            seen += ws[i].toLong()
            if (seen * 2 >= total) return vals[i]
        }
        return vals[idx.last()]
    }

    /** A group with many clearly low AND many clearly high lines is two people: split it. */
    private fun splitMixed(label: IntArray, f0: FloatArray) {
        val n = label.size
        var next = (label.maxOrNull() ?: -1) + 1
        val ids = label.filter { it >= 0 }.toSortedSet().toList()
        for (id in ids) {
            var m = 0
            var low = 0
            var high = 0
            for (i in 0 until n) {
                if (label[i] != id || f0[i] <= 0f) continue
                m++
                if (f0[i] < 140f) low++
                if (f0[i] > 190f) high++
            }
            if (m >= 6 && low * 10 >= m * 3 && high * 10 >= m * 3) {
                val newId = next
                next++
                for (i in 0 until n) {
                    if (label[i] == id && f0[i] >= MALE_BELOW) label[i] = newId
                }
            }
        }
    }

    // ───────────────────────── embeddings ─────────────────────────

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val m = min(a.size, b.size)
        for (i in 0 until m) s += a[i] * b[i]
        return s
    }

    private fun norm(a: FloatArray): Float = sqrt(dot(a, a))

    private fun cos(a: FloatArray, b: FloatArray): Float {
        val d = norm(a) * norm(b)
        return if (d > 1e-9f) dot(a, b) / d else 0f
    }

    private fun clusterByEmbedding(raw: Array<FloatArray?>): IntArray {
        val n = raw.size
        val label = IntArray(n) { -1 }
        val dim = raw.firstOrNull { it != null }?.size ?: return label
        // only fingerprints of the same length can be compared
        val emb: Array<FloatArray?> = Array(n) { i -> raw[i]?.takeIf { it.size == dim } }

        val sums = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()

        // 1) walk through the lines; join the nearest group or start a new one
        for (i in 0 until n) {
            val e = emb[i] ?: continue
            var best = -1
            var bestSim = -2f
            for (c in sums.indices) {
                val s = cos(e, sums[c])
                if (s > bestSim) {
                    bestSim = s
                    best = c
                }
            }
            // (at most 40 groups while walking, so a noisy film can never make this slow)
            if (best >= 0 && (bestSim >= SAME || sums.size >= 40)) {
                val sm = sums[best]
                for (k in 0 until dim) sm[k] += e[k]
                counts[best] = counts[best] + 1
                label[i] = best
            } else {
                sums.add(e.copyOf())
                counts.add(1)
                label[i] = sums.size - 1
            }
        }

        // 2) settle: every line moves to its nearest group (centres are recomputed)
        for (round in 0 until 3) {
            val k = sums.size
            val fresh = Array(k) { FloatArray(dim) }
            val cnt = IntArray(k)
            for (i in 0 until n) {
                val e = emb[i] ?: continue
                var best = label[i]
                var bestSim = if (best >= 0) cos(e, sums[best]) else -2f
                for (c in 0 until k) {
                    val s = cos(e, sums[c])
                    if (s > bestSim + 0.02f) {
                        bestSim = s
                        best = c
                    }
                }
                label[i] = best
                if (best >= 0) {
                    cnt[best]++
                    val f = fresh[best]
                    for (q in 0 until dim) f[q] += e[q]
                }
            }
            for (c in 0 until k) {
                if (cnt[c] > 0) {
                    sums[c] = fresh[c]
                    counts[c] = cnt[c]
                } else {
                    counts[c] = 0
                }
            }
        }

        // 3) join groups that are really one person, then absorb tiny groups, then cap
        val alive = BooleanArray(sums.size) { counts[it] > 0 }
        fun aliveCount(): Int = alive.count { it }
        fun merge(from: Int, into: Int) {
            val a = sums[into]
            val b = sums[from]
            for (q in 0 until dim) a[q] += b[q]
            counts[into] = counts[into] + counts[from]
            counts[from] = 0
            alive[from] = false
            for (i in 0 until n) if (label[i] == from) label[i] = into
        }
        fun nearest(c: Int): Int {
            var best = -1
            var bestSim = -2f
            for (o in sums.indices) {
                if (o == c || !alive[o]) continue
                val s = cos(sums[c], sums[o])
                if (s > bestSim) {
                    bestSim = s
                    best = o
                }
            }
            return best
        }
        fun nearestSim(c: Int): Float {
            val o = nearest(c)
            return if (o < 0) -2f else cos(sums[c], sums[o])
        }

        var again = true
        while (again) {
            again = false
            var bestA = -1
            var bestB = -1
            var bestSim = SAME + 0.05f
            for (a in sums.indices) {
                if (!alive[a]) continue
                for (b in a + 1 until sums.size) {
                    if (!alive[b]) continue
                    val s = cos(sums[a], sums[b])
                    if (s >= bestSim) {
                        bestSim = s
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (bestA >= 0) {
                merge(bestB, bestA)
                again = true
            }
        }
        for (c in sums.indices) {
            if (alive[c] && counts[c] <= 1 && aliveCount() > 2 && nearestSim(c) >= SAME - 0.15f) {
                val o = nearest(c)
                if (o >= 0) merge(c, o)
            }
        }
        while (aliveCount() > MAX_VOICES) {
            var small = -1
            for (c in sums.indices) {
                if (alive[c] && (small < 0 || counts[c] < counts[small])) small = c
            }
            val o = if (small >= 0) nearest(small) else -1
            if (small < 0 || o < 0) break
            merge(small, o)
        }
        return label
    }
}
