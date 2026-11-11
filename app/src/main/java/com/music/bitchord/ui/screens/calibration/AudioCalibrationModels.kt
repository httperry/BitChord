/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.ui.screens.calibration

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class HeadphoneFormFactor(val label: String) {
    IEM("In-Ear Monitor"),
    OVER_EAR("Over-Ear"),
    EARBUDS("Earbuds"),
}

data class HeadphoneModel(
    val id: String,
    val name: String,
    val brand: String,
    val targetBaseline: String,
    val formFactor: HeadphoneFormFactor,
    val impedanceOhms: Int,
    val sensitivityDb: Float,
    val description: String = "",
)

data class FrequencyZone(
    val frequencyHz: Int,
    val label: String,
    val technicalName: String,
)

data class AudioCalibrationProfile(
    val isCalibrated: Boolean = false,
    val calibratedModelId: String? = null,
    val calibrationTimestamp: Long = 0L,
    val leftEarDeltas: List<Float> = emptyList(),
    val rightEarDeltas: List<Float> = emptyList(),
    val isBypassed: Boolean = false,
)

object HeadphoneCatalog {
    val ZONES = listOf(
        FrequencyZone(125, "Sub-Bass", "125 Hz"),
        FrequencyZone(250, "Bass", "250 Hz"),
        FrequencyZone(500, "Low-Mids", "500 Hz"),
        FrequencyZone(1000, "Mids", "1.0 kHz"),
        FrequencyZone(2000, "Upper-Mids", "2.0 kHz"),
        FrequencyZone(4000, "Presence", "4.0 kHz"),
        FrequencyZone(8000, "Air / Treble", "8.0 kHz"),
    )

