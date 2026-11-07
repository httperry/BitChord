package com.music.bitchord.perf

import android.app.Application
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main entry point and coordinator for the BitChord Telemetry & Performance Overlay.
 *
 * Install with a single call in BitChordApplication.onCreate:
 *   AppPerformanceBank.install(this)
 *
 * To remove completely: delete the perf/ package and remove that single line.
 */
object AppPerformanceBank : TelemetryHttpServer.TelemetryDataProvider {

    private const val TAG = "AppPerformanceBank"
    private const val HTTP_PORT = 7456

    private val isInstalled = AtomicBoolean(false)
    private val activityIdCounter = AtomicLong(0)
    private var appStartTimeMs: Long = System.currentTimeMillis()

    // ── Components ────────────────────────────────────────────────────────────
    val stallWatcher = MainThreadStallWatcher(stallThresholdMs = 16L) { stall ->
        appendStallToDisk(stall)
    }

    val frameCollector = FrameMetricsCollector(targetFrameDurationMs = 16.66)
    var powerMonitor: PowerAndCpuMonitor? = null

    private val httpServer = TelemetryHttpServer(HTTP_PORT, this)

    // ── In-Memory Ring Buffers ────────────────────────────────────────────────
    val recentActivities = ConcurrentLinkedDeque<ActivityRecord>()
    private const val MAX_RECENT_ACTIVITIES = 1000

    private val activityAggregates = ConcurrentHashMap<String, ActivityAggregate>()

    private var storageDir: File? = null
    private var diskFlusherThread: Thread? = null

