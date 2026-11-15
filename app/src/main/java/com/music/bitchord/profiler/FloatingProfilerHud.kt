package com.music.bitchord.profiler

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import com.music.bitchord.data.settings.AppSettings

/**
 * Lightweight real-time floating status overlay showing live CPU, thermal, and battery telemetry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingProfilerHud(
    onOpenProfiler: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled by AppSettings.floatingProfilerHud.collectAsStateWithLifecycle()
    if (!enabled) return

    val context = LocalContext.current
    DisposableEffect(Unit) {
        ProcessProfiler.startProfiling(context)
        onDispose {
            ProcessProfiler.stopProfiling()
        }
    }

    val snapshot by ProcessProfiler.snapshot.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 6.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.88f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), shape)
                .combinedClickable(
                    onClick = onOpenProfiler,
                    onLongClick = {
                        val file = ProcessProfiler.exportReport(context)
                        if (file != null) {
                            Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                            ProcessProfiler.logEvent("HUD", "Exported report to ${file.name}")
                        } else {
                            Toast.makeText(context, "Failed to export report", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
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
                    val cpuColor = when {
                        snapshot.totalProcessCpuPercent < 15f -> Color(0xFF4CAF50)
                        snapshot.totalProcessCpuPercent < 35f -> Color(0xFFFFC107)
                        else -> Color(0xFFFF5252)
                    }

                    Text(
                        text = "CPU: %.1f%%".format(snapshot.totalProcessCpuPercent),
                        color = cpuColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )

                    if (snapshot.batteryTemperatureC > 0f) {
                        Text(
                            text = "%.1f°C".format(snapshot.batteryTemperatureC),
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    if (snapshot.batteryDischargeCurrentMa > 0) {
                        Text(
                            text = "-%d mA".format(snapshot.batteryDischargeCurrentMa),
                            color = Color(0xFF64B5F6),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                // Subsystem quick breakdown
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val uiCpu = snapshot.subsystemCpu[Subsystem.UI_MAIN] ?: 0f
                    val renderCpu = snapshot.subsystemCpu[Subsystem.RENDER_GPU] ?: 0f
                    val audioCpu = snapshot.subsystemCpu[Subsystem.AUDIO_ENGINE] ?: 0f

                    SubsystemBadge("UI", uiCpu, Subsystem.UI_MAIN.color)
                    SubsystemBadge("Render", renderCpu, Subsystem.RENDER_GPU.color)
                    SubsystemBadge("Audio", audioCpu, Subsystem.AUDIO_ENGINE.color)
                }
            }
        }
    }
}

@Composable
private fun SubsystemBadge(label: String, cpu: Float, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .padding(horizontal = 3.dp, vertical = 0.5.dp)
        ) {
            Text(
                text = label,
                color = Color.Black,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = "%.1f%%".format(cpu),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
