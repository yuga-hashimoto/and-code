package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.ui.components.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun session(
        id: String,
        title: String,
        directory: String?,
        isPinned: Boolean = false,
    ) = DrawerRecentSession(
        id = id,
        title = title,
        relativeTime = "now",
        directory = directory,
        isPinned = isPinned,
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
