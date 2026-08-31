package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRetryPolicyTest {
    @Test
    fun `the first retry waits out a whole failed attempt`() {
        val delay = ScheduleRetryPolicy.delayAfter(1)

        assertNotNull(delay)
        // A retry landing while the attempt it covers for is still going is turned away as an
        // overlap and the gap is wasted, so this has to clear the runtime boot wait plus the
        // connect window - held against those constants rather than a copy of their sum, so that
        // lengthening either one fails here instead of silently breaking the retry chain.
        assertTrue(
            "a retry has to outlast a whole attempt",
            delay!! > ScheduleRetryPolicy.WORST_CASE_ATTEMPT_MS,
        )
    }

    @Test
    fun `the worst case attempt is the runtime boot wait plus the connect window`() {
        assertEquals(
            ScheduleRetryPolicy.LOCAL_RUNTIME_START_TIMEOUT_MS + ScheduleRetryPolicy.CONNECT_DEADLINE_MS,
            ScheduleRetryPolicy.WORST_CASE_ATTEMPT_MS,
        )
    }

    @Test
    fun `the gaps widen with every failed attempt`() {
        val delays = (1..ScheduleRetryPolicy.maxRetries).mapNotNull(ScheduleRetryPolicy::delayAfter)

        assertTrue(delays.zipWithNext().all { (earlier, later) -> later > earlier })
    }

    @Test
    fun `retries run out so a schedule falls back to its next slot`() {
        assertNull(ScheduleRetryPolicy.delayAfter(ScheduleRetryPolicy.maxRetries + 1))
    }

    @Test
    fun `the whole retry window stays well inside a daily schedule`() {
        val total = (1..ScheduleRetryPolicy.maxRetries).sumOf { ScheduleRetryPolicy.delayAfter(it) ?: 0L }

        assertTrue("retries must not still be firing at the next day's slot", total < 2 * 60 * 60_000L)
    }
}
