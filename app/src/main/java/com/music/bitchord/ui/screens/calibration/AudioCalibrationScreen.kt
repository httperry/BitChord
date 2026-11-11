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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.calibration.AcousticModelCatalog
import com.music.bitchord.playback.calibration.CalibrationCombiner
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.screens.GROUP_INSET
import com.music.bitchord.ui.screens.GroupShape
import com.music.bitchord.ui.screens.ICON_GAP
import com.music.bitchord.ui.screens.ICON_SIZE
import com.music.bitchord.ui.screens.ROW_INSET
import com.music.bitchord.ui.screens.RowDivider
import com.music.bitchord.ui.screens.SettingsPageTitle
import com.music.bitchord.ui.screens.SettingsRow
import java.util.Locale

/**
 * Dedicated Audio Personalisation screen matching BitChord architecture & user sketch.
 *
 * Implements:
 * 1. Top Header: Back navigation button + "Audio Personalisation" title.
 *    - When routed to phone speaker: displays prominent notice below title
 *      "Please connect to your audio device to continue", and disables both rows and graph.
 * 2. Main Control Container (Card with 2 rows):
 *    - Row 1: Device Preset -> device name / "Disabled on phone speaker" + chevron >.
 *             Tapping opens the headphone / IEM catalog picker.
 *    - Row 2: Personal EQ -> "Audio tuning for your ears" + vertical divider | + IosSwitch.
 *             Tapping the switch toggles Personal EQ on/off without leaving the page.
 *             Tapping anywhere else on the row body opens the Personal EQ setup wizard.
 * 3. "Current EQ" Section:
 *    - Interactive frequency response Canvas:
 *      - Device Preset curve (Cyan 0xFF38BDF8).
 *      - Personal EQ curve (Amber 0xFFF59E0B).
 *      - If either is disabled, that line is faded significantly (alpha = 0.20f).
 *      - If phone speaker: NO lines are drawn at all.
 *    - Device name and color-coded legend indicators.
 *    - Live A/B hold-to-preview uncalibrated pill (disabled on phone speaker).
 */