    private val BUILTIN_MODELS: List<HeadphoneModel> = listOf(
        // Moondrop
        HeadphoneModel("moondrop_blessing_3", "Blessing 3", "Moondrop", "Harman IE 2019 v2 Target", HeadphoneFormFactor.IEM, 14, 120.0f, "Hybrid 2DD + 4BA in-ear monitor"),
        HeadphoneModel("moondrop_chu_2", "Chu II", "Moondrop", "VDSF Target (Al-Mg Dome)", HeadphoneFormFactor.IEM, 18, 119.0f, "10mm dynamic driver monitor"),
        HeadphoneModel("moondrop_aria_2", "Aria 2", "Moondrop", "VDSF Target", HeadphoneFormFactor.IEM, 33, 122.0f, "Ceramic-coated dynamic driver"),
        HeadphoneModel("moondrop_kato", "KATO", "Moondrop", "VDSF Reference Target", HeadphoneFormFactor.IEM, 32, 123.0f, "ULT dynamic driver flagship"),
        HeadphoneModel("moondrop_variations", "Variations", "Moondrop", "Harman IE Reference", HeadphoneFormFactor.IEM, 15, 118.0f, "Tribrid 1DD + 2BA + 2EST"),
        HeadphoneModel("moondrop_space_travel", "Space Travel", "Moondrop", "VDSF Target Curve", HeadphoneFormFactor.EARBUDS, 32, 115.0f, "True wireless monitor"),

        // Tangzu
        HeadphoneModel("tangzu_waner", "Wan'er S.G", "Tangzu", "Tangzu House Target", HeadphoneFormFactor.IEM, 20, 107.0f, "10mm PET diaphragm monitor"),
        HeadphoneModel("tangzu_waner_se", "Wan'er Studio Edition", "Tangzu", "Diffuse Field Studio Ref", HeadphoneFormFactor.IEM, 16, 105.0f, "Neutral monitoring edition"),
        HeadphoneModel("tangzu_fudu", "Fudu Verse 1", "Tangzu", "Harman IE 2019 Target", HeadphoneFormFactor.IEM, 16, 106.0f, "1DD + 2BA hybrid"),
        HeadphoneModel("tangzu_zetian_wu", "Zetian Wu Heyday", "Tangzu", "Planar Reference Target", HeadphoneFormFactor.IEM, 16, 100.0f, "14.5mm planar magnetic driver"),

        // Truthear
        HeadphoneModel("truthear_zero_red", "Zero:RED", "Truthear", "Project RED Harman Target", HeadphoneFormFactor.IEM, 17, 117.5f, "Dual dynamic driver tuned by Crinacle"),
        HeadphoneModel("truthear_hexa", "HEXA", "Truthear", "Diffuse Field + Sub-Bass Boost", HeadphoneFormFactor.IEM, 20, 120.0f, "1DD + 3BA hybrid monitor"),
        HeadphoneModel("truthear_nova", "NOVA", "Truthear", "Harman IE 2019 Strict", HeadphoneFormFactor.IEM, 14, 123.0f, "1DD + 4BA hybrid system"),
        HeadphoneModel("truthear_gate", "GATE", "Truthear", "Harman Linear Target", HeadphoneFormFactor.IEM, 28, 122.0f, "10mm carbon LCP dynamic driver"),
        HeadphoneModel("truthear_hola", "HOLA", "Truthear", "Harman Warm Target", HeadphoneFormFactor.IEM, 28, 120.0f, "11mm PU+LCP dynamic driver"),

        // Sennheiser
        HeadphoneModel("sennheiser_hd600", "HD 600", "Sennheiser", "Diffuse Field Reference", HeadphoneFormFactor.OVER_EAR, 300, 97.0f, "Open-back reference audiophile standard"),
        HeadphoneModel("sennheiser_hd650", "HD 650", "Sennheiser", "Harman Over-Ear Target", HeadphoneFormFactor.OVER_EAR, 300, 103.0f, "Classic open-back dynamic headphone"),
        HeadphoneModel("sennheiser_hd800s", "HD 800 S", "Sennheiser", "Harman OE Acoustic Baseline", HeadphoneFormFactor.OVER_EAR, 300, 102.0f, "Ring radiator flagship spatial monitor"),
        HeadphoneModel("sennheiser_ie200", "IE 200", "Sennheiser", "TrueResponse Target", HeadphoneFormFactor.IEM, 18, 119.0f, "7mm TrueResponse dynamic driver"),
        HeadphoneModel("sennheiser_ie600", "IE 600", "Sennheiser", "Acoustic Neutral Reference", HeadphoneFormFactor.IEM, 18, 118.0f, "Amorphous zirconium acoustic chamber"),
        HeadphoneModel("sennheiser_momentum_4", "Momentum 4", "Sennheiser", "Harman OE Adaptive", HeadphoneFormFactor.OVER_EAR, 60, 106.0f, "42mm transducer wireless system"),

        // Sony
        HeadphoneModel("sony_wh1000xm5", "WH-1000XM5", "Sony", "Sony Studio Monitor Target", HeadphoneFormFactor.OVER_EAR, 48, 102.0f, "30mm carbon composite noise canceling"),
        HeadphoneModel("sony_wf1000xm5", "WF-1000XM5", "Sony", "Harman IE Balanced", HeadphoneFormFactor.EARBUDS, 16, 100.0f, "Dynamic Driver X true wireless"),
        HeadphoneModel("sony_mdr7506", "MDR-7506", "Sony", "Broadcast Studio Reference", HeadphoneFormFactor.OVER_EAR, 63, 106.0f, "Professional studio monitoring standard"),
        HeadphoneModel("sony_ierm9", "IER-M9", "Sony", "Stage Monitor Reference", HeadphoneFormFactor.IEM, 20, 103.0f, "5BA stage monitor system"),

        // Apple
        HeadphoneModel("apple_airpods_pro_2", "AirPods Pro 2", "Apple", "Harman IE Adaptive Target", HeadphoneFormFactor.EARBUDS, 16, 104.0f, "Custom high-excursion driver with H2"),
        HeadphoneModel("apple_airpods_max", "AirPods Max", "Apple", "Harman OE Reference Target", HeadphoneFormFactor.OVER_EAR, 32, 108.0f, "40mm dual neodymium motor"),
        HeadphoneModel("apple_earpods_35mm", "EarPods (3.5mm)", "Apple", "Free Field Earbud Baseline", HeadphoneFormFactor.EARBUDS, 45, 109.0f, "Classic dynamic open earbud"),
        HeadphoneModel("apple_earpods_usbc", "EarPods (USB-C)", "Apple", "Digital Reference Earbud", HeadphoneFormFactor.EARBUDS, 45, 110.0f, "Digital 24-bit/48kHz direct monitor"),

        // 7Hz
        HeadphoneModel("sevenhz_timeless", "Timeless", "7Hz", "Planar Neutral Target", HeadphoneFormFactor.IEM, 14, 104.0f, "14.2mm ultra-thin planar diaphragm"),
        HeadphoneModel("sevenhz_zero2", "Zero 2", "7Hz", "Harman IE 2019 Target", HeadphoneFormFactor.IEM, 32, 108.0f, "10mm dynamic driver tuned by Crinacle"),
        HeadphoneModel("sevenhz_legato", "Legato", "7Hz", "Bass Enhanced Target", HeadphoneFormFactor.IEM, 26, 108.0f, "Dual dynamic driver subwoofer architecture"),

        // Fiio
        HeadphoneModel("fiio_fh3", "FH3", "Fiio", "Fiio Balanced Acoustic Curve", HeadphoneFormFactor.IEM, 24, 114.0f, "1DD + 2BA beryllium diaphragm"),
        HeadphoneModel("fiio_fd5", "FD5", "Fiio", "Semi-Open Reference Target", HeadphoneFormFactor.IEM, 32, 109.0f, "12mm DLC dynamic driver"),
        HeadphoneModel("fiio_ft3", "FT3", "Fiio", "Harman Over-Ear Target", HeadphoneFormFactor.OVER_EAR, 350, 105.0f, "60mm large dynamic diaphragm"),

        // Audio-Technica
        HeadphoneModel("audiotechnica_m50x", "ATH-M50x", "Audio-Technica", "Studio Monitoring Baseline", HeadphoneFormFactor.OVER_EAR, 38, 99.0f, "45mm large-aperture studio monitor"),
        HeadphoneModel("audiotechnica_r70x", "ATH-R70x", "Audio-Technica", "Diffuse Field Reference", HeadphoneFormFactor.OVER_EAR, 470, 98.0f, "Open-back reference headphone"),
    )

