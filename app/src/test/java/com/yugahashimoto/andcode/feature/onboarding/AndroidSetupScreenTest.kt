package com.yugahashimoto.andcode.feature.onboarding

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudeInstallStatus
import com.yugahashimoto.andcode.runtime.local.CodexInstallStatus
import com.yugahashimoto.andcode.runtime.local.CodexUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSetupScreenTest {
    @Test
    fun `minimal install is skipped only when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = false, installFullDevelopmentTools = false))
        assertFalse(shouldStartRuntimeInstall(installComplete = true, installFullDevelopmentTools = false))
    }

    @Test
    fun `full toolchain option runs even when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = true, installFullDevelopmentTools = true))
    }

    @Test
    fun `full toolchain is not started again when it is already installed`() {
        assertFalse(
            shouldStartRuntimeInstall(
                installComplete = true,
                installFullDevelopmentTools = true,
                fullDevelopmentToolsInstalled = true,
            ),
        )
    }

    @Test
    fun `no install failure while the runtime is not installed`() {
        assertFalse(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.NotInstalled,
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `no install failure while the runtime is installing`() {
        assertFalse(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.Installing(0.5f, "step"),
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `no install failure when the runtime is ready`() {
        assertFalse(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.Ready("1.0.0", 4097),
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `no install failure when the runtime is stopped`() {
        assertFalse(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.Stopped("1.0.0", 4097),
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `a Broken runtime counts as a first-install failure - issue 428 OpenCode regression`() {
        assertTrue(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.Broken("boom"),
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `a failed Claude install counts as an install failure`() {
        assertTrue(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.NotInstalled,
                claude = ClaudeCodeUiState(install = ClaudeInstallStatus.Failed("x")),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `a failed Codex install counts as an install failure`() {
        assertTrue(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.NotInstalled,
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(install = CodexInstallStatus.Failed("x")),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `a failed Antigravity install counts as an install failure`() {
        assertTrue(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.NotInstalled,
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(install = AntigravityInstallStatus.Failed("x")),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = false,
            ),
        )
    }

    @Test
    fun `a failed full development tools install counts as an install failure`() {
        assertTrue(
            setupInstallFailed(
                runtimeStatus = LocalRuntimeStatus.NotInstalled,
                claude = ClaudeCodeUiState(),
                antigravity = AntigravityControllerState(),
                codex = CodexUiState(),
                fullDevelopmentToolsInstallFailed = true,
            ),
        )
    }

    @Test
    fun `the full toolchain wait is released by a failure that was never observed running`() {
        assertTrue(
            shouldLeaveFullToolsInstallWait(
                installFailed = true,
                installObserved = false,
                installRunning = false,
                agentsInstallComplete = false,
            ),
        )
    }

    @Test
    fun `the full toolchain wait is released by a failure while another install is still running`() {
        assertTrue(
            shouldLeaveFullToolsInstallWait(
                installFailed = true,
                installObserved = true,
                installRunning = true,
                agentsInstallComplete = false,
            ),
        )
    }

    @Test
    fun `the full toolchain wait does not end before the install was ever observed`() {
        assertFalse(
            shouldLeaveFullToolsInstallWait(
                installFailed = false,
                installObserved = false,
                installRunning = false,
                agentsInstallComplete = true,
            ),
        )
    }

    @Test
    fun `the full toolchain wait does not end while the install is still running`() {
        assertFalse(
            shouldLeaveFullToolsInstallWait(
                installFailed = false,
                installObserved = true,
                installRunning = true,
                agentsInstallComplete = true,
            ),
        )
    }

    @Test
    fun `the full toolchain wait ends once the observed install is idle and everything is in place`() {
        assertTrue(
            shouldLeaveFullToolsInstallWait(
                installFailed = false,
                installObserved = true,
                installRunning = false,
                agentsInstallComplete = true,
            ),
        )
    }

    @Test
    fun `the full toolchain wait does not end when the agents are not complete`() {
        assertFalse(
            shouldLeaveFullToolsInstallWait(
                installFailed = false,
                installObserved = true,
                installRunning = false,
                agentsInstallComplete = false,
            ),
        )
    }
}
