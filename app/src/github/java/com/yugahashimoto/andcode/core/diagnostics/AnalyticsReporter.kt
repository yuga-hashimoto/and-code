package com.yugahashimoto.andcode.core.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/** Reports anonymous product-usage events while Firebase Analytics supplies automatic metrics. */
object AnalyticsReporter {
    @Volatile
    private var analytics: FirebaseAnalytics? = null

    /** Installs the client, but only enables collection after explicit user opt-in. */
    fun install(
        context: Context,
        enabled: Boolean = false,
    ) {
        val client =
            runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }
                .getOrNull()
                ?: return
        analytics = client
        runCatching {
            client.setAnalyticsCollectionEnabled(enabled && !com.yugahashimoto.andcode.BuildConfig.DEBUG)
        }
    }

    fun setEnabled(enabled: Boolean) {
        runCatching { analytics?.setAnalyticsCollectionEnabled(enabled && !com.yugahashimoto.andcode.BuildConfig.DEBUG) }
    }

    fun recordRuntimeSessionCompleted() {
        record(AnalyticsEvents.runtimeSessionCompleted())
    }

    fun recordRuntimeSessionError() {
        record(AnalyticsEvents.runtimeSessionError())
    }

    fun recordRuntimeSessionStalled(reason: StallReason) {
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
