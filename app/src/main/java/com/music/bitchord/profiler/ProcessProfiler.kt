package com.music.bitchord.profiler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * Subsystem categories for attributing CPU time to functional areas of the app.
 */
enum class Subsystem(val label: String, val color: Color) {
    UI_MAIN("UI & Main Thread", Color(0xFF4CAF50)),
    RENDER_GPU("RenderThread & Shaders", Color(0xFFFF9800)),
    AUDIO_ENGINE("Audio Engine & Automix", Color(0xFF2196F3)),
    NETWORK("Network & Streaming", Color(0xFF9C27B0)),
    COROUTINES("Background Coroutines", Color(0xFF00BCD4)),
    DATABASE("Database & Storage", Color(0xFF795548)),
    OTHER("System & JVM Runtime", Color(0xFF9E9E9E));

    companion object {
        fun classify(threadName: String): Subsystem {
            val name = threadName.lowercase()
            return when {
                name == "main" -> UI_MAIN
                name.contains("renderthread") || name.contains("hwui") || name.contains("gpu") -> RENDER_GPU
                name.contains("exoplayer") || name.contains("audio") || name.contains("superpowered") || 
                    name.contains("automix") || name.contains("decoder") || name.contains("track") -> AUDIO_ENGINE
                name.contains("okhttp") || name.contains("cronet") || name.contains("download") || name.contains("http") -> NETWORK
                name.contains("defaultdispatch") || name.contains("commonpool") || name.contains("coroutine") -> COROUTINES
                name.contains("room") || name.contains("sqlite") || name.contains("disk") || name.contains("db") -> DATABASE
                else -> OTHER
            }
        }
    }
}

/**
 * CPU utilization metrics for a single thread in the current process.
 */
data class ThreadCpuSample(
    val tid: Int,
    val name: String,
    val state: String,
    val cpuPercent: Float,
    val subsystem: Subsystem
)

/**
 * Point-in-time process telemetry snapshot.
 */
data class ProcessSnapshot(
    val totalProcessCpuPercent: Float,
    val coreCount: Int,
    val thermalStatus: String,
    val thermalHeadroom: Float,
    val batteryDischargeCurrentMa: Int,
    val batteryTemperatureC: Float,
    val javaHeapMb: Float,
    val nativeHeapMb: Float,
    val subsystemCpu: Map<Subsystem, Float>,
    val topThreads: List<ThreadCpuSample>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Timestamped diagnostic trace event.
 */
data class ProfilerLogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String
)

/**
 * In-app high-precision Linux userspace thread profiler and hardware telemetry engine.
 *
 * Reads `/proc/self/task/<tid>/stat` to compute exact CPU tick delta per thread
 * with zero root requirements, low CPU overhead (<0.1%), and real-time attribution.
 */
object ProcessProfiler {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sampleJob: kotlinx.coroutines.Job? = null

    // Reference counts for active viewers (Screen or HUD)
    private var activeSubscribers = 0
    private val subscriberLock = Any()

    private val _snapshot = MutableStateFlow(createEmptySnapshot())
    val snapshot: StateFlow<ProcessSnapshot> = _snapshot.asStateFlow()

    private val eventQueue = ConcurrentLinkedDeque<ProfilerLogEvent>()
    private val _recentEvents = MutableStateFlow<List<ProfilerLogEvent>>(emptyList())
    val recentEvents: StateFlow<List<ProfilerLogEvent>> = _recentEvents.asStateFlow()

