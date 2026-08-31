package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRetryPolicyTest {
    @Test
    fun `the first refusal is retried soon`() {
        val delay = ScheduleRetryPolicy.delayAfter(1)

        assertNotNull(delay)
        assertTrue("a retry has to outlast the connect deadline", delay!! >= 60_000L)
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
