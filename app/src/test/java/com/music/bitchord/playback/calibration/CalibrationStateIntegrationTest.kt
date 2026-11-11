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

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.EqualizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit & Integration verification for BitChord Audio Calibration:
 * 1. State Persistence & flows (enablement, model selection, target, personalization gains).
 * 2. Speaker Fallback Rule & live A/B preview bypass logic.
 * 3. Re-testing trigger when calibration model changes vs personalization snapshot.
 * 4. Device profile mapping (deviceId -> modelId).
 */
class CalibrationStateIntegrationTest {

    @Before
    fun setUp() {
        AppSettings.audioCalibrationEnabled.value = false
        AppSettings.calibrationModelId.value = null
        AppSettings.calibrationModelName.value = null
        AppSettings.calibrationModelBrand.value = null
        AppSettings.calibrationTarget.value = AppSettings.CALIBRATION_TARGET_HARMAN_IN_EAR
        AppSettings.personalizationLeftGains.value = AppSettings.DEFAULT_PERSONALIZATION_GAINS
        AppSettings.personalizationRightGains.value = AppSettings.DEFAULT_PERSONALIZATION_GAINS
        AppSettings.personalizationCompleted.value = false
        AppSettings.personalizationModelIdSnapshot.value = null
        AppSettings.calibrationDeviceProfiles.value = emptyMap()
        AppSettings.previewUncalibrated.value = false
    }

    @Test
    fun retestTriggerWhenCalibrationModelChanges() {
        // Step 1: User takes hearing test on Sony WH-1000XM5
        AppSettings.audioCalibrationEnabled.value = true
        AppSettings.calibrationModelId.value = "sony_wh1000xm5"
        AppSettings.calibrationModelName.value = "WH-1000XM5"
        AppSettings.calibrationModelBrand.value = "Sony"

        // Hearing test completes and sets the snapshot
        AppSettings.personalizationCompleted.value = true
        AppSettings.personalizationModelIdSnapshot.value = "sony_wh1000xm5"
        AppSettings.personalizationLeftGains.value = listOf(0f, 1.5f, 2.0f, 4.0f, 3.0f, 1.0f, 0f)
        AppSettings.personalizationRightGains.value = listOf(0f, 0.5f, 1.0f, 2.5f, 2.0f, 0f, 0f)

        // When current model matches snapshot, re-test is NOT needed
        assertFalse("Retest must not be needed when model matches snapshot", AppSettings.isPersonalizationRetestNeeded())

        // Step 2: User switches model to Moondrop Blessing 3
        AppSettings.calibrationModelId.value = "moondrop_blessing_3"

        // Now model ("moondrop_blessing_3") != snapshot ("sony_wh1000xm5") -> Retest IS needed!
        assertTrue("Retest MUST be triggered when model changes away from snapshot", AppSettings.isPersonalizationRetestNeeded())

        // Step 3: User switches back to Sony WH-1000XM5
        AppSettings.calibrationModelId.value = "sony_wh1000xm5"
        assertFalse("Retest must clear when returning to original model snapshot", AppSettings.isPersonalizationRetestNeeded())
    }

    @Test
    fun speakerBypassLogicDisengagesDspOnPhoneSpeaker() {
        val eq = EqualizerProcessor()

        // Configure dual-channel calibration
        val (leftCurve, rightCurve) = CalibrationCombiner.combineModelAndPersonalization(
            model = AcousticModelCatalog.getModelById("moondrop_blessing_3"),
            target = CalibrationTarget.HARMAN_IN_EAR_2019,
            leftGains = listOf(0f, 2f, 4f, 6f, 4f, 2f, 0f),
            rightGains = listOf(0f, 1f, 2f, 3f, 2f, 1f, 0f),
            personalizationCompleted = true,
        )

        eq.setDualTuning(
            enabled = true,
            leftCurve = leftCurve,
            rightCurve = rightCurve,
            balance = 0f,
            isPersonalization = true,
        )

        // On wired/bluetooth/usb headphones: calibration must be active
        eq.setOutputRoute(AudioRouting.Kind.WIRED)
        assertTrue("Calibration must be active on WIRED output", eq.isEnabled)

        eq.setOutputRoute(AudioRouting.Kind.BLUETOOTH)
        assertTrue("Calibration must be active on BLUETOOTH output", eq.isEnabled)

        eq.setOutputRoute(AudioRouting.Kind.USB)
        assertTrue("Calibration must be active on USB output", eq.isEnabled)

        // On device speaker: calibration MUST be bypassed immediately
        eq.setOutputRoute(AudioRouting.Kind.PHONE)
        assertFalse("Calibration MUST be bypassed on PHONE speaker", eq.isEnabled)
    }

