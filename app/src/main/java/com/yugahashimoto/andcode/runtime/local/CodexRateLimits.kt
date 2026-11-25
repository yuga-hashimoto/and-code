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
 * Parses Codex app-server rate-limit payloads from `account/rateLimits/read` responses and
 * `account/rateLimits/updated` notifications.
 */
object CodexRateLimits {
    fun parse(
        body: JsonObject,
        nowMs: Long = System.currentTimeMillis(),
    ): SubscriptionRateLimit? {
        val groups = mutableListOf<RateLimitGroup>()
        val byId = body["rateLimitsByLimitId"] as? JsonObject
        if (byId != null && byId.isNotEmpty()) {
            byId.forEach { (key, value) ->
                val obj = value as? JsonObject ?: return@forEach
                parseSnapshot(obj, fallbackId = key)?.let(groups::add)
            }
        }
        if (groups.isEmpty()) {
            val single = (body["rateLimits"] as? JsonObject) ?: body
            parseSnapshot(single, fallbackId = "codex")?.let(groups::add)
        }
        if (groups.isEmpty()) return null
        return SubscriptionRateLimit(groups = groups, updatedAtMs = nowMs)
    }

    private fun parseSnapshot(
        snapshot: JsonObject,
        fallbackId: String,
    ): RateLimitGroup? {
        val primary = parseWindow(snapshot["primary"] as? JsonObject)
        val secondary = parseWindow(snapshot["secondary"] as? JsonObject)
        if (primary == null && secondary == null) return null
        val id = snapshot.string("limitId")?.takeIf(String::isNotBlank) ?: fallbackId
        val label = snapshot.string("limitName")?.takeIf(String::isNotBlank)
        return RateLimitGroup(
            id = id,
            label = label,
            fiveHour = primary,
            weekly = secondary,
        )
    }

    private fun parseWindow(window: JsonObject?): RateLimitWindow? {
        if (window == null) return null
        val rawPercent =
            window.double("usedPercent")
                ?: window.double("used_percent")
                ?: return null
        val percent = rawPercent.toFloat().coerceIn(0f, 100f)
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
