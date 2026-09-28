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
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
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
        val g = gain
        if (g == 1f || !inputBuffer.hasRemaining()) {
            if (inputBuffer.hasRemaining()) {
                val copy = ByteBuffer.allocateDirect(inputBuffer.remaining())
                    .order(ByteOrder.nativeOrder())
                copy.put(inputBuffer)
                copy.flip()
                replaceOutput(copy)
            }
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val bytes = inputBuffer.remaining()
        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val floats = inputBuffer.asFloatBuffer()
            val outFloats = out.asFloatBuffer()
            while (floats.hasRemaining()) {
                outFloats.put((floats.get() * g).coerceIn(-1f, 1f))
            }
            out.position(bytes)
        } else {
            val shorts = inputBuffer.asShortBuffer()
            val outShorts = out.asShortBuffer()
            while (shorts.hasRemaining()) {
                val amplified = (shorts.get() * g).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                outShorts.put(amplified)
            }
            out.position(bytes)
        }
        out.flip()
        inputBuffer.position(inputBuffer.limit())
        replaceOutput(out)
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    private fun replaceOutput(buffer: ByteBuffer) {
        if (outputBuffer === AudioProcessor.EMPTY_BUFFER) {
            outputBuffer = buffer
            return
        }
        val merged = ByteBuffer.allocateDirect(outputBuffer.remaining() + buffer.remaining())
            .order(ByteOrder.nativeOrder())
        merged.put(outputBuffer)
        merged.put(buffer)
        merged.flip()
        outputBuffer = merged
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
