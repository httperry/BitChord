package com.music.bitchord.playback.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HearingToneGeneratorTest {

    @Test
    fun standardAudiometricFrequenciesAreValid() {
        val expected = floatArrayOf(125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f)
        for (f in expected) {
            assertTrue("Expected $f Hz to be audiometric frequency", HearingToneGenerator.isAudiometricFrequency(f))
        }
        assertFalse(HearingToneGenerator.isAudiometricFrequency(60f))
        assertFalse(HearingToneGenerator.isAudiometricFrequency(3000f))
        assertFalse(HearingToneGenerator.isAudiometricFrequency(14000f))
    }

    @Test
    fun hannWindowEnvelopeIsSmoothAndClickFree() {
        val sampleRate = 44100
        val fadeFrames = 221 // ~5ms
        val totalFrames = 1000

        // At frame 0: must start at exact zero
        val env0 = HearingToneGenerator.calculateHannEnvelope(0, totalFrames, fadeFrames)
        assertEquals(0.0f, env0, 1e-6f)

        // Mid-ramp should be strictly positive and less than 1.0
        val envMid = HearingToneGenerator.calculateHannEnvelope(fadeFrames / 2, totalFrames, fadeFrames)
        assertTrue("Mid-ramp must be strictly between 0 and 1: $envMid", envMid in 0.01f..0.99f)

        // At end of attack: must reach 1.0 (sustain)
        val envSustain = HearingToneGenerator.calculateHannEnvelope(fadeFrames, totalFrames, fadeFrames)
        assertEquals(1.0f, envSustain, 1e-6f)

        // In sustain plateau: must remain 1.0
        val envPlat = HearingToneGenerator.calculateHannEnvelope(totalFrames / 2, totalFrames, fadeFrames)
        assertEquals(1.0f, envPlat, 1e-6f)

        // At start of decay: must be 1.0
        val envDecayStart = HearingToneGenerator.calculateHannEnvelope(totalFrames - fadeFrames, totalFrames, fadeFrames)
        assertEquals(1.0f, envDecayStart, 1e-6f)

        // Monotonicity during attack ramp
        var prev = 0.0f
        for (f in 0 until fadeFrames) {
            val cur = HearingToneGenerator.calculateHannEnvelope(f, totalFrames, fadeFrames)
            assertTrue("Attack ramp must be monotonically non-decreasing at frame $f: cur=$cur, prev=$prev", cur >= prev)
            prev = cur
        }
    }

    @Test
    fun leftChannelIsolationStrictlyZeroesRightChannel() {
        val samples = HearingToneGenerator.generatePulse(
            frequencyHz = 1000f,
            channel = HearingToneGenerator.Channel.LEFT,
            pulseCount = 1,
            amplitudeDb = -6f,
            sampleRate = 44100,
            pulseDurationMs = 200,
        )

        val frameCount = samples.size / 2
        var leftNonZero = false

        for (f in 0 until frameCount) {
            val left = samples[f * 2]
            val right = samples[f * 2 + 1]

            // Right channel must be strictly 0.0f everywhere
            assertEquals("Right channel must be 0.0f in left isolation at frame $f", 0.0f, right, 0.0f)
            if (abs(left) > 0.01f) {
                leftNonZero = true
            }
        }
        assertTrue("Left channel must contain non-zero audio samples", leftNonZero)
    }

    @Test
    fun rightChannelIsolationStrictlyZeroesLeftChannel() {
        val samples = HearingToneGenerator.generatePulse(
            frequencyHz = 4000f,
            channel = HearingToneGenerator.Channel.RIGHT,
            pulseCount = 1,
            amplitudeDb = -6f,
            sampleRate = 44100,
            pulseDurationMs = 200,
        )

        val frameCount = samples.size / 2
        var rightNonZero = false

        for (f in 0 until frameCount) {
            val left = samples[f * 2]
            val right = samples[f * 2 + 1]

            // Left channel must be strictly 0.0f everywhere
            assertEquals("Left channel must be 0.0f in right isolation at frame $f", 0.0f, left, 0.0f)
            if (abs(right) > 0.01f) {
                rightNonZero = true
            }
        }
        assertTrue("Right channel must contain non-zero audio samples", rightNonZero)
    }

    @Test
    fun pulseCountProducesCorrectBeepSequenceAndSilenceGaps() {
        val sampleRate = 44100
        val pulseMs = 100
        val gapMs = 50
        val pulseFrames = (pulseMs * sampleRate) / 1000
        val gapFrames = (gapMs * sampleRate) / 1000

        // Test for 1, 2, and 3 pulses
        for (count in 1..3) {
            val samples = HearingToneGenerator.generatePulse(
                frequencyHz = 1000f,
                channel = HearingToneGenerator.Channel.BOTH,
                pulseCount = count,
                amplitudeDb = -6f,
                sampleRate = sampleRate,
                pulseDurationMs = pulseMs,
                gapDurationMs = gapMs,
            )

            val expectedFrames = (count * pulseFrames) + ((count - 1) * gapFrames)
            assertEquals("Total frames mismatch for pulse count $count", expectedFrames * 2, samples.size)

            // Verify silence in the gap between pulse 1 and pulse 2
            if (count > 1) {
                val gapStartFrame = pulseFrames
                for (gf in 0 until gapFrames) {
                    val idx = (gapStartFrame + gf) * 2
                    assertEquals("Gap L must be silence", 0.0f, samples[idx], 0.0f)
                    assertEquals("Gap R must be silence", 0.0f, samples[idx + 1], 0.0f)
                }
            }
        }
    }
}
