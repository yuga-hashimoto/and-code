package com.yugahashimoto.andcode.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerGesturesTest {
    @Test
    fun `allows a drawer gesture that starts inside the edge hit target`() {
        assertTrue(isDrawerGestureAllowed(startX = 0f, edgeWidthPx = 48f, drawerIsOpen = false))
        assertTrue(isDrawerGestureAllowed(startX = 48f, edgeWidthPx = 48f, drawerIsOpen = false))
    }

    @Test
    fun `rejects a drawer gesture that starts away from the edge`() {
        assertFalse(isDrawerGestureAllowed(startX = 48.1f, edgeWidthPx = 48f, drawerIsOpen = false))
        assertFalse(isDrawerGestureAllowed(startX = 240f, edgeWidthPx = 48f, drawerIsOpen = false))
    }

    @Test
    fun `allows a closing gesture from any position while the drawer is open`() {
        assertTrue(isDrawerGestureAllowed(startX = 240f, edgeWidthPx = 48f, drawerIsOpen = true))
    }
}