    class ActivityAggregate(
        val name: String,
        val category: String,
        var count: Long = 0,
        var totalDurationMs: Double = 0.0,
        var maxDurationMs: Double = 0.0,
        var minDurationMs: Double = Double.MAX_VALUE,
        var totalCpuMs: Double = 0.0,
        var totalMemBytes: Long = 0,
    ) {
        fun update(record: ActivityRecord) {
            count++
            totalDurationMs += record.durationMs
            totalCpuMs += record.cpuTimeMs
            totalMemBytes += record.memoryDeltaBytes
            if (record.durationMs > maxDurationMs) maxDurationMs = record.durationMs
            if (record.durationMs < minDurationMs) minDurationMs = record.durationMs
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("category", category)
            put("count", count)
            put("totalMs", "%.2f".format(Locale.ROOT, totalDurationMs).toDouble())
            put("avgMs", "%.2f".format(Locale.ROOT, if (count > 0) totalDurationMs / count else 0.0).toDouble())
            put("minMs", "%.2f".format(Locale.ROOT, if (minDurationMs != Double.MAX_VALUE) minDurationMs else 0.0).toDouble())
            put("maxMs", "%.2f".format(Locale.ROOT, maxDurationMs).toDouble())
            put("totalCpuMs", "%.2f".format(Locale.ROOT, totalCpuMs).toDouble())
            put("totalMemBytes", totalMemBytes)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun install(app: Application) {
        if (!isInstalled.compareAndSet(false, true)) return
        appStartTimeMs = System.currentTimeMillis()

        // 1. Resolve storage directory: prefer /storage/emulated/0/Documents/BitChord/telemetry/
        val sharedDocs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BitChord/telemetry")
        storageDir = if (sharedDocs.exists() || sharedDocs.mkdirs()) {
            sharedDocs
        } else {
            app.getExternalFilesDir("telemetry") ?: File(app.filesDir, "telemetry")
        }.also { it.mkdirs() }

        Log.i(TAG, "Telemetry Bank initialized at: ${storageDir?.absolutePath}")

        // 2. Initialize Power and CPU Monitor
        powerMonitor = PowerAndCpuMonitor(app)

        // 3. Start Main Thread Stall Watcher
        stallWatcher.start()

        // 4. Register Frame Metrics
        frameCollector.start()
        app.registerActivityLifecycleCallbacks(frameCollector)

        // 5. Start Embedded HTTP Server on 127.0.0.1:7456
        httpServer.start()

        // 6. Start Background Disk Flusher
        startDiskFlusher()

        record("App.startup", "STARTUP", 0.0, metadata = mapOf("dir" to (storageDir?.absolutePath ?: "")))
    }

    // ── Measurement API ───────────────────────────────────────────────────────

    /**
     * Measures synchronous block execution.
     */
    inline fun <T> measure(name: String, category: String, metadata: Map<String, String> = emptyMap(), block: () -> T): T {
        val t0 = System.nanoTime()
        val cpu0 = SystemClock.currentThreadTimeMillis()
        val mem0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        return try {
            block()
        } finally {
            val t1 = System.nanoTime()
            val cpu1 = SystemClock.currentThreadTimeMillis()
            val mem1 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val durationMs = (t1 - t0) / 1_000_000.0
            val cpuMs = (cpu1 - cpu0).toDouble()
            val memDelta = (mem1 - mem0).coerceAtLeast(0L)
            record(name, category, durationMs, cpuMs, memDelta, metadata)
        }
    }

    fun record(
        name: String,
        category: String,
        durationMs: Double,
        cpuTimeMs: Double = 0.0,
        memoryDeltaBytes: Long = 0L,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val record = ActivityRecord(
            id = activityIdCounter.incrementAndGet(),
            name = name,
            category = category,
            durationMs = durationMs,
            cpuTimeMs = cpuTimeMs,
            memoryDeltaBytes = memoryDeltaBytes,
            threadName = Thread.currentThread().name,
            timestampMs = System.currentTimeMillis(),
            metadata = metadata,
        )

        recentActivities.addFirst(record)
        while (recentActivities.size > MAX_RECENT_ACTIVITIES) {
            recentActivities.pollLast()
        }

        // Aggregate
        activityAggregates.compute(name) { _, existing ->
            (existing ?: ActivityAggregate(name, category)).apply { update(record) }
        }
    }

    // ── TelemetryDataProvider Implementations ─────────────────────────────────

    override fun getSummaryJson(): JSONObject = JSONObject().apply {
        put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).format(Date()))
        put("system", getSystemSnapshot().toJson())
        put("frameStats", frameCollector.getStats().toJson())

        // Power & Battery
        powerMonitor?.let { pm ->
            put("power", pm.samplePower().toJson())
            put("cpu", pm.sampleCpu().toJson())
        }

        // Top Main Thread Stalls
        val clusters = stallWatcher.getClusters()
        val stallsArray = JSONArray()
        clusters.take(10).forEach { stallsArray.put(it.toJson()) }
        put("topMainThreadStalls", stallsArray)

        // Top Activities by Total Duration
        val topActivities = activityAggregates.values.sortedByDescending { it.totalDurationMs }
        val actArray = JSONArray()
        topActivities.take(15).forEach { actArray.put(it.toJson()) }
        put("topActivitiesByTotalTime", actArray)

        // Subsystem Breakdown
        val categoryTotals = activityAggregates.values.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.totalDurationMs } }
        val totalMs = categoryTotals.values.sum().coerceAtLeast(1.0)
        val catObj = JSONObject()
        categoryTotals.forEach { (cat, sum) ->
            catObj.put(cat, "%.1f%% (%.1f ms)".format(Locale.ROOT, (sum / totalMs) * 100.0, sum))
        }
        put("subsystemBreakdown", catObj)
    }

    override fun getPowerJson(): JSONObject = powerMonitor?.samplePower()?.toJson() ?: JSONObject()

    override fun getCpuJson(): JSONObject = powerMonitor?.sampleCpu()?.toJson() ?: JSONObject()

    override fun getStallsJson(): JSONArray = JSONArray().apply {
        stallWatcher.recentStalls.forEach { put(it.toJson()) }
    }

    override fun getFramesJson(): JSONArray = JSONArray().apply {
        frameCollector.recentFrames.take(100).forEach { put(it.toJson()) }
    }

    override fun getActivitiesJson(): JSONArray = JSONArray().apply {
        recentActivities.take(200).forEach { put(it.toJson()) }
    }

    override fun exportDataJson(): JSONObject {
        val summary = getSummaryJson()
        flushSummaryToDisk(summary)
        return summary
    }

    override fun clearMetrics() {
        stallWatcher.clear()
        frameCollector.clear()
        recentActivities.clear()
        activityAggregates.clear()
    }

    // ── System Snapshots & Disk Flusher ───────────────────────────────────────

    fun getSystemSnapshot(): SystemSnapshot {
        val runtime = Runtime.getRuntime()
        val uptime = (System.currentTimeMillis() - appStartTimeMs) / 1000
        val heapAllocated = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val heapMax = runtime.maxMemory() / (1024.0 * 1024.0)
        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

        return SystemSnapshot(
            uptimeSeconds = uptime,
            heapAllocatedMb = heapAllocated,
            heapMaxMb = heapMax,
            nativeHeapAllocatedMb = nativeHeapAllocated,
            activeThreads = Thread.activeCount(),
        )
    }

    private fun startDiskFlusher() {
        diskFlusherThread = Thread({
            while (isInstalled.get()) {
                try {
                    Thread.sleep(15_000) // Flush summary every 15s
                    flushSummaryToDisk(getSummaryJson())
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "perf-disk-flusher").apply {
            isDaemon = true
            start()
        }
    }

    private fun flushSummaryToDisk(summary: JSONObject) {
        val dir = storageDir ?: return
        runCatching {
            val file = File(dir, "app_resource_summary.json")
            FileWriter(file, false).use { it.write(summary.toString(2)) }
        }
    }

    private fun appendStallToDisk(stall: MainThreadStall) {
        val dir = storageDir ?: return
        runCatching {
            val file = File(dir, "telemetry_stalls.jsonl")
            FileWriter(file, true).use {
                it.write(stall.toJson().toString() + "\n")
            }
        }
    }
}
