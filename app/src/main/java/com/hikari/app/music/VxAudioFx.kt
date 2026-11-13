package com.hikari.app.music

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.hikari.app.data.Logs

/**
 * Android AudioFX layer (VxMusic-style, no native DSP).
 * 15-band target curve mapped onto the device Equalizer bands.
 * Bit-Perfect releases everything.
 */
object VxAudioFx {
    private const val TAG = "HikariVxFx"
    @Volatile private var eq: Equalizer? = null
    @Volatile private var loud: LoudnessEnhancer? = null
    @Volatile private var sessionId: Int = -1

    fun attach(ctx: Context, audioSessionId: Int) {
        try {
            if (audioSessionId == sessionId && (eq != null || VxQuality.bitPerfect(ctx))) return
            release()
            sessionId = audioSessionId
            apply(ctx)
        } catch (e: Exception) {
            Logs.log(TAG, "attach failed: " + e.message)
        }
    }

    fun apply(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val sid = sessionId
            if (sid < 0) return
            // Bit-perfect: no EQ, no enhancer, unity gain.
            if (VxQuality.bitPerfect(app)) {
                release()
                return
            }
            val gains = VxSettings.eqGains(app)
            val eqOn = VxSettings.eqEnabled(app) || VxQuality.studioClarity(app)
            val target = when {
                VxSettings.eqEnabled(app) -> gains
                VxQuality.studioClarity(app) -> VxSettings.STUDIO_MASTER
                else -> List(15) { 0f }
            }
            if (!eqOn) {
                eq?.enabled = false
                loud?.release().let { loud = null }
                // Keep clarity loudness? Off when EQ off unless clarity on.
                if (!VxQuality.studioClarity(app)) return
            }
            var e = eq
            if (e == null) {
                e = runCatching { Equalizer(0, sid) }.getOrNull() ?: return
                eq = e
            }
            val bands = e.numberOfBands.toInt()
            if (bands <= 0) return
            // Map 15 ISO bands onto device bands by nearest center freq.
            for (b in 0 until bands) {
                val freqRange = runCatching { e.getBandFreqRange(b.toShort()) }.getOrNull() ?: continue
                val centerHz = ((freqRange[0] + freqRange[1]) / 2 / 1000)
                var best = 0
                var bestDist = Int.MAX_VALUE
                for (i in VxSettings.EQ_FREQS.indices) {
                    val d = kotlin.math.abs(VxSettings.EQ_FREQS[i] - centerHz)
                    if (d < bestDist) { bestDist = d; best = i }
                }
                val db = target[best].coerceIn(-8f, 8f)
                val range = e.bandLevelRange
                // band level in millibels
                val mb = (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                runCatching { e.setBandLevel(b.toShort(), mb.toShort()) }
            }
            e.enabled = true
            // Gentle loudness for clarity curve only.
            if (VxQuality.studioClarity(app)) {
                if (loud == null) loud = runCatching { LoudnessEnhancer(sid) }.getOrNull()
                runCatching { loud?.setTargetGain(150) }
                runCatching { loud?.enabled = true }
            } else {
                runCatching { loud?.enabled = false }
            }
        } catch (e: Exception) {
            Logs.log(TAG, "apply failed: " + e.message)
        }
    }

    fun release() {
        runCatching { eq?.release() }
        eq = null
        runCatching { loud?.release() }
        loud = null
    }
}
