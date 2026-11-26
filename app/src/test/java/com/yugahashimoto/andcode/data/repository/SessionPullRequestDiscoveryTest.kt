package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PullRequestRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPullRequestDiscoveryTest {
    @Test
    fun `scans the transcripts the drawer is about to show and records what they linked`() =
        runTest {
            val loads = mutableListOf<String>()
            val discovered = mutableMapOf<String, List<PullRequestRef>>()
            val discovery =
                discovery(
                    loads = loads,
                    onDiscovered = { id, refs -> discovered[id] = refs },
                )

            discovery.discover(listOf(sessionRef("s1"), sessionRef("s2")))
            advanceUntilIdle()

            assertEquals(listOf("s1", "s2"), loads)
            assertEquals(mapOf("s1" to listOf(PullRequestRef("o", "r", 7))), discovered)
        }

    @Test
    fun `does not rescan a chat whose transcript has not changed`() =
        runTest {
            val loads = mutableListOf<String>()
            val discovery = discovery(loads = loads)

            discovery.discover(listOf(sessionRef("s1", updated = 10)))
            advanceUntilIdle()
            discovery.discover(listOf(sessionRef("s1", updated = 10)))
            advanceUntilIdle()

            assertEquals(listOf("s1"), loads)
        }

    @Test
    fun `rescans a chat after a newer turn changed its transcript`() =
        runTest {
            val loads = mutableListOf<String>()
            val discovery = discovery(loads = loads)

            discovery.discover(listOf(sessionRef("s1", updated = 10)))
            advanceUntilIdle()
            discovery.discover(listOf(sessionRef("s1", updated = 11)))
            advanceUntilIdle()

            assertEquals(listOf("s1", "s1"), loads)
        }

    @Test
    fun `skips the chat that is open because its view model already tracks it`() =
        runTest {
            val loads = mutableListOf<String>()
            val discovery = discovery(loads = loads)

            discovery.discover(listOf(sessionRef("s1")), openSessionId = "s1")
            advanceUntilIdle()

            assertTrue(loads.isEmpty())
        }

    @Test
    fun `skips subagent transcripts that are shown under their parent`() =
        runTest {
            val loads = mutableListOf<String>()
            val discovery = discovery(loads = loads)

            discovery.discover(listOf(sessionRef("child", parentId = "parent")))
            advanceUntilIdle()

            assertTrue(loads.isEmpty())
        }

    @Test
    fun `retries a failed read only after the cooldown`() =
        runTest {
            var now = 1_000L
            var fail = true
            val loads = mutableListOf<String>()
            val discovery =
                discovery(
                    clock = { now },
                    loads = loads,
                    load = { ref ->
                        if (fail) error("offline") else messagesFor(ref.session.id)
                    },
                )

            discovery.discover(listOf(sessionRef("s1", updated = 1)))
            advanceUntilIdle()
            fail = false
            now += 30_000L
            discovery.discover(listOf(sessionRef("s1", updated = 1)))
            advanceUntilIdle()

            assertEquals(listOf("s1"), loads)

            now += 31_000L
            discovery.discover(listOf(sessionRef("s1", updated = 1)))
            advanceUntilIdle()

            assertEquals(listOf("s1", "s1"), loads)
        }

    private fun TestScope.discovery(
        clock: () -> Long = { 0L },
        loads: MutableList<String> = mutableListOf(),
        load: suspend (RuntimeSessionRef) -> List<OpenCodeMessage>? = { messagesFor(it.session.id) },
        onDiscovered: (String, List<PullRequestRef>) -> Unit = { _, _ -> },
    ) = SessionPullRequestDiscovery(
        scope = this,
        clock = clock,
        loadMessages = { ref ->
            loads += ref.session.id
            load(ref)
        },
        onDiscovered = onDiscovered,
    )

    private fun sessionRef(
        id: String,
        updated: Long = 1L,
        parentId: String? = null,
    ) = RuntimeSessionRef(
        runtimeId = "local",
        agent = null,
        session =
            OpenCodeSession(
                id = id,
                parentId = parentId,
                title = id,
                time = OpenCodeTime(created = 1L, updated = updated),
            ),
    )

    private fun messagesFor(sessionId: String): List<OpenCodeMessage> =
        if (sessionId != "s1") {
            emptyList()
        } else {
            listOf(
                OpenCodeMessage(
                    info = OpenCodeMessageInfo(id = "m1", sessionId = sessionId, role = "assistant"),
                    parts =
                        listOf(
                            OpenCodePart(
                                id = "p1",
                                sessionId = sessionId,
                                messageId = "m1",
                                type = "text",
                                text = "Opened https://github.com/o/r/pull/7",
                            ),
                        ),
                ),
            )
        }
}
