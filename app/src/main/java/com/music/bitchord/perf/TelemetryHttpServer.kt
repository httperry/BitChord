package com.music.bitchord.perf

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-dependency embedded HTTP server listening on 127.0.0.1:7456.
 * Provides real-time JSON endpoints for retrieving live app performance metrics,
 * frame times, and main thread stalls.
 */
class TelemetryHttpServer(
    private val port: Int = 7456,
    private val provider: TelemetryDataProvider,
) {
    interface TelemetryDataProvider {
        fun getSummaryJson(): JSONObject
        fun getStallsJson(): JSONArray
        fun getFramesJson(): JSONArray
        fun getActivitiesJson(): JSONArray
        fun getPowerJson(): JSONObject
        fun getCpuJson(): JSONObject
        fun exportDataJson(): JSONObject
        fun clearMetrics()
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workerThread: Thread? = null

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        workerThread = Thread({
            try {
                // Bind strictly to 127.0.0.1 (localhost only)
                val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i("TelemetryHttpServer", "BitChord Telemetry Server running on http://127.0.0.1:$port")

                while (isRunning.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread {
                        handleClient(client)
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w("TelemetryHttpServer", "Server socket error on port $port", e)
                }
            } finally {
                stop()
            }
        }, "perf-http-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workerThread?.interrupt()
        workerThread = null
    }

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            val responseJson = when (path) {
                "/", "/summary" -> provider.getSummaryJson().toString(2)
                "/stalls" -> provider.getStallsJson().toString(2)
                "/frames" -> provider.getFramesJson().toString(2)
                "/activities" -> provider.getActivitiesJson().toString(2)
                "/power" -> provider.getPowerJson().toString(2)
                "/cpu" -> provider.getCpuJson().toString(2)
                "/live" -> JSONObject().apply {
                    put("summary", provider.getSummaryJson())
                    put("recentStalls", provider.getStallsJson())
                    put("recentFrames", provider.getFramesJson())
                }.toString(2)
                "/export" -> provider.exportDataJson().toString(2)
                "/clear" -> {
                    provider.clearMetrics()
                    JSONObject().put("status", "cleared").toString()
                }
                else -> {
                    val notFound = JSONObject().put("error", "Not Found").put("path", path)
                    sendResponse(writer, 404, "Not Found", notFound.toString())
                    return
                }
            }

            sendResponse(writer, 200, "OK", responseJson)
        }
    }

    private fun sendResponse(writer: OutputStreamWriter, code: Int, status: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $code $status\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
        writer.flush()
    }
}
