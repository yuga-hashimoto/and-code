package com.yugahashimoto.andcode.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRateLimitTest {
    @Test
    fun `peakUsedPercent returns higher of fiveHour and weekly`() {
        val group =
            RateLimitGroup(
                id = "claude",
                fiveHour = RateLimitWindow(usedPercent = 24f),
                weekly = RateLimitWindow(usedPercent = 78f),
            )
        assertEquals(78f, group.peakUsedPercent)

        val fiveHourHigher =
            RateLimitGroup(
                id = "codex",
                fiveHour = RateLimitWindow(usedPercent = 82f),
                weekly = RateLimitWindow(usedPercent = 31f),
            )
        assertEquals(82f, fiveHourHigher.peakUsedPercent)
    }

    @Test
    fun `primaryGroup selects matching model family for multi-group snapshots`() {
        val snapshot =
            SubscriptionRateLimit(
                groups =
                    listOf(
                        RateLimitGroup(
                            id = "gemini-models",
                            label = "Gemini Models",
                            fiveHour = RateLimitWindow(usedPercent = 12f),
                            weekly = RateLimitWindow(usedPercent = 34f),
                        ),
                        RateLimitGroup(
                            id = "claude-and-gpt-models",
                            label = "Claude and GPT models",
                            fiveHour = RateLimitWindow(usedPercent = 65f),
                            weekly = RateLimitWindow(usedPercent = 50f),
                        ),
                    ),
            )

        assertEquals("gemini-models", snapshot.primaryGroup("gemini-4-neon")?.id)
        assertEquals(34f, snapshot.peakUsedPercentFor("gemini-4-neon"))

        assertEquals("claude-and-gpt-models", snapshot.primaryGroup("claude-sonnet-5-5")?.id)
        assertEquals(65f, snapshot.peakUsedPercentFor("claude-sonnet-5-5"))

        // Unknown model falls back to the group with the highest peak usage.
        assertEquals("claude-and-gpt-models", snapshot.primaryGroup(null)?.id)
        assertEquals(65f, snapshot.peakUsedPercentFor(null))
    }

    @Test
    fun `normalizeEpochSeconds handles seconds, millis, and non-positive values`() {
        assertEquals(1_730_947_200L, RateLimitTimestamps.normalizeEpochSeconds(1_730_947_200L))
        assertEquals(1_730_947_200L, RateLimitTimestamps.normalizeEpochSeconds(1_730_947_200_000L))
        assertNull(RateLimitTimestamps.normalizeEpochSeconds(0L))
        assertNull(RateLimitTimestamps.normalizeEpochSeconds(-1L))
        assertNull(RateLimitTimestamps.parseIsoEpochSeconds("1970-01-01T00:00:00Z"))
    }
}
