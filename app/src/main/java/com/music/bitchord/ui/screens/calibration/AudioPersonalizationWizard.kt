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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.calibration.HearingToneGenerator
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

// Hughson-Westlake Audiometric Calibration Constants (ISO 8253-1 / NAL-NL2)
private const val STARTING_AMPLITUDE_DB: Float = -62.0f
private const val BASELINE_AMPLITUDE_DB: Float = -60.0f
private const val MAX_AMPLITUDE_DB: Float = -14.0f
private const val STEP_UP_DB: Float = 4.0f

private enum class WizardStage {
    ONBOARDING,
    TESTING,
    COMPLETION,
}

private enum class EarChannel(val label: String) {
    LEFT("Left Ear"),
    RIGHT("Right Ear"),
}

/**
 * Audio Personalization Wizard.
 *
 * Implements:
 * 1. Pre-test onboarding: clean instructions (quiet space, count beeps, replay anytime).
 * 2. Test Screen:
 *    - Displays current channel ("Left Ear" then "Right Ear") and frequency zone.
 *    - Audio wave pulse visualizer.
 *    - [ Replay Audio ] button.
 *    - 2-Step Question:
 *      Step 1: "Did you hear any beeps?" -> [ No ] and [ Yes ] buttons.
 *      Step 2 (if Yes): "How many beeps did you hear?" -> [ 1 ], [ 2 ], [ 3 ] buttons.
 * 3. Completion Screen:
 *    - Dual-ear frequency response curve summary.
 *    - Live A/B preview: [ Hold to preview uncalibrated ].
 *    - Save and Apply button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPersonalizationWizard(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    val closeAfter: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true
            action()
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }

    var stage by remember { mutableStateOf(WizardStage.ONBOARDING) }

    // Frequency zones to test
    val zones = HeadphoneCatalog.ZONES
    val totalSteps = zones.size * 2 // Left ear zones, then Right ear zones

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentEar = if (currentStepIndex < zones.size) EarChannel.LEFT else EarChannel.RIGHT
    val currentZone = zones[currentStepIndex % zones.size]

    // 2-Step question state
    var answeredStep1Yes by remember { mutableStateOf(false) }
    var currentRandomBeeps by remember { mutableIntStateOf(2) }
    var isPlayingTone by remember { mutableStateOf(false) }

    // Collected deltas (in dB)
    val leftEarDeltas = remember { mutableStateListOf<Float>() }
    val rightEarDeltas = remember { mutableStateListOf<Float>() }

    // Initialize/reset deltas if empty
    if (leftEarDeltas.isEmpty()) {
        repeat(zones.size) { leftEarDeltas.add(0.0f) }
    }
    if (rightEarDeltas.isEmpty()) {
        repeat(zones.size) { rightEarDeltas.add(0.0f) }
    }

    var currentAmplitudeDb by remember { mutableFloatStateOf(STARTING_AMPLITUDE_DB) }

    // Function to generate and play tone at specific amplitude
    val playToneAtVolume: (Float) -> Unit = { amp ->
        val beeps = Random.nextInt(1, 4) // 1, 2, or 3 beeps
        currentRandomBeeps = beeps
        answeredStep1Yes = false
        ToneSynthesizer.playTones(
            scope = scope,
            frequencyHz = currentZone.frequencyHz,
            ear = currentEar,
            beepCount = beeps,
            amplitudeDb = amp,
            onPlayingChange = { isPlayingTone = it },
        )
    }

    // Start tone on entering testing or advancing steps
    LaunchedEffect(stage, currentStepIndex) {
        if (stage == WizardStage.TESTING) {
            currentAmplitudeDb = STARTING_AMPLITUDE_DB
            ToneSynthesizer.ensureStarted(scope)
            delay(300)
            playToneAtVolume(STARTING_AMPLITUDE_DB)
        } else {
            ToneSynthesizer.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ToneSynthesizer.stop()
        }
    }

    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    ModalBottomSheet(
        onDismissRequest = { closeAfter {} },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (isGlass) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // Drag Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
                },
                label = "WizardStageTransition",
            ) { currentStage ->
                when (currentStage) {
                    WizardStage.ONBOARDING -> {
                        WizardOnboardingContent(
                            onStart = {
                                stage = WizardStage.TESTING
                            },
                            onClose = { closeAfter {} },
                        )
                    }
                    WizardStage.TESTING -> {
                        WizardTestingContent(
                            currentStepIndex = currentStepIndex,
                            totalSteps = totalSteps,
                            currentEar = currentEar,
                            currentZone = currentZone,
                            currentAmplitudeDb = currentAmplitudeDb,
                            isPlayingTone = isPlayingTone,
                            answeredStep1Yes = answeredStep1Yes,
                            onReplayAudio = { playToneAtVolume(currentAmplitudeDb) },
                            onStep1No = {
                                // User didn't hear tone -> step up volume by +4dB (up to -14dB) to find threshold!
                                val nextAmp = (currentAmplitudeDb + STEP_UP_DB).coerceAtMost(MAX_AMPLITUDE_DB)
                                currentAmplitudeDb = nextAmp
                                playToneAtVolume(nextAmp)
                            },
                            onStep1Yes = {
                                answeredStep1Yes = true
                            },
                            onStep2Count = { count ->
                                val zoneIdx = currentStepIndex % zones.size
                                if (count == currentRandomBeeps) {
                                    // User accurately heard beeps -> lock in threshold!
                                    // Calculate threshold against -60 dBFS baseline with NAL-NL2 0.45x prescription weighting
                                    val hearingLossDb = (currentAmplitudeDb - BASELINE_AMPLITUDE_DB).coerceAtLeast(0.0f)
                                    val delta = ((hearingLossDb * 0.45f) * 2f).roundToInt() / 2f
                                    val clampedDelta = delta.coerceIn(0.0f, 9.0f)

                                    if (currentEar == EarChannel.LEFT) {
                                        leftEarDeltas[zoneIdx] = clampedDelta
                                    } else {
                                        rightEarDeltas[zoneIdx] = clampedDelta
                                    }

                                    if (currentStepIndex + 1 < totalSteps) {
                                        currentStepIndex++
                                    } else {
                                        stage = WizardStage.COMPLETION
                                    }
                                } else {
                                    // Miscounted: step up volume by +4dB and play new count to verify
                                    val nextAmp = (currentAmplitudeDb + STEP_UP_DB).coerceAtMost(MAX_AMPLITUDE_DB)
                                    currentAmplitudeDb = nextAmp
                                    playToneAtVolume(nextAmp)
                                }
                            },
                            onClose = { closeAfter {} },
                        )
                    }
                    WizardStage.COMPLETION -> {
                        WizardCompletionContent(
                            leftDeltas = leftEarDeltas.toList(),
                            rightDeltas = rightEarDeltas.toList(),
                            zones = zones,
                            onSave = {
                                AudioCalibrationManager.saveCalibration(
                                    leftDeltas = leftEarDeltas.toList(),
                                    rightDeltas = rightEarDeltas.toList(),
                                )
                                AppSettings.setPersonalizationEnabled(true)
                                AppSettings.setAudioCalibrationEnabled(true)
                                closeAfter {}
                            },
                            onClose = { closeAfter {} },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Onboarding Content
// ---------------------------------------------------------------------------

@Composable
private fun WizardOnboardingContent(
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.personalization_wizard_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = SfIcons.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.personalization_onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Instruction Item 1: Quiet space
        InstructionCard(
            icon = Icons.Rounded.VolumeDown,
            title = stringResource(R.string.instruction_quiet_title),
            description = stringResource(R.string.instruction_quiet_desc),
        )

        Spacer(Modifier.height(14.dp))

        // Instruction Item 2: Count beeps
        InstructionCard(
            icon = SfIcons.Hearing,
            title = stringResource(R.string.instruction_beeps_title),
            description = stringResource(R.string.instruction_beeps_desc),
        )

        Spacer(Modifier.height(14.dp))

        // Instruction Item 3: Replay anytime
        InstructionCard(
            icon = Icons.Rounded.Replay,
            title = stringResource(R.string.instruction_replay_title),
            description = stringResource(R.string.instruction_replay_desc),
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(R.string.begin_calibration),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InstructionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
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
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Testing Screen Content
// ---------------------------------------------------------------------------

@Composable
private fun WizardTestingContent(
    currentStepIndex: Int,
    totalSteps: Int,
    currentEar: EarChannel,
    currentZone: FrequencyZone,
    currentAmplitudeDb: Float,
    isPlayingTone: Boolean,
    answeredStep1Yes: Boolean,
    onReplayAudio: () -> Unit,
    onStep1No: () -> Unit,
    onStep1Yes: () -> Unit,
    onStep2Count: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val progress = (currentStepIndex + 1f) / totalSteps.toFloat()
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top header with close & progress
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Step ${currentStepIndex + 1} of $totalSteps",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = SfIcons.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Channel and Frequency Zone Header
        Text(
            text = if (currentEar == EarChannel.LEFT) {
                stringResource(R.string.channel_left_ear)
            } else {
                stringResource(R.string.channel_right_ear)
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "${currentZone.label} · ${currentZone.technicalName}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        val levelLabel = when {
            currentAmplitudeDb <= -54f -> "Volume: Very Faint (Testing Threshold)"
            currentAmplitudeDb <= -42f -> "Volume: Faint"
            currentAmplitudeDb <= -30f -> "Volume: Soft"
            currentAmplitudeDb <= -22f -> "Volume: Moderate"
            else -> "Volume: Elevated"
        }
        Text(
            text = levelLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        )

        Spacer(Modifier.height(24.dp))

        // Wave Pulse Visualizer
        AudioWaveVisualizer(
            isPlaying = isPlayingTone,
            modifier = Modifier.size(160.dp),
        )

        Spacer(Modifier.height(20.dp))

        // Replay Audio Button
        OutlinedButton(
            onClick = onReplayAudio,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.replay_audio),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(28.dp))

        // 2-Step Question Flow
        if (!answeredStep1Yes) {
            // Step 1: Did you hear any beeps?
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.did_you_hear_beeps),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedButton(
                        onClick = onStep1No,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = "No",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Button(
                        onClick = onStep1Yes,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = "Yes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else {
            // Step 2: How many beeps did you hear?
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.how_many_beeps),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(1, 2, 3).forEach { count ->
                        Button(
                            onClick = { onStep2Count(count) },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioWaveVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.32f

            if (isPlaying) {
                // Expanding outer ring 1
                drawCircle(
                    color = primaryColor.copy(alpha = 0.12f),
                    radius = baseRadius * pulseScale * 1.25f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
                // Expanding outer ring 2
                drawCircle(
                    color = primaryColor.copy(alpha = 0.22f),
                    radius = baseRadius * pulseScale,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }

            // Core center circle
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isPlaying) 0.85f else 0.40f),
                        primaryColor.copy(alpha = if (isPlaying) 0.30f else 0.15f),
                    ),
                    center = center,
                    radius = baseRadius,
                ),
                radius = baseRadius,
                center = center,
            )
        }

        Icon(
            imageVector = SfIcons.Hearing,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(34.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Completion Screen Content
// ---------------------------------------------------------------------------

@Composable
private fun WizardCompletionContent(
    leftDeltas: List<Float>,
    rightDeltas: List<Float>,
    zones: List<FrequencyZone>,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var isPreviewingUncalibrated by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.calibration_complete),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.calibration_complete_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = SfIcons.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Dual-Ear Frequency Response Curve Summary
        DualEarCurveGraph(
            leftDeltas = leftDeltas,
            rightDeltas = rightDeltas,
            zones = zones,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        )

        Spacer(Modifier.height(14.dp))

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(color = Color(0xFF00E5FF), label = "Left Ear")
            Spacer(Modifier.width(18.dp))
            LegendItem(color = Color(0xFFFF6E40), label = "Right Ear")
            Spacer(Modifier.width(18.dp))
            LegendItem(color = Color(0xFF888888), label = "Harman Baseline", isDashed = true)
        }

        Spacer(Modifier.height(24.dp))

        // Live A/B Preview Button: [ Hold to preview uncalibrated ]
        HoldToPreviewButton(
            isHeld = isPreviewingUncalibrated,
            onHoldChanged = { held ->
                isPreviewingUncalibrated = held
                AudioCalibrationManager.setBypass(held)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // Save and Apply Button
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.save_and_apply),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    isDashed: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 3.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DualEarCurveGraph(
    leftDeltas: List<Float>,
    rightDeltas: List<Float>,
    zones: List<FrequencyZone>,
    modifier: Modifier = Modifier,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(16.dp)

    val backgroundModifier = if (isGlass) {
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

    Box(
        modifier = modifier
            .then(backgroundModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val gridColor = Color.White.copy(alpha = 0.08f)
            val baselineColor = Color(0xFF888888).copy(alpha = 0.6f)
            val leftColor = Color(0xFF00E5FF)
            val rightColor = Color(0xFFFF6E40)

            // Grid lines (+6dB, 0dB, -6dB)
            val yTop = h * 0.15f
            val yMid = h * 0.50f
            val yBottom = h * 0.85f

            drawLine(gridColor, Offset(0f, yTop), Offset(w, yTop), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, yMid), Offset(w, yMid), strokeWidth = 1.5f)
            drawLine(gridColor, Offset(0f, yBottom), Offset(w, yBottom), strokeWidth = 1f)

            // Harman Reference baseline (dashed line at mid)
            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            drawLine(
                color = baselineColor,
                start = Offset(0f, yMid),
                end = Offset(w, yMid),
                strokeWidth = 2f,
                pathEffect = dashedEffect,
            )

            val count = zones.size
            if (count > 1) {
                val stepX = w / (count - 1).toFloat()

                // Left ear curve path
                val leftPath = Path()
                // Right ear curve path
                val rightPath = Path()

                for (i in 0 until count) {
                    val x = i * stepX
                    val lDelta = leftDeltas.getOrElse(i) { 0f }
                    val rDelta = rightDeltas.getOrElse(i) { 0f }

                    // Map delta: 0dB -> yMid, +6dB -> yTop, -6dB -> yBottom
                    val lY = yMid - (lDelta / 6f) * (yMid - yTop)
                    val rY = yMid - (rDelta / 6f) * (yMid - yTop)

                    if (i == 0) {
                        leftPath.moveTo(x, lY)
                        rightPath.moveTo(x, rY)
                    } else {
                        val prevX = (i - 1) * stepX
                        val prevLY = yMid - (leftDeltas.getOrElse(i - 1) { 0f } / 6f) * (yMid - yTop)
                        val prevRY = yMid - (rightDeltas.getOrElse(i - 1) { 0f } / 6f) * (yMid - yTop)

                        val cx = (prevX + x) / 2f
                        leftPath.cubicTo(cx, prevLY, cx, lY, x, lY)
                        rightPath.cubicTo(cx, prevRY, cx, rY, x, rY)
                    }
                }

                // Draw curves
                drawPath(
                    path = leftPath,
                    color = leftColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(
                    path = rightPath,
                    color = rightColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )

                // Draw node dots
                for (i in 0 until count) {
                    val x = i * stepX
                    val lDelta = leftDeltas.getOrElse(i) { 0f }
                    val rDelta = rightDeltas.getOrElse(i) { 0f }
                    val lY = yMid - (lDelta / 6f) * (yMid - yTop)
                    val rY = yMid - (rDelta / 6f) * (yMid - yTop)

                    drawCircle(leftColor, radius = 4.dp.toPx(), center = Offset(x, lY))
                    drawCircle(rightColor, radius = 4.dp.toPx(), center = Offset(x, rY))
                }
            }
        }
    }
}

@Composable
fun HoldToPreviewButton(
    isHeld: Boolean,
    onHoldChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(14.dp)

    val buttonBgModifier = if (isHeld) {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.tertiary, shape)
    } else if (isGlass) {
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

    Box(
        modifier = modifier
            .then(buttonBgModifier)
            .height(50.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onHoldChanged(true)
                    waitForUpOrCancellation()
                    onHoldChanged(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isHeld) Color(0xFFFF9100) else MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isHeld) {
                    stringResource(R.string.uncalibrated_preview_label)
                } else {
                    stringResource(R.string.hold_to_preview_uncalibrated)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isHeld) FontWeight.Bold else FontWeight.Medium,
                color = if (isHeld) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Pure Tone Synthesizer using Android AudioTrack
// ---------------------------------------------------------------------------

private object ToneSynthesizer {
    private const val TAG = "ToneSynthesizer"
    private const val SAMPLE_RATE = HearingToneGenerator.DEFAULT_SAMPLE_RATE
    private const val CARRIER_CHUNK_FRAMES = 1024

    private val lock = Any()
    private var activeTrack: AudioTrack? = null
    private var streamingJob: Job? = null

    private class ToneRequest(
        val samples: FloatArray,
        val onPlayingChange: (Boolean) -> Unit,
    )

    private val toneChannel = Channel<ToneRequest>(Channel.CONFLATED)

    private val carrierChunk = HearingToneGenerator.generateKeepAliveCarrier(
        frameCount = CARRIER_CHUNK_FRAMES,
        ditherAmplitude = 1e-5f,
    )

    fun ensureStarted(scope: CoroutineScope) {
        synchronized(lock) {
            if (activeTrack != null && streamingJob?.isActive == true) return

            try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
                val bufferSize = maxOf(minBufSize, SAMPLE_RATE * 2 * 4 * 250 / 1000)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                activeTrack = track

                streamingJob = scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val request = toneChannel.tryReceive().getOrNull()
                            if (request != null) {
                                request.onPlayingChange(true)
                                try {
                                    val samples = request.samples
                                    var offset = 0
                                    val total = samples.size
                                    val writeChunkSize = CARRIER_CHUNK_FRAMES * 2

                                    while (isActive && offset < total) {
                                        if (!toneChannel.isEmpty) {
                                            break
                                        }
                                        val toWrite = minOf(writeChunkSize, total - offset)
                                        val written = track.write(samples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                                        if (written > 0) {
                                            offset += written
                                        } else {
                                            break
                                        }
                                    }
                                } finally {
                                    request.onPlayingChange(false)
                                }
                            } else {
                                track.write(carrierChunk, 0, carrierChunk.size, AudioTrack.WRITE_BLOCKING)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            track.stop()
                            track.release()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ToneSynthesizer AudioTrack", e)
            }
        }
    }

    fun playTones(
        scope: CoroutineScope,
        frequencyHz: Int,
        ear: EarChannel,
        beepCount: Int,
        amplitudeDb: Float = -40.0f,
        onPlayingChange: (Boolean) -> Unit,
    ) {
        ensureStarted(scope)
        val channel = if (ear == EarChannel.LEFT) {
            HearingToneGenerator.Channel.LEFT
        } else {
            HearingToneGenerator.Channel.RIGHT
        }

        val samples = HearingToneGenerator.generatePulse(
            frequencyHz = frequencyHz.toFloat(),
            channel = channel,
            pulseCount = beepCount,
            amplitudeDb = amplitudeDb,
            sampleRate = SAMPLE_RATE,
            leadInMs = 120,
            leadOutMs = 100,
        )

        toneChannel.trySend(ToneRequest(samples, onPlayingChange))
    }

    fun stop() {
        synchronized(lock) {
            val job = streamingJob
            streamingJob = null
            job?.cancel()

            val track = activeTrack
            activeTrack = null
            try {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}
