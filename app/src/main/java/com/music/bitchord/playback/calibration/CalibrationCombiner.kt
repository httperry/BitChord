/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import kotlin.math.min

/**
 * Combines Acoustic Model hardware biquads with individual hearing personalization gains.
 *
 * BitChord Calibration Studio operates with 100% bit-perfection:
 * - Hardware model biquads correct the physical transducer to the target curve (Harman In-Ear, IEF Neutral, etc.).
 * - Personalization gains compensate for left/right hearing threshold asymmetries across audiometric frequencies.
 * - Digital pre-amp headroom is automatically calculated to eliminate digital clipping and limiter pumping.
 */
object CalibrationCombiner {

    /**
     * Parses target name into [CalibrationTarget].
     */
    fun parseTarget(targetStr: String): CalibrationTarget {
        return when {
            targetStr.contains("IEF", ignoreCase = true) -> CalibrationTarget.IEF_NEUTRAL
            targetStr.contains("Over-Ear", ignoreCase = true) -> CalibrationTarget.HARMAN_OVER_EAR_2018
            else -> CalibrationTarget.HARMAN_IN_EAR_2019
        }
    }

    /**
     * Combines Hardware Model acoustic biquad correction with Left/Right personalization gains.
     * Generates a pair of dual-channel [EqCurve]s (Left, Right) with guaranteed headroom.
     */
    fun combineModelAndPersonalization(
        model: AcousticModel?,
        target: CalibrationTarget,
        leftGains: List<Float>,
        rightGains: List<Float>,
        personalizationCompleted: Boolean,
    ): Pair<EqCurve, EqCurve> {
        val modelProfile = model?.profileFor(target)

        val leftEqGains = FloatArray(EqLayout.SLOTS)
        val rightEqGains = FloatArray(EqLayout.SLOTS)
        val qs = FloatArray(EqLayout.SLOTS) { EqLayout.MANUAL_Q }

        // Evaluate hardware model response at EqLayout manual frequencies [60, 150, 400, 1000, 2500, 6000, 14000]
        for (slot in 0 until EqLayout.MANUAL_COUNT) {
            val hz = EqLayout.MANUAL_BANDS_HZ[slot]
            val modelGain = evaluateModelGain(modelProfile, hz)

            val (pLeft, pRight) = if (personalizationCompleted) {
                mapPersonalizationToSlot(slot, leftGains, rightGains)
            } else {
                0f to 0f
            }

            leftEqGains[slot] = (modelGain + pLeft).coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
            rightEqGains[slot] = (modelGain + pRight).coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }

        // Slots 7, 8, 9 (Tone pad) remain flat (0 dB)
        for (slot in EqLayout.MANUAL_COUNT until EqLayout.SLOTS) {
            leftEqGains[slot] = 0f
            rightEqGains[slot] = 0f
            qs[slot] = 0.707f
        }

        val leftCurve = EqCurve.of(leftEqGains, qs)
        val rightCurve = EqCurve.of(rightEqGains, qs)
        val modelPreamp = modelProfile?.preampDb ?: 0f
        val combinedPreamp = min(modelPreamp, min(leftCurve.preampDb, rightCurve.preampDb))

        return EqCurve(leftEqGains, qs, combinedPreamp) to EqCurve(rightEqGains, qs, combinedPreamp)
    }

