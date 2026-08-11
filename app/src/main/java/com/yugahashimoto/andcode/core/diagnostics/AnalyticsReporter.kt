package com.yugahashimoto.andcode.core.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

internal data class AnalyticsEvent(
    val name: String,
    val parameters: Map<String, String> = emptyMap(),
)

internal object AnalyticsEvents {
    fun runtimeSessionCompleted(): AnalyticsEvent = AnalyticsEvent("runtime_session_completed")

    fun runtimeSessionError(): AnalyticsEvent = AnalyticsEvent("runtime_session_error")

    /** [reason] is a [StallReason] name, so the common causes of a wedged run can be told apart. */
    fun runtimeSessionStalled(reason: String): AnalyticsEvent = AnalyticsEvent("runtime_session_stalled", mapOf("reason" to reason))
}

/** Reports anonymous product-usage events while Firebase Analytics supplies automatic metrics. */
object AnalyticsReporter {
    @Volatile
    private var analytics: FirebaseAnalytics? = null

    /** Enables Analytics for release builds and keeps local debug runs out of production data. */
    fun install(context: Context) {
        val client =
            runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }
                .getOrNull()
                ?: return
        analytics = client
        runCatching {
            client.setAnalyticsCollectionEnabled(!com.yugahashimoto.andcode.BuildConfig.DEBUG)
        }
    }

    fun recordRuntimeSessionCompleted() {
        record(AnalyticsEvents.runtimeSessionCompleted())
    }

    fun recordRuntimeSessionError() {
        record(AnalyticsEvents.runtimeSessionError())
    }

    fun recordRuntimeSessionStalled(reason: String) {
        record(AnalyticsEvents.runtimeSessionStalled(reason))
    }

    private fun record(event: AnalyticsEvent) {
        val client = analytics ?: return
        runCatching {
            val parameters =
                Bundle().apply {
                    event.parameters.forEach { (key, value) -> putString(key, value) }
                }
            client.logEvent(event.name, parameters)
        }
    }
}
