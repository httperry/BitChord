package com.music.bitchord.perf

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data models for the telemetry & performance overlay.
 * Uses org.json for zero-dependency serialization directly on Android.
 */
data class ActivityRecord(
    val id: Long,
    val name: String,
    val category: String,
    val durationMs: Double,
    val cpuTimeMs: Double = 0.0,
    val memoryDeltaBytes: Long = 0L,
    val threadName: String = Thread.currentThread().name,
    val timestampMs: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("durationMs", "%.2f".format(java.util.Locale.ROOT, durationMs).toDouble())
        put("cpuTimeMs", "%.2f".format(java.util.Locale.ROOT, cpuTimeMs).toDouble())
        put("memoryDeltaBytes", memoryDeltaBytes)
        put("threadName", threadName)
        put("timestampMs", timestampMs)
        if (metadata.isNotEmpty()) {
            put("metadata", JSONObject(metadata))
        }
    }
}

data class MainThreadStall(
    val id: Long,
    val durationMs: Double,
    val message: String,
    val stackTrace: List<String>,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun rootCause(): String {
        // Find the first stack trace line from com.music.bitchord or androidx.compose
        val appLine = stackTrace.firstOrNull { 
            it.contains("com.music.bitchord") && !it.contains("perf.") 
        } ?: stackTrace.firstOrNull { 
            it.contains("androidx.compose") || it.contains("android.view") 
        } ?: stackTrace.firstOrNull() ?: "unknown"
        return appLine.trim()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("durationMs", "%.2f".format(java.util.Locale.ROOT, durationMs).toDouble())
        put("message", message)
        put("rootCause", rootCause())
        put("timestampMs", timestampMs)
        put("stackTrace", JSONArray(stackTrace))
    }
}

data class FrameMetricRecord(
    val totalMs: Double,
    val layoutMeasureMs: Double,
    val drawMs: Double,
    val syncMs: Double,
    val commandIssueMs: Double,
    val isJank: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalMs", "%.2f".format(java.util.Locale.ROOT, totalMs).toDouble())
        put("layoutMeasureMs", "%.2f".format(java.util.Locale.ROOT, layoutMeasureMs).toDouble())
        put("drawMs", "%.2f".format(java.util.Locale.ROOT, drawMs).toDouble())
        put("syncMs", "%.2f".format(java.util.Locale.ROOT, syncMs).toDouble())
        put("commandIssueMs", "%.2f".format(java.util.Locale.ROOT, commandIssueMs).toDouble())
        put("isJank", isJank)
        put("timestampMs", timestampMs)
    }
}

data class FrameStats(
    val totalFrames: Long,
    val jankFrames: Long,
    val jankPercentage: Double,
    val avgFrameMs: Double,
    val maxFrameMs: Double,
    val p90FrameMs: Double,
    val p99FrameMs: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalFrames", totalFrames)
        put("jankFrames", jankFrames)
        put("jankPercentage", "%.2f%%".format(java.util.Locale.ROOT, jankPercentage))
        put("avgFrameMs", "%.2f".format(java.util.Locale.ROOT, avgFrameMs).toDouble())
        put("maxFrameMs", "%.2f".format(java.util.Locale.ROOT, maxFrameMs).toDouble())
        put("p90FrameMs", "%.2f".format(java.util.Locale.ROOT, p90FrameMs).toDouble())
        put("p99FrameMs", "%.2f".format(java.util.Locale.ROOT, p99FrameMs).toDouble())
    }
}

data class StallCluster(
    val rootCause: String,
    val count: Int,
    val totalDurationMs: Double,
    val maxDurationMs: Double,
    val sampleStackTrace: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rootCause", rootCause)
        put("count", count)
        put("totalDurationMs", "%.2f".format(java.util.Locale.ROOT, totalDurationMs).toDouble())
        put("maxDurationMs", "%.2f".format(java.util.Locale.ROOT, maxDurationMs).toDouble())
        put("sampleStackTrace", JSONArray(sampleStackTrace))
    }
}

data class SystemSnapshot(
    val uptimeSeconds: Long,
    val heapAllocatedMb: Double,
    val heapMaxMb: Double,
    val nativeHeapAllocatedMb: Double,
    val activeThreads: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uptimeSeconds", uptimeSeconds)
        put("heapAllocatedMb", "%.1f".format(java.util.Locale.ROOT, heapAllocatedMb).toDouble())
        put("heapMaxMb", "%.1f".format(java.util.Locale.ROOT, heapMaxMb).toDouble())
        put("nativeHeapAllocatedMb", "%.1f".format(java.util.Locale.ROOT, nativeHeapAllocatedMb).toDouble())
        put("activeThreads", activeThreads)
    }
}
