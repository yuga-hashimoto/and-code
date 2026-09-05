package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeServiceCommandTest {
    @Test
    fun `maps public service actions to runtime commands`() {
        assertEquals(
            LocalRuntimeServiceCommand.InstallAndStart,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_INSTALL_AND_START),
        )
        assertEquals(
            LocalRuntimeServiceCommand.InstallFullDevelopmentTools,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_INSTALL_FULL_DEVELOPMENT_TOOLS),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Start,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_START),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Reinstall,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_REINSTALL),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Update,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_UPDATE),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Rollback,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_ROLLBACK),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Delete,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_DELETE),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Stop,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_STOP),
        )
        assertEquals(
            LocalRuntimeServiceCommand.Restart,
            localRuntimeServiceCommand(LocalRuntimeService.ACTION_RESTART),
        )
        assertEquals(LocalRuntimeServiceCommand.Restore, localRuntimeServiceCommand(null))
    }

    @Test
    fun `unknown action is ignored`() {
        assertEquals(LocalRuntimeServiceCommand.Ignore, localRuntimeServiceCommand("unknown"))
    }

    /**
     * The setup guide's selection has to survive the trip through the service intent. It used not to
     * be carried at all, so ticking Claude Code or Antigravity next to OpenCode installed neither.
     */
    @Test
    fun `an install carries the selected agents`() {
        assertEquals(
            setOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY),
            localRuntimeInstallAgents(
                arrayOf(LocalAgent.OPEN_CODE.id, LocalAgent.CLAUDE_CODE.id, LocalAgent.ANTIGRAVITY.id),
            ),
        )
    }

    /** Callers with no selection - the notification's restart, the watchdog - mean OpenCode alone. */
    @Test
    fun `an install with no selection means OpenCode`() {
        assertEquals(setOf(LocalAgent.OPEN_CODE), localRuntimeInstallAgents(null))
        assertEquals(setOf(LocalAgent.OPEN_CODE), localRuntimeInstallAgents(emptyArray()))
        assertEquals(setOf(LocalAgent.OPEN_CODE), localRuntimeInstallAgents(arrayOf("not-an-agent")))
    }

    /**
     * A bounded, self-limiting state - a boot or install in progress - has to keep the device out
     * of suspend regardless of whether any work has been observed yet: getting frozen mid-way is a
     * broken state, not an idle one.
     */
    @Test
    fun `an installing, starting or updating runtime keeps the CPU awake regardless of active work`() {
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Starting(version = "1.0.0", port = 4096), hasActiveWork = false))
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Installing(progress = 0.5f, step = "unpacking"), hasActiveWork = false))
        assertTrue(
            localRuntimeNeedsWakeLock(
                LocalRuntimeStatus.Updating(
                    currentVersion = "1.0.0",
                    targetVersion = "1.1.0",
                    progress = null,
                    step = "downloading",
                ),
                hasActiveWork = false,
            ),
        )
    }

    /** Keeping a runtime that holds no process awake would only cost battery. */
    @Test
    fun `a runtime with nothing running lets the device sleep regardless of active work`() {
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.NotInstalled, hasActiveWork = true))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Stopped(version = "1.0.0", port = 4096), hasActiveWork = true))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Broken(reason = "missing rootfs"), hasActiveWork = true))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.UnsupportedAbi(abi = "x86"), hasActiveWork = true))
    }

    /**
     * `Ready` alone used to be enough to hold the wake lock, which meant the device could never
     * suspend for as long as the runtime was simply up. It now only holds the lock while there is
     * work in flight - a chat run, a scheduled run, a runtime operation or a live ADB link.
     */
    @Test
    fun `a ready runtime with active work stays awake`() {
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Ready(version = "1.0.0", port = 4096), hasActiveWork = true))
    }

    @Test
    fun `a ready runtime with nothing in flight lets the device sleep`() {
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Ready(version = "1.0.0", port = 4096), hasActiveWork = false))
    }

    /**
     * Every command a user reaches through the workspace screen that leaves the runtime running -
     * plus the schedule's on-demand start, which resolves to [LocalRuntimeServiceCommand.Start].
     * Without clearing the flag here, a deliberate restart right after a deliberate stop would
     * still read as "leave it down" on the next foreground return.
     */
    @Test
    fun `an explicit start clears the user-stopped flag`() {
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Start))
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.InstallAndStart))
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Restart))
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Reinstall))
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Update))
        assertTrue(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Rollback))
    }

    /**
     * A stop is exactly what should set the flag (done separately in the service, since it needs
     * the settings write), and the rest leave no runtime to restore. Restore in particular is the
     * system re-delivering a null-action intent, not a user or schedule action, so it must not
     * override a deliberate stop.
     */
    @Test
    fun `commands that are not an explicit start leave the user-stopped flag alone`() {
        assertFalse(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Stop))
        assertFalse(clearsUserStoppedFlag(LocalRuntimeServiceCommand.InstallFullDevelopmentTools))
        assertFalse(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Restore))
        assertFalse(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Delete))
        assertFalse(clearsUserStoppedFlag(LocalRuntimeServiceCommand.Ignore))
    }
}