@Composable
fun AudioCalibrationScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit = {},
    onOpenAudioTest: () -> Unit = {},
    onOpenDevicePresetPicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val audioCalibrationEnabled by AppSettings.audioCalibrationEnabled.collectAsStateWithLifecycle()
    val devicePresetEnabled by AppSettings.devicePresetEnabled.collectAsStateWithLifecycle()
    val personalizationEnabled by AppSettings.personalizationEnabled.collectAsStateWithLifecycle()
    val modelId by AppSettings.calibrationModelId.collectAsStateWithLifecycle()
    val modelName by AppSettings.calibrationModelName.collectAsStateWithLifecycle()
    val modelBrand by AppSettings.calibrationModelBrand.collectAsStateWithLifecycle()
    val leftGains by AppSettings.personalizationLeftGains.collectAsStateWithLifecycle()
    val rightGains by AppSettings.personalizationRightGains.collectAsStateWithLifecycle()
    val personalizationCompleted by AppSettings.personalizationCompleted.collectAsStateWithLifecycle()
    val previewUncalibrated by AppSettings.previewUncalibrated.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val hardwareBaselineUpdated by AudioCalibrationManager.hardwareBaselineUpdated.collectAsStateWithLifecycle()

    val selectedModel: HeadphoneModel? = remember(modelId) {
        modelId?.let { id -> HeadphoneCatalog.MODELS.firstOrNull { it.id == id } }
    }

    var showPersonalizationWizard by remember { mutableStateOf(false) }

    val isPhoneSpeaker = outputStatus.routeKind == AudioRouting.Kind.PHONE
    val haptics = rememberHaptics()

    val presetCurveColor = Color(0xFF38BDF8)   // Electric Cyan
    val personalCurveColor = Color(0xFFF59E0B) // Amber / Warm Gold

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.audio_personalisation))

        // --- Speaker State Notice (Displayed directly below title when routed to phone speaker) ---
        AnimatedVisibility(
            visible = isPhoneSpeaker,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = stringResource(R.string.connect_audio_device_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }

        // --- Hardware Baseline Changed Notice (Prompt to retake Audio Test when preset changes) ---
        AnimatedVisibility(
            visible = hardwareBaselineUpdated && !isPhoneSpeaker,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GROUP_INSET)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = SfIcons.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.hardware_baseline_changed_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.hardware_baseline_changed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        haptics.play(Haptic.Tap)
                        onOpenAudioTest()
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.retake_audio_test),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }

        // --- Main Control Card Container (Device Preset + Personal EQ) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GROUP_INSET)
                .clip(GroupShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // ROW 1: Device Preset
            val presetSubtitle = when {
                selectedModel != null -> cleanModelDisplayName(selectedModel.brand, selectedModel.name)
                modelName != null -> cleanModelDisplayName(modelBrand ?: "", modelName ?: "")
                else -> stringResource(R.string.no_preset_flat)
            }
            SettingsRow(
                icon = SfIcons.Headphones,
                title = stringResource(R.string.device_preset),
                subtitle = presetSubtitle,
                onClick = {
                    haptics.play(Haptic.Tap)
                    onOpenDevicePresetPicker()
                },
                enabled = !isPhoneSpeaker,
            )

            RowDivider()

            // ROW 2: Personal EQ (Dual-Action Row: Body opens wizard, Switch toggles ON/OFF)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (!isPhoneSpeaker) 1f else 0.45f)
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left area: Tapping anywhere except the switch opens the Personal EQ Setup/Wizard
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !isPhoneSpeaker) {
                            haptics.play(Haptic.Tap)
                            onOpenAudioTest()
                        }
                        .heightIn(min = 52.dp)
                        .padding(start = ROW_INSET, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = SfIcons.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                    Spacer(Modifier.width(ICON_GAP))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.personal_eq),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.personal_eq_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Vertical hairline divider
                VerticalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )

                // Dedicated Switch Toggle
                Box(
                    modifier = Modifier.padding(horizontal = ROW_INSET),
                    contentAlignment = Alignment.Center,
                ) {
                    IosSwitch(
                        checked = personalizationEnabled && personalizationCompleted,
                        enabled = !isPhoneSpeaker,
                        onCheckedChange = { desired ->
                            if (desired) {
                                if (!personalizationCompleted) {
                                    onOpenAudioTest()
                                } else {
                                    AppSettings.setPersonalizationEnabled(true)
                                    AppSettings.setAudioCalibrationEnabled(true)
                                }
                            } else {
                                AppSettings.setPersonalizationEnabled(false)
                                if (!devicePresetEnabled) {
                                    AppSettings.setAudioCalibrationEnabled(false)
                                }
                            }
                        },
                    )
                }
            }
        }

        // --- SECTION: Current EQ Header ---
        Text(
            text = stringResource(R.string.current_eq).uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )

        // --- Current EQ Graph Card Container ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GROUP_INSET)
                .clip(GroupShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        ) {
                // Interactive Canvas Graph
                val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                val textLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

                val activeAcousticModel = remember(modelId) {
                    modelId?.let { AcousticModelCatalog.getModelById(it) }
                }

                val bands10 = activeAcousticModel?.activeProfile?.bandOffsets10 ?: emptyList()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val paddingTop = 12.dp.toPx()
                        val paddingBottom = 22.dp.toPx()
                        val chartHeight = height - paddingTop - paddingBottom
                        val centerY = paddingTop + chartHeight / 2f

                        val bandCount = 7

                        // 1. Draw horizontal zero-dB baseline and ±6dB guides
                        drawLine(
                            color = baselineColor,
                            start = Offset(0f, centerY),
                            end = Offset(width, centerY),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                        )
                        drawLine(
                            color = gridColor.copy(alpha = 0.2f),
                            start = Offset(0f, centerY - chartHeight * 0.35f),
                            end = Offset(width, centerY - chartHeight * 0.35f),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawLine(
                            color = gridColor.copy(alpha = 0.2f),
                            start = Offset(0f, centerY + chartHeight * 0.35f),
                            end = Offset(width, centerY + chartHeight * 0.35f),
                            strokeWidth = 1.dp.toPx(),
                        )

                        // 2. Draw vertical frequency grid lines
                        val stepX = width / (bandCount - 1).toFloat()
                        for (i in 0 until bandCount) {
                            val x = i * stepX
                            drawLine(
                                color = gridColor,
                                start = Offset(x, paddingTop),
                                end = Offset(x, height - paddingBottom),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }

                        // Function to map dB delta to Y coordinate (-12dB to +12dB range)
                        fun dbToY(db: Float): Float {
                            val clamped = db.coerceIn(-12f, 12f)
                            return centerY - (clamped / 12f) * (chartHeight / 2f)
                        }

                        // ONLY draw lines when output is NOT phone speaker
                        if (!isPhoneSpeaker) {
                            // 3. Draw Device Preset line (Cyan)
                            val presetIsActive = devicePresetEnabled && selectedModel != null && audioCalibrationEnabled
                            val presetAlpha = if (!presetIsActive || previewUncalibrated) 0.20f else 0.90f
                            val presetStrokeWidth = if (presetIsActive && !previewUncalibrated) 3.5.dp.toPx() else 2.0.dp.toPx()

                            val presetPoints = mutableListOf<Offset>()
                            for (i in 0 until bandCount) {
                                val x = i * stepX
                                val hz = HeadphoneCatalog.ZONES[i].frequencyHz.toFloat()
                                val rawDb = CalibrationCombiner.evaluateModelGain(activeAcousticModel?.activeProfile, hz)
                                val y = dbToY(rawDb)
                                presetPoints.add(Offset(x, y))
                            }

                            if (presetPoints.isNotEmpty() && selectedModel != null) {
                                val presetPath = createSmoothSplinePath(presetPoints)
                                drawPath(
                                    path = presetPath,
                                    color = presetCurveColor.copy(alpha = presetAlpha),
                                    style = Stroke(
                                        width = presetStrokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            } else {
                                drawLine(
                                    color = presetCurveColor.copy(alpha = 0.20f),
                                    start = Offset(0f, centerY),
                                    end = Offset(width, centerY),
                                    strokeWidth = 1.6.dp.toPx(),
                                )
                            }

                            // 4. Draw Personal EQ line (Amber)
                            val personalIsActive = personalizationEnabled && personalizationCompleted && audioCalibrationEnabled
                            val personalAlpha = if (!personalIsActive || previewUncalibrated) 0.20f else 0.90f
                            val personalStrokeWidth = if (personalIsActive && !previewUncalibrated) 3.5.dp.toPx() else 2.0.dp.toPx()

                            if (personalizationCompleted) {
                                val personalPoints = mutableListOf<Offset>()
                                for (i in 0 until bandCount) {
                                    val x = i * stepX
                                    val left = leftGains.getOrNull(i) ?: 0f
                                    val right = rightGains.getOrNull(i) ?: 0f
                                    val avg = (left + right) / 2f
                                    val y = dbToY(avg)
                                    personalPoints.add(Offset(x, y))
                                }
                                val personalPath = createSmoothSplinePath(personalPoints)
                                drawPath(
                                    path = personalPath,
                                    color = personalCurveColor.copy(alpha = personalAlpha),
                                    style = Stroke(
                                        width = personalStrokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            } else {
                                drawLine(
                                    color = personalCurveColor.copy(alpha = 0.20f),
                                    start = Offset(0f, centerY),
                                    end = Offset(width, centerY),
                                    strokeWidth = 1.6.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                                )
                            }
                        }
                    }

                    // Frequency Labels along the bottom
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("125", "250", "500", "1k", "2k", "4k", "8k").forEach { freq ->
                            Text(
                                text = freq,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = textLabelColor,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Footer: Device Name & Curve Legend
                val deviceDisplayName = when {
                    selectedModel != null -> cleanModelDisplayName(selectedModel.brand, selectedModel.name)
                    modelName != null -> cleanModelDisplayName(modelBrand ?: "", modelName ?: "")
                    outputStatus.deviceName.isNotBlank() && !isPhoneSpeaker -> outputStatus.deviceName
                    else -> stringResource(R.string.no_preset_flat)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (deviceDisplayName.isNotBlank()) {
                        Text(
                            text = deviceDisplayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Spacer(Modifier.width(8.dp))

                    // Curve Legend Indicators (Only shown when not on phone speaker)
                    if (!isPhoneSpeaker) {
                        val presetActive = devicePresetEnabled && (selectedModel != null || bands10.isNotEmpty()) && audioCalibrationEnabled
                        val personalActive = personalizationEnabled && personalizationCompleted && audioCalibrationEnabled

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LegendDot(
                                color = presetCurveColor.copy(alpha = if (presetActive) 1.0f else 0.35f),
                                label = "Preset",
                                labelAlpha = if (presetActive) 1.0f else 0.45f,
                            )
                            LegendDot(
                                color = personalCurveColor.copy(alpha = if (personalActive) 1.0f else 0.35f),
                                label = "Personal EQ",
                                labelAlpha = if (personalActive) 1.0f else 0.45f,
                            )
                        }
                    }
                }

                // Studio Demo / Live A/B hold-to-preview button (only shown when not on phone speaker)
                if (!isPhoneSpeaker) {
                    Spacer(Modifier.height(16.dp))
                    LivePreviewBar(
                        isBypassed = previewUncalibrated,
                        onBypassChanged = { AppSettings.setPreviewUncalibrated(it) },
                    )
                }
            }

        Spacer(Modifier.height(24.dp))
    }

    // Modal Sheet: Headphone / IEM Catalog Picker
    if (showModelPicker) {
        ModelCatalogPickerSheet(
            selectedModel = selectedModel,
            onSelectModel = { model ->
                if (model == null) {
                    AppSettings.setCalibrationModel(null, null, null)
                    AppSettings.setDevicePresetEnabled(false)
                } else {
                    AudioCalibrationManager.selectModel(model)
                    AppSettings.setCalibrationModel(model.id, model.name, model.brand)
                    AppSettings.setDevicePresetEnabled(true)
                    AppSettings.setAudioCalibrationEnabled(true)
                }
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    // Modal Sheet: Audio Personalization Wizard (Adaptive Threshold Test)
    if (showPersonalizationWizard) {
        AudioPersonalizationWizard(
            onDismiss = { showPersonalizationWizard = false },
        )
    }
}

@Composable
private fun LegendDot(
    color: Color,
    label: String,
    labelAlpha: Float = 1.0f,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = labelAlpha),
        )
    }
}

/**
 * Live A/B Studio Comparison: press and hold to temporarily preview uncalibrated audio.
 */
@Composable
private fun LivePreviewBar(
    isBypassed: Boolean,
    onBypassChanged: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    val currentOnBypassChanged by rememberUpdatedState(onBypassChanged)

    val bgColor = if (isBypassed) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
                    haptics.play(Haptic.Tap)
                    currentOnBypassChanged(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val anyPressed = event.changes.any { it.pressed }
                            if (!anyPressed) {
                                haptics.play(Haptic.Tap)
                                break
                            }
                        }
                    } finally {
                        currentOnBypassChanged(false)
                    }
                }
            }
            .padding(vertical = 11.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBypassed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
            )
            Text(
                text = if (isBypassed) {
                    stringResource(R.string.uncalibrated_preview_label)
                } else {
                    stringResource(R.string.hold_to_preview_uncalibrated)
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isBypassed) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                ),
                color = if (isBypassed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Constructs a smooth cubic Bezier spline path passing through [points] with continuous first derivatives.
 * Eliminates sharp angular corners, rendering a fluid iOS-grade curve.
 */
private fun createSmoothSplinePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) {
        path.moveTo(points[0].x, points[0].y)
        return path
    }
    path.moveTo(points[0].x, points[0].y)
    val n = points.size
    for (i in 0 until n - 1) {
        val p0 = if (i > 0) points[i - 1] else points[i]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = if (i + 2 < n) points[i + 2] else points[i + 1]

        val cp1x = p1.x + (p2.x - p0.x) / 6f
        val cp1y = p1.y + (p2.y - p0.y) / 6f
        val cp2x = p2.x - (p3.x - p1.x) / 6f
        val cp2y = p2.y - (p3.y - p1.y) / 6f

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
    }
    return path
}
