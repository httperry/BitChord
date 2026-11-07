package com.music.bitchord.perf

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks Android HardwareRenderer frame timings and jank percentage directly from the OS.
 * Uses Window.OnFrameMetricsAvailableListener (API 24+) to measure exact layout, draw,
 * and GPU sync times per frame.
 */
class FrameMetricsCollector(
    private val targetFrameDurationMs: Double = 16.66, // 60fps target; adjust to 8.33 on 120Hz
    private val onJankFrame: (FrameMetricRecord) -> Unit = {},
) : Application.ActivityLifecycleCallbacks {

    private val totalFramesCount = AtomicLong(0)
    private val jankFramesCount = AtomicLong(0)

    val recentFrames = ConcurrentLinkedDeque<FrameMetricRecord>()
    private val maxRecentFrames = 500

    private var metricsThread: HandlerThread? = null
    private var metricsHandler: Handler? = null

    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (metricsThread != null) return

        val thread = HandlerThread("perf-frame-metrics").apply { start() }
        metricsThread = thread
        val handler = Handler(thread.looper)
        metricsHandler = handler

        frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val layoutNs = frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            val drawNs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
            val syncNs = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
            val commandNs = frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)

            val totalMs = totalNs / 1_000_000.0
            val layoutMs = layoutNs / 1_000_000.0
            val drawMs = drawNs / 1_000_000.0
            val syncMs = syncNs / 1_000_000.0
            val commandMs = commandNs / 1_000_000.0
            val isJank = totalMs > targetFrameDurationMs

            totalFramesCount.incrementAndGet()
            if (isJank) {
                jankFramesCount.incrementAndGet()
            }

            val record = FrameMetricRecord(
                totalMs = totalMs,
                layoutMeasureMs = layoutMs,
                drawMs = drawMs,
                syncMs = syncMs,
                commandIssueMs = commandMs,
                isJank = isJank,
                timestampMs = System.currentTimeMillis(),
            )

            recentFrames.addFirst(record)
            while (recentFrames.size > maxRecentFrames) {
                recentFrames.pollLast()
            }

            if (isJank) {
                onJankFrame(record)
            }
        }
    }

    fun stop() {
        metricsThread?.quitSafely()
        metricsThread = null
        metricsHandler = null
        frameMetricsListener = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val listener = frameMetricsListener
            val handler = metricsHandler
            if (listener != null && handler != null) {
                runCatching {
                    activity.window.addOnFrameMetricsAvailableListener(listener, handler)
                }
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val listener = frameMetricsListener
            if (listener != null) {
                runCatching {
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    fun getStats(): FrameStats {
        val total = totalFramesCount.get()
        val jank = jankFramesCount.get()
        val percentage = if (total > 0) (jank.toDouble() / total) * 100.0 else 0.0

        val snapshot = recentFrames.toList()
        if (snapshot.isEmpty()) {
            return FrameStats(total, jank, percentage, 0.0, 0.0, 0.0, 0.0)
        }

        val sorted = snapshot.map { it.totalMs }.sorted()
        val avg = sorted.average()
        val max = sorted.last()
        val p90 = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)]
        val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]

        return FrameStats(
            totalFrames = total,
            jankFrames = jank,
            jankPercentage = percentage,
            avgFrameMs = avg,
            maxFrameMs = max,
            p90FrameMs = p90,
            p99FrameMs = p99,
        )
    }

    fun clear() {
        totalFramesCount.set(0)
        jankFramesCount.set(0)
        recentFrames.clear()
    }
}
