package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleExecutionCoordinatorTest {
    @Test
    fun `different schedules can run at the same time`() {
        val coordinator = ScheduleExecutionCoordinator()

        assertTrue(coordinator.tryStart("first", 1))
        assertTrue(coordinator.tryStart("second", 2))
        assertEquals(null, coordinator.finish("first"))
        assertEquals(2, coordinator.finish("second"))
    }

    @Test
    fun `same schedule cannot be started twice`() {
        val coordinator = ScheduleExecutionCoordinator()

        assertTrue(coordinator.tryStart("same", 1))
        assertFalse(coordinator.tryStart("same", 2))
        assertEquals(2, coordinator.finish("same"))
        assertTrue(coordinator.tryStart("same", 3))
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
