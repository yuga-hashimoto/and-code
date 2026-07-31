package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeApiException

internal enum class ChatErrorKind {
    RUNTIME_NOT_READY,
    TRANSIENT_CONNECTION,
    AUTH_ERROR,
    RATE_LIMITED,
    SERVER_ERROR,
    NOT_FOUND,
    GENERIC,
}

internal fun classifyChatError(throwable: Throwable?): ChatErrorKind? {
    if (throwable == null) return null
    if (throwable is OpenCodeApiException) {
        return classifyByStatusCode(throwable.statusCode)
    }
    return classifyChatError(throwable.message)
}

internal fun classifyChatError(message: String?): ChatErrorKind? {
    val normalized = message?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

    val httpCode = HTTP_CODE_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
    if (httpCode != null) return classifyByStatusCode(httpCode)

    val runtimeNotReadySignals =
        listOf(
            "runtime is not installed",
            "connection is not configured",
            "runtime is not configured",
            "no runtime configured",
        )
    val transientSignals =
        listOf(
            "unexpected end of stream",
            "stream was reset",
            "connection reset",
            "connection refused",
            "connection closed",
            "connection aborted",
            "failed to connect",
            "connect failed",
            "connect timed out",
            "could not connect",
            "socket closed",
            "socket is closed",
            "closed by peer",
            "timeout",
            "timed out",
            "event stream closed",
        )
    return when {
        runtimeNotReadySignals.any(normalized::contains) -> ChatErrorKind.RUNTIME_NOT_READY
        transientSignals.any(normalized::contains) -> ChatErrorKind.TRANSIENT_CONNECTION
        else -> ChatErrorKind.GENERIC
    }
}

private fun classifyByStatusCode(code: Int): ChatErrorKind =
    when {
        code == 401 || code == 403 -> ChatErrorKind.AUTH_ERROR
        code == 404 -> ChatErrorKind.NOT_FOUND
        code == 429 -> ChatErrorKind.RATE_LIMITED
        code in 500..599 -> ChatErrorKind.SERVER_ERROR
        code == 0 -> ChatErrorKind.TRANSIENT_CONNECTION
        else -> ChatErrorKind.GENERIC
    }

private val HTTP_CODE_REGEX = Regex("""http\s+(\d{3})""")
