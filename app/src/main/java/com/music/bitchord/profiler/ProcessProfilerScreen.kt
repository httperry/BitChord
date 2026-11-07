package com.music.bitchord.profiler

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-precision developer diagnostics suite:
 * - Real-time thread CPU breakdown
 * - Hardware Mali GPU duration & frame metrics
 * - Rolling 60-second time series trend graph
 * - Continuous activity trace logging
 */
@Composable
fun ProcessProfilerScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        ProcessProfiler.startProfiling(context)
        onDispose {
            ProcessProfiler.stopProfiling()
        }
    }

    val snapshot by ProcessProfiler.snapshot.collectAsStateWithLifecycle()
    val history by ProcessProfiler.history.collectAsStateWithLifecycle()
    val events by ProcessProfiler.recentEvents.collectAsStateWithLifecycle()
    val floatingHud by AppSettings.floatingProfilerHud.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Process & Hardware Profiler",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Hardware Summary
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "HARDWARE & SYSTEM TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            title = "Process Total CPU",
                            value = "%.1f%%".format(snapshot.totalProcessCpuPercent),
                            subtitle = "${snapshot.coreCount} Cores (UI: %.1f%%)".format(snapshot.mainThreadCpuPercent),
                            modifier = Modifier.weight(1f),
                            valueColor = when {
                                snapshot.totalProcessCpuPercent < 25f -> Color(0xFF4CAF50)
                                snapshot.totalProcessCpuPercent < 60f -> Color(0xFFFFC107)
                                else -> Color(0xFFFF5252)
                            }
                        )
                        MetricCard(
                            title = "Battery Drain",
                            value = if (snapshot.batteryDischargeCurrentMa > 0) "-${snapshot.batteryDischargeCurrentMa} mA" else "N/A",
                            subtitle = "Temp: %.1f °C".format(snapshot.batteryTemperatureC),
                            modifier = Modifier.weight(1f),
                            valueColor = Color(0xFF64B5F6)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            title = "Mali GPU Work",
                            value = "%.2f ms".format(snapshot.avgGpuDurationMs),
                            subtitle = "Frame: %.1fms · Jank: %.1f%%".format(snapshot.avgFrameDurationMs, snapshot.jankPercentage),
                            modifier = Modifier.weight(1f),
                            valueColor = if (snapshot.avgGpuDurationMs > 8.0f) Color(0xFFFF9800) else Color(0xFF4CAF50)
                        )
                        MetricCard(
                            title = "Thermal Status",
                            value = snapshot.thermalStatus,
                            subtitle = "Headroom: %.2f / 1.0".format(snapshot.thermalHeadroom),
                            modifier = Modifier.weight(1f),
                            valueColor = if (snapshot.thermalHeadroom >= 0.85f) Color(0xFFFF7043) else Color(0xFF81C784)
                        )
                    }
                }
            }

            // Section 2: 60-Second Real-Time Timeline Trend
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "60-SECOND ROLLING TIMELINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Green: Total CPU · Orange: RenderThread",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Black.copy(alpha = 0.82f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        CpuTimelineGraph(
                            history = history,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Section 3: Subsystem CPU Attribution
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "CPU ATTRIBUTION BY SUBSYSTEM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Subsystem.entries.forEach { sub ->
                                val cpu = snapshot.subsystemCpu[sub] ?: 0f
                                SubsystemRow(subsystem = sub, cpuPercent = cpu)
                            }
                        }
                    }
                }
            }

            // Section 4: Dev Controls
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "DEV CONTROLS & EXPORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Floating Profiler HUD",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Pin live CPU %, GPU work, and mA on-screen while using the app",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IosSwitch(
                                    checked = floatingHud,
                                    onCheckedChange = AppSettings::setFloatingProfilerHud
                                )
                            }

                            Button(
                                onClick = {
                                    val file = ProcessProfiler.exportReport(context)
                                    val msg = if (file != null) "Saved multi-point profile to Download/${file.name}" else "Failed to write report"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Export In-Depth Diagnostic Report to Downloads")
                            }
                        }
                    }
                }
            }

            // Section 5: Top Active Threads
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOP ACTIVE THREADS (${snapshot.topThreads.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Live Linux /proc/self/task/ sampling",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (snapshot.topThreads.isEmpty()) {
                item {
                    Text(
                        text = "Collecting thread samples...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(snapshot.topThreads) { thread ->
                    ThreadRow(thread = thread)
                }
            }

            // Section 6: Continuous Activity Trace Log
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CONTINUOUS ACTIVITY TRACE LOG (${events.size} Events)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.88f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (events.isEmpty()) {
                                Text(
                                    text = "Monitoring activity (playback, navigation, frame spikes)...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                                events.take(25).forEach { ev ->
                                    val time = timeFormat.format(Date(ev.timestamp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = "[$time]",
                                            color = Color.Gray,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(
                                                    when (ev.tag) {
                                                        "Playback" -> Color(0xFF1976D2)
                                                        "Automix" -> Color(0xFF00796B)
                                                        "Jank" -> Color(0xFFD32F2F)
                                                        "UI", "Nav" -> Color(0xFFE64A19)
                                                        else -> Color(0xFF455A64)
                                                    }
                                                )
                                                .padding(horizontal = 4.dp, vertical = 0.5.dp)
                                        ) {
                                            Text(
                                                text = ev.tag,
                                                color = Color.White,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Text(
                                            text = ev.message,
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CpuTimelineGraph(
    history: List<HistoryPoint>,
    modifier: Modifier = Modifier
) {
    if (history.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Building timeline history (samples every 1s)...",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        return
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxPoints = 60
        val points = history.takeLast(maxPoints)

        val maxVal = maxOf(100f, points.maxOf { it.totalCpu } * 1.15f)

        // Draw grid lines
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, h * 0.5f), Offset(w, h * 0.5f))
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f))

        val stepX = w / (maxPoints - 1).coerceAtLeast(1).toFloat()
        val totalPath = Path()
        val renderPath = Path()

        points.forEachIndexed { i, p ->
            val x = i * stepX
            val yTotal = h - ((p.totalCpu / maxVal) * h).coerceIn(0f, h)
            val yRender = h - ((p.renderThreadCpu / maxVal) * h).coerceIn(0f, h)

            if (i == 0) {
                totalPath.moveTo(x, yTotal)
                renderPath.moveTo(x, yRender)
            } else {
                totalPath.lineTo(x, yTotal)
                renderPath.lineTo(x, yRender)
            }
        }

        // Draw RenderThread line (Orange)
        drawPath(renderPath, Color(0xFFFF9800), style = Stroke(width = 1.5.dp.toPx()))
        // Draw Total CPU line (Green)
        drawPath(totalPath, Color(0xFF4CAF50), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SubsystemRow(subsystem: Subsystem, cpuPercent: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(subsystem.color)
                )
                Text(
                    text = subsystem.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "%.1f%%".format(cpuPercent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (cpuPercent > 0.05f) subsystem.color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { (cpuPercent / 100f).coerceIn(0f, 1f) },
            color = subsystem.color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun ThreadRow(thread: ThreadCpuSample) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = thread.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "TID:${thread.tid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = thread.subsystem.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = thread.subsystem.color,
                    fontSize = 11.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.1f%%".format(thread.cpuPercent),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        thread.cpuPercent < 5f -> MaterialTheme.colorScheme.onSurface
                        thread.cpuPercent < 15f -> Color(0xFFFF9800)
                        else -> Color(0xFFFF5252)
                    }
                )
                Text(
                    text = if (thread.state == "R") "RUNNING" else "SLEEP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (thread.state == "R") Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
