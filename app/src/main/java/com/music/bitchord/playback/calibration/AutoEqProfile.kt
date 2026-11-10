package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Standard acoustic calibration target curves.
 *
 * BitChord Calibration Studio supports Harman In-Ear, Harman Over-Ear,
 * and Crinacle's IEF Neutral target curves.
 */
@Serializable
enum class CalibrationTarget(val displayName: String, val description: String) {
    HARMAN_IN_EAR_2019(
        "Harman In-Ear 2019",
        "Harman research target for in-ear monitors with calibrated sub-bass boost and pinna elevation"
    ),
    HARMAN_OVER_EAR_2018(
        "Harman Over-Ear 2018",
        "Harman research target for over-ear headphones with smooth bass shelf and balanced treble"
    ),
    IEF_NEUTRAL(
        "IEF Neutral",
        "In-Ear Fidelity diffuse-field neutral target curve with linear bass and natural timbre"
    )
}

/**
 * Filter topology kind for parametric equalization sections.
 */
@Serializable
enum class BiquadFilterType {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF
}

/**
 * A second-order parametric biquad filter section.
 *
 * @property filterType The biquad filter topology (PEAKING bell, LOW_SHELF, or HIGH_SHELF).
 * @property frequencyHz Center or corner frequency in Hertz [10Hz .. 22050Hz].
 * @property gainDb Gain in decibels [-30dB .. +30dB].
 * @property q Quality factor (bandwidth indicator). Butterworth shelf standard is 0.707f.
 */
@Serializable
data class BiquadBand(
    val filterType: BiquadFilterType = BiquadFilterType.PEAKING,
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float = 1.414f
) {
    init {
        require(frequencyHz in 10f..22050f) {
            "Frequency $frequencyHz Hz out of audible range [10..22050]"
        }
        require(q > 0.01f) {
            "Q factor must be positive, got $q"
        }
    }

    /**
     * Calculates the frequency response gain of this filter section at [hz] in decibels.
     * Evaluated using the continuous-time analog prototype matching BitChord's SVF bilinear engine.
     */
    fun responseAt(hz: Float): Float {
        if (abs(gainDb) < 0.005f) return 0f
        val a = 10.0.pow(gainDb / 40.0)
        val x = (hz / frequencyHz).toDouble().coerceAtLeast(1e-6)
        val x2 = x * x
        val qq = (q * q).toDouble().coerceAtLeast(1e-6)

        val mag = when (filterType) {
            BiquadFilterType.PEAKING -> {
                val flat = (1.0 - x2) * (1.0 - x2)
                val a2 = a * a
                sqrt((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)))
            }
            BiquadFilterType.LOW_SHELF -> {
                val common = x2 * a / qq
                a * sqrt(((a - x2) * (a - x2) + common) / ((1.0 - a * x2) * (1.0 - a * x2) + common))
            }
            BiquadFilterType.HIGH_SHELF -> {
                val common = x2 * a / qq
                a * sqrt(((1.0 - a * x2) * (1.0 - a * x2) + common) / ((a - x2) * (a - x2) + common))
            }
        }
        return (20.0 * log10(mag.coerceAtLeast(1e-12))).toFloat()
    }
}

/**
 * Acoustic calibration profile containing parametric biquad bands and pre-calculated
 * headroom attenuation for a specific target curve.
 *
 * @property target The target sound curve.
 * @property preampDb Headroom pre-amp gain in decibels (<= 0.0 dB) to eliminate digital clipping and limiter pumping.
 * @property biquadBands List of parametric biquad bands implementing the calibration curve.
 * @property bandOffsets10 10-band calibration gains matching ISO standard 10-band EQ frequencies.
 */