    val MODELS: List<HeadphoneModel> by lazy {
        val loaded = try {
            com.music.bitchord.playback.calibration.AcousticModelCatalog.getModels()
        } catch (_: Throwable) {
            emptyList()
        }
        if (loaded.isNotEmpty()) {
            loaded.map { am ->
                HeadphoneModel(
                    id = am.id,
                    name = am.name,
                    brand = am.brand,
                    targetBaseline = am.defaultTarget.displayName,
                    formFactor = when (am.type) {
                        com.music.bitchord.playback.calibration.AcousticDeviceType.IEM -> HeadphoneFormFactor.IEM
                        com.music.bitchord.playback.calibration.AcousticDeviceType.OVER_EAR -> HeadphoneFormFactor.OVER_EAR
                        com.music.bitchord.playback.calibration.AcousticDeviceType.TWS -> HeadphoneFormFactor.EARBUDS
                    },
                    impedanceOhms = 32,
                    sensitivityDb = 105.0f,
                    description = "${am.brand} ${am.name}",
                )
            }
        } else {
            BUILTIN_MODELS
        }
    }

    val DEFAULT_MODEL: HeadphoneModel get() = MODELS.firstOrNull { it.id == "moondrop_blessing_3" } ?: MODELS.first()

    fun findModel(id: String?): HeadphoneModel? {
        if (id == null) return null
        return MODELS.firstOrNull { it.id == id }
    }

    val BRANDS: List<String> get() = listOf("All") + MODELS.map { it.brand }.distinct()
}

/**
 * Global audio calibration state manager.
 * Supports per-device profile memory and automatic profile switching based on connected audio sink.
 */
