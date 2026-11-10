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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.ui.icons.SfIcons

/**
 * Audio Calibration studio section in BitChord Settings.
 *
 * Implements:
 * 1. Route Status Indicator: Clean typographic badge showing current output device
 *    (e.g. "Wired Headset - Active" or "Phone Speaker - Bypassed") with a subtle status dot.
 * 2. Hardware Model Card: Shows current model name, brand, target baseline, and
 *    placeholder device silhouette (56x56dp). Tapping opens Model Picker.
 * 3. Re-testing Trigger: Subtle banner if hardware baseline changed:
 *    "Hardware baseline updated. Retake Audio Personalization to optimize for your new device."
 * 4. Audio Personalization Card: Shows status ("Dual-Ear Calibrated" or "Not Calibrated"),
 *    button to start/retake test.
 * 5. Live Studio Demo: A/B comparison button ([ Hold to preview uncalibrated ]).
 */
@Composable
fun AudioCalibrationSection(
    outputStatus: AudioOutputStatus.Snapshot,
    onOpenModelPicker: () -> Unit,
    onOpenWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AudioCalibrationManager.init(context)

    val selectedModel by AudioCalibrationManager.selectedModel.collectAsStateWithLifecycle()
    val profile by AudioCalibrationManager.calibrationProfile.collectAsStateWithLifecycle()
    val isBypassed by AudioCalibrationManager.isBypassed.collectAsStateWithLifecycle()
    val hardwareBaselineUpdated by AudioCalibrationManager.hardwareBaselineUpdated.collectAsStateWithLifecycle()

    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // Section Header & Route Status Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.audio_calibration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.audio_calibration_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Route Status Indicator Badge
            RouteStatusBadge(outputStatus = outputStatus)
        }

        // Hardware Model Card (56x56dp placeholder vector container)
        HardwareModelCard(
            model = selectedModel,
            onClick = onOpenModelPicker,
        )

        // Re-testing Trigger Banner (if baseline updated after calibration)
        AnimatedVisibility(
            visible = hardwareBaselineUpdated,
            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            HardwareBaselineUpdatedBanner(
                onRetake = onOpenWizard,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // Audio Personalization Card
        AudioPersonalizationCard(
            profile = profile,
            onStartWizard = onOpenWizard,
        )

        Spacer(Modifier.height(12.dp))

        // Live Studio Demo: A/B Comparison
        LiveStudioDemoCard(
            isBypassed = isBypassed,
            isCalibrated = profile.isCalibrated,
            onHoldChanged = { held ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                AudioCalibrationManager.setBypass(held)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Route Status Indicator Badge
// ---------------------------------------------------------------------------

@Composable
private fun RouteStatusBadge(outputStatus: AudioOutputStatus.Snapshot) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    val (routeName, isActive) = when (outputStatus.routeKind) {
        AudioRouting.Kind.WIRED -> "Wired Headset - Active" to true
        AudioRouting.Kind.USB -> "USB DAC - Active" to true
        AudioRouting.Kind.BLUETOOTH -> "Bluetooth Audio - Active" to true
        AudioRouting.Kind.PHONE -> "Phone Speaker - Bypassed" to false
        else -> "${outputStatus.deviceName} - Active" to true
    }

    val dotColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF00E676) else Color(0xFFFFA000),
        animationSpec = tween(300),
        label = "statusDotColor",
    )

    val shape = RoundedCornerShape(20.dp)
    val badgeModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = shape,
            )
    }

    Row(
        modifier = Modifier
            .then(badgeModifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = routeName,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// Hardware Model Card
// ---------------------------------------------------------------------------

@Composable
private fun HardwareModelCard(
    model: HeadphoneModel,
    onClick: () -> Unit,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(16.dp)

    val cardModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = shape,
            )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardModifier)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Standardized 56x56dp placeholder vector container
        DeviceSilhouette(
            formFactor = model.formFactor,
            isSelected = true,
            size = 56.dp,
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.hardware_model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "${model.brand} ${model.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.targetBaseline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            imageVector = SfIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Re-testing Trigger Banner
// ---------------------------------------------------------------------------

@Composable
private fun HardwareBaselineUpdatedBanner(
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(14.dp)

    val bannerModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.20f),
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                shape = shape,
            )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(bannerModifier)
            .clickable(onClick = onRetake)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.hardware_baseline_updated_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = "Retake",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

// ---------------------------------------------------------------------------
// Audio Personalization Card
// ---------------------------------------------------------------------------

@Composable
private fun AudioPersonalizationCard(
    profile: AudioCalibrationProfile,
    onStartWizard: () -> Unit,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(16.dp)

    val cardModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = shape,
            )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardModifier)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = SfIcons.Hearing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.audio_personalization),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Status Badge
            if (profile.isCalibrated) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.dual_ear_calibrated),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.not_calibrated),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (profile.isCalibrated) {
                "Individual Left & Right ear acoustic compensation active"
            } else {
                "Measure and balance your personal hearing threshold across frequency bands"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onStartWizard,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (profile.isCalibrated) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (profile.isCalibrated) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (profile.isCalibrated) {
                    stringResource(R.string.retake_personalization)
                } else {
                    stringResource(R.string.start_personalization)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Live Studio Demo: A/B Comparison
// ---------------------------------------------------------------------------

@Composable
private fun LiveStudioDemoCard(
    isBypassed: Boolean,
    isCalibrated: Boolean,
    onHoldChanged: (Boolean) -> Unit,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(16.dp)

    val cardModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = shape,
            )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardModifier)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Live Studio Demo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Current Mode Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isBypassed) Color(0xFFFF9100)
                            else if (isCalibrated) Color(0xFF00E676)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isBypassed) {
                        "Uncalibrated (Bypassed)"
                    } else if (isCalibrated) {
                        "Calibrated (Active)"
                    } else {
                        "Hardware Baseline"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBypassed) {
                        Color(0xFFFF9100)
                    } else if (isCalibrated) {
                        Color(0xFF00E676)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Hold down to instantly compare against raw, uncalibrated audio",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // A/B Comparison Hold Button
        HoldToPreviewButton(
            isHeld = isBypassed,
            onHoldChanged = onHoldChanged,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
