package com.yugahashimoto.andcode.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRoutesTest {
    @Test
    fun `editor pattern keeps the schedule id optional`() {
        // "New schedule" navigates to the bare route, which only matches the destination while the
        // id stays a query parameter - as a path segment Navigation would require it and throw.
        assertEquals(ROUTE_SCHEDULE_EDIT, SCHEDULE_EDIT_ROUTE_PATTERN.substringBefore('?'))
        assertTrue(SCHEDULE_EDIT_ROUTE_PATTERN.contains("?$SCHEDULE_EDIT_ARG_ID={$SCHEDULE_EDIT_ARG_ID}"))
    }

    @Test
    fun `editing an existing schedule carries the id through the route`() {
        val route = scheduleEditRoute("schedule-1/with odd chars")
        assertEquals(ROUTE_SCHEDULE_EDIT, route.substringBefore('?'))
        val encoded = route.substringAfter("$SCHEDULE_EDIT_ARG_ID=")
        assertEquals("schedule-1/with odd chars", decodeRouteArg(encoded))
    }

    @Test
    fun `schedule detail route round trips its id`() {
        val route = scheduleDetailRoute("schedule-2")
        assertEquals(ROUTE_SCHEDULE_DETAIL, route.substringBefore('/'))
        assertEquals("schedule-2", decodeRouteArg(route.substringAfter('/')))
    }
}