object AudioCalibrationManager {
    private const val PREFS_NAME = "bitchord_audio_calibration"
    private const val KEY_MODEL_ID = "selected_model_id"
    private const val KEY_IS_CALIBRATED = "is_calibrated"
    private const val KEY_CALIBRATED_MODEL_ID = "calibrated_model_id"
    private const val KEY_CALIBRATION_TIMESTAMP = "calibration_timestamp"
    private const val KEY_LEFT_DELTAS = "left_ear_deltas"
    private const val KEY_RIGHT_DELTAS = "right_ear_deltas"
    private const val KEY_DEVICE_PROFILES_JSON = "device_profiles_json"

    private var sharedPrefs: SharedPreferences? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _activeDeviceKey = MutableStateFlow("default")
    val activeDeviceKey: StateFlow<String> = _activeDeviceKey.asStateFlow()

    private val _selectedModel = MutableStateFlow<HeadphoneModel?>(null)
    val selectedModel: StateFlow<HeadphoneModel?> = _selectedModel.asStateFlow()

    private val _calibrationProfile = MutableStateFlow(AudioCalibrationProfile())
    val calibrationProfile: StateFlow<AudioCalibrationProfile> = _calibrationProfile.asStateFlow()

    private val _isBypassed = MutableStateFlow(false)
    val isBypassed: StateFlow<Boolean> = _isBypassed.asStateFlow()

    private val _hardwareBaselineUpdated = MutableStateFlow(false)
    val hardwareBaselineUpdated: StateFlow<Boolean> = _hardwareBaselineUpdated.asStateFlow()

    fun resolveDeviceKey(snapshot: AudioOutputStatus.Snapshot): String {
        return when (snapshot.routeKind) {
            AudioRouting.Kind.PHONE -> "phone_speaker"
            AudioRouting.Kind.WIRED -> "wired_headset"
            AudioRouting.Kind.USB -> "usb_${snapshot.deviceName}"
            AudioRouting.Kind.BLUETOOTH -> {
                snapshot.deviceAddress?.takeIf { it.isNotBlank() } ?: "bt_${snapshot.deviceName}"
            }
            else -> "other_${snapshot.deviceName}"
        }
    }