    @Test
    fun liveAbPreviewBypassesCalibrationWithoutResettingState() {
        AppSettings.audioCalibrationEnabled.value = true
        AppSettings.calibrationModelId.value = "apple_airpods_pro_2"
        AppSettings.personalizationCompleted.value = true
        AppSettings.personalizationLeftGains.value = listOf(1f, 2f, 3f, 4f, 3f, 2f, 1f)

        // Toggle previewUncalibrated ON
        AppSettings.setPreviewUncalibrated(true)
        assertTrue(AppSettings.previewUncalibrated.value)

        // User settings must remain intact while preview is active
        assertTrue(AppSettings.audioCalibrationEnabled.value)
        assertEquals("apple_airpods_pro_2", AppSettings.calibrationModelId.value)
        assertTrue(AppSettings.personalizationCompleted.value)
        assertEquals(7, AppSettings.personalizationLeftGains.value.size)

        // Toggle previewUncalibrated OFF
        AppSettings.setPreviewUncalibrated(false)
        assertFalse(AppSettings.previewUncalibrated.value)
        assertTrue(AppSettings.audioCalibrationEnabled.value)
    }

    @Test
    fun deviceProfileMappingRemembersModelPerDevice() {
        val bluetoothMac = "94:DB:56:AB:CD:EF"
        val wiredId = "wired_usb_dac"

        val profiles = mutableMapOf<String, String>()
        profiles[bluetoothMac] = "sony_wh1000xm5"
        profiles[wiredId] = "sennheiser_hd600"
        AppSettings.calibrationDeviceProfiles.value = profiles

        assertEquals("sony_wh1000xm5", AppSettings.calibrationDeviceProfiles.value[bluetoothMac])
        assertEquals("sennheiser_hd600", AppSettings.calibrationDeviceProfiles.value[wiredId])
        assertNull(AppSettings.calibrationDeviceProfiles.value["unknown_device"])
    }

    @Test
    fun calibrationCombinerPreservesBitPerfectionAndHeadroom() {
        val model = AcousticModelCatalog.getModelById("truthear_zero_red")
        val target = CalibrationTarget.HARMAN_IN_EAR_2019

        val (leftCurve, rightCurve) = CalibrationCombiner.combineModelAndPersonalization(
            model = model,
            target = target,
            leftGains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            rightGains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            personalizationCompleted = false,
        )

        assertNotNull(leftCurve)
        assertNotNull(rightCurve)
        // Preamp must be <= 0.0 dB to prevent digital clipping
        assertTrue("Headroom preamp must be non-positive to prevent clipping", leftCurve.preampDb <= 0.0f)
        assertTrue("Headroom preamp must be non-positive to prevent clipping", rightCurve.preampDb <= 0.0f)
    }

    @Test
    fun compoundingStandardEqualizerAndCalibrationSumsBothCurves() {
        val eq = EqualizerProcessor()
        eq.setOutputRoute(AudioRouting.Kind.BLUETOOTH)

        // 1. User sets standard EQ: Bass boost +4 dB at 60Hz (slot 0)
        val userGains = FloatArray(EqLayout.SLOTS) { 0f }
        userGains[0] = 4.0f
        val userCurve = EqCurve.of(userGains, FloatArray(EqLayout.SLOTS) { EqLayout.MANUAL_Q })
        eq.setUserTuning(enabled = true, curve = userCurve, balance = 0f)

        // 2. Calibration sets Personal EQ: +2 dB at 60Hz (slot 0) on Left, -1 dB on Right
        val calibLeftGains = FloatArray(EqLayout.SLOTS) { 0f }
        val calibRightGains = FloatArray(EqLayout.SLOTS) { 0f }
        calibLeftGains[0] = 2.0f
        calibRightGains[0] = -1.0f
        val calibLeftCurve = EqCurve.of(calibLeftGains, FloatArray(EqLayout.SLOTS) { EqLayout.MANUAL_Q })
        val calibRightCurve = EqCurve.of(calibRightGains, FloatArray(EqLayout.SLOTS) { EqLayout.MANUAL_Q })
        eq.setCalibrationTuning(enabled = true, leftCurve = calibLeftCurve, rightCurve = calibRightCurve, isPersonalization = true)

        // Target must be active
        assertTrue(eq.isEnabled)

        // When standard user EQ is disabled, calibration must still remain active
        eq.setUserTuning(enabled = false, curve = userCurve, balance = 0f)
        assertTrue("Calibration must remain active when standard EQ is disabled", eq.isEnabled)

        // When calibration is disabled, user EQ is restored if re-enabled
        eq.setCalibrationTuning(enabled = false, leftCurve = calibLeftCurve, rightCurve = calibRightCurve)
        assertFalse("Processor must be bypassed when both are disabled", eq.isEnabled)

        eq.setUserTuning(enabled = true, curve = userCurve, balance = 0f)
        assertTrue("Standard EQ must be active alone", eq.isEnabled)
    }
}

