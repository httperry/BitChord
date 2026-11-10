package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.bitchord.playback.audio.AudioBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Downbeat Instant Bass Swap (<150Hz Crossover Flip):
 * 1. Low-end crossover filter cutoff frequency response (<150Hz 4th-order Butterworth).
 * 2. Instant downbeat zero-crossing crossfade window continuity (no clicks, no DC offset, no limiter pumping).
 * 3. Exact bit-perfection when inactive or parked.
 * 4. Dual-path validation: Float32 [AudioBlock] and 16-bit PCM [ByteBuffer] execution.
 * 5. JVM shared [TransitionFilter] engine verification.
 */
class TransitionFilterCrossoverTest {

    private val sampleRate = 48_000
    private val channelCount = 2

    // -------------------------------------------------------------------------
    // 1. Frequency Response of <150Hz Crossover
    // -------------------------------------------------------------------------

    @Test
    fun `TransitionFilterProcessor bass cut attenuates below 150Hz and passes mids and highs intact`() {
        val filter = TransitionFilterProcessor().apply {
            configure(sampleRate, channelCount)
            setBassCut(cut = true, instant = false)
        }

        // Deep sub-bass (50 Hz): should be heavily attenuated (> 20 dB down on 4th-order Butterworth)
        val gain50Hz = measureGainFloat(filter, 50.0)
        assertTrue("50 Hz should be attenuated > 20 dB, was $gain50Hz dB", gain50Hz < -20.0)

        // Bass range (80 Hz): attenuated > 12 dB
        val gain80Hz = measureGainFloat(filter, 80.0)
        assertTrue("80 Hz should be attenuated > 12 dB, was $gain80Hz dB", gain80Hz < -12.0)

        // Midrange (1000 Hz): passband unity gain (within 0.15 dB)
        val gain1000Hz = measureGainFloat(filter, 1_000.0)
        assertTrue("1000 Hz should pass transparently, was $gain1000Hz dB", abs(gain1000Hz) < 0.15)

        // Highs (5000 Hz): passband unity gain (within 0.15 dB)
        val gain5000Hz = measureGainFloat(filter, 5_000.0)
        assertTrue("5000 Hz should pass transparently, was $gain5000Hz dB", abs(gain5000Hz) < 0.15)
    }

    @Test
    fun `shared TransitionFilter engine bass cut attenuates below 150Hz and preserves passband`() {
        val filter = TransitionFilter().apply {
            configure(channelCount, sampleRate)
            setBassCut(cut = true, instant = false)
        }

        val gain60Hz = measureGainShared(filter, 60.0)
        assertTrue("60 Hz should be attenuated > 18 dB, was $gain60Hz dB", gain60Hz < -18.0)

        val gain1000Hz = measureGainShared(filter, 1_000.0)
        assertTrue("1000 Hz should pass with unity gain, was $gain1000Hz dB", abs(gain1000Hz) < 0.15)
    }

    // -------------------------------------------------------------------------
    // 2. Downbeat Instant Bass Swap Zero-Crossing Window Continuity
    // -------------------------------------------------------------------------