    fun init(context: Context) {
        if (sharedPrefs != null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs

        // Restore active route
        val currentSnapshot = AudioOutputStatus.current.value
        val initialKey = resolveDeviceKey(currentSnapshot)
        _activeDeviceKey.value = initialKey

        // Load profile for initial key
        loadProfileForDevice(initialKey)

        // Observe route transitions and auto-switch per-device profile
        scope.launch {
            AudioOutputStatus.current
                .map { resolveDeviceKey(it) }
                .distinctUntilChanged()
                .collect { newKey ->
                    onDeviceChanged(newKey)
                }
        }
    }

    private fun onDeviceChanged(newKey: String) {
        val oldKey = _activeDeviceKey.value
        if (oldKey == newKey) return

        // Save current device state before switching (if not phone speaker)
        if (oldKey != "phone_speaker") {
            saveCurrentDeviceRecord(oldKey)
        }

        _activeDeviceKey.value = newKey
        loadProfileForDevice(newKey)
    }

    private fun loadProfileForDevice(deviceKey: String) {
        val prefs = sharedPrefs ?: return
        val profilesJson = prefs.getString(KEY_DEVICE_PROFILES_JSON, null)
        val profileObj = if (!profilesJson.isNullOrBlank()) {
            runCatching { JSONObject(profilesJson).optJSONObject(deviceKey) }.getOrNull()
        } else null

        if (profileObj != null) {
            val modelId = profileObj.optString("modelId", "").ifBlank { null }
            val model = HeadphoneCatalog.findModel(modelId)
            _selectedModel.value = model

            val isCalibrated = profileObj.optBoolean("isCalibrated", false)
            val calibratedModelId = profileObj.optString("calibratedModelId", "").ifBlank { null }
            val timestamp = profileObj.optLong("timestamp", 0L)
            val leftDeltas = parseDeltas(profileObj.optString("leftDeltas", ""))
            val rightDeltas = parseDeltas(profileObj.optString("rightDeltas", ""))
            val devicePresetEnabled = profileObj.optBoolean("devicePresetEnabled", model != null)
            val personalizationEnabled = profileObj.optBoolean("personalizationEnabled", isCalibrated)

            _calibrationProfile.value = AudioCalibrationProfile(
                isCalibrated = isCalibrated,
                calibratedModelId = calibratedModelId,
                calibrationTimestamp = timestamp,
                leftEarDeltas = leftDeltas,
                rightEarDeltas = rightDeltas,
                isBypassed = false,
            )

            runCatching {
                AppSettings.setCalibrationModel(model?.id, model?.name, model?.brand)
                AppSettings.setDevicePresetEnabled(devicePresetEnabled)
                AppSettings.setPersonalizationEnabled(personalizationEnabled)
                AppSettings.setPersonalizationGains(
                    left = leftDeltas,
                    right = rightDeltas,
                    completed = isCalibrated,
                    modelIdSnapshot = calibratedModelId,
                )
            }
        } else {
            // First time seeing this device: initialize from legacy global prefs if available, otherwise flat
            val legacyModelId = prefs.getString(KEY_MODEL_ID, null)
            val model = HeadphoneCatalog.findModel(legacyModelId)
            _selectedModel.value = model

            val isCalibrated = prefs.getBoolean(KEY_IS_CALIBRATED, false)
            val calibratedModelId = prefs.getString(KEY_CALIBRATED_MODEL_ID, null)
            val timestamp = prefs.getLong(KEY_CALIBRATION_TIMESTAMP, 0L)
            val leftDeltas = parseDeltas(prefs.getString(KEY_LEFT_DELTAS, "") ?: "")
            val rightDeltas = parseDeltas(prefs.getString(KEY_RIGHT_DELTAS, "") ?: "")

            _calibrationProfile.value = AudioCalibrationProfile(
                isCalibrated = isCalibrated,
                calibratedModelId = calibratedModelId,
                calibrationTimestamp = timestamp,
                leftEarDeltas = leftDeltas,
                rightEarDeltas = rightDeltas,
                isBypassed = false,
            )

            runCatching {
                AppSettings.setCalibrationModel(model?.id, model?.name, model?.brand)
                AppSettings.setDevicePresetEnabled(model != null)
                AppSettings.setPersonalizationEnabled(isCalibrated)
                AppSettings.setPersonalizationGains(
                    left = leftDeltas,
                    right = rightDeltas,
                    completed = isCalibrated,
                    modelIdSnapshot = calibratedModelId,
                )
            }

            if (deviceKey != "phone_speaker") {
                saveCurrentDeviceRecord(deviceKey)
            }
        }

        updateBaselineNotice()
    }

    private fun saveCurrentDeviceRecord(deviceKey: String) {
        val prefs = sharedPrefs ?: return
        val currentModel = _selectedModel.value
        val profile = _calibrationProfile.value

        val rootObj = runCatching {
            val raw = prefs.getString(KEY_DEVICE_PROFILES_JSON, null)
            if (!raw.isNullOrBlank()) JSONObject(raw) else JSONObject()
        }.getOrDefault(JSONObject())

        val devObj = JSONObject().apply {
            put("modelId", currentModel?.id.orEmpty())
            put("isCalibrated", profile.isCalibrated)
            put("calibratedModelId", profile.calibratedModelId.orEmpty())
            put("timestamp", profile.calibrationTimestamp)
            put("leftDeltas", profile.leftEarDeltas.joinToString(","))
            put("rightDeltas", profile.rightEarDeltas.joinToString(","))
            put("devicePresetEnabled", AppSettings.devicePresetEnabled.value)
            put("personalizationEnabled", AppSettings.personalizationEnabled.value)
        }

        rootObj.put(deviceKey, devObj)
        prefs.edit().putString(KEY_DEVICE_PROFILES_JSON, rootObj.toString()).apply()
    }

    fun selectModel(model: HeadphoneModel?) {
        _selectedModel.value = model
        sharedPrefs?.edit()?.putString(KEY_MODEL_ID, model?.id.orEmpty())?.apply()
        runCatching {
            AppSettings.setCalibrationModel(model?.id, model?.name, model?.brand)
            AppSettings.setDevicePresetEnabled(model != null)
        }
        val key = _activeDeviceKey.value
        if (key != "phone_speaker") {
            saveCurrentDeviceRecord(key)
        }
        updateBaselineNotice()
    }

    fun saveCalibration(
        leftDeltas: List<Float>,
        rightDeltas: List<Float>,
        calibratedModelId: String? = _selectedModel.value?.id,
    ) {
        val now = System.currentTimeMillis()
        val newProfile = AudioCalibrationProfile(
            isCalibrated = true,
            calibratedModelId = calibratedModelId,
            calibrationTimestamp = now,
            leftEarDeltas = leftDeltas,
            rightEarDeltas = rightDeltas,
            isBypassed = false,
        )
        _calibrationProfile.value = newProfile

        sharedPrefs?.edit()
            ?.putBoolean(KEY_IS_CALIBRATED, true)
            ?.putString(KEY_CALIBRATED_MODEL_ID, calibratedModelId)
            ?.putLong(KEY_CALIBRATION_TIMESTAMP, now)
            ?.putString(KEY_LEFT_DELTAS, leftDeltas.joinToString(","))
            ?.putString(KEY_RIGHT_DELTAS, rightDeltas.joinToString(","))
            ?.apply()

        runCatching {
            AppSettings.setPersonalizationGains(
                left = leftDeltas,
                right = rightDeltas,
                completed = true,
                modelIdSnapshot = calibratedModelId,
            )
        }

        val key = _activeDeviceKey.value
        if (key != "phone_speaker") {
            saveCurrentDeviceRecord(key)
        }

        updateBaselineNotice()
    }

    fun setBypass(bypassed: Boolean) {
        _isBypassed.value = bypassed
        runCatching {
            AppSettings.setPreviewUncalibrated(bypassed)
        }
    }

    fun resetCalibration() {
        val newProfile = AudioCalibrationProfile(
            isCalibrated = false,
            calibratedModelId = null,
            calibrationTimestamp = 0L,
            leftEarDeltas = emptyList(),
            rightEarDeltas = emptyList(),
            isBypassed = false,
        )
        _calibrationProfile.value = newProfile
        sharedPrefs?.edit()
            ?.putBoolean(KEY_IS_CALIBRATED, false)
            ?.remove(KEY_CALIBRATED_MODEL_ID)
            ?.remove(KEY_CALIBRATION_TIMESTAMP)
            ?.remove(KEY_LEFT_DELTAS)
            ?.remove(KEY_RIGHT_DELTAS)
            ?.apply()
        runCatching {
            AppSettings.setPersonalizationCompleted(false)
            AppSettings.setPersonalizationModelIdSnapshot(null)
            AppSettings.setPersonalizationGains(emptyList(), emptyList(), completed = false, modelIdSnapshot = null)
        }
        val key = _activeDeviceKey.value
        if (key != "phone_speaker") {
            saveCurrentDeviceRecord(key)
        }
        updateBaselineNotice()
    }

    private fun updateBaselineNotice() {
        val profile = _calibrationProfile.value
        val currentModel = _selectedModel.value
        _hardwareBaselineUpdated.value = profile.isCalibrated &&
                profile.calibratedModelId != null &&
                profile.calibratedModelId != currentModel?.id
    }

    private fun parseDeltas(str: String): List<Float> {
        if (str.isBlank()) return emptyList()
        return str.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    fun getActiveAcousticModel(): com.music.bitchord.playback.calibration.AcousticModel? {
        val model = _selectedModel.value ?: return null
        return com.music.bitchord.playback.calibration.AcousticModelCatalog.getModelById(model.id)
    }

    fun getActiveAutoEqProfile(
        target: com.music.bitchord.playback.calibration.CalibrationTarget? = null
    ): com.music.bitchord.playback.calibration.AutoEqProfile? {
        val model = getActiveAcousticModel() ?: return null
        return if (target != null) model.getProfile(target) else model.activeProfile
    }
}

