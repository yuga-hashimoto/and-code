package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CodexRateLimitsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses rateLimitsByLimitId map`() {
        val payload =
            json.parseToJsonElement(
                """
                {
                  "rateLimitsByLimitId": {
                    "codex": {
                      "limitId": "codex",
                      "limitName": "Codex Default",
                      "primary": {
                        "usedPercent": 25.0,
                        "resetsAt": 1730947200
                      },
                      "secondary": {
                        "usedPercent": 62.5,
                        "resetsAt": 1731552000
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).jsonObject

        val parsed = CodexRateLimits.parse(payload, nowMs = 1234L)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.groups.size)
        val group = parsed.groups.first()
        assertEquals("codex", group.id)
        assertEquals("Codex Default", group.label)
        assertEquals(25f, group.fiveHour?.usedPercent)
        assertEquals(1730947200L, group.fiveHour?.resetsAtEpochSeconds)
        assertEquals(62.5f, group.weekly?.usedPercent)
        assertEquals(1731552000L, group.weekly?.resetsAtEpochSeconds)
        assertEquals(62.5f, parsed.peakUsedPercentFor())
    }

    @Test
    fun `parses single rateLimits fallback object`() {
        val payload =
            json.parseToJsonElement(
                """
                {
                  "rateLimits": {
                    "primary": {
                      "used_percent": 40,
                      "resets_at": "2026-11-25T05:00:00Z"
                    }
                  }
                }
                """.trimIndent(),
            ).jsonObject

        val parsed = CodexRateLimits.parse(payload)
        assertNotNull(parsed)
        assertEquals(40f, parsed!!.peakUsedPercentFor())
        assertNotNull(parsed.groups.first().fiveHour?.resetsAtEpochSeconds)
        assertNull(parsed.groups.first().weekly)
    }

    @Test
    fun `returns null when no windows are present`() {
        val payload = json.parseToJsonElement("""{"rateLimitsByLimitId": {}}""").jsonObject
        assertNull(CodexRateLimits.parse(payload))
    }
}
