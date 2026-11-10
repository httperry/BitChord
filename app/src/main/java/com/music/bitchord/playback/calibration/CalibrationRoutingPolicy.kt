package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting

/**
 * Policy enforcing output routing validation and the Speaker Fallback Rule for BitChord Audio Calibration.
 *
 * ## The Speaker Fallback Rule:
 * When audio is routed to the device's built-in speaker ([AudioRouting.Kind.PHONE]),
 * calibration and per-ear hearing personalization must be IMMEDIATELY bypassed in the DSP chain.
 *
 * Calibration/personalization processing is strictly permitted ONLY when routed to
 * headphones or in-ear monitors (IEMs):
 * - [AudioRouting.Kind.WIRED]: 3.5mm / analog wired headphones or headset.
 * - [AudioRouting.Kind.USB]: Dedicated external USB DAC / AMP.
 * - [AudioRouting.Kind.BLUETOOTH]: A2DP / LE Audio wireless headphones, earbuds, or hearing aids.
 */
object CalibrationRoutingPolicy {

    /** Output categories specifically eligible for per-ear hearing personalization. */
    val ALLOWED_CALIBRATION_ROUTES = setOf(
        AudioRouting.Kind.WIRED,
        AudioRouting.Kind.USB,
        AudioRouting.Kind.BLUETOOTH,
    )

    /** Reason message when calibration is bypassed on phone speaker. */
    const val REASON_PHONE_SPEAKER = "Calibration bypassed on device speaker"

    /** Reason message when calibration is bypassed on HDMI or dock. */
    const val REASON_HDMI_OUTPUT = "Calibration bypassed on HDMI output"

    /** Reason message when calibration is bypassed on unsupported routes. */
    const val REASON_UNSUPPORTED_OUTPUT = "Calibration bypassed on unsupported output"

    /**
     * Returns true if [routeKind] is permitted to run per-ear audio calibration.
     */
    fun isCalibrationAllowed(routeKind: AudioRouting.Kind): Boolean {
        return routeKind in ALLOWED_CALIBRATION_ROUTES
    }

    /**
     * Returns true if calibration and personalization must be IMMEDIATELY bypassed in the DSP chain.
     */
    fun shouldBypassCalibration(routeKind: AudioRouting.Kind): Boolean {
        return !isCalibrationAllowed(routeKind)
    }

    /**
     * Resolves human-readable explanation for why calibration is bypassed, or null if allowed.
     */
    fun getBypassReason(routeKind: AudioRouting.Kind): String? = when (routeKind) {
        AudioRouting.Kind.PHONE -> REASON_PHONE_SPEAKER
        AudioRouting.Kind.HDMI -> REASON_HDMI_OUTPUT
        AudioRouting.Kind.OTHER -> REASON_UNSUPPORTED_OUTPUT
        else -> null
    }

    data class OutputTarget(
        val routeKind: AudioRouting.Kind,
        val deviceName: String,
        val deviceAddress: String?,
        val isHeadphoneOrIem: Boolean,
        val isCalibrationAllowed: Boolean,
        val bypassReason: String?,
    )

    /**
     * Evaluates live [AudioOutputStatus.Snapshot] and returns authoritative calibration routing decision.
     */
    fun evaluateOutputTarget(snapshot: AudioOutputStatus.Snapshot): OutputTarget {
        val allowed = isCalibrationAllowed(snapshot.routeKind)
        val reason = getBypassReason(snapshot.routeKind)

        return OutputTarget(
            routeKind = snapshot.routeKind,
            deviceName = snapshot.deviceName,
            deviceAddress = snapshot.deviceAddress,
            isHeadphoneOrIem = allowed,
            isCalibrationAllowed = allowed,
            bypassReason = reason,
        )
    }
}
