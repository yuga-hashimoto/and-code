package com.yugahashimoto.andcode.feature.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        BrowserHandoff.dismiss()
        BrowserHandoff.bindWorkspaceStatusFile(null)
        BrowserHandoff.updateAgentWorking(false)
    }

    @Test
    fun `user takeover writes user status and hands back to agent`() {
        val statusFile = browserStatusFile(temporaryFolder.newFolder("workspace"))
        BrowserHandoff.bindWorkspaceStatusFile(statusFile)

        BrowserHandoff.takeOver()

        assertEquals(BrowserControlOwner.USER_TAKEOVER, BrowserHandoff.controlOwner)
        assertTrue(BrowserHandoff.userHasControl)
        assertTrue(statusFile.readText().contains("\"state\":\"$BROWSER_STATUS_USER\""))

        BrowserHandoff.handBack()

        assertEquals(BrowserControlOwner.AGENT, BrowserHandoff.controlOwner)
        assertFalse(BrowserHandoff.userHasControl)
        assertTrue(statusFile.readText().contains("\"state\":\"$BROWSER_STATUS_AGENT\""))
    }

    @Test
    fun `agent handoff takes precedence and dismiss records dismissed`() {
        val statusFile = browserStatusFile(temporaryFolder.newFolder("workspace"))
        BrowserHandoff.begin(statusFile)

        assertEquals(BrowserControlOwner.AGENT_HANDOFF, BrowserHandoff.controlOwner)

        BrowserHandoff.dismiss()

        assertNull(BrowserHandoff.active)
        assertEquals(BrowserControlOwner.AGENT, BrowserHandoff.controlOwner)
        assertTrue(statusFile.readText().contains("\"state\":\"$BROWSER_STATUS_DISMISSED\""))
    }

    @Test
    fun `takeover works without a workspace status file`() {
        BrowserHandoff.bindWorkspaceStatusFile(null)

        BrowserHandoff.takeOver()
        assertEquals(BrowserControlOwner.USER_TAKEOVER, BrowserHandoff.controlOwner)

        BrowserHandoff.dismiss()
        assertEquals(BrowserControlOwner.AGENT, BrowserHandoff.controlOwner)
    }
}
