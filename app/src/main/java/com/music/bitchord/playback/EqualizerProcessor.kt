package com.music.bitchord.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.PcmBoundary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 10-band parametric equaliser, tone controls, pre-amp and left/right balance.
 *
 * ## The layout
 *
 * [EqLayout] fixes the layout of sections. Bands 0 and 9 are shelves (cornered
 * at 60 Hz and 14 kHz); the eight between them are peaking bells. The tone
 * controls (Bass / Treble) share the two shelf sections rather than sitting in
 * series with them, so a curve that touches both adds the two gains together.
 *
 * Balance is an output trim rather than an extra section, and does not run at
 * all for mono files — see [onConfigure].
 *
 * ## State-variable filters
 *
 * Implemented as two-integrator state-variable sections using the bilinear
 * transform with frequency pre-warping (the standard trapezoidal SVF). Chosen
 * because SVFs decouple frequency from Q — changing gain or bandwidth does not
 * shift the centre — and because their internal states stay bounded under
 * parameter modulation, which lets the user drag a band smoothly without
 * generating transients.
 *
 * ## Gliding
 *
 * Every slider change is a target, not a step. The processor updates its
 * coefficients once per [GLIDE_FRAMES] frames, chasing the target curve
 * smoothly. This turns an abrupt preset switch into a short, inaudible glide
 * instead of a transient that would clip or click.
 *
 * ## Overs
 *
 * The make-up only takes back half of a boost ([EqCurve.preampDb]), so the
 * odd peak can land past full scale. On the float path that is left alone
 * here — a later stage may well turn it back down — and caught at the end of
 * the chain by [LoudnessProcessor]'s limiter, which [isShaping] keeps engaged.
 * The 16-bit path has nowhere to keep it, so it runs its own [PeakLimiter].
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {

    /** What the processor is aiming at. Swapped whole, never mutated in place. */
    private class Tuning(
        val leftCurve: EqCurve,
        val rightCurve: EqCurve,
        val balance: Float,
        val isPersonalization: Boolean = false,
        val isEnabled: Boolean = true,
    ) {
        val isDual: Boolean get() = leftCurve !== rightCurve
        companion object {
            val OFF = Tuning(EqCurve.FLAT, EqCurve.FLAT, 0f, isPersonalization = false, isEnabled = false)
            fun linked(curve: EqCurve, balance: Float, isPersonalization: Boolean = false, isEnabled: Boolean = true) =
                Tuning(curve, curve, balance, isPersonalization, isEnabled)
        }
    }

    @Volatile
    private var userTuning: Tuning = Tuning.OFF

    @Volatile
    private var calibrationTuning: Tuning = Tuning.OFF

    @Volatile
    private var target: Tuning = Tuning.OFF

    @Volatile
    private var outputRoute: AudioRouting.Kind = AudioRouting.Kind.PHONE

    /**
     * Updates active output route.
     * Enforces Speaker Fallback Rule: if [route] is PHONE, calibration contributions are bypassed.
     */
    fun setOutputRoute(route: AudioRouting.Kind) {
        val changed = outputRoute != route
        outputRoute = route
        if (changed) {
            recomputeCombinedTarget()
        }
    }

    val currentRoute: AudioRouting.Kind get() = outputRoute

    private fun effectiveTuning(tuning: Tuning): Tuning {
        if (!tuning.isEnabled) return Tuning.OFF
        return if (outputRoute == AudioRouting.Kind.PHONE) {
            // Speaker Fallback Rule: calibration contributions bypassed, standard user EQ still works
            if (userTuning.isEnabled) userTuning else Tuning.OFF
        } else {
            tuning
        }
    }

    private var channelCount = 0
    private var sampleRate = 0

    private val currentGainDb = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val currentQ = Array(2) { FloatArray(EqLayout.SLOTS) { 0.707f } }
    private var currentPreampDb = 0f
    private var currentBalance = 0f

    private val coeffA1 = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val coeffA2 = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val coeffA3 = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val mixInput = Array(2) { FloatArray(EqLayout.SLOTS) { 1f } }
    private val mixBand = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val mixLow = Array(2) { FloatArray(EqLayout.SLOTS) }
    private val precomputedBaseTan = FloatArray(EqLayout.SLOTS)

    /** Which sections are worth running this sub-block, and how many. */
    private val activeSlots = IntArray(EqLayout.SLOTS)
    private val running = BooleanArray(EqLayout.SLOTS)

    /** Two integrator states per section, per channel. */
    private var state = FloatArray(0)

    // Low-shelf crossover attenuation (<150Hz) / bass swap shelf support
    @Volatile
    private var bassSwapShelfEnabled = false

    @Volatile
    private var targetBassSwapAttenuationDb = 0f

    @Volatile
    private var targetBassSwapCutoffHz = BASS_SWAP_SHELF_HZ

    private var currentBassSwapGainDb = 0f
    private var currentBassSwapCutoffHz = BASS_SWAP_SHELF_HZ
    private var bassSwapRunning = false
    private var bassSwapState = FloatArray(0)

    private var bassSwapCoeffA1 = 0f
    private var bassSwapCoeffA2 = 0f
    private var bassSwapCoeffA3 = 0f
    private var bassSwapMixIn = 1f
    private var bassSwapMixBand = 0f
    private var bassSwapMixLow = 0f
    private var bassSwapBaseTan = 0f

    /** Output trim per channel: make-up attenuation, and balance where stereo. */
    private var channelGain = FloatArray(0)

    /** One frame held back on the 16-bit path, so the limiter can see every channel of it. */
    private var frame = FloatArray(0)

    private val limiter = PeakLimiter()

    /**
     * Whether the last float block came out altered. Read by [DspChain] on the
     * audio thread to keep the end-of-chain limiter engaged for as long as
     * this can be pushing peaks past full scale.
     */
    var isShaping: Boolean = false
        private set

    /**
     * Updates standard user equalizer tuning (tone controls or manual sliders + balance).
     */
    fun setUserTuning(enabled: Boolean, curve: EqCurve, balance: Float) {
        userTuning = if (enabled) {
            Tuning.linked(curve, balance.coerceIn(-1f, 1f), isPersonalization = false, isEnabled = true)
        } else {
            Tuning.OFF
        }
        recomputeCombinedTarget()
    }

    /**
     * Independent dual-channel acoustic calibration tuning (device preset + personalization).
     */
    fun setCalibrationTuning(
        enabled: Boolean,
        leftCurve: EqCurve,
        rightCurve: EqCurve,
        isPersonalization: Boolean = true,
    ) {
        calibrationTuning = if (enabled) {
            Tuning(leftCurve, rightCurve, 0f, isPersonalization = isPersonalization, isEnabled = true)
        } else {
            Tuning.OFF
        }
        recomputeCombinedTarget()
    }

    /**
     * Aims the equaliser. Backward-compatible API forwarding to [setUserTuning].
     */
    fun setTuning(enabled: Boolean, curve: EqCurve, balance: Float) {
        setUserTuning(enabled, curve, balance)
    }

    /**
     * Independent dual-channel tuning for per-ear calibration.
     * Backward-compatible API forwarding to [setCalibrationTuning].
     */
    fun setDualTuning(
        enabled: Boolean,
        leftCurve: EqCurve,
        rightCurve: EqCurve,
        balance: Float = 0f,
        isPersonalization: Boolean = true,
    ) {
        setCalibrationTuning(enabled, leftCurve, rightCurve, isPersonalization)
    }

    /**
     * Computes the 3-layer compounded acoustic target:
     * Effective Filter = (Standard Equalizer) + (Device Preset) + (Personal EQ)
     */
    private fun recomputeCombinedTarget() {
        val u = userTuning
        val c = calibrationTuning
        val userEnabled = u.isEnabled && u !== Tuning.OFF
        val calibEnabled = c.isEnabled && c !== Tuning.OFF

        if (!userEnabled && !calibEnabled) {
            target = Tuning.OFF
            logTargetGains(target)
            return
        }

        val leftGains = FloatArray(EqLayout.SLOTS)
        val rightGains = FloatArray(EqLayout.SLOTS)
        val leftQs = FloatArray(EqLayout.SLOTS)
        val rightQs = FloatArray(EqLayout.SLOTS)

        for (slot in 0 until EqLayout.SLOTS) {
            val uGain = if (userEnabled) u.leftCurve.gainsDb[slot] else 0f
            val cLeft = if (calibEnabled) c.leftCurve.gainsDb[slot] else 0f
            val cRight = if (calibEnabled) c.rightCurve.gainsDb[slot] else 0f

            leftGains[slot] = uGain + cLeft
            rightGains[slot] = uGain + cRight

            leftQs[slot] = when {
                userEnabled && u.leftCurve.qs[slot] > 0.05f -> u.leftCurve.qs[slot]
                calibEnabled && c.leftCurve.qs[slot] > 0.05f -> c.leftCurve.qs[slot]
                else -> EqLayout.MANUAL_Q
            }
            rightQs[slot] = when {
                userEnabled && u.rightCurve.qs[slot] > 0.05f -> u.rightCurve.qs[slot]
                calibEnabled && c.rightCurve.qs[slot] > 0.05f -> c.rightCurve.qs[slot]
                else -> EqLayout.MANUAL_Q
            }
        }

        val userPreamp = if (userEnabled) u.leftCurve.preampDb else 0f
        val calibPreamp = if (calibEnabled) minOf(c.leftCurve.preampDb, c.rightCurve.preampDb) else 0f
        val combinedPreamp = minOf(userPreamp, calibPreamp)
        val combinedBalance = if (userEnabled) u.balance else 0f

        val combinedLeftCurve = EqCurve(leftGains, leftQs, combinedPreamp)
        val combinedRightCurve = EqCurve(rightGains, rightQs, combinedPreamp)

        target = Tuning(
            leftCurve = combinedLeftCurve,
            rightCurve = combinedRightCurve,
            balance = combinedBalance,
            isPersonalization = calibEnabled,
            isEnabled = true,
        )
        logTargetGains(target)
    }

    private fun logTargetGains(t: Tuning) {
        val eff = effectiveTuning(t)
        val l = eff.leftCurve.gainsDb
        val r = eff.rightCurve.gainsDb
        // EqLayout.MANUAL_BANDS_HZ: 150Hz = slot 1, 1kHz = slot 3, 6kHz = slot 5
        val g150L = l.getOrElse(1) { 0f }
        val g150R = r.getOrElse(1) { 0f }
        val g1kL = l.getOrElse(3) { 0f }
        val g1kR = r.getOrElse(3) { 0f }
        val g6kL = l.getOrElse(5) { 0f }
        val g6kR = r.getOrElse(5) { 0f }
        val preamp = eff.leftCurve.preampDb

        Log.i(
            TAG,
            "Target updated [route=$outputRoute, userEnabled=${userTuning.isEnabled}, calibEnabled=${calibrationTuning.isEnabled}]: " +
                "150Hz=(L: ${g150L}dB, R: ${g150R}dB), " +
                "1kHz=(L: ${g1kL}dB, R: ${g1kR}dB), " +
                "6kHz=(L: ${g6kL}dB, R: ${g6kR}dB), " +
                "preamp=${preamp}dB"
        )
    }

    val isEnabled: Boolean
        get() {
            val t = effectiveTuning(target)
            return t !== Tuning.OFF && t.isEnabled
        }

    /**
     * Engages or disengages low-shelf crossover attenuation (<150Hz) for the downbeat bass swap.
     * When [enabled], attenuates frequencies below [cutoffHz] (<150Hz) by [attenuationDb]
     * (e.g. -24dB to -48dB) to clear the sub/kick range without phase anomalies or limiter pumping.
     */
    fun setBassSwapShelf(
        enabled: Boolean,
        attenuationDb: Float = DEFAULT_BASS_SWAP_ATTENUATION_DB,
        cutoffHz: Float = BASS_SWAP_SHELF_HZ,
    ) {
        bassSwapShelfEnabled = enabled
        targetBassSwapAttenuationDb = if (enabled) attenuationDb.coerceIn(-96f, 0f) else 0f
        targetBassSwapCutoffHz = cutoffHz.coerceIn(MIN_HZ, if (sampleRate > 0) sampleRate * MAX_FREQUENCY_FRACTION else 20_000f)
        if (sampleRate > 0) {
            bassSwapBaseTan = tan(Math.PI * usableFrequency(targetBassSwapCutoffHz) / sampleRate).toFloat()
        }
    }

    val isBassSwapShelfEnabled: Boolean get() = bassSwapShelfEnabled

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        val requiredSize = channelCount * EqLayout.SLOTS * 2
        if (state.size != requiredSize) {
            state = FloatArray(requiredSize)
        }
        val requiredBassSwapSize = channelCount * 2
        if (bassSwapState.size != requiredBassSwapSize) {
            bassSwapState = FloatArray(requiredBassSwapSize)
        }
        if (channelGain.size != channelCount) {
            channelGain = FloatArray(channelCount) { 1f }
        }
        if (frame.size != channelCount) {
            frame = FloatArray(channelCount)
        }
        if (sampleRate > 0) {
            for (slot in 0 until EqLayout.SLOTS) {
                val spec = EqLayout.slots[slot]
                precomputedBaseTan[slot] = tan(Math.PI * usableFrequency(spec.frequencyHz) / sampleRate).toFloat()
            }
            bassSwapBaseTan = tan(Math.PI * usableFrequency(targetBassSwapCutoffHz) / sampleRate).toFloat()
        }
        running.fill(false)
        bassSwapRunning = false
        limiter.configure(sampleRate)
        snapToTarget()
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: AudioBlock) {
        val frameCount = block.frameCount
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return

        val tuning = effectiveTuning(target)
        val bassSwapActiveOrShaping = bassSwapShelfEnabled || abs(currentBassSwapGainDb) >= SETTLED_DB || abs(targetBassSwapAttenuationDb) >= SETTLED_DB
        if (isFlat(tuning) && isSettled(tuning) && !bassSwapActiveOrShaping) {
            isShaping = false
            return
        }
        isShaping = true

        var remaining = frameCount
        var frameOffset = 0
        while (remaining > 0) {
            val subBlock = min(remaining, GLIDE_FRAMES)
            glideTowards(tuning)
            val active = prepareSections()
            prepareChannelGains()

            currentBassSwapGainDb = linearGlide(currentBassSwapGainDb, targetBassSwapAttenuationDb)
            val bassSwapActive = abs(currentBassSwapGainDb) >= SETTLED_DB
            if (bassSwapActive) {
                updateBassSwapCoefficients()
                bassSwapRunning = true
            } else if (bassSwapRunning) {
                bassSwapState.fill(0f)
                bassSwapRunning = false
            }

            for (f in 0 until subBlock) {
                val baseIdx = (frameOffset + f) * channelCount
                for (channel in 0 until channelCount) {
                    var sample = block.samples[baseIdx + channel]
                    for (index in 0 until active) {
                        sample = section(activeSlots[index], channel, sample)
                    }
                    if (bassSwapActive) {
                        sample = applyBassSwapShelf(channel, sample)
                    }
                    // Headroom is preserved: sample is stored directly as Float
                    block.samples[baseIdx + channel] = sample * channelGain[channel]
                }
            }
            flushDenormals(active)
            if (bassSwapActive) {
                for (i in 0 until channelCount * 2) {
                    if (abs(bassSwapState[i]) < DENORMAL_FLOOR) bassSwapState[i] = 0f
                }
            }
            frameOffset += subBlock
            remaining -= subBlock
        }
    }

    /**
     * 16-bit PCM, any channel count.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Equaliser inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        state.fill(0f)
        bassSwapState.fill(0f)
        running.fill(false)
        bassSwapRunning = false
        currentBassSwapGainDb = targetBassSwapAttenuationDb
        limiter.reset()
        snapToTarget()
    }

    override fun onReset() {
        state = FloatArray(0)
        bassSwapState = FloatArray(0)
        channelGain = FloatArray(0)
        frame = FloatArray(0)
        precomputedBaseTan.fill(0f)
        running.fill(false)
        bassSwapRunning = false
        bassSwapShelfEnabled = false
        targetBassSwapAttenuationDb = 0f
        currentBassSwapGainDb = 0f
        limiter.reset()
        isShaping = false
        channelCount = 0
        sampleRate = 0
        userTuning = Tuning.OFF
        calibrationTuning = Tuning.OFF
        target = Tuning.OFF
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val tuning = effectiveTuning(target)
        val bassSwapActiveOrShaping = bassSwapShelfEnabled || abs(currentBassSwapGainDb) >= SETTLED_DB || abs(targetBassSwapAttenuationDb) >= SETTLED_DB
        if (isFlat(tuning) && isSettled(tuning) && !bassSwapActiveOrShaping) {
            limiter.reset()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val invScale = 1.0f / 32768.0f
        var remaining = frameCount
        while (remaining > 0) {
            val block = min(remaining, GLIDE_FRAMES)
            glideTowards(tuning)
            val active = prepareSections()
            prepareChannelGains()

            currentBassSwapGainDb = linearGlide(currentBassSwapGainDb, targetBassSwapAttenuationDb)
            val bassSwapActive = abs(currentBassSwapGainDb) >= SETTLED_DB
            if (bassSwapActive) {
                updateBassSwapCoefficients()
                bassSwapRunning = true
            } else if (bassSwapRunning) {
                bassSwapState.fill(0f)
                bassSwapRunning = false
            }

            repeat(block) {
                var peak = 0f
                for (channel in 0 until channelCount) {
                    var sample = inputBuffer.short.toFloat() * invScale
                    for (index in 0 until active) {
                        sample = section(activeSlots[index], channel, sample)
                    }
                    if (bassSwapActive) {
                        sample = applyBassSwapShelf(channel, sample)
                    }
                    sample *= channelGain[channel]
                    frame[channel] = sample
                    if (abs(sample) > peak) peak = abs(sample)
                }
                val gain = limiter.gainFor(peak)
                for (channel in 0 until channelCount) {
                    outputBuffer.putShort(PcmBoundary.clamp16FromFloat(frame[channel] * gain))
                }
            }
            flushDenormals(active)
            if (bassSwapActive) {
                for (i in 0 until channelCount * 2) {
                    if (abs(bassSwapState[i]) < DENORMAL_FLOOR) bassSwapState[i] = 0f
                }
            }
            remaining -= block
        }
        outputBuffer.flip()
    }

    // ---- Gliding -----------------------------------------------------------

    private fun snapToTarget() {
        val tuning = effectiveTuning(target)
        tuning.leftCurve.gainsDb.copyInto(currentGainDb[0])
        tuning.rightCurve.gainsDb.copyInto(currentGainDb[1])
        tuning.leftCurve.qs.copyInto(currentQ[0])
        tuning.rightCurve.qs.copyInto(currentQ[1])
        currentPreampDb = minOf(tuning.leftCurve.preampDb, tuning.rightCurve.preampDb)
        currentBalance = tuning.balance
    }

    private fun glideTowards(tuning: Tuning) {
        val eff = effectiveTuning(tuning)
        for (slot in 0 until EqLayout.SLOTS) {
            currentGainDb[0][slot] = linearGlide(currentGainDb[0][slot], eff.leftCurve.gainsDb[slot])
            currentGainDb[1][slot] = linearGlide(currentGainDb[1][slot], eff.rightCurve.gainsDb[slot])
            currentQ[0][slot] = geometricGlide(currentQ[0][slot], eff.leftCurve.qs[slot])
            currentQ[1][slot] = geometricGlide(currentQ[1][slot], eff.rightCurve.qs[slot])
        }
        val targetPreamp = minOf(eff.leftCurve.preampDb, eff.rightCurve.preampDb)
        currentPreampDb = linearGlide(currentPreampDb, targetPreamp)
        currentBalance = linearGlide(currentBalance, eff.balance)
    }

    private fun linearGlide(current: Float, target: Float): Float =
        current + (target - current) * GLIDE_RATE

    private fun geometricGlide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_Q))
        val to = ln(target.coerceAtLeast(MIN_Q))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun isFlat(tuning: Tuning): Boolean {
        val eff = effectiveTuning(tuning)
        if (abs(eff.balance) >= SETTLED_BALANCE) return false
        val targetPreamp = minOf(eff.leftCurve.preampDb, eff.rightCurve.preampDb)
        if (abs(targetPreamp) >= SETTLED_DB) return false
        return eff.leftCurve.gainsDb.all { abs(it) < SETTLED_DB } &&
            eff.rightCurve.gainsDb.all { abs(it) < SETTLED_DB }
    }

    private fun isSettled(tuning: Tuning): Boolean {
        val eff = effectiveTuning(tuning)
        if (abs(currentBalance - eff.balance) >= SETTLED_BALANCE) return false
        val targetPreamp = minOf(eff.leftCurve.preampDb, eff.rightCurve.preampDb)
        if (abs(currentPreampDb - targetPreamp) >= SETTLED_DB) return false
        for (slot in 0 until EqLayout.SLOTS) {
            if (abs(currentGainDb[0][slot] - eff.leftCurve.gainsDb[slot]) >= SETTLED_DB) return false
            if (abs(currentGainDb[1][slot] - eff.rightCurve.gainsDb[slot]) >= SETTLED_DB) return false
        }
        return true
    }

    // ---- Coefficients ------------------------------------------------------

    private fun prepareSections(): Int {
        var active = 0
        for (slot in 0 until EqLayout.SLOTS) {
            val activeL = abs(currentGainDb[0][slot]) >= SETTLED_DB
            val activeR = abs(currentGainDb[1][slot]) >= SETTLED_DB
            if (activeL || activeR) {
                updateCoefficients(slot, 0)
                updateCoefficients(slot, 1)
                activeSlots[active++] = slot
                running[slot] = true
            } else if (running[slot]) {
                clearState(slot)
                running[slot] = false
            }
        }
        return active
    }

    private fun updateCoefficients(slot: Int, ch: Int) {
        if (sampleRate <= 0) return
        val spec = EqLayout.slots[slot]
        val a = 10f.pow(currentGainDb[ch][slot] / 40f)
        val q = currentQ[ch][slot].coerceAtLeast(MIN_Q)
        val base = precomputedBaseTan[slot]
        val g: Float
        val k: Float
        when (spec.kind) {
            FilterKind.BELL -> {
                g = base
                k = 1f / (q * a)
                mixInput[ch][slot] = 1f
                mixBand[ch][slot] = k * (a * a - 1f)
                mixLow[ch][slot] = 0f
            }
            FilterKind.LOW_SHELF -> {
                g = base / sqrt(a)
                k = 1f / q
                mixInput[ch][slot] = 1f
                mixBand[ch][slot] = k * (a - 1f)
                mixLow[ch][slot] = a * a - 1f
            }
            FilterKind.HIGH_SHELF -> {
                g = base * sqrt(a)
                k = 1f / q
                mixInput[ch][slot] = a * a
                mixBand[ch][slot] = k * (1f - a) * a
                mixLow[ch][slot] = 1f - a * a
            }
        }
        val d = 1f / (1f + g * (g + k))
        coeffA1[ch][slot] = d
        coeffA2[ch][slot] = g * d
        coeffA3[ch][slot] = g * (g * d)
    }

    private fun usableFrequency(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_FREQUENCY_FRACTION)

    private fun prepareChannelGains() {
        val preamp = 10f.pow(currentPreampDb / 20f)
        if (channelCount == 2) {
            channelGain[0] = preamp * min(1f, 1f - currentBalance)
            channelGain[1] = preamp * min(1f, 1f + currentBalance)
        } else {
            channelGain.fill(preamp)
        }
    }

    // ---- Filter ------------------------------------------------------------

    private fun section(slot: Int, channel: Int, input: Float): Float {
        val chCoeff = if (channel < 2) channel else 0
        val i = (channel * EqLayout.SLOTS + slot) * 2
        val ic1 = state[i]
        val ic2 = state[i + 1]
        val v3 = input - ic2
        val v1 = coeffA1[chCoeff][slot] * ic1 + coeffA2[chCoeff][slot] * v3
        val v2 = ic2 + coeffA2[chCoeff][slot] * ic1 + coeffA3[chCoeff][slot] * v3
        state[i] = 2f * v1 - ic1
        state[i + 1] = 2f * v2 - ic2
        return mixInput[chCoeff][slot] * input + mixBand[chCoeff][slot] * v1 + mixLow[chCoeff][slot] * v2
    }

    private fun clearState(slot: Int) {
        for (channel in 0 until channelCount) {
            val i = (channel * EqLayout.SLOTS + slot) * 2
            state[i] = 0f
            state[i + 1] = 0f
        }
    }

    private fun flushDenormals(active: Int) {
        for (index in 0 until active) {
            val slot = activeSlots[index]
            for (channel in 0 until channelCount) {
                val i = (channel * EqLayout.SLOTS + slot) * 2
                if (abs(state[i]) < DENORMAL_FLOOR) state[i] = 0f
                if (abs(state[i + 1]) < DENORMAL_FLOOR) state[i + 1] = 0f
            }
        }
    }

    private fun updateBassSwapCoefficients() {
        if (sampleRate <= 0) return
        val a = 10f.pow(currentBassSwapGainDb / 40f)
        val q = 0.707f
        val base = bassSwapBaseTan
        val g = base / sqrt(a)
        val k = 1f / q
        bassSwapMixIn = 1f
        bassSwapMixBand = k * (a - 1f)
        bassSwapMixLow = a * a - 1f
        val d = 1f / (1f + g * (g + k))
        bassSwapCoeffA1 = d
        bassSwapCoeffA2 = g * d
        bassSwapCoeffA3 = g * (g * d)
    }

    private fun applyBassSwapShelf(channel: Int, input: Float): Float {
        val i = channel * 2
        val ic1 = bassSwapState[i]
        val ic2 = bassSwapState[i + 1]
        val v3 = input - ic2
        val v1 = bassSwapCoeffA1 * ic1 + bassSwapCoeffA2 * v3
        val v2 = ic2 + bassSwapCoeffA2 * ic1 + bassSwapCoeffA3 * v3
        bassSwapState[i] = 2f * v1 - ic1
        bassSwapState[i + 1] = 2f * v2 - ic2
        return bassSwapMixIn * input + bassSwapMixBand * v1 + bassSwapMixLow * v2
    }

    companion object {
        private const val TAG = "BitChordEqualizer"

        private const val BYTES_PER_SAMPLE = 2

        /** Crossover cutoff for downbeat bass swap low-shelf attenuation (<150Hz). */
        const val BASS_SWAP_SHELF_HZ = 150.0f

        /** Default attenuation for bass swap low-shelf cut in dB. */
        const val DEFAULT_BASS_SWAP_ATTENUATION_DB = -24.0f

        /** Frames between coefficient updates. ~1.5 ms at 44.1 kHz. */
        private const val GLIDE_FRAMES = 64

        /** Per-sub-block glide fraction. ~18 ms time constant — a fast drag still tracks. */
        private const val GLIDE_RATE = 0.08f

        /** Below this a band is doing nothing anyone can hear, so it counts as flat. */
        private const val SETTLED_DB = 0.01f

        /** Same idea for the balance trim, where the scale is -1 to 1. */
        private const val SETTLED_BALANCE = 0.0005f

        private const val MIN_Q = 0.05f
        private const val MIN_HZ = 10f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_FREQUENCY_FRACTION = 0.45f

        /** Safe noise floor threshold to prevent floating point denormals. */
        private const val DENORMAL_FLOOR = 1e-12f
    }
}
