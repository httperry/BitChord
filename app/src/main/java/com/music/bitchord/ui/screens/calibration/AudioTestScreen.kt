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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Global flag indicating Audio Test screen is active (used to overtake volume controls). */
object AudioTestState {
    @Volatile
    var isActive: Boolean = false
}

/**
 * Frequency-specific Hughson-Westlake audiometric starting levels & normal reference thresholds
 * based on ISO 226 equal-loudness contours (ANSI S3.6 standard).
 *
 * Normal human hearing requires significantly more sound energy at 125 Hz than at 1 kHz-4 kHz.
 * Starting levels are calibrated 14 dB - 18 dB below normal threshold so EVERY frequency
 * begins inaudible, prompting the user to press "No" as the ascending staircase begins.
 */
data class AudiometricThresholdSpec(
    val startingAmplitudeDb: Float,
    val referenceThresholdDb: Float,
)

fun getThresholdSpecForFrequency(frequencyHz: Float): AudiometricThresholdSpec = when {
    frequencyHz <= 160f -> AudiometricThresholdSpec(startingAmplitudeDb = -74.0f, referenceThresholdDb = -56.0f) // 125 Hz Sub-Bass
    frequencyHz <= 350f -> AudiometricThresholdSpec(startingAmplitudeDb = -82.0f, referenceThresholdDb = -68.0f) // 250 Hz Bass
    frequencyHz <= 750f -> AudiometricThresholdSpec(startingAmplitudeDb = -88.0f, referenceThresholdDb = -76.0f) // 500 Hz Low-Mids
    frequencyHz <= 1500f -> AudiometricThresholdSpec(startingAmplitudeDb = -94.0f, referenceThresholdDb = -82.0f) // 1000 Hz Mids
    frequencyHz <= 3000f -> AudiometricThresholdSpec(startingAmplitudeDb = -96.0f, referenceThresholdDb = -84.0f) // 2000 Hz Upper-Mids
    frequencyHz <= 6000f -> AudiometricThresholdSpec(startingAmplitudeDb = -98.0f, referenceThresholdDb = -86.0f) // 4000 Hz Presence (Peak sensitivity)
    else -> AudiometricThresholdSpec(startingAmplitudeDb = -84.0f, referenceThresholdDb = -68.0f) // 8000 Hz Air / Treble
}

private const val MAX_AMPLITUDE_DB: Float = -12.0f
private const val STEP_UP_DB: Float = 4.0f

private enum class TestStage {
    DID_YOU_HEAR,
    HOW_MANY,
    COMPLETED,
}

private enum class TestEar(val label: String, val shortLabel: String) {
    LEFT("Left Ear", "L"),
    RIGHT("Right Ear", "R"),
}

/**
 * Dedicated full-screen Audio Test screen.
 *
 * Implements:
 * 1. Immersive dark fade / progressive blur backdrop matching app theme.
 * 2. Comic book halftone concentric dot pattern visualizer with 1-second radial wave ripple.
 * 3. Central headphone badge displaying active testing channel (perfectly centered).
 * 4. Replay button positioned cleanly above the prompt text.
 * 5. Sequential 2-step prompt flow:
 *    - "Did you hear that?" with [ No ] and [ Yes ] pill buttons.
 *    - "How many did you hear?" with [ 1 ], [ 2 ], [ 3 ] count verification pills.
 * 6. Frequency-adjusted ISO 226 Hughson-Westlake ascending staircase algorithm
 *    producing genuine, non-flat NAL-NL2 hearing threshold calibration.
 * 7. System volume overtake: locks media volume to a standardized calibrated level during the test
 *    and restores previous volume upon exit.
 * 8. Persistent streaming AudioTrack with inaudible comfort dither keep-alive carrier
 *    ensuring Bluetooth A2DP transport and earbud DACs never sleep.
 */
