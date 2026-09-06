package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleExecutionCoordinatorTest {
    @Test
    fun `different schedules can run at the same time`() {
        val coordinator = ScheduleExecutionCoordinator()

        assertTrue(coordinator.tryStart("first"))
        assertTrue(coordinator.tryStart("second"))
        assertFalse(coordinator.finish("first"))
        assertTrue(coordinator.finish("second"))
    }

    @Test
    fun `same schedule cannot be started twice`() {
        val coordinator = ScheduleExecutionCoordinator()

        assertTrue(coordinator.tryStart("same"))
        assertFalse(coordinator.tryStart("same"))
        assertTrue(coordinator.finish("same"))
        assertTrue(coordinator.tryStart("same"))
    }

    @Test
    fun `execution state is not shared between sessions`() {
        val first = ScheduleExecutionState()
        val second = ScheduleExecutionState()

        first.streamFailure = "first failure"
        first.lastProgressAt = 1L

        assertNotSame(first, second)
        assertNotEquals(first.streamFailure, second.streamFailure)
        assertNotEquals(first.lastProgressAt, second.lastProgressAt)
    }
}