    // Clock ticks per second (usually 100 on Linux / Android)
    private val clockTicksPerSec: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(100L)
    } catch (_: Throwable) {
        100L
    }

    private val coreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Previous sampling state per thread: tid -> (totalJiffies, timestampMs)
    private val previousThreadJiffies = HashMap<Int, Long>()
    private var previousSampleTimeMs = 0L

    fun startProfiling(context: Context) {
        synchronized(subscriberLock) {
            activeSubscribers++
            if (activeSubscribers == 1 && sampleJob == null) {
                sampleJob = scope.launch {
                    val appContext = context.applicationContext
                    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

                    while (isActive) {
                        try {
                            val snap = sampleProcess(appContext, powerManager, batteryManager)
                            _snapshot.value = snap
                        } catch (t: Throwable) {
                            // Defensive: prevent sampler crash
                        }
                        delay(1000L)
                    }
                }
            }
        }
    }

    fun stopProfiling() {
        synchronized(subscriberLock) {
            if (activeSubscribers > 0) activeSubscribers--
            if (activeSubscribers == 0) {
                sampleJob?.cancel()
                sampleJob = null
                previousThreadJiffies.clear()
                previousSampleTimeMs = 0L
            }
        }
    }

    fun logEvent(tag: String, message: String) {
        val event = ProfilerLogEvent(tag = tag, message = message)
        eventQueue.addFirst(event)
        while (eventQueue.size > 80) {
            eventQueue.pollLast()
        }
        _recentEvents.value = eventQueue.toList()
    }

    private fun sampleProcess(
        context: Context,
        powerManager: PowerManager?,
        batteryManager: BatteryManager?
    ): ProcessSnapshot {
        val nowMs = SystemClock.elapsedRealtime()
        val timeDeltaSec = if (previousSampleTimeMs > 0L) {
            ((nowMs - previousSampleTimeMs) / 1000.0).coerceAtLeast(0.1)
        } else {
            1.0
        }
        previousSampleTimeMs = nowMs

        val tasksDir = File("/proc/self/task")
        val taskFiles = tasksDir.listFiles() ?: emptyArray()

        val currentThreadJiffies = HashMap<Int, Long>()
        val threadSamples = ArrayList<ThreadCpuSample>()
        val subsystemTotals = HashMap<Subsystem, Float>()

        for (taskFile in taskFiles) {
            val tid = taskFile.name.toIntOrNull() ?: continue
            val statFile = File(taskFile, "stat")
            if (!statFile.canRead()) continue

            try {
                val line = statFile.readText()
                val openParen = line.indexOf('(')
                val closeParen = line.lastIndexOf(')')
                if (openParen == -1 || closeParen == -1) continue

                val threadName = line.substring(openParen + 1, closeParen)
                val remaining = line.substring(closeParen + 2).split(" ")
                if (remaining.size < 13) continue

                val state = remaining[0]
                val utime = remaining[11].toLongOrNull() ?: 0L
                val stime = remaining[12].toLongOrNull() ?: 0L
                val totalJiffies = utime + stime

                currentThreadJiffies[tid] = totalJiffies

                val prevJiffies = previousThreadJiffies[tid]
                if (prevJiffies != null && totalJiffies >= prevJiffies) {
                    val deltaJiffies = totalJiffies - prevJiffies
                    // CPU % of a single core (0% - 100%)
                    val singleCorePercent = ((deltaJiffies.toDouble() / clockTicksPerSec) / timeDeltaSec * 100.0).toFloat()
                    if (singleCorePercent > 0.05f) {
                        val subsystem = Subsystem.classify(threadName)
                        threadSamples.add(
                            ThreadCpuSample(
                                tid = tid,
                                name = threadName,
                                state = state,
                                cpuPercent = singleCorePercent,
                                subsystem = subsystem
                            )
                        )
                        subsystemTotals[subsystem] = (subsystemTotals[subsystem] ?: 0f) + singleCorePercent
                    }
                }
            } catch (_: Throwable) {
                // Thread may have terminated during read
            }
        }

        previousThreadJiffies.clear()
        previousThreadJiffies.putAll(currentThreadJiffies)

        threadSamples.sortByDescending { it.cpuPercent }

        val totalProcessCpuPercent = threadSamples.sumOf { it.cpuPercent.toDouble() }.toFloat()

        // Hardware & Thermal Status
        val thermalStatusStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Cool (None)"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light Throttling"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency Throttling"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Thermal Shutdown"
                else -> "Normal"
            }
        } else {
            "Normal"
        }

        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
            try {
                powerManager.getThermalHeadroom(30)
            } catch (_: Throwable) {
                0.0f
            }
        } else {
            0.0f
        }

        val batteryDrainMa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let {
            abs(it) / 1000
        } ?: 0

        val batteryTempC = try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (_: Throwable) {
            0.0f
        }

        val runtime = Runtime.getRuntime()
        val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)

        return ProcessSnapshot(
            totalProcessCpuPercent = totalProcessCpuPercent,
            coreCount = coreCount,
            thermalStatus = thermalStatusStr,
            thermalHeadroom = thermalHeadroom,
            batteryDischargeCurrentMa = batteryDrainMa,
            batteryTemperatureC = batteryTempC,
            javaHeapMb = javaHeapMb,
            nativeHeapMb = nativeHeapMb,
            subsystemCpu = subsystemTotals,
            topThreads = threadSamples
        )
    }

    private fun createEmptySnapshot() = ProcessSnapshot(
        totalProcessCpuPercent = 0f,
        coreCount = coreCount,
        thermalStatus = "Sampling...",
        thermalHeadroom = 0f,
        batteryDischargeCurrentMa = 0,
        batteryTemperatureC = 0f,
        javaHeapMb = 0f,
        nativeHeapMb = 0f,
        subsystemCpu = emptyMap(),
        topThreads = emptyList()
    )

    /**
     * Exports a comprehensive snapshot markdown report to /storage/emulated/0/Download/
     */
    fun exportReport(context: Context): File? {
        val snap = snapshot.value
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "BitChord_Profiler_${dateFormat.format(Date())}.md"
        val downloadDir = File("/storage/emulated/0/Download")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val file = File(downloadDir, fileName)
        val sb = StringBuilder()
        sb.appendLine("# BitChord Process & Hardware Profile Report")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")

        sb.appendLine("## System & Hardware Telemetry")
        sb.appendLine("- Total Process CPU: ${"%.1f".format(snap.totalProcessCpuPercent)}% (across ${snap.coreCount} cores)")
        sb.appendLine("- Thermal Status: ${snap.thermalStatus}")
        sb.appendLine("- Thermal Headroom: ${"%.2f".format(snap.thermalHeadroom)} / 1.0 (>= 1.0 = hardware throttling)")
        sb.appendLine("- Battery Discharge Current: -${snap.batteryDischargeCurrentMa} mA")
        sb.appendLine("- Battery Temperature: ${"%.1f".format(snap.batteryTemperatureC)} °C")
        sb.appendLine("- Java Heap: ${"%.1f".format(snap.javaHeapMb)} MB | Native Heap: ${"%.1f".format(snap.nativeHeapMb)} MB\n")

        sb.appendLine("## CPU Utilization by Subsystem")
        for ((subsystem, cpu) in snap.subsystemCpu.entries.sortedByDescending { it.value }) {
            sb.appendLine("- **${subsystem.label}**: ${"%.2f".format(cpu)}% CPU")
        }
        sb.appendLine()

        sb.appendLine("## Top Active Threads")
        sb.appendLine("| TID | Thread Name | Subsystem | State | CPU % |")
        sb.appendLine("| :--- | :--- | :--- | :--- | :--- |")
        for (t in snap.topThreads.take(25)) {
            sb.appendLine("| ${t.tid} | `${t.name}` | ${t.subsystem.label} | ${t.state} | ${"%.2f".format(t.cpuPercent)}% |")
        }
        sb.appendLine()

        sb.appendLine("## Recent Activity Trace Events")
        for (e in recentEvents.value.take(40)) {
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.timestamp))
            sb.appendLine("- `[$timeStr]` **[${e.tag}]** ${e.message}")
        }

        return try {
            file.writeText(sb.toString())
            file
        } catch (_: Throwable) {
            null
        }
    }
}
