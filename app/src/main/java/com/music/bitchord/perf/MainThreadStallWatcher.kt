package com.music.bitchord.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Monitors the Android Main (UI) thread for hangs, stalls, and frame drops.
 *
 * Combines two techniques:
 * 1. Looper message logging: Measures exact execution time of every dispatch.
 * 2. Asynchronous Watchdog thread: If a dispatch exceeds [stallThresholdMs],
 *    the watchdog captures the active call stack of the main thread while it is frozen!
 */
class MainThreadStallWatcher(
    private val stallThresholdMs: Long = 16L, // >= 16ms causes a dropped frame at 60Hz
    private val onStallRecorded: (MainThreadStall) -> Unit = {},
) {
    private val idCounter = AtomicLong(0)
    private val mainLooper = Looper.getMainLooper()
    private val mainThread = mainLooper.thread

    private val dispatchStartTime = AtomicLong(0L)
    private val activeMessage = AtomicReference<String>("")
    private val sampledStack = AtomicReference<List<String>>(emptyList())
    private val isMonitoring = AtomicBoolean(false)

    // Ring buffer of recent stalls
    val recentStalls = ConcurrentLinkedDeque<MainThreadStall>()
    private val maxRecentStalls = 200

    private var watchdogThread: Thread? = null

    fun start() {
        if (!isMonitoring.compareAndSet(false, true)) return

        // 1. Hook into Main Looper message dispatcher
        mainLooper.setMessageLogging(object : Printer {
            override fun println(x: String) {
                if (x.startsWith(">>>>>")) {
                    dispatchStartTime.set(SystemClock.uptimeMillis())
                    activeMessage.set(x)
                    sampledStack.set(emptyList())
                } else if (x.startsWith("<<<<<")) {
                    val start = dispatchStartTime.getAndSet(0L)
                    if (start > 0) {
                        val duration = (SystemClock.uptimeMillis() - start).toDouble()
                        if (duration >= stallThresholdMs) {
                            val stack = sampledStack.get().takeIf { it.isNotEmpty() }
                                ?: mainThread.stackTrace.map { it.toString() }
                            val stall = MainThreadStall(
                                id = idCounter.incrementAndGet(),
                                durationMs = duration,
                                message = activeMessage.get(),
                                stackTrace = stack,
                                timestampMs = System.currentTimeMillis(),
                            )
                            recordStall(stall)
                        }
                    }
                }
            }
        })

        // 2. Watchdog sampler: snapshots stack trace while freeze is ongoing
        watchdogThread = Thread({
            while (isMonitoring.get()) {
                val start = dispatchStartTime.get()
                if (start > 0) {
                    val elapsed = SystemClock.uptimeMillis() - start
                    if (elapsed >= stallThresholdMs && sampledStack.get().isEmpty()) {
                        val trace = mainThread.stackTrace.map { it.toString() }
                        if (trace.isNotEmpty()) {
                            sampledStack.set(trace)
                        }
                    }
                }
                try {
                    Thread.sleep(8) // Check every 8ms
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "perf-main-thread-watchdog").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        if (!isMonitoring.compareAndSet(true, false)) return
        mainLooper.setMessageLogging(null)
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun recordStall(stall: MainThreadStall) {
        recentStalls.addFirst(stall)
        while (recentStalls.size > maxRecentStalls) {
            recentStalls.pollLast()
        }
        onStallRecorded(stall)
    }

    /**
     * Groups stalls by root cause (the first app/compose line in stack trace).
     */
    fun getClusters(): List<StallCluster> {
        return recentStalls
            .groupBy { it.rootCause() }
            .map { (cause, list) ->
                StallCluster(
                    rootCause = cause,
                    count = list.size,
                    totalDurationMs = list.sumOf { it.durationMs },
                    maxDurationMs = list.maxOfOrNull { it.durationMs } ?: 0.0,
                    sampleStackTrace = list.firstOrNull()?.stackTrace ?: emptyList(),
                )
            }
            .sortedByDescending { it.totalDurationMs }
    }

    fun clear() {
        recentStalls.clear()
    }
}
