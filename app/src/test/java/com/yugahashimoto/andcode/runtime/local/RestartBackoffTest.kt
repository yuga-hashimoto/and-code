package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RestartBackoffTest {
    private var clock = 0L

    @Test
    fun `first crash is immediate`() {
        val backoff = RestartBackoff(nowMillis = { clock })

        val delay = backoff.recordCrash()

        assertEquals(0L, delay)
    }

    @Test
    fun `second consecutive crash gets base delay`() {
        val backoff = RestartBackoff(nowMillis = { clock }, baseDelayMs = 1_000L)

        backoff.recordCrash()
        val delay = backoff.recordCrash()

        assertEquals(1_000L, delay)
    }

    @Test
    fun `backoff doubles each consecutive crash`() {
        val backoff = RestartBackoff(nowMillis = { clock }, baseDelayMs = 1_000L, maxDelayMs = 120_000L)

        assertEquals(0L, backoff.recordCrash())
        clock += 1_000
        assertEquals(1_000L, backoff.recordCrash())
        clock += 1_000
        assertEquals(2_000L, backoff.recordCrash())
        clock += 1_000
        assertEquals(4_000L, backoff.recordCrash())
        clock += 1_000
        assertEquals(8_000L, backoff.recordCrash())
    }

    @Test
    fun `backoff capped at max delay`() {
        val backoff = RestartBackoff(nowMillis = { clock }, baseDelayMs = 1_000L, maxDelayMs = 2_000L)

        backoff.recordCrash()
        clock += 1_000
        assertEquals(1_000L, backoff.recordCrash())
        clock += 1_000
        assertEquals(2_000L, backoff.recordCrash())
        clock += 1_000
        assertEquals(2_000L, backoff.recordCrash())
    }

    @Test
    fun `reset clears consecutive crash counter`() {
        val backoff = RestartBackoff(nowMillis = { clock }, baseDelayMs = 1_000L)

        backoff.recordCrash() // 1st: 0ms
        clock += 1_000
        backoff.recordCrash() // 2nd: 1000ms
        clock += 1_000

        backoff.reset()

        assertEquals(0L, backoff.recordCrash())
    }

    @Test
    fun `crashes after the reset window start a new burst`() {
        val backoff =
            RestartBackoff(
                nowMillis = { clock },
                baseDelayMs = 1_000L,
                resetWindowMs = 30_000L,
            )

        backoff.recordCrash() // 1st
        clock += 1_000
        backoff.recordCrash() // 2nd: 1000ms
        clock += 1_000
        backoff.recordCrash() // 3rd: 2000ms

        clock += 60_000

        assertEquals(0L, backoff.recordCrash())
    }

    @Test
    fun `recordUptime resets counter when uptime meets threshold`() {
        val backoff =
            RestartBackoff(
                nowMillis = { clock },
                baseDelayMs = 1_000L,
                resetWindowMs = 30_000L,
            )

        backoff.recordCrash()
        clock += 1_000
        assertEquals(1_000L, backoff.recordCrash())

        backoff.recordUptime(35_000L)

        assertEquals(0L, backoff.recordCrash())
    }

    @Test
    fun `short uptime does not reset the counter`() {
        val backoff =
            RestartBackoff(
                nowMillis = { clock },
                baseDelayMs = 1_000L,
                resetWindowMs = 30_000L,
            )

        backoff.recordCrash()
        clock += 1_000
        backoff.recordCrash()

        backoff.recordUptime(5_000L)

        assertEquals(2_000L, backoff.recordCrash())
    }
}
