package com.hikari.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The volume booster's gain stage: a fixed software multiplier living INSIDE
 * the ExoPlayer audio sink, so it works on every device.
 *
 * The previous answer was a platform `LoudnessEnhancer` hung on the player's
 * audio session — and devices whose audio HAL has no loudness effect simply
 * refuse it, so on exactly the phones that needed the boost the switch only
 * ever relabelled the volume number (the readout said 200% while the sound
 * stayed at 100%): the reported "200% feels the same as 100%". A gain applied
 * to the decoded PCM before it reaches the HAL cannot be refused — there is
 * no capability to query and no effect to instantiate — so ON is audible
 * everywhere, with hard clipping at full scale so a loud film cannot distort
 * past 0 dBFS.
 *
 * [gain] is read per buffer and is safe to change mid-playback: 1.0 is a
 * bit-exact passthrough, 2.0 is the booster's "200%". Only 16-bit int and
 * 32-bit float PCM are touched; anything else throws out of [configure] and
 * the sink routes around this stage.
 */
@UnstableApi
class VolumeBoostProcessor : AudioProcessor {

    /** The live multiplier. 1.0 = off (passthrough), 2.0 = the booster. */
    @Volatile
    var gain: Float = 1f

    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    /**
     * Two reusable direct buffers, ping-ponged between accumulation and the
     * sink: audio reaches here ~50–100 buffers a second, and the old code did
     * a fresh `allocateDirect` (a native malloc plus Cleaner registration) for
     * EVERY one — including a second allocation whenever two inputs merged —
     * which is pure GC pressure and heat for zero audible difference. The sink
     * consumes at most one handed-out buffer before feeding more input (the
     * contract media3's own BaseAudioProcessor relies on), so two buffers
     * rotating is steady-state allocation-free; growth only ever reallocates
     * when a bigger buffer is genuinely needed.
     */
    private var back: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    /** A handed-out buffer the sink is still reading (at most one — the sink
     *  consumes it before feeding more input). Recycled as spare on the next
     *  pickup. */
    private var outstanding: ByteBuffer? = null
    /** An empty buffer kept for reuse, so growth is the only allocator. */
    private var spare: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        gain != 1f && inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes <= 0) return
        val g = gain
        val acc = accFor(bytes)
        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT && g != 1f) {
            val floats = inputBuffer.asFloatBuffer()
            val outFloats = acc.asFloatBuffer()
            // The view starts at the accumulator's current position: advance
            // it past the samples just written.
            outFloats.position(acc.position() / 4)
            while (floats.hasRemaining()) {
                outFloats.put((floats.get() * g).coerceIn(-1f, 1f))
            }
            acc.position(acc.position() + bytes)
        } else if (g != 1f) {
            val shorts = inputBuffer.asShortBuffer()
            val outShorts = acc.asShortBuffer()
            outShorts.position(acc.position() / 2)
            while (shorts.hasRemaining()) {
                val amplified = (shorts.get() * g).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                outShorts.put(amplified)
            }
            acc.position(acc.position() + bytes)
        } else {
            acc.put(inputBuffer)
        }
        inputBuffer.position(inputBuffer.limit())
    }

    /** The accumulator with room for [extra] more bytes appended, grown only
     *  when it genuinely does not fit (amortised doubling, contents kept). */
    private fun accFor(extra: Int): ByteBuffer {
        var acc = back
        if (acc === AudioProcessor.EMPTY_BUFFER || acc.capacity() - acc.position() < extra) {
            val keep = if (acc === AudioProcessor.EMPTY_BUFFER) 0 else acc.position()
            var cap = maxOf(acc.capacity(), 4096)
            while (cap - keep < extra) cap *= 2
            val grown = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder())
            if (keep > 0) {
                acc.flip()
                grown.put(acc)
            }
            back = grown
            acc = grown
        }
        return acc
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        // The previously handed-out buffer is consumed by now (single live
        // output at a time) — keep the bigger of it and the spare for reuse.
        outstanding?.let { used ->
            used.clear()
            if (used.capacity() > spare.capacity()) spare = used
        }
        outstanding = null
        if (back === AudioProcessor.EMPTY_BUFFER || back.position() == 0) {
            return AudioProcessor.EMPTY_BUFFER
        }
        back.flip()
        val ready = back
        back = spare
        spare = AudioProcessor.EMPTY_BUFFER
        outstanding = ready
        return ready
    }

    override fun isEnded(): Boolean {
        if (!inputEnded) return false
        if (outstanding != null) return false
        return back === AudioProcessor.EMPTY_BUFFER || back.position() == 0
    }

    override fun flush() {
        if (back !== AudioProcessor.EMPTY_BUFFER) back.clear()
        outstanding = null
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}

/**
 * [NextRenderersFactory] (hardware MediaCodec first, FFmpeg software fallback)
 * with [VolumeBoostProcessor] wired into the audio sink — the one place a
 * PCM gain stage can live. The processor instance belongs to the player built
 * from this factory; toggling the booster later only sets its [gain], so no
 * rebuild, no session juggling, and no device capability to refuse.
 */
@UnstableApi
class BoostRenderersFactory(
    context: Context,
    private val boost: VolumeBoostProcessor,
) : NextRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(boost))
            .build()
    }
}
