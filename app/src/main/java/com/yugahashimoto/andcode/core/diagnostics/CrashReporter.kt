package com.yugahashimoto.andcode.core.diagnostics

import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.yugahashimoto.andcode.core.security.SecretRedaction

/**
 * Best-effort bridge for non-fatal diagnostics.
 *
 * Fatal crashes are collected by Crashlytics automatically. This wrapper keeps ordinary error
 * reporting from making an already failing app fail again, and centralizes the small amount of
 * sanitization needed before sending messages and keys off-device.
 */
object CrashReporter {
    private const val MAX_LOG_CHARS = 1_000
    private const val MAX_KEY_CHARS = 40
    private const val MAX_VALUE_CHARS = 100

    @Volatile
    private var crashlytics: FirebaseCrashlytics? = null

    /** Initializes Crashlytics and records only non-sensitive app metadata. */
    fun install() {
        val client =
            runCatching { FirebaseCrashlytics.getInstance() }
                .getOrNull()
                ?: return
        crashlytics = client
        runCatching {
            client.setCrashlyticsCollectionEnabled(!com.yugahashimoto.andcode.BuildConfig.DEBUG)
            client.setCustomKey("app_version", com.yugahashimoto.andcode.BuildConfig.VERSION_NAME)
            client.setCustomKey(
                "build_type",
                if (com.yugahashimoto.andcode.BuildConfig.DEBUG) "debug" else "release",
            )
            client.setCustomKey("os_version", Build.VERSION.RELEASE)
        }
    }

    fun log(message: String) {
        crashlytics?.let { client ->
            runCatching { client.log(CrashReportSanitizer.message(message)) }
        }
    }

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) {
        val client = crashlytics ?: return
        runCatching {
            message?.let { client.log(CrashReportSanitizer.message(it)) }
            customKeys.forEach { (key, value) ->
                client.setCustomKey(
                    CrashReportSanitizer.key(key),
                    CrashReportSanitizer.value(value),
                )
            }
            client.recordException(error)
        }
    }

    internal object CrashReportSanitizer {
        fun message(value: String): String = SecretRedaction.redact(value.replace(Regex("\\s+"), " ").trim()).take(MAX_LOG_CHARS)

        fun key(value: String): String = value.replace(Regex("[^A-Za-z0-9_]"), "_").take(MAX_KEY_CHARS).ifBlank { "key" }

        fun value(value: String): String = message(value).take(MAX_VALUE_CHARS)
    }
}
