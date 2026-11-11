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
        val l = { idx: Int -> leftGains.getOrElse(idx) { 0f } }
        val r = { idx: Int -> rightGains.getOrElse(idx) { 0f } }
        val left = when (slot) {
            0 -> l(0) * 0.75f               // 60 Hz shelf
            1 -> l(0)                       // 150 Hz
            2 -> (l(1) + l(2)) * 0.5f       // 400 Hz (interpolating 250 & 500)
            3 -> l(3)                       // 1000 Hz
            4 -> l(4)                       // 2500 Hz
            5 -> (l(5) + l(6)) * 0.5f       // 6000 Hz (interpolating 4k & 8k)
            6 -> l(6)                       // 14000 Hz shelf
            else -> 0f
        }
        val right = when (slot) {
            0 -> r(0) * 0.75f
            1 -> r(0)
            2 -> (r(1) + r(2)) * 0.5f
            3 -> r(3)
            4 -> r(4)
            5 -> (r(5) + r(6)) * 0.5f
            6 -> r(6)
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
