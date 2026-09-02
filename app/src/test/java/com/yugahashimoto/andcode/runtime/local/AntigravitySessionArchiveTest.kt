package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Archiving an Antigravity chat.
 *
 * The CLI has no archive of its own, so the flag lives on the session record. Without
 * [AntigravityTarget.archiveSession] the call fell through to the backend's "unsupported", which
 * the auto-archive settings and the drawer's archive action both swallow - chats piled up forever.
 */
class AntigravitySessionArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): AntigravityTarget = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

    @Test
    fun `an archived chat disappears from the session list but stays resolvable`() =
        runBlocking {
            val target = target()
            val kept = target.createSession("Keep me", "/workspace")
            val archived = target.createSession("Archive me", "/workspace")

            val result = target.archiveSession(archived.id)

            assertEquals("Archive me", result.title)
            assertEquals(listOf(kept.id), target.listSessions(null).map { it.id })
        }

    @Test
    fun `archiving an unknown session fails loudly`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { target().archiveSession("no-such-session") }
        }
    }

    /** The flag lives on the persisted record, so the chat stays hidden after an app restart. */
    @Test
    fun `an archive survives a new runtime instance`() {
        runBlocking {
            val target = target()
            val session = target.createSession("Archive me", "/workspace")
            target.archiveSession(session.id)
        }
        runBlocking {
            assertEquals(emptyList<String>(), target().listSessions(null).map { it.id })
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

    /** Renaming an archived chat must not resurrect it in the drawer. */
    @Test
    fun `a rename keeps an archived chat archived`() =
        runBlocking {
            val target = target()
            val session = target.createSession(null, "/workspace")
            target.archiveSession(session.id)

            target.renameSession(session.id, "Fix the login crash")

            assertEquals(emptyList<String>(), target.listSessions(null).map { it.id })
        }
}
