package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeUpdateResultTest {
    @Test
    fun `a changed version reports both sides of the upgrade`() {
        assertEquals(
            ClaudeUpdateResult.Updated("2.1.212", "2.1.220"),
            claudeUpdateResult(before = "2.1.212", after = "2.1.220"),
        )
    }

    @Test
    fun `an unchanged version reports that nothing newer was published`() {
        assertEquals(
            ClaudeUpdateResult.AlreadyLatest("2.1.212"),
            claudeUpdateResult(before = "2.1.212", after = "2.1.212"),
        )
    }

    @Test
    fun `an unknown starting version does not invent an upgrade`() {
        // The pre-update read can fail on a sandbox that has just been repaired; reporting
        // "updated from nothing" would be a claim the versions do not support.
        assertEquals(
            ClaudeUpdateResult.AlreadyLatest("2.1.212"),
            claudeUpdateResult(before = null, after = "2.1.212"),
        )
        assertEquals(
            ClaudeUpdateResult.AlreadyLatest("2.1.212"),
            claudeUpdateResult(before = "  ", after = "2.1.212"),
        )
    }
}