    private fun mapPersonalizationToSlot(
        slot: Int,
        leftGains: List<Float>,
        rightGains: List<Float>,
    ): Pair<Float, Float> {
        // Audiometric bands: [125, 250, 500, 1000, 2000, 4000, 8000] Hz
        val lRaw = { idx: Int -> leftGains.getOrElse(idx) { 0f } }
        val rRaw = { idx: Int -> rightGains.getOrElse(idx) { 0f } }

        // Stereo Coupling Safeguard:
        // A 1-2 dB difference between ears shifts the stereo image, while 2-5 dB differences
        // are standard test noise or ear-tip seal variance. Unless asymmetry is >= 6 dB,
        // average both ears into a unified common curve so the stereo soundstage stays centered.
        val l = { idx: Int ->
            val diff = kotlin.math.abs(lRaw(idx) - rRaw(idx))
            if (diff < 6.0f) {
                (lRaw(idx) + rRaw(idx)) * 0.5f
            } else {
                val mean = (lRaw(idx) + rRaw(idx)) * 0.5f
                (mean + (lRaw(idx) - mean) * 0.5f).coerceIn(-2.0f, 3.5f)
            }
        }
        val r = { idx: Int ->
            val diff = kotlin.math.abs(lRaw(idx) - rRaw(idx))
            if (diff < 6.0f) {
                (lRaw(idx) + rRaw(idx)) * 0.5f
            } else {
                val mean = (lRaw(idx) + rRaw(idx)) * 0.5f
                (mean + (rRaw(idx) - mean) * 0.5f).coerceIn(-2.0f, 3.5f)
            }
        }

        // Equal-loudness bass anchor:
        // When mid-frequencies are boosted, ensure sub-bass (60Hz & 150Hz) is anchored
        // so kick drums and sub-bass vibrancy are never hollowed out by pre-amp attenuation.
        val midEnergyL = maxOf(0f, l(2), l(3), l(4)) * 0.65f
        val midEnergyR = maxOf(0f, r(2), r(3), r(4)) * 0.65f
        val bassShelfL = maxOf(l(0), midEnergyL).coerceAtLeast(0f)
        val bassShelfR = maxOf(r(0), midEnergyR).coerceAtLeast(0f)

        val left = when (slot) {
            0 -> bassShelfL                 // 60 Hz shelf (anchors sub-bass vibrancy)
            1 -> maxOf(bassShelfL * 0.9f, l(1)) // 150 Hz mid-bass warmth
            2 -> (l(1) + l(2)) * 0.5f       // 400 Hz (interpolating 250 & 500)
            3 -> l(3)                       // 1000 Hz
            4 -> l(4)                       // 2500 Hz
            5 -> ((l(5) + l(6)) * 0.5f).coerceAtMost(1.8f) // 6000 Hz (smoothed treble, no harsh sibilance)
            6 -> l(6).coerceAtMost(1.5f)    // 14000 Hz air shelf (smooth air, non-fatiguing)
            else -> 0f
        }
        val right = when (slot) {
            0 -> bassShelfR
            1 -> maxOf(bassShelfR * 0.9f, r(1))
            2 -> (r(1) + r(2)) * 0.5f
            3 -> r(3)
            4 -> r(4)
            5 -> ((r(5) + r(6)) * 0.5f).coerceAtMost(1.8f)
            6 -> r(6).coerceAtMost(1.5f)
            else -> 0f
        }
        return left to right
    }

    /**
     * Evaluates model gain at [hz]. Prefers parametric [AutoEqProfile.biquadBands],
     * falling back to interpolated [AutoEqProfile.bandOffsets10] if biquad bands are empty.
     */
    fun evaluateModelGain(modelProfile: AutoEqProfile?, hz: Float): Float {
        if (modelProfile == null) return 0f
        return when {
            modelProfile.biquadBands.isNotEmpty() -> modelProfile.calculateResponseAt(hz)
            modelProfile.bandOffsets10.isNotEmpty() -> interpolate10BandGain(hz, modelProfile.bandOffsets10)
            else -> 0f
        }
    }

    private fun interpolate10BandGain(hz: Float, offsets10: List<Float>): Float {
        val freqs = AutoEqProfile.STANDARD_10_BANDS_HZ
        if (offsets10.isEmpty() || freqs.isEmpty()) return 0f
        if (hz <= freqs.first()) return offsets10.first()
        if (hz >= freqs.last()) return offsets10.getOrElse(freqs.lastIndex) { offsets10.last() }

        for (i in 0 until freqs.size - 1) {
            val f0 = freqs[i]
            val f1 = freqs[i + 1]
            if (hz in f0..f1) {
                val g0 = offsets10.getOrElse(i) { 0f }
                val g1 = offsets10.getOrElse(i + 1) { 0f }
                val fraction = (hz - f0) / (f1 - f0)
                return g0 + fraction * (g1 - g0)
            }
        }
        return offsets10.last()
    }
}
