package com.yugahashimoto.andcode.core.diagnostics

import android.content.Context

/**
 * No-op stand-in for the `github` flavor's Firebase Analytics reporter.
 *
 * The F-Droid build excludes Firebase entirely (F-Droid's build server only compiles from source
 * and does not accept proprietary Google Play services), so this flavor collects no usage
 * analytics at all rather than sending them anywhere else.
 */
object AnalyticsReporter {
    fun install(
        context: Context,
        enabled: Boolean = false,
    ) = Unit

    fun setEnabled(enabled: Boolean) = Unit

    fun recordRuntimeSessionCompleted() = Unit

    fun recordRuntimeSessionError() = Unit

    fun recordRuntimeSessionStalled(reason: StallReason) = Unit
}
