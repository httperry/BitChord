package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import kotlin.math.max

/**
 * Per-ear hearing calibration profile capturing audiometric threshold offsets
 * and mapping them into independent Left and Right EQ curves.
 *
 * Implements the Half-Gain Rule (prescriptive audiology formula) to compensate
 * for individual hearing loss while strictly preventing digital overs via automatic
 * pre-amp headroom attenuation.
 */
data class HearingProfile(
    val id: String = "default",
    val name: String = "Calibrated Profile",
    val deviceAddress: String? = null,
    val leftThresholdsDb: FloatArray = FloatArray(HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size),
    val rightThresholdsDb: FloatArray = FloatArray(HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size),
    val enabled: Boolean = true,
) {
    init {
        require(leftThresholdsDb.size == HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size) {
            "leftThresholdsDb must have exactly ${HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size} points"
        }
        require(rightThresholdsDb.size == HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size) {
            "rightThresholdsDb must have exactly ${HearingToneGenerator.AUDIOMETRIC_FREQUENCIES_HZ.size} points"
        }
    }

    /**
     * Converts hearing loss threshold in dB HL to target compensation gain in dB using the Half-Gain Rule.
     * Thresholds <= 15 dB HL are considered normal hearing (0 dB boost).
     */
    fun calculateCompensationGain(thresholdDb: Float): Float {
        val loss = (thresholdDb - NORMAL_HEARING_THRESHOLD_DB).coerceAtLeast(0f)
        return (loss * HALF_GAIN_FACTOR).coerceIn(0f, MAX_COMPENSATION_BOOST_DB)
    }

    /**
     * Generates a 10-band [EqCurve] for the specified ear.
     */
    fun curveForEar(isLeft: Boolean): EqCurve {
        val thresholds = if (isLeft) leftThresholdsDb else rightThresholdsDb
        val gains = FloatArray(EqLayout.SLOTS)
        val qs = FloatArray(EqLayout.SLOTS) { 1.0f }

        // Map audiometric frequencies [125, 250, 500, 1000, 2000, 4000, 8000]
        // to BitChord's manual EQ bands [60, 150, 400, 1000, 2500, 6000, 14000]:
        val comp125 = calculateCompensationGain(thresholds[0])
        val comp250 = calculateCompensationGain(thresholds[1])
        val comp500 = calculateCompensationGain(thresholds[2])
        val comp1000 = calculateCompensationGain(thresholds[3])
        val comp2000 = calculateCompensationGain(thresholds[4])
        val comp4000 = calculateCompensationGain(thresholds[5])
        val comp8000 = calculateCompensationGain(thresholds[6])

        gains[0] = comp125 * 0.75f // 60 Hz shelf
        gains[1] = comp125        // 150 Hz
        gains[2] = (comp250 + comp500) * 0.5f // 400 Hz
        gains[3] = comp1000       // 1000 Hz
        gains[4] = comp2000       // 2500 Hz
        gains[5] = (comp4000 + comp8000) * 0.5f // 6000 Hz
        gains[6] = comp8000       // 14000 Hz shelf

        // Tone pad slots 7, 8, 9 remain flat
        gains[7] = 0f
        gains[8] = 0f
        gains[9] = 0f

        return EqCurve.of(gains, qs)
    }

    val leftCurve: EqCurve get() = curveForEar(isLeft = true)
    val rightCurve: EqCurve get() = curveForEar(isLeft = false)

    /**
     * Computes the recommended pre-amp attenuation (in dB) required across both ears
     * to eliminate digital overs and ensure zero audio clipping.
     */
    val recommendedPreampDb: Float
        get() {
            var maxBoost = 0f
            val lCurve = leftCurve
            val rCurve = rightCurve
            for (i in 0 until EqLayout.SLOTS) {
                if (lCurve.gainsDb[i] > maxBoost) maxBoost = lCurve.gainsDb[i]
                if (rCurve.gainsDb[i] > maxBoost) maxBoost = rCurve.gainsDb[i]
            }
            return if (maxBoost > 0f) -maxBoost else 0f
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HearingProfile) return false
        return id == other.id &&
            name == other.name &&
            deviceAddress == other.deviceAddress &&
            enabled == other.enabled &&
            leftThresholdsDb.contentEquals(other.leftThresholdsDb) &&
            rightThresholdsDb.contentEquals(other.rightThresholdsDb)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (deviceAddress?.hashCode() ?: 0)
        result = 31 * result + enabled.hashCode()
        result = 31 * result + leftThresholdsDb.contentHashCode()
        result = 31 * result + rightThresholdsDb.contentHashCode()
        return result
    }

    companion object {
        const val NORMAL_HEARING_THRESHOLD_DB: Float = 15.0f
        const val HALF_GAIN_FACTOR: Float = 0.5f
        const val MAX_COMPENSATION_BOOST_DB: Float = 18.0f

        val FLAT = HearingProfile(id = "flat", name = "Flat (No Compensation)")
    }
}
