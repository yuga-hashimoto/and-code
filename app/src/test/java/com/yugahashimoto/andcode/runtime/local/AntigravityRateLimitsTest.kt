package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AntigravityRateLimitsTest {
    @Test
    fun `parses live agy usage json output and converts remaining to used percent`() {
        val liveOutput =
            """
            {
              "conversation_id": "",
              "status": "SUCCESS",
              "response": "...",
              "command": {
                "name": "usage",
                "data": {
                  "groups": [
                    {
                      "name": "Gemini Models",
                      "buckets": [
                        {
                          "id": "gemini-weekly",
                          "name": "Weekly Limit Remaining",
                          "window": "weekly",
                          "remaining_fraction": 0.7564878463745117,
                          "reset_time": "2026-11-30T22:22:13Z"
                        },
                        {
                          "id": "gemini-5h",
                          "name": "Five Hour Limit Remaining",
                          "window": "5h",
                          "remaining_fraction": 0.9575568437576294,
                          "reset_time": "2026-11-25T01:43:19Z"
                        }
                      ]
                    },
                    {
                      "name": "Claude and GPT models",
                      "buckets": [
                        {
                          "id": "3p-weekly",
                          "name": "Weekly Limit Remaining",
                          "window": "weekly",
                          "remaining_fraction": 1,
                          "reset_time": "1970-01-01T00:00:00Z"
                        },
                        {
                          "id": "3p-5h",
                          "name": "Five Hour Limit Remaining",
                          "window": "5h",
                          "remaining_fraction": 1,
                          "reset_time": "1970-01-01T00:00:00Z"
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val parsed = AntigravityRateLimits.parse(liveOutput)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.groups.size)

        val gemini = parsed.groups[0]
        assertEquals("Gemini Models", gemini.label)
        assertEquals(4.244f, gemini.fiveHour!!.usedPercent, 0.01f)
        assertNotNull(gemini.fiveHour!!.resetsAtEpochSeconds)
        assertEquals(24.351f, gemini.weekly!!.usedPercent, 0.01f)
        assertNotNull(gemini.weekly!!.resetsAtEpochSeconds)

        val thirdParty = parsed.groups[1]
        assertEquals("Claude and GPT models", thirdParty.label)
        assertEquals(0f, thirdParty.fiveHour!!.usedPercent, 0.01f)
        assertNull(thirdParty.fiveHour!!.resetsAtEpochSeconds)
        assertEquals(0f, thirdParty.weekly!!.usedPercent, 0.01f)
        assertNull(thirdParty.weekly!!.resetsAtEpochSeconds)

        assertEquals(24.351f, parsed.peakUsedPercentFor("gemini-4-neon")!!, 0.01f)
        assertEquals(0f, parsed.peakUsedPercentFor("claude-sonnet-5-5")!!, 0.01f)
    }

    @Test
    fun `tolerates diagnostic lines around json payload`() {
        val jsonLine =
            """{"command":{"name":"usage","data":{"groups":[{"name":"Gemini Models",""" +
                """"buckets":[{"window":"5h","remaining_fraction":0.2,"reset_time":"2026-11-25T05:00:00Z"}]}]}}}"""
        val output = "I1125 loading...\n$jsonLine"

        val parsed = AntigravityRateLimits.parse(output)
        assertNotNull(parsed)
        assertEquals(80f, parsed!!.peakUsedPercentFor()!!, 0.01f)
    }
}