@Serializable
data class AutoEqProfile(
    val target: CalibrationTarget,
    val preampDb: Float,
    val biquadBands: List<BiquadBand>,
    val bandOffsets10: List<Float> = emptyList()
) {
    /**
     * Evaluates total composite frequency response gain in dB at [hz] (excluding pre-amp).
     */
    fun calculateResponseAt(hz: Float): Float {
        var sum = 0f
        for (i in biquadBands.indices) {
            sum += biquadBands[i].responseAt(hz)
        }
        return sum
    }

    /**
     * Evaluates the peak gain boost across the audible spectrum (20 Hz - 20 kHz).
     */
    fun computePeakBoostDb(points: Int = 256): Float {
        var peak = 0f
        // Include filter center frequencies as exact evaluation points
        for (band in biquadBands) {
            val gain = calculateResponseAt(band.frequencyHz)
            if (gain > peak) peak = gain
        }
        // Sample logarithmically spaced points across 20Hz - 20kHz
        for (i in 0 until points) {
            val fraction = i.toDouble() / (points - 1)
            val hz = (20.0 * 1000.0.pow(fraction)).toFloat()
            val gain = calculateResponseAt(hz)
            if (gain > peak) peak = gain
        }
        return peak
    }

    /**
     * Calculates the necessary digital pre-amp attenuation (in negative dB) required to ensure
     * that applying this profile never exceeds 0 dBFS, preventing digital clipping and limiter pumping.
     *
     * @param safetyMarginDb Additional headroom margin in dB (default 0.05 dB).
     */
    fun computeRecommendedPreampDb(safetyMarginDb: Float = 0.05f): Float {
        val peak = computePeakBoostDb()
        return if (peak > 0f) {
            -(peak + safetyMarginDb)
        } else {
            0f
        }
    }

    /**
     * Returns true if the configured [preampDb] provides full bit-perfect headroom
     * (i.e. maximum signal gain <= 0.0 dBFS).
     */
    fun hasBitPerfectHeadroom(): Boolean {
        val peak = computePeakBoostDb()
        return (peak + preampDb) <= 0.005f
    }

    /**
     * Converts this profile into a BitChord [EqCurve] by mapping into [EqLayout.slots].
     */
    fun toEqCurve(): EqCurve {
        val gains = FloatArray(EqLayout.SLOTS)
        val qs = FloatArray(EqLayout.SLOTS) { 0.707f }

        // Sample the exact response at the frequencies of BitChord's EqLayout slots
        for (slot in 0 until EqLayout.SLOTS) {
            val spec = EqLayout.slots[slot]
            val evaluated = calculateResponseAt(spec.frequencyHz)
            gains[slot] = evaluated.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
            qs[slot] = EqLayout.MANUAL_Q
        }

        return EqCurve(gains, qs, preampDb.coerceAtMost(0f))
    }

    /**
     * Converts this profile into a 7-band manual [EqCurve] matching BitChord's manual sliders.
     */
    fun toManualCurve(): EqCurve {
        val gains = EqLayout.MANUAL_BANDS_HZ.map { hz ->
            calculateResponseAt(hz).coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
        return com.music.bitchord.playback.manualCurve(gains)
    }

    companion object {
        /** ISO standard 10-band graphic equalizer center frequencies in Hertz */
        val STANDARD_10_BANDS_HZ = floatArrayOf(
            31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        /**
         * Creates an [AutoEqProfile] from biquad bands, automatically computing the optimal
         * bit-perfect pre-amp attenuation and 10-band offsets.
         */
        fun create(
            target: CalibrationTarget,
            biquadBands: List<BiquadBand>,
            explicitPreampDb: Float? = null
        ): AutoEqProfile {
            val tempProfile = AutoEqProfile(target, 0f, biquadBands, emptyList())
            val offsets = STANDARD_10_BANDS_HZ.map { hz ->
                val g = tempProfile.calculateResponseAt(hz)
                (round(g * 10f) / 10f)
            }
            val safePreamp = explicitPreampDb ?: run {
                val recommended = tempProfile.computeRecommendedPreampDb(safetyMarginDb = 0.05f)
                (round(recommended * 10f) / 10f)
            }
            return AutoEqProfile(
                target = target,
                preampDb = safePreamp,
                biquadBands = biquadBands,
                bandOffsets10 = offsets
            )
        }
    }
}
