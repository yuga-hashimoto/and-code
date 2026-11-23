package com.yugahashimoto.andcode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerRemovalsTest {
    @Test
    fun `remove hides the chat immediately`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val offer = CompletableDeferred<Boolean>()
            offers.addLast(offer)
            val removals = drawerRemovals(offers)

            removals.remove("chat", RemovalKind.DELETE)

            assertTrue("chat" in removals.pending.value)
            offer.complete(true)
            advanceUntilIdle()
            assertFalse("chat" in removals.pending.value)
        }

    @Test
    fun `undo clears the pending id without committing`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val offer = CompletableDeferred<Boolean>()
            offers.addLast(offer)
            val commits = mutableListOf<Pair<String, RemovalKind>>()
            val removals = drawerRemovals(offers, commit = { id, kind -> commits += id to kind })

            removals.remove("chat", RemovalKind.DELETE)
            runCurrent()
            offer.complete(true)
            advanceUntilIdle()

            assertFalse("chat" in removals.pending.value)
            assertTrue(commits.isEmpty())
        }

    @Test
    fun `ending the offer commits exactly once with the right kind`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val offer = CompletableDeferred<Boolean>()
            offers.addLast(offer)
            val commits = mutableListOf<Pair<String, RemovalKind>>()
            val removals = drawerRemovals(offers, commit = { id, kind -> commits += id to kind })

            removals.remove("chat", RemovalKind.ARCHIVE)
            runCurrent()
            offer.complete(false)
            advanceUntilIdle()

            assertEquals(listOf("chat" to RemovalKind.ARCHIVE), commits)
            assertFalse("chat" in removals.pending.value)
        }

    @Test
    fun `a second removal works independently while the first offer is open`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val first = CompletableDeferred<Boolean>()
            val second = CompletableDeferred<Boolean>()
            offers.addLast(first)
            offers.addLast(second)
            val commits = mutableListOf<Pair<String, RemovalKind>>()
            val removals = drawerRemovals(offers, commit = { id, kind -> commits += id to kind })

            removals.remove("first", RemovalKind.DELETE)
            runCurrent()
            removals.remove("second", RemovalKind.ARCHIVE)
            runCurrent()
            assertEquals(setOf("first", "second"), removals.pending.value)

            second.complete(false)
            advanceUntilIdle()
            assertEquals(listOf("second" to RemovalKind.ARCHIVE), commits)
            assertTrue("first" in removals.pending.value)

            first.complete(true)
            advanceUntilIdle()
            assertTrue(removals.pending.value.isEmpty())
        }

    @Test
    fun `a failed commit clears the id and reports exactly once`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val offer = CompletableDeferred<Boolean>()
            offers.addLast(offer)
            val failures = mutableListOf<Triple<String, RemovalKind, Throwable>>()
            val removals =
                drawerRemovals(
                    offers,
                    commit = { _, _ -> error("commit failed") },
                    onCommitFailed = { id, kind, error -> failures += Triple(id, kind, error) },
                )

            removals.remove("chat", RemovalKind.ARCHIVE)
            runCurrent()
            offer.complete(false)
            advanceUntilIdle()

            assertTrue(removals.pending.value.isEmpty())
            assertEquals(1, failures.size)
            assertEquals("chat", failures.first().first)
            assertEquals(RemovalKind.ARCHIVE, failures.first().second)
            assertTrue(failures.first().third is IllegalStateException)
        }

    @Test
    fun `cancellation from commit is rethrown rather than reported as a failure`() =
        runTest {
            val offers = ArrayDeque<CompletableDeferred<Boolean>>()
            val offer = CompletableDeferred<Boolean>()
            offers.addLast(offer)
            var failureCount = 0
            val removals =
                drawerRemovals(
                    offers,
                    commit = { _, _ -> throw CancellationException("cancelled") },
                    onCommitFailed = { _, _, _ -> failureCount++ },
                )

            removals.remove("chat", RemovalKind.DELETE)
            runCurrent()
            offer.complete(false)
            advanceUntilIdle()

            assertTrue(removals.pending.value.isEmpty())
            assertEquals(0, failureCount)
        }

    private fun TestScope.drawerRemovals(
        offers: ArrayDeque<CompletableDeferred<Boolean>>,
        commit: suspend (String, RemovalKind) -> Unit = { _, _ -> },
        onCommitFailed: (String, RemovalKind, Throwable) -> Unit = { _, _, _ -> },
    ) = DrawerRemovals(
        scope = this,
        offerUndo = { offers.removeFirst().await() },
        commit = commit,
        onCommitFailed = onCommitFailed,
    )
}
