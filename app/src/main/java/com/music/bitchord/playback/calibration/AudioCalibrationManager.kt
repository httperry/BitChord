package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.audio.NativeDspChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central architect and manager for BitChord Audio Calibration & Hearing Personalization.
 *
 * Coordinates between:
 * 1. Per-ear audiometric hearing profiles ([HearingProfile]).
 * 2. Active output tracking & the Speaker Fallback Rule ([CalibrationRoutingPolicy]).
 * 3. Dual-Channel DSP execution via [NativeDspChain] (ARM NEON SIMD) and [EqualizerProcessor].
 *
 * ## Speaker Fallback Rule:
 * When audio routes to the phone speaker ([AudioRouting.Kind.PHONE]), calibration and
 * personalization are IMMEDIATELY bypassed in the DSP chain to avoid acoustic distortion.
 * Processing is active ONLY when routed to headphones or IEMs ([AudioRouting.Kind.WIRED],
 * [AudioRouting.Kind.USB], or [AudioRouting.Kind.BLUETOOTH]).
 */
class AudioCalibrationManager(
    private val dspChain: DspChain? = null,
    private val nativeDsp: NativeDspChain? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
) {

    private val _activeProfile = MutableStateFlow<HearingProfile?>(null)
    val activeProfile: StateFlow<HearingProfile?> = _activeProfile.asStateFlow()

    private val _isPersonalizationActive = MutableStateFlow(false)
    val isPersonalizationActive: StateFlow<Boolean> = _isPersonalizationActive.asStateFlow()

    private val _bypassReason = MutableStateFlow<String?>(null)
    val bypassReason: StateFlow<String?> = _bypassReason.asStateFlow()

    private val _activeRouteKind = MutableStateFlow(AudioRouting.Kind.PHONE)
    val activeRouteKind: StateFlow<AudioRouting.Kind> = _activeRouteKind.asStateFlow()

    init {
        // Observe output route changes from AudioOutputStatus
        scope.launch {
            AudioOutputStatus.current.collect { snapshot ->
                onOutputRouteChanged(snapshot.routeKind)
            }
        }
    }

    /**
     * Sets or updates the active hearing profile.
     */
    fun setProfile(profile: HearingProfile?) {
        _activeProfile.value = profile
        applyRoutingAndDspPolicy()
    }

    /**
     * Handles route transitions and enforces the Speaker Fallback Rule.
     */
    fun onOutputRouteChanged(routeKind: AudioRouting.Kind) {
        _activeRouteKind.value = routeKind
        dspChain?.activeRouteKind = routeKind
        applyRoutingAndDspPolicy()
    }

    /**
     * Evaluates current route kind and profile, immediately updating DSP chain.
     */
    private fun applyRoutingAndDspPolicy() {
        val route = _activeRouteKind.value
        val profile = _activeProfile.value

        val isAllowed = CalibrationRoutingPolicy.isCalibrationAllowed(route)
        val shouldBypass = !isAllowed || profile == null || !profile.enabled

        if (shouldBypass) {
            val reason = if (!isAllowed) {
                CalibrationRoutingPolicy.getBypassReason(route)
            } else if (profile == null) {
                "No active calibration profile"
            } else {
                "Personalization disabled"
            }
            _bypassReason.value = reason
            _isPersonalizationActive.value = false
            bypassDsp()
        } else {
            _bypassReason.value = null
            _isPersonalizationActive.value = true
            applyDualDsp(profile)
        }
    }

    private fun bypassDsp() {
        // Disengage dual-channel personalization in native NEON DSP
        nativeDsp?.setDualEqualizer(
            enabled = false,
            gainsDbL = null,
            qsL = null,
            gainsDbR = null,
            qsR = null,
            preampDb = 0f,
            balance = 0f,
        )

        // Disengage dual-channel personalization in Kotlin EqualizerProcessor
        dspChain?.equalizer?.setDualTuning(
            enabled = false,
            leftCurve = com.music.bitchord.playback.EqCurve.FLAT,
            rightCurve = com.music.bitchord.playback.EqCurve.FLAT,
            isPersonalization = true,
        )
    }

    private fun applyDualDsp(profile: HearingProfile) {
        val leftCurve = profile.leftCurve
        val rightCurve = profile.rightCurve
        val preampDb = profile.recommendedPreampDb

        // Update ARM NEON SIMD dual-channel biquad cascade
        nativeDsp?.setDualEqualizer(
            enabled = true,
            gainsDbL = leftCurve.gainsDb,
            qsL = leftCurve.qs,
            gainsDbR = rightCurve.gainsDb,
            qsR = rightCurve.qs,
            preampDb = preampDb,
            balance = 0f,
        )

        // Update Kotlin fallback EqualizerProcessor
        dspChain?.equalizer?.setDualTuning(
            enabled = true,
            leftCurve = leftCurve,
            rightCurve = rightCurve,
            isPersonalization = true,
        )
    }
}
