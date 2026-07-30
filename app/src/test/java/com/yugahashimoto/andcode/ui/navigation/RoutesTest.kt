package com.yugahashimoto.andcode.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun `code viewer route round trips special paths`() {
        val runtimeId = "remote/id ?#"
        val workspacePath = "/repo with spaces/日本語#fragment"
        val filePath = "src/a+b?c=d%20.kt"

        val segments = codeViewerRoute(runtimeId, workspacePath, filePath).split('/')

        assertEquals(ROUTE_CODE_VIEWER, segments[0])
        assertEquals(runtimeId, decodeRouteArg(segments[1]))
        assertEquals(workspacePath, decodeRouteArg(segments[2]))
        assertEquals(filePath, decodeRouteArg(segments[3]))
    }
}
