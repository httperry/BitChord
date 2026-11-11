package com.music.bitchord.playback.calibration

import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSpeakerFallbackTest {

    @Test
    fun speakerFallbackRuleBypassesCalibrationOnPhoneSpeaker() {
        // Built-in device speaker must ALWAYS bypass calibration
        assertFalse(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.PHONE))
        assertTrue(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.PHONE))
        assertEquals(
            "Calibration bypassed on device speaker",
            CalibrationRoutingPolicy.getBypassReason(AudioRouting.Kind.PHONE),
        )
    }

    @Test
    fun calibrationOnlyAllowedOnHeadphonesAndIems() {
        // Wired 3.5mm headset
        assertTrue(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.WIRED))
        assertFalse(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.WIRED))
        assertNull(CalibrationRoutingPolicy.getBypassReason(AudioRouting.Kind.WIRED))

        // USB DAC
        assertTrue(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.USB))
        assertFalse(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.USB))
        assertNull(CalibrationRoutingPolicy.getBypassReason(AudioRouting.Kind.USB))

        // Bluetooth headphones / buds
        assertTrue(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.BLUETOOTH))
        assertFalse(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.BLUETOOTH))
        assertNull(CalibrationRoutingPolicy.getBypassReason(AudioRouting.Kind.BLUETOOTH))

        // External speakers / HDMI must also bypass
        assertFalse(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.HDMI))
        assertTrue(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.HDMI))

        assertFalse(CalibrationRoutingPolicy.isCalibrationAllowed(AudioRouting.Kind.OTHER))
        assertTrue(CalibrationRoutingPolicy.shouldBypassCalibration(AudioRouting.Kind.OTHER))
    }

    @Test
    fun equalizerProcessorImmediatelyBypassesPersonalizationOnPhoneSpeaker() {
        val eq = EqualizerProcessor()

        // Create an asymmetric hearing calibration curve: +6dB in Left ear, +12dB in Right ear
        val leftGains = FloatArray(EqLayout.SLOTS) { 0f }.apply { this[3] = 6.0f }
        val rightGains = FloatArray(EqLayout.SLOTS) { 0f }.apply { this[3] = 12.0f }
        val qs = FloatArray(EqLayout.SLOTS) { 1.0f }

        val leftCurve = EqCurve.of(leftGains, qs)
        val rightCurve = EqCurve.of(rightGains, qs)

        // Set dual tuning for personalization
        eq.setDualTuning(
            enabled = true,
            leftCurve = leftCurve,
            rightCurve = rightCurve,
            balance = 0f,
            isPersonalization = true,
        )

        // Route: WIRED (Headphones) -> Active
        eq.setOutputRoute(AudioRouting.Kind.WIRED)
        assertTrue("Personalization must be active on wired headphones", eq.isEnabled)

        // Route: PHONE (Speaker Fallback Rule) -> MUST IMMEDIATELY BYPASS
        eq.setOutputRoute(AudioRouting.Kind.PHONE)
        assertFalse("Personalization must be IMMEDIATELY bypassed on phone speaker", eq.isEnabled)

        // Route: BLUETOOTH -> Restores active personalization
        eq.setOutputRoute(AudioRouting.Kind.BLUETOOTH)
        assertTrue("Personalization must re-engage on Bluetooth headphones", eq.isEnabled)

        // Route: USB -> Restores active personalization
        eq.setOutputRoute(AudioRouting.Kind.USB)
        assertTrue("Personalization must engage on USB DAC", eq.isEnabled)
    }

    @Test
    fun hearingProfileConvertsLossToPerEarCompensationCurvesWithPreamp() {
        // Left ear has mild loss (+30 dB HL at 1kHz, 2kHz), Right ear is normal (<15 dB HL)
        val leftLoss = floatArrayOf(10f, 10f, 15f, 35f, 40f, 15f, 10f)
        val rightLoss = floatArrayOf(10f, 10f, 15f, 10f, 15f, 10f, 10f)

        val profile = HearingProfile(
            id = "test-profile",
            name = "Test Ear Profile",
            leftThresholdsDb = leftLoss,
            rightThresholdsDb = rightLoss,
            enabled = true,
        )

        val leftCurve = profile.leftCurve
        val rightCurve = profile.rightCurve

        // Left ear: 1000 Hz (index 3) should have boost: (35 - 15) * 0.5 = 10.0 dB
        assertEquals(10.0f, leftCurve.gainsDb[3], 0.1f)
        // Left ear: 2500 Hz (index 4) should have boost: (40 - 15) * 0.5 = 12.5 dB
        assertEquals(12.5f, leftCurve.gainsDb[4], 0.1f)

        // Right ear should remain flat (0.0 dB across all bands)
        for (slot in 0 until EqLayout.SLOTS) {
            assertEquals("Right ear slot $slot must be 0dB", 0.0f, rightCurve.gainsDb[slot], 0.01f)
        }

        // Preamp attenuation must be negative of the max boost (-12.5 dB) to guarantee zero clipping
        assertTrue("Recommended preamp must be negative to prevent digital overs", profile.recommendedPreampDb <= -12.0f)
    }

    @Test
    fun evaluateOutputTargetTracksBluetoothAndUsbDescriptors() {
        val btSnapshot = AudioOutputStatus.Snapshot(
            deviceName = "Sony WH-1000XM5",
            deviceAddress = "00:11:22:33:44:55",
            routeKind = AudioRouting.Kind.BLUETOOTH,
        )
        val target = CalibrationRoutingPolicy.evaluateOutputTarget(btSnapshot)
        assertEquals(AudioRouting.Kind.BLUETOOTH, target.routeKind)
        assertEquals("Sony WH-1000XM5", target.deviceName)
        assertEquals("00:11:22:33:44:55", target.deviceAddress)
        assertTrue(target.isHeadphoneOrIem)
        assertTrue(target.isCalibrationAllowed)
        assertNull(target.bypassReason)

        val speakerSnapshot = AudioOutputStatus.Snapshot(
            deviceName = "This phone",
            routeKind = AudioRouting.Kind.PHONE,
        )
        val speakerTarget = CalibrationRoutingPolicy.evaluateOutputTarget(speakerSnapshot)
        assertFalse(speakerTarget.isCalibrationAllowed)
        assertFalse(speakerTarget.isHeadphoneOrIem)
        assertNotNull(speakerTarget.bypassReason)
    }

    @Test
    fun acousticThreeLayerStackingEnforcesUserPlusCalibrationAndSpeakerFallback() {
        val eq = EqualizerProcessor()

        // 1. User Standard EQ: +3dB at 1kHz (slot 3)
        val userGains = FloatArray(EqLayout.SLOTS) { 0f }.apply { this[3] = 3.0f }
        val userQs = FloatArray(EqLayout.SLOTS) { 1.0f }
        val userCurve = EqCurve.of(userGains, userQs)
        eq.setUserTuning(enabled = true, curve = userCurve, balance = 0.2f)

        // 2. Calibration Tuning: Left +2dB at 1kHz, Right +5dB at 1kHz
        val calibLeftGains = FloatArray(EqLayout.SLOTS) { 0f }.apply { this[3] = 2.0f }
        val calibRightGains = FloatArray(EqLayout.SLOTS) { 0f }.apply { this[3] = 5.0f }
        val calibQs = FloatArray(EqLayout.SLOTS) { 1.0f }
        val calibLeftCurve = EqCurve.of(calibLeftGains, calibQs)
        val calibRightCurve = EqCurve.of(calibRightGains, calibQs)
        eq.setCalibrationTuning(enabled = true, leftCurve = calibLeftCurve, rightCurve = calibRightCurve)

        // Route: WIRED -> Both active (Effective = User + Calibration)
        eq.setOutputRoute(AudioRouting.Kind.WIRED)
        assertTrue("Processor must be enabled on wired", eq.isEnabled)

        // Route: PHONE -> Calibration bypassed, but User EQ MUST STILL BE ACTIVE!
        eq.setOutputRoute(AudioRouting.Kind.PHONE)
        assertTrue("User EQ must remain active on PHONE speaker fallback", eq.isEnabled)

        // If User EQ disabled while on PHONE -> Fully bypassed
        eq.setUserTuning(enabled = false, curve = userCurve, balance = 0f)
        assertFalse("When user EQ disabled, speaker output must be flat/bypassed", eq.isEnabled)

        // Switching back to WIRED -> Calibration re-engages!
        eq.setOutputRoute(AudioRouting.Kind.WIRED)
        assertTrue("Calibration must re-engage on WIRED even if user EQ is disabled", eq.isEnabled)
    }
}
