package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.RateLimitGroup
import com.yugahashimoto.andcode.runtime.RateLimitTimestamps
import com.yugahashimoto.andcode.runtime.RateLimitWindow
import com.yugahashimoto.andcode.runtime.SubscriptionRateLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Parses `agy --output-format json --print /usage` output into [SubscriptionRateLimit].
 *
 * Antigravity reports remaining quota fractions (`remaining_fraction` in `0.0..1.0`) per model
 * group ("Gemini Models", "Claude and GPT models") and per rolling window (`5h`, `weekly`).
 * This converts each bucket to consumed percentage (`(1 - remaining_fraction) * 100`) so it aligns
 * with Codex and Claude Code.
 */
object AntigravityRateLimits {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        output: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SubscriptionRateLimit? {
        val rawGroups =
            ((extractJsonObject(output)?.get("command") as? JsonObject)?.get("data") as? JsonObject)
                ?.get("groups") as? JsonArray ?: return null

        val groups =
            rawGroups.mapNotNull { entry ->
                val groupObj = entry as? JsonObject ?: return@mapNotNull null
                val name = groupObj.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val buckets = groupObj["buckets"] as? JsonArray ?: return@mapNotNull null
                var fiveHour: RateLimitWindow? = null
                var weekly: RateLimitWindow? = null
                buckets.forEach { bucketEl ->
                    val bucket = bucketEl as? JsonObject ?: return@forEach
                    val remaining = bucket.double("remaining_fraction") ?: return@forEach
                    val usedPercent = ((1.0 - remaining) * 100.0).toFloat().coerceIn(0f, 100f)
                    val resetsAt = RateLimitTimestamps.parseIsoEpochSeconds(bucket.string("reset_time"))
                    val window = RateLimitWindow(usedPercent = usedPercent, resetsAtEpochSeconds = resetsAt)
                    when (bucket.string("window")?.lowercase()) {
                        "5h", "five_hour" -> fiveHour = window
                        "weekly", "7d", "seven_day" -> weekly = window
                    }
                }
                if (fiveHour == null && weekly == null) return@mapNotNull null
                RateLimitGroup(
                    id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                    label = name,
                    fiveHour = fiveHour,
                    weekly = weekly,
                )
            }
        if (groups.isEmpty()) return null
        return SubscriptionRateLimit(groups = groups, updatedAtMs = nowMs)
    }

    private fun extractJsonObject(output: String): JsonObject? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()?.let { return it }
        // Tolerate diagnostic lines printed before or after the JSON object.
        return trimmed
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("{") && it.endsWith("}") }
            .firstNotNullOfOrNull { line ->
                runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
