package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.audio.AudioBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-tone pulse generator for BitChord Audio Calibration & Hearing Personalization.
 *
 * Generates gentle pure-tone sine pulse bursts (1, 2, or 3 pulses) at key audiometric frequencies:
 * 125 Hz, 250 Hz, 500 Hz, 1,000 Hz, 2,000 Hz, 4,000 Hz, 8,000 Hz.
 *
 * Each pulse is sculpted with a smooth 5ms Hann window envelope at both start and end
 * to completely eliminate acoustic transient clicks and spectral splatter during audiometric testing.
 *
 * Fully supports channel isolation to ensure pulses are routed strictly to the Left channel
 * or strictly to the Right channel for per-ear hearing threshold testing.
 */
object HearingToneGenerator {

    /** Standard audiometric test frequencies according to ANSI S3.6 / ISO 8253-1. */
    val AUDIOMETRIC_FREQUENCIES_HZ = floatArrayOf(
        125f,
        250f,
        500f,
        1_000f,
        2_000f,
        4_000f,
        8_000f,
    )

    const val DEFAULT_SAMPLE_RATE: Int = 44100
    const val DEFAULT_PULSE_DURATION_MS: Int = 250
    const val DEFAULT_GAP_DURATION_MS: Int = 150
    const val HANN_WINDOW_DURATION_MS: Float = 5.0f
    const val DEFAULT_AMPLITUDE_DB: Float = -12.0f

    enum class Channel {
        LEFT,
        RIGHT,
        BOTH,
    }

    /**
     * Verifies whether [frequencyHz] is one of the standard audiometric test frequencies.
     */
    fun isAudiometricFrequency(frequencyHz: Float): Boolean {
        return AUDIOMETRIC_FREQUENCIES_HZ.any { kotlin.math.abs(it - frequencyHz) < 1.0f }
    }

    /**
     * Calculates the Hann window envelope factor for [frameIndex] out of [totalFrames].
     *
     * @param frameIndex Current frame (0 until [totalFrames]).
     * @param totalFrames Total frames in the pulse.
     * @param fadeFrames Number of frames in the 5ms Hann attack/decay ramp.
     * @return Envelope scaling factor in range [0.0f, 1.0f].
     */
    fun calculateHannEnvelope(frameIndex: Int, totalFrames: Int, fadeFrames: Int): Float {
        if (totalFrames <= 0) return 0.0f
        val effectiveFade = fadeFrames.coerceAtMost(totalFrames / 2).coerceAtLeast(1)

        return when {
            frameIndex < effectiveFade -> {
                // Hann rising envelope: 0.5 * (1 - cos(pi * k / N_fade))
                0.5f * (1.0f - cos(Math.PI.toFloat() * frameIndex / effectiveFade))
            }
            frameIndex >= totalFrames - effectiveFade -> {
                // Hann falling envelope: 0.5 * (1 + cos(pi * decayIndex / N_fade))
                val decayIndex = frameIndex - (totalFrames - effectiveFade)
                0.5f * (1.0f + cos(Math.PI.toFloat() * decayIndex / effectiveFade))
            }
            else -> 1.0f
        }
    }

