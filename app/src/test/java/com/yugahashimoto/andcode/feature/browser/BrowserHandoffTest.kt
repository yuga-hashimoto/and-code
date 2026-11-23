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

    @Test
    fun `clearStaleUserTakeover resets stale user file but preserves awaiting_user`() {
        val statusFile = browserStatusFile(temporaryFolder.newFolder("workspace"))
        writeBrowserStatus(statusFile, BROWSER_STATUS_USER)

        BrowserHandoff.clearStaleUserTakeover(statusFile)
        assertEquals(BROWSER_STATUS_AGENT, readBrowserStatus(statusFile))

        writeBrowserStatus(statusFile, BROWSER_STATUS_AWAITING_USER)
        BrowserHandoff.clearStaleUserTakeover(statusFile)
        assertEquals(BROWSER_STATUS_AWAITING_USER, readBrowserStatus(statusFile))
    }

    @Test
    fun `releaseUserTakeover clears manual takeover without dismissing agent handoff`() {
        val statusFile = browserStatusFile(temporaryFolder.newFolder("workspace"))
        BrowserHandoff.bindWorkspaceStatusFile(statusFile)
        BrowserHandoff.takeOver()

        BrowserHandoff.releaseUserTakeover()
        assertEquals(BrowserControlOwner.AGENT, BrowserHandoff.controlOwner)
        assertEquals(BROWSER_STATUS_AGENT, readBrowserStatus(statusFile))

        BrowserHandoff.begin(statusFile)
        BrowserHandoff.releaseUserTakeover()
        assertEquals(BrowserControlOwner.AGENT_HANDOFF, BrowserHandoff.controlOwner)
    }
}
