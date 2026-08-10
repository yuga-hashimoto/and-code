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
     * A state with a live process behind it has to keep the device out of suspend: the agent is a
     * proot child of this process, so a screen-off suspend freezes a run that is still going.
     */
    @Test
    fun `a running runtime keeps the CPU awake`() {
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Ready(version = "1.0.0", port = 4096)))
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Starting(version = "1.0.0", port = 4096)))
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Installing(progress = 0.5f, step = "unpacking")))
        assertTrue(
            localRuntimeNeedsWakeLock(
                LocalRuntimeStatus.Updating(
                    currentVersion = "1.0.0",
                    targetVersion = "1.1.0",
                    progress = null,
                    step = "downloading",
                ),
            ),
        )
    }

    /** Keeping a runtime that holds no process awake would only cost battery. */
    @Test
    fun `a runtime with nothing running lets the device sleep`() {
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.NotInstalled))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Stopped(version = "1.0.0", port = 4096)))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Broken(reason = "missing rootfs")))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.UnsupportedAbi(abi = "x86")))
    }
}
