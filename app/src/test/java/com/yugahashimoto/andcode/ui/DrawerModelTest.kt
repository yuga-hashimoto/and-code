package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerModelTest {
    @Test
    fun `builder drops subagent sessions and pending removals`() {
        val sessions =
            listOf(
                ref("keep", updated = 10),
                ref("subagent", parentId = "keep", updated = 20),
                ref("removing", updated = 30),
            )

        val built =
            drawerRecentSessions(
                allSessions = sessions,
                activeSessionIds = emptySet(),
                completedSessionIds = emptySet(),
                pinnedSessionIds = emptySet(),
                pendingRemoval = setOf("removing"),
                timeLabel = { it.toString() },
            )

        assertEquals(listOf("keep"), built.map { it.id })
    }

    @Test
    fun `builder keeps the catalog order`() {
        val sessions = listOf(ref("first"), ref("second"), ref("third"))

        val built =
            drawerRecentSessions(
                allSessions = sessions,
                activeSessionIds = emptySet(),
                completedSessionIds = emptySet(),
                pinnedSessionIds = setOf("third"),
                pendingRemoval = emptySet(),
                timeLabel = { it.toString() },
            )

        assertEquals(listOf("first", "second", "third"), built.map { it.id })
    }

    @Test
    fun `builder caps unpinned sessions at 25 but keeps every pinned one`() {
        val sessions = (1..30).map { ref("chat-$it") }

        val built =
            drawerRecentSessions(
                allSessions = sessions,
                activeSessionIds = emptySet(),
                completedSessionIds = emptySet(),
                pinnedSessionIds = setOf("chat-28", "chat-30"),
                pendingRemoval = emptySet(),
                timeLabel = { it.toString() },
            )

        assertEquals(27, built.size)
        assertEquals((1..25).map { "chat-$it" } + listOf("chat-28", "chat-30"), built.map { it.id })
    }

    @Test
    fun `builder marks which sessions are pinned`() {
        val built =
            drawerRecentSessions(
                allSessions = listOf(ref("pinned"), ref("plain")),
                activeSessionIds = emptySet(),
                completedSessionIds = emptySet(),
                pinnedSessionIds = setOf("pinned"),
                pendingRemoval = emptySet(),
                timeLabel = { it.toString() },
            )

        assertTrue(built.first { it.id == "pinned" }.isPinned)
        assertFalse(built.first { it.id == "plain" }.isPinned)
    }

    @Test
    fun `builder maps running completed unread and idle statuses`() {
        val built =
            drawerRecentSessions(
                allSessions = listOf(ref("running"), ref("done"), ref("idle")),
                activeSessionIds = setOf("running"),
                completedSessionIds = setOf("done"),
                pinnedSessionIds = emptySet(),
                pendingRemoval = emptySet(),
                timeLabel = { "label-$it" },
            )

        assertEquals(SessionStatus.RUNNING, built.first { it.id == "running" }.status)
        assertEquals(SessionStatus.COMPLETED_UNREAD, built.first { it.id == "done" }.status)
        assertEquals(SessionStatus.IDLE, built.first { it.id == "idle" }.status)
        assertTrue(built.first { it.id == "running" }.isActive)
        assertTrue(built.first { it.id == "done" }.hasUnread)
        assertEquals("label-1000", built.first().relativeTime)
    }

    @Test
    fun `blank search returns the same list unchanged`() {
        val sessions = listOf(session("one", "Alpha", "/workspace/a"), session("two", "Beta", "/workspace/b"))

        assertEquals(sessions, filterDrawerSessions(sessions, ""))
        assertEquals(sessions, filterDrawerSessions(sessions, "   "))
    }

    @Test
    fun `search requires every whitespace-separated term and ignores case`() {
        val sessions =
            listOf(
                session("one", "Fix auth bug", "/workspace/android"),
                session("two", "Fix tests", "/workspace/api"),
                session("three", "Update docs", "/workspace/android"),
            )

        assertEquals(listOf("one"), filterDrawerSessions(sessions, "FIX ANDROID").map { it.id })
        assertEquals(listOf("one", "two"), filterDrawerSessions(sessions, "fix").map { it.id })
    }

    @Test
    fun `search matches the directory as well as the title`() {
        val sessions = listOf(session("one", "Plain title", "/workspace/api-server"))

        assertEquals(listOf("one"), filterDrawerSessions(sessions, "api-server").map { it.id })
    }

    @Test
    fun `search with no match returns an empty list`() {
        val sessions = listOf(session("one", "Plain title", "/workspace/api"))

        assertTrue(filterDrawerSessions(sessions, "missing").isEmpty())
    }

    @Test
    fun `search preserves the original order`() {
        val sessions =
            listOf(
                session("three", "Fix three", null),
                session("one", "Fix one", null),
                session("two", "Fix two", null),
            )

        assertEquals(
            listOf("three", "one", "two"),
            filterDrawerSessions(sessions, "fix").map { it.id },
        )
    }

    @Test
    fun `toggled section collapses and expands`() {
        val collapsed = toggledSection(emptySet(), "pinned")
        assertEquals(setOf("pinned"), collapsed)
        assertEquals(emptySet<String>(), toggledSection(collapsed, "pinned"))
    }

    @Test
    fun `awaitSessionGone returns immediately when the id is absent`() =
        runTest {
            val sessions = MutableStateFlow(listOf(ref("other")))

            assertTrue(awaitSessionGone(sessions, "chat"))

            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `awaitSessionGone waits for a list without the id`() =
        runTest {
            val sessions = MutableStateFlow(listOf(ref("chat")))
            var gone = false
            val job =
                launch {
                    gone = awaitSessionGone(sessions, "chat")
                }
            runCurrent()
            assertFalse(job.isCompleted)

            sessions.value = listOf(ref("other"))
            runCurrent()

            assertTrue(job.isCompleted)
            assertTrue(gone)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `awaitSessionGone returns after the timeout when the id never disappears`() =
        runTest {
            val sessions = MutableStateFlow(listOf(ref("chat")))
            val started = testScheduler.currentTime

            assertFalse(awaitSessionGone(sessions, "chat", timeoutMillis = 1_000L))

            assertEquals(1_000L, testScheduler.currentTime - started)
        }

    @Test
    fun `search results show everything under the cap`() {
        val sessions =
            listOf(
                session("one", "Fix one", null),
                session("two", "Fix two", null),
                session("three", "Update docs", null),
            )

        val results = searchResults(sessions, "fix")

        assertEquals(listOf("one", "two"), results.shown.map { it.id })
        assertEquals(2, results.totalCount)
    }

    @Test
    fun `search results cap the shown rows while keeping the full count`() {
        val sessions = (1..60).map { session("chat-$it", "Chat $it", null) }

        val results = searchResults(sessions, "chat", max = 50)

        assertEquals((1..50).map { "chat-$it" }, results.shown.map { it.id })
        assertEquals(60, results.totalCount)
    }

    @Test
    fun `blank search results cap the rows and count every session`() {
        val sessions = (1..60).map { session("chat-$it", "Chat $it", null) }

        val results = searchResults(sessions, "   ", max = 50)

        assertEquals((1..50).map { "chat-$it" }, results.shown.map { it.id })
        assertEquals(60, results.totalCount)
    }

    @Test
    fun `browsable sessions cap unpinned chats but keep pinned ones beyond the cap`() {
        val sessions =
            (1..30).map { index ->
                session(
                    id = "chat-$index",
                    title = "chat-$index",
                    directory = null,
                    isPinned = index == 28 || index == 30,
                )
            }

        val browsable = browsableSessions(sessions)

        assertEquals(
            (1..25).map { "chat-$it" } + listOf("chat-28", "chat-30"),
            browsable.map { it.id },
        )
    }

    @Test
    fun `builder can keep every chat when the browse cap is disabled`() {
        val sessions = (1..30).map { ref("chat-$it") }

        val built =
            drawerRecentSessions(
                allSessions = sessions,
                activeSessionIds = emptySet(),
                completedSessionIds = emptySet(),
                pinnedSessionIds = emptySet(),
                pendingRemoval = emptySet(),
                maxUnpinned = Int.MAX_VALUE,
                timeLabel = { it.toString() },
            )

        assertEquals(30, built.size)
    }

    @Test
    fun `selection keeps only chats visible under the query`() {
        val sessions =
            listOf(
                session("one", "Fix auth", null),
                session("two", "Update docs", null),
            )

        assertEquals(
            setOf("one"),
            retainVisibleSelection(setOf("one", "two"), sessions, "fix"),
        )
        assertEquals(
            emptySet<String>(),
            retainVisibleSelection(setOf("one"), sessions, "missing"),
        )
    }

    @Test
    fun `blank query keeps the whole selection`() {
        val sessions = listOf(session("one", "Alpha", null))
        val selected = setOf("one", "older")

        assertEquals(selected, retainVisibleSelection(selected, sessions, ""))
        assertEquals(selected, retainVisibleSelection(selected, sessions, "   "))
    }

    @Test
    fun `project filter returns every project for a blank query`() {
        val workspaces = listOf(workspace("android-code"), workspace("api-server"))

        assertEquals(workspaces, filterDrawerProjects(workspaces, ""))
        assertEquals(workspaces, filterDrawerProjects(workspaces, "   "))
    }

    @Test
    fun `project filter matches the name ignoring case`() {
        val workspaces = listOf(workspace("android-code"), workspace("api-server"), workspace("Android-tools"))

        assertEquals(
            listOf("android-code", "Android-tools"),
            filterDrawerProjects(workspaces, "ANDROID").map { it.name },
        )
    }

    @Test
    fun `project filter trims the query`() {
        val workspaces = listOf(workspace("android-code"), workspace("api-server"))

        assertEquals(listOf("api-server"), filterDrawerProjects(workspaces, "  api  ").map { it.name })
    }

    @Test
    fun `project filter returns an empty list when nothing matches`() {
        val workspaces = listOf(workspace("android-code"))

        assertTrue(filterDrawerProjects(workspaces, "missing").isEmpty())
    }

    @Test
    fun `grouped chats order projects by their newest chat`() {
        val chats =
            listOf(
                session("a", "A", "/workspace/P2"),
                session("b", "B", "/workspace/P1"),
                session("c", "C", "/workspace/P2"),
            )

        val groups = groupedByProject(chats)

        // The project whose newest chat appears first in the newest-first list leads.
        assertEquals(listOf("/workspace/P2", "/workspace/P1"), groups.map { it.first })
        assertEquals(listOf("a", "c"), groups.first().second.map { it.id })
    }

    @Test
    fun `grouped chats put attention chats first and keep the rest in order`() {
        val chats =
            listOf(
                session("one", "One", "/workspace/P"),
                session("two", "Two", "/workspace/P", hasAttention = true),
                session("three", "Three", "/workspace/P"),
                session("four", "Four", "/workspace/P", hasAttention = true),
            )

        assertEquals(
            listOf("two", "four", "one", "three"),
            groupedByProject(chats).single().second.map { it.id },
        )
    }

    @Test
    fun `chats without a usable directory share the empty project group`() {
        val chats =
            listOf(
                session("one", "One", null),
                session("two", "Two", "/root"),
                session("three", "Three", "/workspace"),
                session("four", "Four", "/workspace/real-project"),
            )

        val groups = groupedByProject(chats)

        assertEquals("", groups.first().first)
        assertEquals(listOf("one", "two", "three"), groups.first().second.map { it.id })
        assertEquals("/workspace/real-project", groups.last().first)
    }

    private fun workspace(name: String) =
        WorkspaceRef(
            id = name,
            name = name,
            path = "/workspace/$name",
        )

    private fun session(
        id: String,
        title: String,
        directory: String?,
        isPinned: Boolean = false,
        hasAttention: Boolean = false,
    ) = DrawerRecentSession(
        id = id,
        title = title,
        relativeTime = "now",
        directory = directory,
        isPinned = isPinned,
        hasAttention = hasAttention,
    )

    private fun ref(
        id: String,
        parentId: String? = null,
        updated: Long = 1_000L,
    ) = RuntimeSessionRef(
        runtimeId = "local",
        agent = null,
        session =
            OpenCodeSession(
                id = id,
                parentId = parentId,
                title = id,
                directory = "/workspace/$id",
                time = OpenCodeTime(created = updated, updated = updated),
            ),
    )
}