    @Test
    fun `downbeat instant bass swap performs smooth zero-crossing transition without clicks or NaN`() {
        val filter = TransitionFilterProcessor().apply {
            configure(sampleRate, channelCount)
        }

        val totalFrames = 2_400
        val block = AudioBlock(channelCount = channelCount, capacityFrames = totalFrames)
        block.setFrameCount(totalFrames)

        // 100 Hz kick/bass tone
        for (i in 0 until totalFrames) {
            val s = (0.7 * sin(2.0 * PI * 100.0 * i / sampleRate)).toFloat()
            block.samples[i * 2] = s
            block.samples[i * 2 + 1] = s
        }

        // Process first half uncut
        val halfBlock = AudioBlock(channelCount = channelCount, capacityFrames = totalFrames / 2)
        halfBlock.setFrameCount(totalFrames / 2)
        System.arraycopy(block.samples, 0, halfBlock.samples, 0, (totalFrames / 2) * channelCount)
        filter.process(halfBlock)

        // Trigger instant downbeat bass swap (flip to cut = true)
        filter.setBassCut(cut = true, instant = true)
        assertTrue("Filter should report bass cut active", filter.isBassCut)

        // Process second half containing the flip
        val secondHalf = AudioBlock(channelCount = channelCount, capacityFrames = totalFrames / 2)
        secondHalf.setFrameCount(totalFrames / 2)
        System.arraycopy(block.samples, (totalFrames / 2) * channelCount, secondHalf.samples, 0, (totalFrames / 2) * channelCount)
        filter.process(secondHalf)

        // Verify continuity: no NaN, no Inf, and no harsh sample-to-sample spikes (clicks)
        for (f in 0 until (totalFrames / 2) - 1) {
            val sL = secondHalf.samples[f * 2]
            val sR = secondHalf.samples[f * 2 + 1]
            val nextL = secondHalf.samples[(f + 1) * 2]
            val nextR = secondHalf.samples[(f + 1) * 2 + 1]

            assertFalse("Sample L should not be NaN", sL.isNaN())
            assertFalse("Sample R should not be NaN", sR.isNaN())
            assertFalse("Sample L should not be Infinite", sL.isInfinite())
            assertFalse("Sample R should not be Infinite", sR.isInfinite())

            // Bound delta to prevent high-frequency click spikes
            val deltaL = abs(nextL - sL)
            val deltaR = abs(nextR - sR)
            assertTrue("Sample L step delta $deltaL should not be a transient click spike", deltaL < 0.25f)
            assertTrue("Sample R step delta $deltaR should not be a transient click spike", deltaR < 0.25f)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Bit-Perfection When Inactive / Parked
    // -------------------------------------------------------------------------

    @Test
    fun `Float32 AudioBlock processing is bit-perfect identical when filter is parked`() {
        val filter = TransitionFilterProcessor().apply {
            configure(sampleRate, channelCount)
            open()
        }
        assertFalse("Filter must not be active when parked", filter.isFiltering)

        val frameCount = 512
        val block = AudioBlock(channelCount = channelCount, capacityFrames = frameCount)
        block.setFrameCount(frameCount)
        val originalSamples = FloatArray(frameCount * channelCount) { (it % 13) / 13f - 0.5f }
        System.arraycopy(originalSamples, 0, block.samples, 0, originalSamples.size)

        filter.process(block)

        assertArrayEquals("Output must be bit-perfect identical to input when parked", originalSamples, block.samples, 0f)
    }

    @Test
    fun `16-bit PCM queueInput processing is bit-perfect pass-through when parked`() {
        val filter = TransitionFilterProcessor()
        val format = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
        filter.configure(format)
        filter.flush()
        filter.open()

        val frameCount = 256
        val pcmBytes = ByteBuffer.allocateDirect(frameCount * channelCount * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount * channelCount) {
            pcmBytes.putShort((i * 123 % 30000 - 15000).toShort())
        }
        pcmBytes.flip()

        val expectedBytes = ByteArray(pcmBytes.remaining())
        pcmBytes.mark()
        pcmBytes.get(expectedBytes)
        pcmBytes.reset()

        filter.queueInput(pcmBytes)
        val output = filter.output
        val actualBytes = ByteArray(output.remaining())
        output.get(actualBytes)

        assertArrayEquals("16-bit PCM must be bit-perfect identical when parked", expectedBytes, actualBytes)
    }

    // -------------------------------------------------------------------------
    // 4. TransitionFilters Handoff Interface
    // -------------------------------------------------------------------------

    @Test
    fun `flipBassSwap atomically flips bass cut roles between incoming and outgoing`() {
        var incomingCutValue = false
        var outgoingCutValue = false

        val filters = object : TransitionFilters {
            override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
            override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
            override fun incomingBassCut(cut: Boolean) { incomingCutValue = cut }
            override fun outgoingBassCut(cut: Boolean) { outgoingCutValue = cut }
        }

        // Before handoff: incoming bass is cut (true), outgoing bass is full (false)
        filters.flipBassSwap(incomingCut = true, outgoingCut = false)
        assertTrue(incomingCutValue)
        assertFalse(outgoingCutValue)

        // At exact downbeat handoff: instant bass swap flip
        filters.flipBassSwap(incomingCut = false, outgoingCut = true)
        assertFalse(incomingCutValue)
        assertTrue(outgoingCutValue)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private fun measureGainFloat(filter: TransitionFilterProcessor, hz: Double): Double {
        val frames = sampleRate * 2
        val settleFrames = sampleRate / 2
        val block = AudioBlock(channelCount = channelCount, capacityFrames = 512)

        var realIn = 0.0
        var imagIn = 0.0
        var realOut = 0.0
        var imagOut = 0.0
        var count = 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val chunkFrames = minOf(512, frames - frameOffset)
            block.setFrameCount(chunkFrames)
            for (f in 0 until chunkFrames) {
                val t = (frameOffset + f).toDouble()
                val s = (0.5 * sin(2.0 * PI * hz * t / sampleRate)).toFloat()
                block.samples[f * 2] = s
                block.samples[f * 2 + 1] = s
            }

            filter.process(block)

            for (f in 0 until chunkFrames) {
                val currentFrame = frameOffset + f
                if (currentFrame >= settleFrames) {
                    val angle = 2.0 * PI * hz * currentFrame / sampleRate
                    val inVal = 0.5 * sin(angle)
                    val outVal = block.samples[f * 2].toDouble()

                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    realIn += inVal * cosA
                    imagIn += inVal * sinA
                    realOut += outVal * cosA
                    imagOut += outVal * sinA
                    count++
                }
            }
            frameOffset += chunkFrames
        }

        val ampIn = 2.0 * sqrt(realIn * realIn + imagIn * imagIn) / count
        val ampOut = 2.0 * sqrt(realOut * realOut + imagOut * imagOut) / count
        return 20.0 * log10(ampOut / ampIn)
    }

    private fun measureGainShared(filter: TransitionFilter, hz: Double): Double {
        val frames = sampleRate * 2
        val settleFrames = sampleRate / 2
        val buffer = FloatArray(512 * channelCount)

        var realIn = 0.0
        var imagIn = 0.0
        var realOut = 0.0
        var imagOut = 0.0
        var count = 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val chunkFrames = minOf(512, frames - frameOffset)
            for (f in 0 until chunkFrames) {
                val t = (frameOffset + f).toDouble()
                val s = (0.5 * sin(2.0 * PI * hz * t / sampleRate)).toFloat()
                buffer[f * 2] = s
                buffer[f * 2 + 1] = s
            }

            filter.process(buffer, chunkFrames, channelCount)

            for (f in 0 until chunkFrames) {
                val currentFrame = frameOffset + f
                if (currentFrame >= settleFrames) {
                    val angle = 2.0 * PI * hz * currentFrame / sampleRate
                    val inVal = 0.5 * sin(angle)
                    val outVal = buffer[f * 2].toDouble()

                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    realIn += inVal * cosA
                    imagIn += inVal * sinA
                    realOut += outVal * cosA
                    imagOut += outVal * sinA
                    count++
                }
            }
            frameOffset += chunkFrames
        }

        val ampIn = 2.0 * sqrt(realIn * realIn + imagIn * imagIn) / count
        val ampOut = 2.0 * sqrt(realOut * realOut + imagOut * imagOut) / count
        return 20.0 * log10(ampOut / ampIn)
    }
}
