package com.music.bitchord.perf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Tracks real-time CPU time, thread utilization, instantaneous battery power (mW),
 * current draw (mA), battery temperature, and SoC thermal throttling states.
 */
class PowerAndCpuMonitor(private val context: Context) {

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // CPU tick tracking for process
    private var lastProcessCpuTimeMs: Long = 0
    private var lastSampleUptimeMs: Long = 0
    private var lastProcessCpuUsage: Double = 0.0

    data class PowerSnapshot(
        val batteryPct: Int,
        val currentNowMa: Double,
        val voltageMv: Int,
        val powerMw: Double,
        val temperatureC: Double,
        val thermalStatus: String,
        val thermalHeadroom: Double,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("batteryPct", batteryPct)
            put("currentNowMa", "%.1f".format(Locale.ROOT, currentNowMa).toDouble())
            put("voltageMv", voltageMv)
            put("powerMw", "%.1f".format(Locale.ROOT, powerMw).toDouble())
            put("temperatureC", "%.1f".format(Locale.ROOT, temperatureC).toDouble())
            put("thermalStatus", thermalStatus)
            put("thermalHeadroom", "%.2f".format(Locale.ROOT, thermalHeadroom).toDouble())
        }
    }

    data class ThreadCpuInfo(
        val tid: Int,
        val name: String,
        val userTimeMs: Long,
        val systemTimeMs: Long,
        val totalCpuMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tid", tid)
            put("name", name)
            put("userTimeMs", userTimeMs)
            put("systemTimeMs", systemTimeMs)
            put("totalCpuMs", totalCpuMs)
        }
    }

    data class CpuSnapshot(
        val totalProcessCpuMs: Long,
        val processCpuUsagePct: Double,
        val activeThreadCount: Int,
        val topThreads: List<ThreadCpuInfo>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("totalProcessCpuMs", totalProcessCpuMs)
            put("processCpuUsagePct", "%.1f%%".format(Locale.ROOT, processCpuUsagePct))
            put("activeThreadCount", activeThreadCount)
            val arr = JSONArray()
            topThreads.forEach { arr.put(it.toJson()) }
            put("topThreads", arr)
        }
    }

    /**
     * Reads instantaneous battery current, voltage, power, and thermal status.
     */
    fun samplePower(): PowerSnapshot {
        var currentUa = 0
        var batteryPct = -1

        batteryManager?.let { bm ->
            currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        // Current is negative when discharging on most Android HALs; normalize to positive draw
        val currentMa = abs(currentUa) / 1000.0

        var voltageMv = 3850 // fallback nominal Li-ion voltage
        var tempC = 25.0

        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let {
                voltageMv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850)
                tempC = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10.0
                if (batteryPct <= 0) {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = (level * 100) / scale
                    }
                }
            }
        }

        // P = V * I (mW = mV * mA / 1000)
        val powerMw = (voltageMv * currentMa) / 1000.0

        // Thermal status
        val pm = powerManager
        val thermalStatusStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE (Normal)"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT (Minor Throttling)"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE (Throttling active)"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE (Heavy Throttling)"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL (Emergency shutdown risk)"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else {
            "UNAVAILABLE (< API 29)"
        }

        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm != null) {
            runCatching { pm.getThermalHeadroom(10).toDouble() }.getOrDefault(1.0)
        } else {
            1.0
        }

        return PowerSnapshot(
            batteryPct = batteryPct,
            currentNowMa = currentMa,
            voltageMv = voltageMv,
            powerMw = powerMw,
            temperatureC = tempC,
            thermalStatus = thermalStatusStr,
            thermalHeadroom = thermalHeadroom,
        )
    }

    /**
     * Reads process and per-thread CPU time from /proc/self/stat and /proc/self/task/.
     */
    fun sampleCpu(): CpuSnapshot {
        val nowUptime = SystemClock.uptimeMillis()
        val totalProcessCpuMs = Process.getElapsedCpuTime()

        // Calculate process CPU usage % since last sample
        if (lastSampleUptimeMs > 0 && nowUptime > lastSampleUptimeMs) {
            val deltaUptime = nowUptime - lastSampleUptimeMs
            val deltaCpu = totalProcessCpuMs - lastProcessCpuTimeMs
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            lastProcessCpuUsage = ((deltaCpu.toDouble() / (deltaUptime * cores)) * 100.0).coerceIn(0.0, 100.0)
        }
        lastSampleUptimeMs = nowUptime
        lastProcessCpuTimeMs = totalProcessCpuMs

        // Sample thread stats from /proc/self/task
        val threads = mutableListOf<ThreadCpuInfo>()
        runCatching {
            val taskDir = File("/proc/self/task")
            taskDir.listFiles()?.forEach { tidDir ->
                val tid = tidDir.name.toIntOrNull() ?: return@forEach
                val commFile = File(tidDir, "comm")
                val statFile = File(tidDir, "stat")

                val name = if (commFile.exists()) commFile.readText().trim() else "thread-$tid"

                if (statFile.exists()) {
                    val statParts = statFile.readText().split(" ")
                    if (statParts.size >= 16) {
                        // In /proc/[pid]/stat: field 14 is utime, 15 is stime (in clock ticks)
                        // Clock ticks are usually 100Hz on Android (10ms per tick)
                        val utimeTicks = statParts[13].toLongOrNull() ?: 0L
                        val stimeTicks = statParts[14].toLongOrNull() ?: 0L
                        val userMs = utimeTicks * 10L
                        val sysMs = stimeTicks * 10L
                        threads.add(ThreadCpuInfo(tid, name, userMs, sysMs, userMs + sysMs))
                    }
                }
            }
        }

        val topThreads = threads.sortedByDescending { it.totalCpuMs }.take(10)

        return CpuSnapshot(
            totalProcessCpuMs = totalProcessCpuMs,
            processCpuUsagePct = lastProcessCpuUsage,
            activeThreadCount = threads.size.takeIf { it > 0 } ?: Thread.activeCount(),
            topThreads = topThreads,
        )
    }
}
