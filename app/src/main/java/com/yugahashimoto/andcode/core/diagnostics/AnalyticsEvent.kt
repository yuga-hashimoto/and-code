package com.yugahashimoto.andcode.core.diagnostics

internal data class AnalyticsEvent(
    val name: String,
    val parameters: Map<String, String> = emptyMap(),
)

internal object AnalyticsEvents {
    fun runtimeSessionCompleted(): AnalyticsEvent = AnalyticsEvent("runtime_session_completed")

    fun runtimeSessionError(): AnalyticsEvent = AnalyticsEvent("runtime_session_error")

    /**
     * Records why a wedged run went quiet, so the common causes can be told apart. Only the reasons
     * that leave a run stuck get here: a stall that turns out to be a finished or failed turn is
     * settled as one, and counts as a completion or an error instead.
     */
    fun runtimeSessionStalled(reason: StallReason): AnalyticsEvent =
        AnalyticsEvent("runtime_session_stalled", mapOf("reason" to reason.name))
}
