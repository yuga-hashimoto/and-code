package com.yugahashimoto.andcode.core.diagnostics

/**
 * No-op stand-in for the `github` flavor's Firebase Crashlytics reporter.
 *
 * The F-Droid build excludes Firebase entirely (F-Droid's build server only compiles from source
 * and does not accept proprietary Google Play services), so this flavor reports crashes nowhere;
 * they are only visible through the device's own logs.
 */
object CrashReporter {
    fun install() = Unit

    fun log(message: String) = Unit

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) = Unit
}
