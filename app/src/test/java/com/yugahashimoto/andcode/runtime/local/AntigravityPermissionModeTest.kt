package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The permission mode chosen in settings is the one a new Antigravity chat runs in.
 *
 * It was not: sessions were created with the enum's own default, and the composer offered the modes
 * as agents, so the globally remembered agent id - "plan", picked once under OpenCode - selected
 * Antigravity's plan mode over whatever settings said. [ClaudeCodeTarget] never had either problem,
 * and these tests hold Antigravity to the same behaviour.
 */
class AntigravityPermissionModeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): AntigravityTarget = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

    private fun AntigravityTarget.storedMode(sessionId: String): String =
        runtime.listSessions(null).single { it.appSessionId == sessionId }.permissionMode

    @Test
    fun `a new session starts in the mode settings chose`() =
        runBlocking {
            val target = target()
            target.setPermissionMode(AntigravityPermissionMode.FULL_ACCESS)
            val session = target.createSession(null, "/workspace")
            assertEquals(AntigravityPermissionMode.FULL_ACCESS.cliValue, target.storedMode(session.id))
        }

    @Test
    fun `the chosen mode outlives the process that chose it`() {
        runBlocking { target().setPermissionMode(AntigravityPermissionMode.FULL_ACCESS) }
        runBlocking {
            val target = target()
            assertEquals(AntigravityPermissionMode.FULL_ACCESS, target.defaultPermissionMode.value)
            val session = target.createSession(null, "/workspace")
            assertEquals(AntigravityPermissionMode.FULL_ACCESS.cliValue, target.storedMode(session.id))
        }
    }

    /**
     * The agent id is remembered globally, across runtimes. While the modes were offered as agents,
     * a "plan" left over from OpenCode was a valid Antigravity agent and quietly won.
     */
    @Test
    fun `the modes are not offered as agents another runtime could name`() =
        runBlocking {
            assertEquals(listOf("antigravity"), target().listAgents().map { it.name })
        }
}
