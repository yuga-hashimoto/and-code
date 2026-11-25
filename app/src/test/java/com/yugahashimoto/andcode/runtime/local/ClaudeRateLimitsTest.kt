package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClaudeRateLimitsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses unifiedWindows from stream-json rate_limit_event`() {
        val line =
            """
            {
              "type": "rate_limit_event",
              "uuid": "evt-1",
              "session_id": "sess-1",
              "rate_limit_info": {
                "status": "allowed",
                "rateLimitType": "five_hour",
                "resetsAt": 1789478400,
                "utilization": 0.09,
                "unifiedWindows": {
                  "five_hour": { "utilization": 0.09, "resetsAt": 1789478400 },
                  "seven_day": { "utilization": 0.38, "resetsAt": 1789651200 }
                }
              }
            }
            """.trimIndent()

        val parser = ClaudeStreamJsonParser(sessionId = "s1", json = json)
        val parsed = parser.parse(line)
        val rateLimit = parsed.rateLimit
        assertNotNull(rateLimit)
        val group = rateLimit!!.groups.first()
        assertEquals(9f, group.fiveHour!!.usedPercent, 0.01f)
        assertEquals(1789478400L, group.fiveHour!!.resetsAtEpochSeconds)
        assertEquals(38f, group.weekly!!.usedPercent, 0.01f)
        assertEquals(1789651200L, group.weekly!!.resetsAtEpochSeconds)
        assertEquals(38f, rateLimit.peakUsedPercentFor()!!, 0.01f)
    }

    @Test
    fun `merges single-window events without losing the other window`() {
        val fiveHourEvent =
            ClaudeRateLimits.parse(
                json.parseToJsonElement(
                    """
                    {
                      "type": "rate_limit_event",
                      "rate_limit_info": {
                        "rateLimitType": "five_hour",
                        "utilization": 0.25,
                        "resetsAt": 1789478400
                      }
                    }
                    """.trimIndent(),
                ).jsonObject,
            )!!
        val weeklyEvent =
            ClaudeRateLimits.parse(
                json.parseToJsonElement(
                    """
                    {
                      "type": "rate_limit_event",
                      "rate_limit_info": {
                        "rateLimitType": "seven_day",
                        "utilization": 0.72,
                        "resetsAt": 1789651200
                      }
                    }
                    """.trimIndent(),
                ).jsonObject,
            )!!

        val merged = ClaudeRateLimits.merge(fiveHourEvent, weeklyEvent)
        assertEquals(25f, merged.groups.first().fiveHour!!.usedPercent, 0.01f)
        assertEquals(72f, merged.groups.first().weekly!!.usedPercent, 0.01f)
        assertEquals(72f, merged.peakUsedPercentFor()!!, 0.01f)
    }
}
