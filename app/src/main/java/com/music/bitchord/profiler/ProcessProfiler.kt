package com.music.bitchord.profiler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
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
    RENDER_GPU("RenderThread & GPU", Color(0xFFFF9800)),
    AUDIO_ENGINE("Audio Engine & Automix", Color(0xFF2196F3)),
    NETWORK("Network & Streaming", Color(0xFF9C27B0)),
    COROUTINES("Background Coroutines", Color(0xFF00BCD4)),
    DATABASE("Database & Storage", Color(0xFF795548)),
    OTHER("System & JVM Runtime", Color(0xFF9E9E9E));

    companion object {
        fun classify(tid: Int, threadName: String): Subsystem {
            val myPid = Process.myPid()
            val name = threadName.lowercase()
            return when {
                tid == myPid || name.contains("bitchord") || name == "main" -> UI_MAIN
                name.contains("renderthread") || name.contains("hwui") || name.startsWith("mali-") || 
                    name.contains("ged-") || name.contains("gpu") -> RENDER_GPU
                name.contains("exoplayer") || name.contains("audio") || name.contains("mediacodec") || 
                    name.contains("superpowered") || name.contains("automix") || name.contains("decoder") || 
                    name.contains("track") -> AUDIO_ENGINE
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
    val mainThreadCpuPercent: Float,
    val renderThreadCpuPercent: Float,
    val audioCpuPercent: Float,
    val coreCount: Int,
    val thermalStatus: String,
    val thermalHeadroom: Float,
    val batteryDischargeCurrentMa: Int,
    val batteryTemperatureC: Float,
    val javaHeapMb: Float,
    val nativeHeapMb: Float,
    val subsystemCpu: Map<Subsystem, Float>,
    val topThreads: List<ThreadCpuSample>,
    // Hardware FrameMetrics
    val avgFrameDurationMs: Float,
    val avgGpuDurationMs: Float,
    val avgDrawDurationMs: Float,
    val jankPercentage: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Historical time-series sample point for trend graphing.
 */
data class HistoryPoint(
    val timestamp: Long,
    val totalCpu: Float,
    val mainThreadCpu: Float,
    val renderThreadCpu: Float,
    val audioCpu: Float,
    val gpuDurationMs: Float,
    val batteryMa: Int,
    val tempC: Float
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
 * In-app high-precision Linux userspace thread profiler, hardware GPU frame monitor,
 * and continuous diagnostic telemetry engine.
 */
object ProcessProfiler {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sampleJob: kotlinx.coroutines.Job? = null

    private var activeSubscribers = 0
    private val subscriberLock = Any()

    private val _snapshot = MutableStateFlow(createEmptySnapshot())
    val snapshot: StateFlow<ProcessSnapshot> = _snapshot.asStateFlow()

    // 60-second rolling history buffer
    private val historyBuffer = ArrayList<HistoryPoint>(65)
    private val _history = MutableStateFlow<List<HistoryPoint>>(emptyList())
    val history: StateFlow<List<HistoryPoint>> = _history.asStateFlow()

    // Activity trace event queue
    private val eventQueue = ConcurrentLinkedDeque<ProfilerLogEvent>()
    private val _recentEvents = MutableStateFlow<List<ProfilerLogEvent>>(emptyList())
    val recentEvents: StateFlow<List<ProfilerLogEvent>> = _recentEvents.asStateFlow()

    private val clockTicksPerSec: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(100L)
    } catch (_: Throwable) {
        100L
    }

    private val coreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private val previousThreadJiffies = HashMap<Int, Long>()
    private var previousSampleTimeMs = 0L

    // Hardware FrameMetrics telemetry
    private var activeWindow: Window? = null
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private val frameMetricsThread = android.os.HandlerThread("profiler-framemetrics").apply { start() }
    private val frameMetricsHandler = Handler(frameMetricsThread.looper)
    private val frameMetricsLock = Any()

    @Volatile private var frameAccTotalMs = 0.0
    @Volatile private var frameAccGpuMs = 0.0
    @Volatile private var frameAccDrawMs = 0.0
    @Volatile private var frameCountInWindow = 0
    @Volatile private var jankCountInWindow = 0

    init {
        logEvent("System", "ProcessProfiler engine initialized on ${coreCount}-core ARM64")
    }

    fun attachWindow(window: Window) {
        if (activeWindow == window) return
        detachWindow()
        activeWindow = window

        frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val drawNs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
            val gpuNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                frameMetrics.getMetric(FrameMetrics.GPU_DURATION)
            } else 0L

            val totalMs = totalNs / 1_000_000.0
            val drawMs = drawNs / 1_000_000.0
            val gpuMs = gpuNs / 1_000_000.0

            synchronized(frameMetricsLock) {
                frameAccTotalMs += totalMs
                frameAccGpuMs += gpuMs
                frameAccDrawMs += drawMs
                frameCountInWindow++

                if (totalMs > 16.6) { // Drop below 60fps
                    jankCountInWindow++
                }

                if (frameCountInWindow > 1200) {
                    frameAccTotalMs /= 2
                    frameAccGpuMs /= 2
                    frameAccDrawMs /= 2
                    frameCountInWindow /= 2
                    jankCountInWindow /= 2
                }
            }

            if (totalMs > 35.0) {
                logEvent(
                    "Jank",
                    "Frame spike: %.1fms (GPU: %.1fms, Draw: %.1fms)".format(totalMs, gpuMs, drawMs)
                )
            }
        }

        try {
            window.addOnFrameMetricsAvailableListener(frameMetricsListener!!, frameMetricsHandler)
            logEvent("HWUI", "Attached hardware FrameMetrics listener (Mali GPU duration enabled)")
        } catch (t: Throwable) {
            logEvent("HWUI", "Failed to attach FrameMetrics: ${t.message}")
        }
    }

    fun detachWindow() {
        val w = activeWindow
        val l = frameMetricsListener
        if (w != null && l != null) {
            try {
                w.removeOnFrameMetricsAvailableListener(l)
            } catch (_: Throwable) {}
        }
        activeWindow = null
        frameMetricsListener = null
    }

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

                            // Append to rolling history
                            synchronized(historyBuffer) {
                                historyBuffer.add(
                                    HistoryPoint(
                                        timestamp = snap.timestamp,
                                        totalCpu = snap.totalProcessCpuPercent,
                                        mainThreadCpu = snap.mainThreadCpuPercent,
                                        renderThreadCpu = snap.renderThreadCpuPercent,
                                        audioCpu = snap.audioCpuPercent,
                                        gpuDurationMs = snap.avgGpuDurationMs,
                                        batteryMa = snap.batteryDischargeCurrentMa,
                                        tempC = snap.batteryTemperatureC
                                    )
                                )
                                while (historyBuffer.size > 60) {
                                    historyBuffer.removeAt(0)
                                }
                                _history.value = ArrayList(historyBuffer)
                            }
                        } catch (_: Throwable) {}
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
        while (eventQueue.size > 120) {
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

        val myPid = Process.myPid()
        var mainThreadCpu = 0f
        var renderThreadCpu = 0f
        var audioCpu = 0f

        for (taskFile in taskFiles) {
            val tid = taskFile.name.toIntOrNull() ?: continue
            val statFile = File(taskFile, "stat")
            if (!statFile.canRead()) continue

            try {
                val line = statFile.readText()
                val openParen = line.indexOf('(')
                val closeParen = line.lastIndexOf(')')
                if (openParen == -1 || closeParen == -1) continue

                val rawThreadName = line.substring(openParen + 1, closeParen)
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
                    if (singleCorePercent > 0.05f || tid == myPid) {
                        val subsystem = Subsystem.classify(tid, rawThreadName)
                        val displayName = if (tid == myPid) {
                            "Main UI Thread ($rawThreadName)"
                        } else {
                            rawThreadName
                        }

                        threadSamples.add(
                            ThreadCpuSample(
                                tid = tid,
                                name = displayName,
                                state = state,
                                cpuPercent = singleCorePercent,
                                subsystem = subsystem
                            )
                        )
                        subsystemTotals[subsystem] = (subsystemTotals[subsystem] ?: 0f) + singleCorePercent

                        if (tid == myPid) mainThreadCpu = singleCorePercent
                        if (rawThreadName.contains("renderthread", ignoreCase = true)) renderThreadCpu = singleCorePercent
                        if (subsystem == Subsystem.AUDIO_ENGINE) audioCpu += singleCorePercent
                    }
                }
            } catch (_: Throwable) {}
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

        // Consume frame metrics accumulator under lock
        val avgTotal: Float
        val avgGpu: Float
        val avgDraw: Float
        val jankPct: Float
        synchronized(frameMetricsLock) {
            val frames = frameCountInWindow.coerceAtLeast(1)
            avgTotal = (frameAccTotalMs / frames).toFloat()
            avgGpu = (frameAccGpuMs / frames).toFloat()
            avgDraw = (frameAccDrawMs / frames).toFloat()
            jankPct = (jankCountInWindow.toFloat() / frames.toFloat()) * 100f

            // Reset frame accumulators for next window
            frameAccTotalMs = 0.0
            frameAccGpuMs = 0.0
            frameAccDrawMs = 0.0
            frameCountInWindow = 0
            jankCountInWindow = 0
        }

        return ProcessSnapshot(
            totalProcessCpuPercent = totalProcessCpuPercent,
            mainThreadCpuPercent = mainThreadCpu,
            renderThreadCpuPercent = renderThreadCpu,
            audioCpuPercent = audioCpu,
            coreCount = coreCount,
            thermalStatus = thermalStatusStr,
            thermalHeadroom = thermalHeadroom,
            batteryDischargeCurrentMa = batteryDrainMa,
            batteryTemperatureC = batteryTempC,
            javaHeapMb = javaHeapMb,
            nativeHeapMb = nativeHeapMb,
            subsystemCpu = subsystemTotals,
            topThreads = threadSamples,
            avgFrameDurationMs = avgTotal,
            avgGpuDurationMs = avgGpu,
            avgDrawDurationMs = avgDraw,
            jankPercentage = jankPct
        )
    }

    private fun createEmptySnapshot() = ProcessSnapshot(
        totalProcessCpuPercent = 0f,
        mainThreadCpuPercent = 0f,
        renderThreadCpuPercent = 0f,
        audioCpuPercent = 0f,
        coreCount = coreCount,
        thermalStatus = "Sampling...",
        thermalHeadroom = 0f,
        batteryDischargeCurrentMa = 0,
        batteryTemperatureC = 0f,
        javaHeapMb = 0f,
        nativeHeapMb = 0f,
        subsystemCpu = emptyMap(),
        topThreads = emptyList(),
        avgFrameDurationMs = 0f,
        avgGpuDurationMs = 0f,
        avgDrawDurationMs = 0f,
        jankPercentage = 0f
    )

    /**
     * Exports a comprehensive multi-point diagnostic markdown report to /storage/emulated/0/Download/
     */
    fun exportReport(context: Context): File? {
        val snap = snapshot.value
        val historyList = history.value
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "BitChord_DeepProfile_${dateFormat.format(Date())}.md"
        val downloadDir = File("/storage/emulated/0/Download")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val file = File(downloadDir, fileName)
        val sb = StringBuilder()
        sb.appendLine("# BitChord In-Depth Process & Hardware Diagnostics Report")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")

        sb.appendLine("## 1. System & Hardware Telemetry")
        sb.appendLine("- **Process Total CPU:** ${"%.1f".format(snap.totalProcessCpuPercent)}% (across ${snap.coreCount} physical cores)")
        sb.appendLine("- **Thermal State:** ${snap.thermalStatus}")
        sb.appendLine("- **Thermal Headroom:** ${"%.2f".format(snap.thermalHeadroom)} / 1.0 (>= 1.0 means active kernel throttling)")
        sb.appendLine("- **Battery Discharge Current:** -${snap.batteryDischargeCurrentMa} mA")
        sb.appendLine("- **Battery Temperature:** ${"%.1f".format(snap.batteryTemperatureC)} °C")
        sb.appendLine("- **Memory:** Java Heap: ${"%.1f".format(snap.javaHeapMb)} MB | Native Heap: ${"%.1f".format(snap.nativeHeapMb)} MB\n")

        sb.appendLine("## 2. Hardware FrameMetrics & GPU Duration (Direct OS Compositor)")
        sb.appendLine("- **Mali GPU Work Time:** ${"%.2f".format(snap.avgGpuDurationMs)} ms/frame")
        sb.appendLine("- **Main Thread Draw Pass:** ${"%.2f".format(snap.avgDrawDurationMs)} ms/frame")
        sb.appendLine("- **Total Frame Duration:** ${"%.2f".format(snap.avgFrameDurationMs)} ms/frame")
        sb.appendLine("- **Janky Frame Ratio:** ${"%.1f".format(snap.jankPercentage)}% dropped\n")

        sb.appendLine("## 3. Subsystem CPU Breakdown")
        for ((subsystem, cpu) in snap.subsystemCpu.entries.sortedByDescending { it.value }) {
            sb.appendLine("- **${subsystem.label}**: ${"%.2f".format(cpu)}% CPU")
        }
        sb.appendLine()

        sb.appendLine("## 4. Top Active Threads (with True Identity)")
        sb.appendLine("| TID | Thread Name | Subsystem | State | CPU % |")
        sb.appendLine("| :--- | :--- | :--- | :--- | :--- |")
        for (t in snap.topThreads.take(30)) {
            sb.appendLine("| ${t.tid} | `${t.name}` | ${t.subsystem.label} | ${t.state} | ${"%.2f".format(t.cpuPercent)}% |")
        }
        sb.appendLine()

        if (historyList.isNotEmpty()) {
            val avgCpu = historyList.map { it.totalCpu }.average()
            val peakCpu = historyList.maxOfOrNull { it.totalCpu } ?: 0f
            val minCpu = historyList.minOfOrNull { it.totalCpu } ?: 0f
            sb.appendLine("## 5. Session Timeline History (Last ${historyList.size} Samples)")
            sb.appendLine("- Peak CPU: ${"%.1f".format(peakCpu)}% | Average CPU: ${"%.1f".format(avgCpu)}% | Min CPU: ${"%.1f".format(minCpu)}%")
            sb.appendLine("| Timestamp | Total CPU | UI Main | RenderThread | Audio Engine | GPU Duration | Battery mA |")
            sb.appendLine("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
            for (p in historyList.takeLast(20)) {
                val tStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(p.timestamp))
                sb.appendLine("| $tStr | ${"%.1f".format(p.totalCpu)}% | ${"%.1f".format(p.mainThreadCpu)}% | ${"%.1f".format(p.renderThreadCpu)}% | ${"%.1f".format(p.audioCpu)}% | ${"%.2f".format(p.gpuDurationMs)}ms | -${p.batteryMa}mA |")
            }
            sb.appendLine()
        }

        sb.appendLine("## 6. Continuous Activity Trace Log (${recentEvents.value.size} Events)")
        for (e in recentEvents.value.take(80)) {
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
