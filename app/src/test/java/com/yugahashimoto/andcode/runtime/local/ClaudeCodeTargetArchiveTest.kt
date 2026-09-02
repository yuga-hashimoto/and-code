package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Archiving a Claude Code chat.
 *
 * Claude Code has no archive of its own, so the flag lives on the session record. Without
 * [ClaudeCodeTarget.archiveSession] the call fell through to the backend's "unsupported", which the
 * auto-archive settings and the drawer's archive action both swallow - chats piled up forever.
 */
class ClaudeCodeTargetArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): ClaudeCodeTarget = ClaudeCodeTarget(ClaudeCodeRuntime(folder.root, { null }))

    @Test
    fun `an archived chat disappears from the session list but stays resolvable`() =
        runBlocking {
            val target = target()
            val kept = target.createSession("Keep me", "/workspace")
            val archived = target.createSession("Archive me", "/workspace")

            val result = target.archiveSession(archived.id)

            assertEquals("Archive me", result.title)
            assertEquals(listOf(kept.id), target.listSessions(null).map { it.id })
            assertEquals(listOf(kept.id), target.listSessions("/workspace").map { it.id })
        }

    @Test
    fun `archiving is idempotent`() =
        runBlocking {
            val target = target()
            val session = target.createSession("Archive me twice", "/workspace")

            target.archiveSession(session.id)
            target.archiveSession(session.id)

            assertEquals(emptyList<String>(), target.listSessions(null).map { it.id })
        }

    @Test
    fun `archiving an unknown session fails loudly`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { target().archiveSession("no-such-session") }
        }
    }

    /** The flag lives on the persisted record, so the chat stays hidden after an app restart. */
    @Test
    fun `an archive survives a new target instance`() {
        val id: String
        runBlocking {
            val target = target()
            target.createSession("Archive me", "/workspace")
            target.createSession("Keep me", "/workspace")
            id = target.listSessions(null).first { it.title == "Archive me" }.id
            target.archiveSession(id)
        }
        runBlocking {
            val reloaded = target().listSessions(null)
            assertEquals(listOf("Keep me"), reloaded.map { it.title })
        }
    }

    /** Archived chats must not drag their workspace out of the picker. */
    @Test
    fun `the workspace of an archived chat is still offered`() =
        runBlocking {
            val target = target()
            val session = target.createSession("Archive me", "/workspace/note-articles")
            target.archiveSession(session.id)

            val workspaces = target.listWorkspaces()

            assertTrue(workspaces.any { it.path == "/workspace/note-articles" })
        }
}
