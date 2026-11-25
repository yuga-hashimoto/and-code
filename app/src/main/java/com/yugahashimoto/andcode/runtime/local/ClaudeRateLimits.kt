package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.RateLimitGroup
import com.yugahashimoto.andcode.runtime.RateLimitTimestamps
import com.yugahashimoto.andcode.runtime.RateLimitWindow
import com.yugahashimoto.andcode.runtime.SubscriptionRateLimit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses `rate_limit_event` lines emitted by Claude Code's `--output-format stream-json`.
 */
object ClaudeRateLimits {
    private const val DEFAULT_GROUP_ID = "claude"

    fun parse(
        root: JsonObject,
        nowMs: Long = System.currentTimeMillis(),
    ): SubscriptionRateLimit? {
        val info = (root["rate_limit_info"] as? JsonObject) ?: return null
        val unified = info["unifiedWindows"] as? JsonObject
        var fiveHour = parseWindow(unified?.get("five_hour") as? JsonObject)
        var weekly = parseWindow(unified?.get("seven_day") as? JsonObject)

        if (fiveHour == null && weekly == null) {
            val single = parseWindow(info)
            if (single != null) {
                val type = info.string("rateLimitType")?.lowercase().orEmpty()
                when {
                    type.contains("seven") || type.contains("week") || type.contains("7d") -> weekly = single
                    else -> fiveHour = single
                }
            }
        }
        if (fiveHour == null && weekly == null) return null
        return SubscriptionRateLimit(
            groups =
                listOf(
                    RateLimitGroup(
                        id = DEFAULT_GROUP_ID,
                        label = null,
                        fiveHour = fiveHour,
                        weekly = weekly,
                    ),
                ),
            updatedAtMs = nowMs,
        )
    }

    /**
     * Merges a newly arrived [incoming] snapshot with [existing] so a single-window event (for
     * example, a five-hour update) does not discard the other window recorded earlier.
     */
    fun merge(
        existing: SubscriptionRateLimit?,
        incoming: SubscriptionRateLimit,
    ): SubscriptionRateLimit {
        val prev = existing?.groups?.firstOrNull() ?: return incoming
        val next = incoming.groups.firstOrNull() ?: return incoming
        return SubscriptionRateLimit(
            groups =
                listOf(
                    RateLimitGroup(
                        id = next.id,
                        label = next.label ?: prev.label,
                        fiveHour = next.fiveHour ?: prev.fiveHour,
                        weekly = next.weekly ?: prev.weekly,
                    ),
                ),
            updatedAtMs = incoming.updatedAtMs,
        )
    }

    private fun parseWindow(window: JsonObject?): RateLimitWindow? {
        if (window == null) return null
        val rawUtilization = window.double("utilization") ?: return null
        val percent =
            if (rawUtilization <= 1.0) {
                (rawUtilization * 100.0).toFloat()
            } else {
                rawUtilization.toFloat()
            }.coerceIn(0f, 100f)
        val resetsAt =
            parseResetEpoch(window["resetsAt"])
                ?: parseResetEpoch(window["resets_at"])
        return RateLimitWindow(usedPercent = percent, resetsAtEpochSeconds = resetsAt)
    }

    private fun parseResetEpoch(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return RateLimitTimestamps.normalizeEpochSeconds(it) }
        return RateLimitTimestamps.parseIsoEpochSeconds(primitive.contentOrNull)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
