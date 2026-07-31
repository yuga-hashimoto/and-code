package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatErrorPresentationTest {
    @Test
    fun `classifies missing local runtime as setup required`() {
        assertEquals(
            ChatErrorKind.RUNTIME_NOT_READY,
            classifyChatError("Android local OpenCode runtime is not installed"),
        )
    }

    @Test
    fun `classifies unconfigured connection as setup required`() {
        assertEquals(
            ChatErrorKind.RUNTIME_NOT_READY,
            classifyChatError("OpenCode connection is not configured"),
        )
    }

    @Test
    fun `classifies timeouts as transient connection failures`() {
        assertEquals(
            ChatErrorKind.TRANSIENT_CONNECTION,
            classifyChatError("Request timed out"),
        )
    }

    @Test
    fun `returns null when no visible error exists`() {
        assertNull(classifyChatError(null as String?))
        assertNull(classifyChatError("   "))
    }

    @Test
    fun `classifies 401 as auth error via exception`() {
        assertEquals(
            ChatErrorKind.AUTH_ERROR,
            classifyChatError(OpenCodeApiException(401, "OpenCode request failed (HTTP 401)")),
        )
    }

    @Test
    fun `classifies 403 as auth error via exception`() {
        assertEquals(
            ChatErrorKind.AUTH_ERROR,
            classifyChatError(OpenCodeApiException(403, "OpenCode request failed (HTTP 403)")),
        )
    }

    @Test
    fun `classifies 404 as not found via exception`() {
        assertEquals(
            ChatErrorKind.NOT_FOUND,
            classifyChatError(OpenCodeApiException(404, "OpenCode request failed (HTTP 404)")),
        )
    }

    @Test
    fun `classifies 429 as rate limited via exception`() {
        assertEquals(
            ChatErrorKind.RATE_LIMITED,
            classifyChatError(OpenCodeApiException(429, "OpenCode request failed (HTTP 429)")),
        )
    }

    @Test
    fun `classifies 500 as server error via exception`() {
        assertEquals(
            ChatErrorKind.SERVER_ERROR,
            classifyChatError(OpenCodeApiException(500, "OpenCode request failed (HTTP 500)")),
        )
    }

    @Test
    fun `classifies 502 as server error via exception`() {
        assertEquals(
            ChatErrorKind.SERVER_ERROR,
            classifyChatError(OpenCodeApiException(502, "OpenCode request failed (HTTP 502)")),
        )
    }

    @Test
    fun `classifies HTTP codes in message string without exception`() {
        assertEquals(
            ChatErrorKind.AUTH_ERROR,
            classifyChatError("OpenCode request failed (HTTP 401)"),
        )
        assertEquals(
            ChatErrorKind.RATE_LIMITED,
            classifyChatError("OpenCode request failed (HTTP 429): too many requests"),
        )
        assertEquals(
            ChatErrorKind.SERVER_ERROR,
            classifyChatError("OpenCode request failed (HTTP 503): service unavailable"),
        )
    }

    @Test
    fun `classifies connection reset as transient`() {
        assertEquals(
            ChatErrorKind.TRANSIENT_CONNECTION,
            classifyChatError("connection reset by peer"),
        )
    }

    @Test
    fun `classifies connection refused as transient`() {
        assertEquals(
            ChatErrorKind.TRANSIENT_CONNECTION,
            classifyChatError("Failed to connect to /127.0.0.1:5040: Connection refused"),
        )
    }

    @Test
    fun `classifies non-HTTP throwable by message`() {
        assertEquals(
            ChatErrorKind.TRANSIENT_CONNECTION,
            classifyChatError(java.net.SocketTimeoutException("connect timed out")),
        )
    }

    @Test
    fun `returns null for null throwable`() {
        assertNull(classifyChatError(null as Throwable?))
    }
}
