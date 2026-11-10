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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.music.bitchord.ui.components.GLASS_EDGE_COLOR
import com.music.bitchord.ui.components.GLASS_EDGE_WIDTH
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.SfIcons
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

    val selectedModel = remember(modelId) {
        modelId?.let { AcousticModelCatalog.getModelById(it)?.toHeadphoneModel() }
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var showPersonalizationWizard by remember { mutableStateOf(false) }

    val isPhoneSpeaker = outputStatus.routeKind == AudioRouting.Kind.PHONE
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val haptics = rememberHaptics()

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isPhoneSpeaker) 0.38f else 1.0f,
        label = "ControlsAlpha",
    )

    val presetCurveColor = Color(0xFF38BDF8)   // Electric Cyan
    val personalCurveColor = Color(0xFFF59E0B) // Amber / Warm Gold

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // --- Top Bar: Back Button & Title ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isPhoneSpeaker) 10.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGlass) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Icon(
                    imageVector = SfIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = stringResource(R.string.audio_personalisation),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = (-0.3).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // --- Speaker State Notice (Displayed directly below title when routed to phone speaker) ---
        AnimatedVisibility(
            visible = isPhoneSpeaker,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isGlass) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = SfIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.connect_audio_device_notice),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    ),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // --- Main Control Card Container (Device Preset + Personal EQ) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .then(
                    if (isGlass) {
                        Modifier
                            .lightweightLiquidGlass(
                                shape = RoundedCornerShape(18.dp),
                                specular = true,
                                tintAlpha = 0.65f,
                            )
                            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(18.dp),
                            )
                    }
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ROW 1: Device Preset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isPhoneSpeaker) {
                            haptics.play(Haptic.Tap)
                            showModelPicker = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(presetCurveColor.copy(alpha = 0.12f * controlsAlpha)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SfIcons.Headphones,
                            contentDescription = null,
                            tint = presetCurveColor.copy(alpha = controlsAlpha),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.device_preset),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha),
                        )
                        Spacer(Modifier.height(2.dp))
                        val subtitleText = when {
                            isPhoneSpeaker -> stringResource(R.string.disabled_on_phone_speaker)
                            !devicePresetEnabled -> "Preset disabled"
                            selectedModel != null -> "${selectedModel.brand} ${selectedModel.name}"
                            modelName != null -> "${modelBrand ?: ""} $modelName".trim()
                            else -> stringResource(R.string.select_device_preset)
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = if (isPhoneSpeaker) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = controlsAlpha)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Icon(
                        imageVector = SfIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * controlsAlpha),
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Hairline Divider
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f * controlsAlpha),
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(start = 68.dp),
                )

                // ROW 2: Personal EQ (Dual-Action Row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left area: Tapping anywhere except the switch opens the Personal EQ Setup/Wizard
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isPhoneSpeaker) {
                                haptics.play(Haptic.Tap)
                                showPersonalizationWizard = true
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(personalCurveColor.copy(alpha = 0.12f * controlsAlpha)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = SfIcons.Hearing,
                                contentDescription = null,
                                tint = personalCurveColor.copy(alpha = controlsAlpha),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.personal_eq),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.personal_eq_subtitle), // "Audio tuning for your ears"
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = controlsAlpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Vertical Divider
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f * controlsAlpha),
                        thickness = 0.8.dp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )

                    // Right area: Dedicated Switch Toggle
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        IosSwitch(
                            checked = personalizationEnabled && personalizationCompleted,
                            enabled = !isPhoneSpeaker,
                            onCheckedChange = { desired ->
                                if (desired) {
                                    if (!personalizationCompleted) {
                                        showPersonalizationWizard = true
                                    } else {
                                        AppSettings.setPersonalizationEnabled(true)
                                        AppSettings.setAudioCalibrationEnabled(true)
                                    }
                                } else {
                                    AppSettings.setPersonalizationEnabled(false)
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))

        // --- SECTION: Current EQ ---
        Text(
            text = stringResource(R.string.current_eq).uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = controlsAlpha),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        // --- Current EQ Graph Card Container ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .alpha(controlsAlpha)
                .then(
                    if (isGlass) {
                        Modifier
                            .lightweightLiquidGlass(
                                shape = RoundedCornerShape(18.dp),
                                specular = true,
                                tintAlpha = 0.65f,
                            )
                            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(18.dp),
                            )
                    }
                )
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                            val presetIsActive = devicePresetEnabled && (selectedModel != null || bands10.isNotEmpty()) && audioCalibrationEnabled
                            val presetAlpha = if (!presetIsActive || previewUncalibrated) 0.20f else 0.90f
                            val presetStrokeWidth = if (presetIsActive && !previewUncalibrated) 2.5.dp.toPx() else 1.8.dp.toPx()

                            if (bands10.isNotEmpty()) {
                                val presetPath = Path()
                                for (i in 0 until bandCount) {
                                    val x = i * stepX
                                    val rawDb = bands10.getOrNull(i.coerceAtMost(bands10.lastIndex)) ?: 0f
                                    val y = dbToY(rawDb)
                                    if (i == 0) presetPath.moveTo(x, y) else presetPath.lineTo(x, y)
                                }
                                drawPath(
                                    path = presetPath,
                                    color = presetCurveColor.copy(alpha = presetAlpha),
                                    style = Stroke(width = presetStrokeWidth, cap = StrokeCap.Round),
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
                            val personalStrokeWidth = if (personalIsActive && !previewUncalibrated) 2.5.dp.toPx() else 1.8.dp.toPx()

                            if (personalizationCompleted) {
                                val personalPath = Path()
                                for (i in 0 until bandCount) {
                                    val x = i * stepX
                                    val left = leftGains.getOrNull(i) ?: 0f
                                    val right = rightGains.getOrNull(i) ?: 0f
                                    val avg = (left + right) / 2f
                                    val y = dbToY(avg)
                                    if (i == 0) personalPath.moveTo(x, y) else personalPath.lineTo(x, y)
                                }
                                drawPath(
                                    path = personalPath,
                                    color = personalCurveColor.copy(alpha = personalAlpha),
                                    style = Stroke(width = personalStrokeWidth, cap = StrokeCap.Round),
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
                    isPhoneSpeaker -> stringResource(R.string.disabled_on_phone_speaker)
                    selectedModel != null -> "${selectedModel.brand} ${selectedModel.name}"
                    modelName != null -> "${modelBrand ?: ""} $modelName".trim()
                    outputStatus.activeDeviceName != null -> outputStatus.activeDeviceName!!
                    else -> "No Device Selected"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

                // Studio Demo / Live A/B hold-to-preview button
                Spacer(Modifier.height(16.dp))
                LivePreviewBar(
                    isBypassed = previewUncalibrated,
                    isEnabled = !isPhoneSpeaker,
                    onBypassChanged = { if (!isPhoneSpeaker) AppSettings.setPreviewUncalibrated(it) },
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
    isEnabled: Boolean = true,
    onBypassChanged: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val currentOnBypassChanged by rememberUpdatedState(onBypassChanged)

    val bgColor = if (!isEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    } else if (isBypassed) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else if (isGlass) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (!isEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                else if (isBypassed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp),
            )
            .then(
                if (isEnabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
                            haptics.play(Haptic.Tap)
                            currentOnBypassChanged(true)
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                val anyPressed = event.changes.any { it.pressed }
                                if (!anyPressed) {
                                    haptics.play(Haptic.Tap)
                                    currentOnBypassChanged(false)
                                    break
                                }
                            }
                        }
                    }
                } else Modifier
            )
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
                        if (!isEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        else if (isBypassed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
            )
            Text(
                text = if (!isEnabled) {
                    stringResource(R.string.disabled_on_phone_speaker)
                } else if (isBypassed) {
                    stringResource(R.string.uncalibrated_preview_label)
                } else {
                    stringResource(R.string.hold_to_preview_uncalibrated)
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isBypassed) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                ),
                color = if (!isEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else if (isBypassed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
