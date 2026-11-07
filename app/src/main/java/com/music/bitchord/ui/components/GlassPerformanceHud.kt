package com.music.bitchord.ui.components

import android.content.Context
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.abs

/**
 * On-screen real-time rendering diagnostics HUD for Liquid Glass performance testing.
 *
 * Displays:
 * 1. Live frame rendering latency in milliseconds.
 * 2. Instantaneous render framerate (FPS).
 * 3. Instantaneous battery discharge current in mA (from BatteryManager).
 * 4. Active optimization flags: [0.25x], [DirectHigh], [SIMD], [45ms].
 */
@Composable
fun GlassPerformanceHud(
    modifier: Modifier = Modifier
) {
    val enabled by AppSettings.glassPerformanceHud.collectAsStateWithLifecycle()
    val downscale by AppSettings.glassDownscale.collectAsStateWithLifecycle()
    val directHighlight by AppSettings.glassDirectHighlight.collectAsStateWithLifecycle()
    val simdMath by AppSettings.glassSimdMath.collectAsStateWithLifecycle()
    val ambientPacing by AppSettings.glassAmbientPacing.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val context = LocalContext.current
        val batteryManager = remember(context) {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }

        var avgFrameTimeMs by remember { mutableFloatStateOf(0f) }
        var currentFps by remember { mutableFloatStateOf(0f) }
        var dischargeCurrentMa by remember { mutableIntStateOf(0) }

        LaunchedEffect(enabled) {
            if (!enabled) return@LaunchedEffect
            var lastFrameNanos = Long.MIN_VALUE
            var frameCount = 0
            var accumulatedNanos = 0L

            while (true) {
                withFrameNanos { nowNanos ->
                    if (lastFrameNanos != Long.MIN_VALUE) {
                        val delta = nowNanos - lastFrameNanos
                        accumulatedNanos += delta
                        frameCount++

                        // Update telemetry every 400ms for stable readability
                        if (accumulatedNanos >= 400_000_000L) {
                            currentFps = (frameCount * 1_000_000_000f) / accumulatedNanos
                            avgFrameTimeMs = (accumulatedNanos / frameCount.toFloat()) / 1_000_000f
                            frameCount = 0
                            accumulatedNanos = 0L

                            val uA = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                            dischargeCurrentMa = abs(uA) / 1000
                        }
                    }
                    lastFrameNanos = nowNanos
                }
            }
        }

        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.82f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val fpsColor = when {
                        currentFps >= 100f -> Color(0xFF4CAF50)
                        currentFps >= 55f -> Color(0xFFFFC107)
                        else -> Color(0xFFFF5252)
                    }
                    Text(
                        text = "%.1f FPS".format(currentFps),
                        color = fpsColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "%.2f ms".format(avgFrameTimeMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (dischargeCurrentMa > 0) {
                        Text(
                            text = "-%d mA".format(dischargeCurrentMa),
                            color = Color(0xFF64B5F6),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OptTag("0.25x", downscale)
                    OptTag("DirectHi", directHighlight)
                    OptTag("SIMD", simdMath)
                    OptTag("45ms", ambientPacing)
                }
            }
        }
    }
}

@Composable
private fun OptTag(label: String, active: Boolean) {
    val bg = if (active) Color(0xFF1B5E20) else Color(0xFF37474F)
    val text = if (active) Color(0xFF81C784) else Color(0xFF90A4AE)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = text,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}