    /**
     * Generates pure-tone sine pulses as an interleaved FloatArray ([L0, R0, L1, R1, ...]).
     *
     * @param frequencyHz Pure-tone sine frequency in Hz (e.g. 1000f).
     * @param channel Target ear/channel ([Channel.LEFT], [Channel.RIGHT], or [Channel.BOTH]).
     * @param pulseCount Number of beeps in the burst (typically 1, 2, or 3).
     * @param amplitudeDb Sine pulse volume in dB relative to full-scale (e.g. -12f dB).
     * @param sampleRate Sampling rate in Hz (defaults to 44100).
     * @param pulseDurationMs Duration of each beep in milliseconds (defaults to 250ms).
     * @param gapDurationMs Duration of silence between beeps in milliseconds (defaults to 150ms).
     * @param hannWindowMs Duration of Hann window ramp at start & end of each beep (5.0ms).
     * @param leadInMs Duration of lead-in silence before the first beep in milliseconds (defaults to 0ms).
     * @param leadOutMs Duration of lead-out silence after the last beep in milliseconds (defaults to 0ms).
     * @return Interleaved stereo FloatArray with strict channel isolation.
     */
    fun generatePulse(
        frequencyHz: Float,
        channel: Channel,
        pulseCount: Int,
        amplitudeDb: Float = DEFAULT_AMPLITUDE_DB,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        pulseDurationMs: Int = DEFAULT_PULSE_DURATION_MS,
        gapDurationMs: Int = DEFAULT_GAP_DURATION_MS,
        hannWindowMs: Float = HANN_WINDOW_DURATION_MS,
        leadInMs: Int = 0,
        leadOutMs: Int = 0,
    ): FloatArray {
        require(pulseCount in 1..3) { "Pulse count must be 1, 2, or 3: $pulseCount" }
        require(frequencyHz > 0f) { "Frequency must be positive: $frequencyHz" }
        require(sampleRate > 0) { "Sample rate must be positive: $sampleRate" }

        val linearGain = 10f.pow(amplitudeDb / 20f).coerceIn(0f, 1f)
        val pulseFrames = ((pulseDurationMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(1)
        val gapFrames = ((gapDurationMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(0)
        val fadeFrames = ((hannWindowMs / 1000.0f) * sampleRate).roundToInt().coerceAtLeast(1)
        val leadInFrames = if (leadInMs > 0) ((leadInMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(0) else 0
        val leadOutFrames = if (leadOutMs > 0) ((leadOutMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(0) else 0

        val totalFrames = leadInFrames + (pulseCount * pulseFrames) + ((pulseCount - 1) * gapFrames) + leadOutFrames
        val interleavedSamples = FloatArray(totalFrames * 2)

        val phaseIncrement = (2.0 * Math.PI * frequencyHz / sampleRate).toFloat()
        var phase = 0f
        var frameOffset = leadInFrames

        for (p in 0 until pulseCount) {
            // Generate single beep with Hann window envelope
            for (f in 0 until pulseFrames) {
                val envelope = calculateHannEnvelope(f, pulseFrames, fadeFrames)
                val rawSine = sin(phase)
                val sampleValue = envelope * rawSine * linearGain

                phase += phaseIncrement
                if (phase >= 2.0 * Math.PI) {
                    phase -= (2.0 * Math.PI).toFloat()
                }

                val outIdx = (frameOffset + f) * 2
                when (channel) {
                    Channel.LEFT -> {
                        interleavedSamples[outIdx] = sampleValue
                        interleavedSamples[outIdx + 1] = 0.0f
                    }
                    Channel.RIGHT -> {
                        interleavedSamples[outIdx] = 0.0f
                        interleavedSamples[outIdx + 1] = sampleValue
                    }
                    Channel.BOTH -> {
                        interleavedSamples[outIdx] = sampleValue
                        interleavedSamples[outIdx + 1] = sampleValue
                    }
                }
            }
            frameOffset += pulseFrames

            // Inter-pulse silence gap
            if (p < pulseCount - 1) {
                // Gap is already zero-initialized in FloatArray
                frameOffset += gapFrames
                // Reset phase for the next pulse to ensure repeatable starting condition
                phase = 0f
            }
        }

        return interleavedSamples
    }

    /**
     * Generates continuous sub-audible comfort dither frames (~ -90 dBFS).
     * Used as a keep-alive carrier to prevent Bluetooth A2DP sinks and earbud DACs
     * from entering power-saving sleep and swallowing initial tone bursts.
     */
    fun generateKeepAliveCarrier(
        frameCount: Int = 1024,
        ditherAmplitude: Float = 1e-5f,
    ): FloatArray {
        val samples = FloatArray(frameCount * 2)
        for (i in 0 until frameCount) {
            val sign = if (i % 2 == 0) 1.0f else -1.0f
            val v = sign * ditherAmplitude
            samples[i * 2] = v
            samples[i * 2 + 1] = v
        }
        return samples
    }

    /**
     * Generates pure-tone sine pulses into an [AudioBlock] for direct playback through BitChord's DSP chain.
     */
    fun generatePulseToBlock(
        frequencyHz: Float,
        channel: Channel,
        pulseCount: Int,
        amplitudeDb: Float = DEFAULT_AMPLITUDE_DB,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        pulseDurationMs: Int = DEFAULT_PULSE_DURATION_MS,
        gapDurationMs: Int = DEFAULT_GAP_DURATION_MS,
        leadInMs: Int = 0,
        leadOutMs: Int = 0,
    ): AudioBlock {
        val samples = generatePulse(
            frequencyHz = frequencyHz,
            channel = channel,
            pulseCount = pulseCount,
            amplitudeDb = amplitudeDb,
            sampleRate = sampleRate,
            pulseDurationMs = pulseDurationMs,
            gapDurationMs = gapDurationMs,
            leadInMs = leadInMs,
            leadOutMs = leadOutMs,
        )
        val frameCount = samples.size / 2
        val block = AudioBlock(channelCount = 2, capacityFrames = frameCount)
        System.arraycopy(samples, 0, block.samples, 0, samples.size)
        block.setFrameCount(frameCount)
        return block
    }

    /**
     * Generates pure-tone sine pulses into a direct [ByteBuffer] for zero-copy JNI / AudioTrack playback.
     */
    fun generatePulseBuffer(
        frequencyHz: Float,
        channel: Channel,
        pulseCount: Int,
        amplitudeDb: Float = DEFAULT_AMPLITUDE_DB,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        pulseDurationMs: Int = DEFAULT_PULSE_DURATION_MS,
        gapDurationMs: Int = DEFAULT_GAP_DURATION_MS,
        leadInMs: Int = 0,
        leadOutMs: Int = 0,
    ): ByteBuffer {
        val samples = generatePulse(
            frequencyHz = frequencyHz,
            channel = channel,
            pulseCount = pulseCount,
            amplitudeDb = amplitudeDb,
            sampleRate = sampleRate,
            pulseDurationMs = pulseDurationMs,
            gapDurationMs = gapDurationMs,
            leadInMs = leadInMs,
            leadOutMs = leadOutMs,
        )
        val buffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(samples)
        buffer.rewind()
        return buffer
    }
}