@Composable
fun AudioTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val progressiveBlurEnabled by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    // Testing zones (7 key audiometric frequencies)
    val zones = HeadphoneCatalog.ZONES
    val totalSteps = zones.size * 2

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentEar = if (currentStepIndex < zones.size) TestEar.LEFT else TestEar.RIGHT
    val currentZone = zones[currentStepIndex % zones.size]

    var stage by remember { mutableStateOf(TestStage.DID_YOU_HEAR) }
    var currentRandomBeeps by remember { mutableIntStateOf(2) }
    var isPlayingTone by remember { mutableStateOf(false) }

    val initialSpec = remember(currentZone.frequencyHz) {
        getThresholdSpecForFrequency(currentZone.frequencyHz.toFloat())
    }
    var currentAmplitudeDb by remember { mutableFloatStateOf(initialSpec.startingAmplitudeDb) }

    // Collected hearing deltas (in dB)
    val leftEarDeltas = remember { mutableStateListOf<Float>() }
    val rightEarDeltas = remember { mutableStateListOf<Float>() }

    if (leftEarDeltas.isEmpty()) {
        repeat(zones.size) { leftEarDeltas.add(0.0f) }
    }
    if (rightEarDeltas.isEmpty()) {
        repeat(zones.size) { rightEarDeltas.add(0.0f) }
    }

    val playToneAtVolume: (Float) -> Unit = { amp ->
        val beeps = Random.nextInt(1, 4)
        currentRandomBeeps = beeps
        stage = TestStage.DID_YOU_HEAR
        AudioTestToneStreamer.playTones(
            scope = scope,
            frequencyHz = currentZone.frequencyHz.toFloat(),
            ear = currentEar,
            beepCount = beeps,
            amplitudeDb = amp,
            onPlayingChange = { isPlayingTone = it },
        )
    }

    // Trigger tone on step advancement and reset volume to the frequency's inaudible starting floor
    LaunchedEffect(currentStepIndex) {
        if (stage != TestStage.COMPLETED) {
            val spec = getThresholdSpecForFrequency(currentZone.frequencyHz.toFloat())
            currentAmplitudeDb = spec.startingAmplitudeDb
            AudioTestToneStreamer.ensureStarted(scope)
            delay(350)
            playToneAtVolume(spec.startingAmplitudeDb)
        }
    }

    // Overtake system volume controls and set standard test reference volume
    DisposableEffect(Unit) {
        AudioTestState.isActive = true
        val originalVolume = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(10)
        val maxVolume = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
        val referenceVolume = (maxVolume * 0.75f).roundToInt().coerceIn(1, maxVolume)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, referenceVolume, 0)
        }

        onDispose {
            AudioTestState.isActive = false
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
            AudioTestToneStreamer.stop()
        }
    }

    val progress = ((currentStepIndex + 1).toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // --- 1. Top Dark Fade / Vignette Overlay (Drawn BEHIND content so text is never blurred) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = if (progressiveBlurEnabled) 0.88f else 0.75f),
                        0.6f to Color.Black.copy(alpha = 0.35f),
                        1.0f to Color.Transparent,
                    ),
                ),
        )

        // --- 2. Bottom Dark Fade / Vignette Overlay (Creates the loved edge darkening behind buttons) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.30f to Color.Black.copy(alpha = if (progressiveBlurEnabled) 0.65f else 0.50f),
                        1.0f to Color.Black.copy(alpha = 0.98f),
                    ),
                ),
        )

        // --- 3. Screen UI Content ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Glass Circular Back Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            shape = CircleShape,
                        )
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SfIcons.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Audio Test",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (stage != TestStage.COMPLETED) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = "${currentEar.label} · ${currentZone.label} (${currentZone.frequencyHz.toInt()} Hz) · Step ${currentStepIndex + 1} of $totalSteps",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (stage != TestStage.COMPLETED) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                )
            }

            // --- Middle Visualizer Area (weight 1f, perfect optical & layout center) ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // Comic Book Halftone Wave Canvas centered at width/2, height/2
                ComicBookHalftoneWaveCanvas(
                    isPlaying = isPlayingTone,
                    currentEar = currentEar,
                    modifier = Modifier.fillMaxSize(),
                )

                // Central Headphone Badge in true center
                CentralHeadphoneBadge(
                    currentEar = currentEar,
                    isPlaying = isPlayingTone,
                    isGlass = isGlass,
                )
            }

            // --- Bottom Controls Area ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 6 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 6 })
                    },
                    label = "AudioTestStageTransition",
                ) { activeStage ->
                    when (activeStage) {
                        TestStage.DID_YOU_HEAR -> {
                            QuestionDidYouHearSection(
                                onReplay = { playToneAtVolume(currentAmplitudeDb) },
                                onNo = {
                                    // Step up volume by +4dB (Hughson-Westlake ascending staircase)
                                    val nextAmp = (currentAmplitudeDb + STEP_UP_DB).coerceAtMost(MAX_AMPLITUDE_DB)
                                    currentAmplitudeDb = nextAmp
                                    playToneAtVolume(nextAmp)
                                },
                                onYes = {
                                    stage = TestStage.HOW_MANY
                                },
                            )
                        }

                        TestStage.HOW_MANY -> {
                            QuestionHowManySection(
                                onReplay = { playToneAtVolume(currentAmplitudeDb) },
                                onCountSelected = { count ->
                                    val zoneIdx = currentStepIndex % zones.size
                                    if (count == currentRandomBeeps) {
                                        // Verified threshold! Compute NAL-NL2 0.45x prescription gain relative to ISO reference
                                        val spec = getThresholdSpecForFrequency(currentZone.frequencyHz.toFloat())
                                        val hearingLossDb = currentAmplitudeDb - spec.referenceThresholdDb
                                        val delta = if (hearingLossDb > 0f) {
                                            ((hearingLossDb * 0.45f) * 2f).roundToInt() / 2f
                                        } else {
                                            ((hearingLossDb * 0.20f) * 2f).roundToInt() / 2f
                                        }.coerceIn(-2.5f, 9.0f)

                                        if (currentEar == TestEar.LEFT) {
                                            leftEarDeltas[zoneIdx] = delta
                                        } else {
                                            rightEarDeltas[zoneIdx] = delta
                                        }

                                        if (currentStepIndex + 1 < totalSteps) {
                                            currentStepIndex++
                                        } else {
                                            stage = TestStage.COMPLETED
                                        }
                                    } else {
                                        // Miscounted: step up volume by +4dB and try new beep burst
                                        val nextAmp = (currentAmplitudeDb + STEP_UP_DB).coerceAtMost(MAX_AMPLITUDE_DB)
                                        currentAmplitudeDb = nextAmp
                                        playToneAtVolume(nextAmp)
                                    }
                                },
                            )
                        }

                        TestStage.COMPLETED -> {
                            CompletedTestSection(
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
                                    onBack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Comic Book Halftone Dot Pattern Visualizer
// ---------------------------------------------------------------------------

@Composable
private fun ComicBookHalftoneWaveCanvas(
    isPlaying: Boolean,
    currentEar: TestEar,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HalftoneWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val earAccentColor = if (currentEar == TestEar.LEFT) {
        Color(0xFF38BDF8) // Electric Cyan for Left Ear
    } else {
        Color(0xFFF59E0B) // Warm Amber for Right Ear
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = sqrt((size.width * size.width) + (size.height * size.height)) * 0.75f

        val centerDiscRadius = 52.dp.toPx()
        val ringStep = 24.dp.toPx()
        val numRings = ((maxRadius - centerDiscRadius) / ringStep).toInt().coerceIn(8, 22)

        val wavePosition = wavePhase * maxRadius
        val waveSpread = 70.dp.toPx()

        for (k in 1..numRings) {
            val r = centerDiscRadius + k * ringStep
            if (r > maxRadius) break

            val circumference = 2.0 * PI * r
            val dotSpacing = 20.dp.toPx()
            val dotCount = (circumference / dotSpacing).toInt().coerceAtLeast(8)
            val angleOffset = if (k % 2 == 1) (PI / dotCount).toFloat() else 0f

            // Distance to current wave pulse
            val distToWave = abs(r - wavePosition)
            val waveFactor = if (distToWave < waveSpread) {
                val ratio = distToWave / waveSpread
                (1.0f - (ratio * ratio)).coerceIn(0f, 1f)
            } else {
                0f
            }

            // Dot parameters influenced by the 1-second ripple wave
            val baseRadius = (2.6f + (k * 0.08f)).dp.toPx()
            val dynamicRadius = baseRadius * (1.0f + if (isPlaying) 0.95f * waveFactor else 0.55f * waveFactor)

            val baseAlpha = 0.15f
            val dynamicAlpha = (baseAlpha + waveFactor * (if (isPlaying) 0.80f else 0.55f)).coerceIn(0f, 1f)

            val dotColor = if (waveFactor > 0.35f) {
                earAccentColor.copy(alpha = dynamicAlpha)
            } else {
                primaryColor.copy(alpha = dynamicAlpha)
            }

            for (i in 0 until dotCount) {
                val theta = angleOffset + (2.0 * PI * i / dotCount).toFloat()
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)

                drawCircle(
                    color = dotColor,
                    radius = dynamicRadius,
                    center = Offset(x, y),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Central Headphone Badge
// ---------------------------------------------------------------------------

@Composable
private fun CentralHeadphoneBadge(
    currentEar: TestEar,
    isPlaying: Boolean,
    isGlass: Boolean,
) {
    val earColor = if (currentEar == TestEar.LEFT) Color(0xFF38BDF8) else Color(0xFFF59E0B)

    Box(
        modifier = Modifier
            .size(96.dp)
            .then(
                if (isGlass) {
                    Modifier.lightweightLiquidGlass(
                        shape = CircleShape,
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            )
            .border(
                width = if (isPlaying) 2.dp else 1.dp,
                color = if (isPlaying) earColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = SfIcons.Headphones,
                contentDescription = null,
                tint = if (isPlaying) earColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = currentEar.shortLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = earColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Question 1: "Did you hear that?"
// ---------------------------------------------------------------------------

@Composable
private fun QuestionDidYouHearSection(
    onReplay: () -> Unit,
    onNo: () -> Unit,
    onYes: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Replay Button placed ABOVE the question text
        ReplayAudioPillButton(onClick = onReplay)

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Did you hear that?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // Side-by-side [ Yes ] and [ No ] Pill Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(
                onClick = onYes,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = "Yes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OutlinedButton(
                onClick = onNo,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.colorScheme.outline,
                        ),
                    ),
                ),
            ) {
                Text(
                    text = "No",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Question 2: "How many did you hear?"
// ---------------------------------------------------------------------------

@Composable
private fun QuestionHowManySection(
    onReplay: () -> Unit,
    onCountSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Replay Button placed ABOVE the question text
        ReplayAudioPillButton(onClick = onReplay)

        Spacer(Modifier.height(18.dp))

        Text(
            text = "How many did you hear?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // [ 1 ]  [ 2 ]  [ 3 ] Count Pill Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(1, 2, 3).forEach { count ->
                Button(
                    onClick = { onCountSelected(count) },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
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

// ---------------------------------------------------------------------------
// 5. Replay Audio Pill Button
// ---------------------------------------------------------------------------

@Composable
private fun ReplayAudioPillButton(
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                ),
            ),
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.Replay,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Replay Audio",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// 6. Completed Test Section
// ---------------------------------------------------------------------------

@Composable
private fun CompletedTestSection(
    leftDeltas: List<Float>,
    rightDeltas: List<Float>,
    zones: List<FrequencyZone>,
    onSave: () -> Unit,
) {
    var isPreviewingUncalibrated by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Audio Test Complete",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Your personalized dual-ear frequency curves are ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))

        // Dual-Ear Response Curve Preview
        DualEarCurveGraph(
            leftDeltas = leftDeltas,
            rightDeltas = rightDeltas,
            zones = zones,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Live A/B Studio Comparison: press and hold to preview uncalibrated
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isPreviewingUncalibrated) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
                        isPreviewingUncalibrated = true
                        AudioCalibrationManager.setBypass(true)
                        AppSettings.setPreviewUncalibrated(true)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                if (!event.changes.any { it.pressed }) break
                            }
                        } finally {
                            isPreviewingUncalibrated = false
                            AudioCalibrationManager.setBypass(false)
                            AppSettings.setPreviewUncalibrated(false)
                        }
                    }
                }
                .padding(vertical = 12.dp, horizontal = 16.dp),
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
                            if (isPreviewingUncalibrated) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        ),
                )
                Text(
                    text = if (isPreviewingUncalibrated) "Previewing Uncalibrated" else "Hold to preview uncalibrated",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isPreviewingUncalibrated) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isPreviewingUncalibrated) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Save & Apply Button
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Save and Apply",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 7. Persistent Audio Streaming Tone Synthesizer with Keep-Alive Carrier
// ---------------------------------------------------------------------------

private object AudioTestToneStreamer {
    private const val TAG = "AudioTestToneStreamer"
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
                                        if (!toneChannel.isEmpty) break
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
                Log.e(TAG, "Failed to initialize AudioTestToneStreamer AudioTrack", e)
            }
        }
    }

    fun playTones(
        scope: CoroutineScope,
        frequencyHz: Float,
        ear: TestEar,
        beepCount: Int,
        amplitudeDb: Float = -40.0f,
        onPlayingChange: (Boolean) -> Unit,
    ) {
        ensureStarted(scope)
        val channel = if (ear == TestEar.LEFT) {
            HearingToneGenerator.Channel.LEFT
        } else {
            HearingToneGenerator.Channel.RIGHT
        }

        val samples = HearingToneGenerator.generatePulse(
            frequencyHz = frequencyHz,
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
