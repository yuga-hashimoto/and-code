package com.opencode.android.accessibility

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

class AccessibilityHttpServer(
    private val port: Int = DEFAULT_PORT,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var isRunning: Boolean = false
        private set

    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = socket
        isRunning = true
        executor.execute {
            while (isRunning) {
                runCatching {
                    val client = socket.accept()
                    handleClient(client.getInputStream(), client.getOutputStream())
                    client.close()
                }.onFailure { e ->
                    if (isRunning) Log.w(TAG, "Connection error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
    }

    private fun handleClient(
        input: java.io.InputStream,
        output: OutputStream,
    ) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore("?")

        val headers = mutableMapOf<String, String>()
        while (true) {
            val headerLine = reader.readLine() ?: break
            if (headerLine.isEmpty()) break
            val colonIndex = headerLine.indexOf(":")
            if (colonIndex > 0) {
                headers[headerLine.substring(0, colonIndex).trim().lowercase()] =
                    headerLine.substring(colonIndex + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) reader.readChars(contentLength) else ""
        val bodyJson = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() ?: JSONObject() else JSONObject()

        val (code, response) = route(method, path, bodyJson)
        val bytes = response.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $code ${statusText(code)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8),
        )
        output.write(bytes)
        output.flush()
    }

    private fun route(
        method: String,
        path: String,
        body: JSONObject,
    ): Pair<Int, String> {
        val service = OpenCodeAccessibilityService.instance
        if (service == null && path != "/health") {
            return 503 to """{"error":"Accessibility service not connected"}"""
        }
        return when (path) {
            "/health" -> {
                if (service != null) {
                    200 to """{"status":"connected"}"""
                } else {
                    503 to """{"status":"disconnected"}"""
                }
            }
            "/screen" -> 200 to (service?.captureViewTree() ?: """{"error":"unavailable"}""")
            "/tap" -> {
                val x = body.optDouble("x", -1.0).toFloat()
                val y = body.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) return 400 to """{"error":"Missing x or y"}"""
                val ok = service!!.performTap(x, y)
                200 to """{"success":$ok}"""
            }
            "/swipe" -> {
                val x1 = body.optDouble("x1", -1.0).toFloat()
                val y1 = body.optDouble("y1", -1.0).toFloat()
                val x2 = body.optDouble("x2", -1.0).toFloat()
                val y2 = body.optDouble("y2", -1.0).toFloat()
                val duration = body.optLong("duration", 300)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return 400 to """{"error":"Missing coordinates"}"""
                val ok = service!!.performSwipe(x1, y1, x2, y2, duration)
                200 to """{"success":$ok}"""
            }
            "/text" -> {
                val text = body.optString("text", "")
                if (text.isEmpty()) return 400 to """{"error":"Missing text"}"""
                val ok = service!!.typeText(text)
                200 to """{"success":$ok}"""
            }
            "/key" -> {
                val key = body.optString("key", "")
                val ok =
                    when (key) {
                        "back" -> service!!.performBack()
                        "home" -> service!!.performHome()
                        "recents" -> service!!.performRecents()
                        else -> false
                    }
                200 to """{"success":$ok,"key":"$key"}"""
            }
            else -> 404 to """{"error":"Not found"}"""
        }
    }

    private fun statusText(code: Int): String =
        when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Unknown"
        }

    private fun BufferedReader.readChars(count: Int): String {
        val chars = CharArray(count)
        var read = 0
        while (read < count) {
            val n = read(chars, read, count - read)
            if (n < 0) break
            read += n
        }
        return String(chars, 0, read)
    }

    companion object {
        const val DEFAULT_PORT = 4098
        private const val TAG = "AccessibilityHttpServer"
    }
}
