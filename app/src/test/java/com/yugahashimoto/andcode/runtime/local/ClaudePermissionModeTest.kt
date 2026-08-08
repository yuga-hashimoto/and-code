package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudePermissionModeTest {
    @Test
    fun `accept edits pre-approves bash so common commands keep working`() {
        assertEquals(listOf("Bash"), ClaudePermissionMode.ACCEPT_EDITS.allowedTools)
    }

    @Test
    fun `ask mode uses default CLI permission mode and needs the bridge`() {
        assertEquals("default", ClaudePermissionMode.ASK.cliValue)
        assertTrue(ClaudePermissionMode.ASK.requiresBridge)
        assertTrue(ClaudePermissionMode.ASK.allowedTools.isEmpty())
    }

    @Test
    fun `plan approves nothing`() {
        assertTrue(ClaudePermissionMode.PLAN.allowedTools.isEmpty())
    }

    @Test
    fun `full access needs no allow list`() {
        assertTrue(ClaudePermissionMode.FULL_ACCESS.allowedTools.isEmpty())
        assertEquals("bypassPermissions", ClaudePermissionMode.FULL_ACCESS.cliValue)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(ClaudePermissionMode.DEFAULT, ClaudePermissionMode.fromCliValue("nonsense"))
        assertEquals(ClaudePermissionMode.DEFAULT, ClaudePermissionMode.fromCliValue(null))
        assertEquals(ClaudePermissionMode.PLAN, ClaudePermissionMode.fromCliValue("plan"))
        assertEquals(ClaudePermissionMode.ASK, ClaudePermissionMode.fromCliValue("default"))
    }

    @Test
    fun `default remains accept edits until ask is opted in`() {
        assertEquals(ClaudePermissionMode.ACCEPT_EDITS, ClaudePermissionMode.DEFAULT)
    }
}
