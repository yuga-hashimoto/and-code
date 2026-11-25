package com.yugahashimoto.andcode.runtime

import java.time.Instant

/** A single rolling subscription quota window (for example, 5-hour or 7-day). */
data class RateLimitWindow(
    /** Consumed quota percentage in the range 0..100. */
    val usedPercent: Float,
    /** Unix epoch seconds when this window resets, or null when not reported / unused. */
    val resetsAtEpochSeconds: Long? = null,
)

/**
 * One subscription quota bucket/group.
 *
 * Codex and Claude typically report one primary bucket (plus optional model-specific Codex buckets),
 * while Antigravity separates Gemini models from third-party (Claude/GPT) models.
 */
data class RateLimitGroup(
    val id: String,
    val label: String? = null,
    val fiveHour: RateLimitWindow? = null,
    val weekly: RateLimitWindow? = null,
) {
    /** Higher of [fiveHour] and [weekly] usage percentages, or null when neither is present. */
    val peakUsedPercent: Float?
        get() = listOfNotNull(fiveHour?.usedPercent, weekly?.usedPercent).maxOrNull()
}

/** Latest known subscription rate-limit snapshot for a runtime target. */
data class SubscriptionRateLimit(
    val groups: List<RateLimitGroup> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    /**
     * Picks the group most relevant to [selectedModelId] when multiple groups exist (for example,
     * Antigravity's "Gemini Models" vs "Claude and GPT models"), falling back to the group with the
     * highest peak usage.
     */
    fun primaryGroup(selectedModelId: String? = null): RateLimitGroup? {
        if (groups.isEmpty()) return null
        if (groups.size == 1) return groups.first()
        val model = selectedModelId?.lowercase().orEmpty()
        val matched =
            when {
                model.contains("claude") || model.contains("gpt") ->
                    groups.firstOrNull { group ->
                        val hay = "${group.id} ${group.label.orEmpty()}".lowercase()
                        hay.contains("claude") || hay.contains("gpt") || hay.contains("3p")
                    }
                model.contains("gemini") || model.contains("orchestrator") ->
                    groups.firstOrNull { group ->
                        val hay = "${group.id} ${group.label.orEmpty()}".lowercase()
                        hay.contains("gemini")
                    }
                else -> null
            }
        return matched ?: groups.maxByOrNull { it.peakUsedPercent ?: -1f } ?: groups.first()
    }

    /** Highest usage percentage for [selectedModelId]'s primary group, or across all groups. */
    fun peakUsedPercentFor(selectedModelId: String? = null): Float? =
        primaryGroup(selectedModelId)?.peakUsedPercent
            ?: groups.mapNotNull { it.peakUsedPercent }.maxOrNull()
}

internal object RateLimitTimestamps {
    private const val MILLIS_THRESHOLD = 1_000_000_000_000L

    fun normalizeEpochSeconds(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return if (raw >= MILLIS_THRESHOLD) raw / 1000L else raw
    }

    fun parseIsoEpochSeconds(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val epoch = runCatching { Instant.parse(trimmed).epochSecond }.getOrNull() ?: return null
        return if (epoch > 0L) epoch else null
    }
}
