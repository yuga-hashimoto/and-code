package com.yugahashimoto.andcode.core.diagnostics

import com.yugahashimoto.andcode.core.security.SecretRedaction

internal object CrashReportSanitizer {
    private const val MAX_LOG_CHARS = 1_000
    private const val MAX_KEY_CHARS = 40
    private const val MAX_VALUE_CHARS = 100

    fun message(value: String): String = SecretRedaction.redact(value.replace(Regex("\\s+"), " ").trim()).take(MAX_LOG_CHARS)

    fun key(value: String): String = value.replace(Regex("[^A-Za-z0-9_]"), "_").take(MAX_KEY_CHARS).ifBlank { "key" }

    fun value(value: String): String = message(value).take(MAX_VALUE_CHARS)
}
