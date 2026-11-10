package com.music.bitchord.playback

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/** The filter a transition rides, shared by both players. */
class TransitionFilter {

    @Volatile
    private var targetLowPassHz: Float = OPEN_HZ

    @Volatile
    private var targetHighPassHz: Float = OFF_HZ

    private var channelCount = 0
    private var sampleRate = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)

    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)

    // Crossover filter (<150Hz 4th-order Butterworth high-pass)
    private var crossoverState = FloatArray(0)
    private val crossoverA1 = FloatArray(STAGES)
    private val crossoverA2 = FloatArray(STAGES)
    private val crossoverA3 = FloatArray(STAGES)
    private val crossoverK = FloatArray(STAGES)

    @Volatile
    private var targetBassCut = false
    private var currentBassCut = false

    // Zero-crossing window state (2-5ms / ~128-256 samples, e.g. 192 samples)
    private var flipWindowLength = DEFAULT_WINDOW_FRAMES
    private val windowGains = FloatArray(MAX_WINDOW_SAMPLES)
    private var flipInProgress = false
    private var flipSampleIndex = 0
    private var flipFromBassCut = false
    private var flipToBassCut = false
    private var flipPendingZeroCross = false
    private var zeroCrossSearchFramesLeft = 0
    private var prevSample = 0f

    private var lowOn = false
    private var highOn = false

    /** True while either section or crossover is doing something audible. */
    val active: Boolean get() = lowOn || highOn || targetBassCut || currentBassCut || flipInProgress

    fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        this.sampleRate = sampleRate
        lowState = FloatArray(channelCount * STAGES * 2)
        highState = FloatArray(channelCount * STAGES * 2)
        crossoverState = FloatArray(channelCount * STAGES * 2)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        updateCrossoverCoefficients()

        flipWindowLength = (sampleRate * FLIP_WINDOW_DURATION_SEC).toInt().coerceIn(MIN_WINDOW_SAMPLES, MAX_WINDOW_SAMPLES)
        val invW = 1.0 / flipWindowLength
        for (i in 0 until flipWindowLength) {
            windowGains[i] = (0.5 * (1.0 - kotlin.math.cos(Math.PI * (i + 0.5) * invW))).toFloat()
        }
        flipInProgress = false
        flipPendingZeroCross = false
        currentBassCut = targetBassCut
    }

    /** Aims the filter. */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /**
     * Arms or performs an instant downbeat low-end crossover flip (<150Hz).
     * When [instant] is true, triggers a smooth zero-crossing crossfade window (2-5ms)
     * preventing dual-kick collision, clicks, DC offset, and limiter pumping.
     */
    fun setBassCut(cut: Boolean, instant: Boolean = true) {
        if (targetBassCut == cut && (!instant || !flipInProgress)) return
        targetBassCut = cut
        if (instant && sampleRate > 0) {
            flipPendingZeroCross = true
            zeroCrossSearchFramesLeft = MAX_ZERO_CROSS_SEARCH_FRAMES
            flipFromBassCut = currentBassCut
            flipToBassCut = cut
            flipInProgress = false
            flipSampleIndex = 0
        } else {
            currentBassCut = cut
            flipInProgress = false
            flipPendingZeroCross = false
        }
    }

    /** Instant downbeat bass swap convenience flip. */
    fun flipBass(cut: Boolean) = setBassCut(cut, instant = true)

    val isBassCut: Boolean get() = targetBassCut || currentBassCut || flipInProgress

    /** Parks both sections and disengages bass cut. */
    fun open() {
        setCutoffs(OPEN_HZ, OFF_HZ)
        setBassCut(false, instant = false)
    }

    /** Parked at both ends, settled, and no bass cut active. */
    val parked: Boolean
        get() = targetLowPassHz >= OPEN_HZ && targetHighPassHz <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ &&
            !targetBassCut && !currentBassCut && !flipInProgress

    /** Moves the cutoffs one sub-block closer to their targets and recomputes coefficients. */
    fun advance() {
        currentLowPassHz = glide(currentLowPassHz, targetLowPassHz)
        currentHighPassHz = glide(currentHighPassHz, targetHighPassHz)
        lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
        highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
        if (lowOn) updateLowCoefficients()
        if (highOn) updateHighCoefficients()
    }

    /** One sample of one channel, filtered by whichever sections and crossover are engaged. */
    fun filter(channel: Int, input: Float): Float {
        var value = input
        if (lowOn) value = lowPass(channel, value)

        // Zero-crossing detection check on first channel
        if (channel == 0 && flipPendingZeroCross) {
            val cross = (prevSample * input <= 0f) || kotlin.math.abs(input) < ZERO_CROSS_EPSILON || zeroCrossSearchFramesLeft <= 0
            if (cross) {
                flipPendingZeroCross = false
                flipInProgress = true
                flipSampleIndex = 0
            } else {
                zeroCrossSearchFramesLeft--
            }
            prevSample = input
        }

        if (flipInProgress) {
            val gain = windowGains[flipSampleIndex]
            val hp = crossoverHighPass(channel, value)
            value = if (flipFromBassCut) {
                // Cut -> Uncut: smoothly restore low-end
                (1f - gain) * hp + gain * value
            } else {
                // Uncut -> Cut: smoothly cut low-end
                (1f - gain) * value + gain * hp
            }
        } else if (currentBassCut) {
            value = crossoverHighPass(channel, value)
        } else if (highOn) {
            value = highPass(channel, value)
        }

        if (channel == channelCount - 1 && flipInProgress) {
            flipSampleIndex++
            if (flipSampleIndex >= flipWindowLength) {
                flipInProgress = false
                currentBassCut = flipToBassCut
                if (!currentBassCut) {
                    crossoverState.fill(0f)
                }
            }
        }
        return value
    }

    /** Block processing with zero allocation for batch audio blocks. */
    fun process(samples: FloatArray, frameCount: Int, channelCount: Int) {
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return
        if (parked && !targetBassCut && !currentBassCut && !flipInProgress) return

        var remaining = frameCount
        var frameOffset = 0
        while (remaining > 0) {
            val subBlock = kotlin.math.min(remaining, GLIDE_FRAMES)
            advance()

            for (f in 0 until subBlock) {
                val baseIdx = (frameOffset + f) * channelCount

                if (flipPendingZeroCross) {
                    val s0 = samples[baseIdx]
                    val cross = (prevSample * s0 <= 0f) || kotlin.math.abs(s0) < ZERO_CROSS_EPSILON || zeroCrossSearchFramesLeft <= 0
                    if (cross) {
                        flipPendingZeroCross = false
                        flipInProgress = true
                        flipSampleIndex = 0
                    } else {
                        zeroCrossSearchFramesLeft--
                    }
                    prevSample = s0
                }

                if (flipInProgress) {
                    val gain = windowGains[flipSampleIndex]
                    for (c in 0 until channelCount) {
                        var sample = samples[baseIdx + c]
                        if (lowOn) sample = lowPass(c, sample)
                        val hp = crossoverHighPass(c, sample)
                        sample = if (flipFromBassCut) {
                            (1f - gain) * hp + gain * sample
                        } else {
                            (1f - gain) * sample + gain * hp
                        }
                        samples[baseIdx + c] = sample
                    }
                    flipSampleIndex++
                    if (flipSampleIndex >= flipWindowLength) {
                        flipInProgress = false
                        currentBassCut = flipToBassCut
                        if (!currentBassCut) {
                            crossoverState.fill(0f)
                        }
                    }
                } else if (currentBassCut) {
                    for (c in 0 until channelCount) {
                        var sample = samples[baseIdx + c]
                        if (lowOn) sample = lowPass(c, sample)
                        sample = crossoverHighPass(c, sample)
                        samples[baseIdx + c] = sample
                    }
                } else if (lowOn || highOn) {
                    for (c in 0 until channelCount) {
                        var sample = samples[baseIdx + c]
                        if (lowOn) sample = lowPass(c, sample)
                        if (highOn) sample = highPass(c, sample)
                        samples[baseIdx + c] = sample
                    }
                }
            }
            frameOffset += subBlock
            remaining -= subBlock
        }
    }

    /** Clears the integrators and snaps the cutoffs to their targets. */
    fun flush() {
        lowState.fill(0f)
        highState.fill(0f)
        crossoverState.fill(0f)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        flipInProgress = false
        flipPendingZeroCross = false
        currentBassCut = targetBassCut
    }

    fun reset() {
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        targetBassCut = false
        currentBassCut = false
        lowState = FloatArray(0)
        highState = FloatArray(0)
        crossoverState = FloatArray(0)
        flipInProgress = false
        flipPendingZeroCross = false
        lowOn = false
        highOn = false
    }

    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    /** Highest cutoff the bilinear transform can still represent without warping to infinity. */
    private fun usableCutoff(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_CUTOFF_FRACTION)

    private fun updateCrossoverCoefficients() {
        if (sampleRate <= 0) return
        val g = tan(Math.PI * usableCutoff(CROSSOVER_HZ) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            crossoverA1[stage] = a1
            crossoverA2[stage] = g * a1
            crossoverA3[stage] = g * (g * a1)
            crossoverK[stage] = k
        }
    }

    private fun updateLowCoefficients() {
        val g = tan(Math.PI * usableCutoff(currentLowPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            lowA1[stage] = a1
            lowA2[stage] = g * a1
            lowA3[stage] = g * (g * a1)
        }
    }

    private fun updateHighCoefficients() {
        val g = tan(Math.PI * usableCutoff(currentHighPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            highA1[stage] = a1
            highA2[stage] = g * a1
            highA3[stage] = g * (g * a1)
            highK[stage] = k
        }
    }

    private fun crossoverHighPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = crossoverState[i]
            val ic2 = crossoverState[i + 1]
            val v3 = value - ic2
            val v1 = crossoverA1[stage] * ic1 + crossoverA2[stage] * v3
            val v2 = ic2 + crossoverA2[stage] * ic1 + crossoverA3[stage] * v3
            crossoverState[i] = 2f * v1 - ic1
            crossoverState[i + 1] = 2f * v2 - ic2
            value -= crossoverK[stage] * v1 + v2
        }
        return value
    }

    private fun lowPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = lowState[i]
            val ic2 = lowState[i + 1]
            val v3 = value - ic2
            val v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            val v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2f * v1 - ic1
            lowState[i + 1] = 2f * v2 - ic2
            value = v2
        }
        return value
    }

    private fun highPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = highState[i]
            val ic2 = highState[i + 1]
            val v3 = value - ic2
            val v1 = highA1[stage] * ic1 + highA2[stage] * v3
            val v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2f * v1 - ic1
            highState[i + 1] = 2f * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    companion object {
        /** A low-pass at or above this is doing nothing audible, so it counts as off. */
        const val OPEN_HZ = 20_000f

        /** A high-pass at or below this is doing nothing audible, so it counts as off. */
        const val OFF_HZ = 20f

        /** Low-end crossover filter cutoff frequency (<150Hz). */
        const val CROSSOVER_HZ = 150.0f

        /** Nothing musical wants the low end lifted above this, and a typo shouldn't be able to. */
        const val MAX_HIGH_PASS_HZ = 2_000f

        /** Frames between coefficient updates. */
        const val GLIDE_FRAMES = 64

        private const val MIN_HZ = 10f

        /** Two cascaded second-order sections: 24 dB/octave, the usual DJ-filter slope. */
        private const val STAGES = 2

        /** Section Qs for a maximally flat (Butterworth) fourth-order response. */
        private val BUTTERWORTH_Q = floatArrayOf(0.54120f, 1.30656f)

        /** Per-sub-block glide fraction. */
        private const val GLIDE_RATE = 0.05f

        /** How close to a parked value counts as parked, so a glide terminates. */
        private const val SETTLED_HZ = 1f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_CUTOFF_FRACTION = 0.45f

        // Zero-crossing window constants (~2-5ms / ~128-256 samples)
        private const val MIN_WINDOW_SAMPLES = 128
        private const val MAX_WINDOW_SAMPLES = 256
        private const val DEFAULT_WINDOW_FRAMES = 192
        private const val FLIP_WINDOW_DURATION_SEC = 0.0035f
        private const val MAX_ZERO_CROSS_SEARCH_FRAMES = 64
        private const val ZERO_CROSS_EPSILON = 0.005f
    }
}
