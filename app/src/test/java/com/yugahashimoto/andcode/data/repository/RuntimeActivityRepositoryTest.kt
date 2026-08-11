package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeConnectionStore
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeActivityRepositoryTest {
    @Test
    fun `opens events for the selected runtime without waiting for a connected state`() =
        runTest {
            // REST calls work regardless of the runtime's connection flag, so gating the stream on
            // it left runtimes that never reported Connected permanently event-less.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))

            advanceUntilIdle()

            assertEquals(1, target.eventCalls)

            target.eventFlow.emit(OpenCodeEvent.ServerConnected)
            advanceUntilIdle()

            assertEquals("Event connection", repository.state.value.logs.single().title)
        }

    @Test
    fun `retries events after stream failure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target =
                FakeTarget(requireConnected = false).apply {
                    eventFlows += flow { throw IllegalStateException("stream dropped") }
                    eventFlows += eventFlow
                }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    retryDelayMillis = 1L,
                )

            advanceUntilIdle()

            assertEquals(2, target.eventCalls)
            assertEquals("stream dropped", repository.state.value.streamError)

            target.eventFlow.emit(OpenCodeEvent.ServerConnected)
            advanceUntilIdle()

            assertEquals(null, repository.state.value.streamError)
            assertEquals("Event connection", repository.state.value.logs.single().title)
        }

    @Test
    fun `runtime state churn never restarts the event stream`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            RuntimeActivityRepository(registry, TestScope(dispatcher))

            advanceUntilIdle()
            assertEquals(1, target.eventCalls)

            // Health rechecks and short drops must not tear the live stream down; the stream's own
            // retry handles a connection that genuinely died.
            target.state.value = RuntimeState.Connecting
            advanceUntilIdle()
            target.state.value = RuntimeState.Connected("1.18.3")
            advanceUntilIdle()
            target.state.value = RuntimeState.Disconnected
            advanceUntilIdle()

            assertEquals(1, target.eventCalls)
        }

    @Test
    fun `losing the runtime clears activity but keeps unread markers`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            target.state.value = RuntimeState.Connected("1.18.3")
            advanceUntilIdle()

            repository.markSessionRunning("ses_running")
            repository.markSessionFinished("ses_done", unread = true)

            target.state.value = RuntimeState.Disconnected
            advanceUntilIdle()

            assertTrue(repository.state.value.activeSessionIds.isEmpty())
            assertEquals(setOf("ses_done"), repository.state.value.completedSessionIds)
        }

    @Test
    fun `a chat reports its own run so the drawer works without events`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("ses_1")
            assertEquals(setOf("ses_1"), repository.state.value.activeSessionIds)

            repository.markSessionFinished("ses_1", unread = true)
            assertTrue(repository.state.value.activeSessionIds.isEmpty())
            assertEquals(setOf("ses_1"), repository.state.value.completedSessionIds)

            repository.markSessionRead("ses_1")
            assertTrue(repository.state.value.completedSessionIds.isEmpty())
        }

    @Test
    fun `late message events do not resurrect a chat finished locally`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("ses_1")
            repository.markSessionFinished("ses_1", unread = false)
            target.eventFlow.emit(
                OpenCodeEvent.MessageUpdated(
                    OpenCodeMessageInfo(
                        id = "msg_final",
                        sessionId = "ses_1",
                        role = "assistant",
                    ),
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.state.value.activeSessionIds.isEmpty())
        }

    @Test
    fun `a new busy status can reactivate a previously settled chat`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("ses_1")
            repository.markSessionFinished("ses_1", unread = true)
            target.eventFlow.emit(OpenCodeEvent.SessionStatusChanged("ses_1", "busy"))
            advanceUntilIdle()

            assertEquals(setOf("ses_1"), repository.state.value.activeSessionIds)
            assertTrue(repository.state.value.completedSessionIds.isEmpty())
        }

    @Test
    fun `switching runtimes keeps an in-flight session active`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val current = FakeTarget(id = "opencode", requireConnected = false)
            val next = FakeTarget(id = "claude", requireConnected = false)
            current.state.value = RuntimeState.Connected("1.0")
            next.state.value = RuntimeState.Connected("1.0")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = current.id),
                    localTarget = current,
                    additionalTargets = listOf(next),
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("session-in-flight")
            registry.select(next.id)
            runCurrent()

            assertEquals(setOf("session-in-flight"), repository.state.value.activeSessionIds)
            assertTrue(repository.state.value.completedSessionIds.isEmpty())
        }

    @Test
    fun `unread markers are restored from the store on startup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val unread = FakeUnreadStore(setOf("ses_old"))

            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    unreadStore = unread,
                )
            advanceUntilIdle()

            assertEquals(setOf("ses_old"), repository.state.value.completedSessionIds)

            repository.markSessionRead("ses_old")
            assertTrue(unread.unreadSessionIds.isEmpty())
        }

    @Test
    fun `resolving a permission cancels its notification and tells other surfaces`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val cancelled = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    onPermissionResolved = { cancelled += it },
                )
            val observed = mutableListOf<String>()
            val collector =
                launch(dispatcher) {
                    repository.resolvedPermissions.collect { observed += it }
                }
            // runCurrent, not advanceUntilIdle: the event stream retries with a backoff delay
            // forever, so advancing virtual time here would never go idle.
            runCurrent()

            repository.resolvePermission("perm-1")
            runCurrent()

            assertEquals("notification cancel callback", listOf("perm-1"), cancelled)
            assertEquals("resolvedPermissions subscribers", listOf("perm-1"), observed)
            collector.cancel()
        }

    @Test
    fun `a question event raises the notification callback`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val asked = mutableListOf<Pair<QuestionRequest, String>>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onQuestionAsked = { request, _, runtimeId -> asked += request to runtimeId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(
                OpenCodeEvent.QuestionAsked(
                    QuestionRequest(
                        id = "q-1",
                        sessionId = "ses_1",
                        questions = listOf(QuestionPrompt(question = "Which framework?")),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf("q-1"), asked.map { it.first.id })
            // The notification needs the runtime to open the right chat, so it travels with the ask.
            assertEquals(listOf(target.id), asked.map { it.second })
        }

    @Test
    fun `session idle raises the completion callback for top-level sessions`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions = listOf(OpenCodeSession(id = "ses_1", title = "Main"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onSessionIdle = { sessionId, _, _ -> completed += sessionId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_1"))
            advanceUntilIdle()

            assertEquals(listOf("ses_1"), completed)
        }

    @Test
    fun `session idle suppresses the completion callback for subagent sessions`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions =
                listOf(OpenCodeSession(id = "child_1", parentId = "ses_1", title = "Explore"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onSessionIdle = { sessionId, _, _ -> completed += sessionId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("child_1"))
            advanceUntilIdle()

            assertTrue(completed.isEmpty())
        }

    @Test
    fun `session idle without a resolvable session does not notify`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onSessionIdle = { sessionId, _, _ -> completed += sessionId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("unknown"))
            advanceUntilIdle()

            assertTrue(completed.isEmpty())
        }

    @Test
    fun `a session error mutes the completion callback for the trailing idle`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions = listOf(OpenCodeSession(id = "ses_1", title = "Main"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    onSessionIdle = { sessionId, _, _ -> completed += sessionId },
                )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionError("ses_1", "boom"))
            advanceUntilIdle()
            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_1"))
            advanceUntilIdle()

            assertTrue(completed.isEmpty())
            assertTrue(repository.state.value.mutedSessionIds.isEmpty())
        }

    @Test
    fun `a muted session notifies again once a new run starts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions = listOf(OpenCodeSession(id = "ses_1", title = "Main"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onSessionIdle = { sessionId, _, _ -> completed += sessionId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionError("ses_1", "boom"))
            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_1"))
            advanceUntilIdle()
            target.eventFlow.emit(OpenCodeEvent.SessionStatusChanged("ses_1", "busy"))
            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_1"))
            advanceUntilIdle()

            assertEquals(listOf("ses_1"), completed)
        }

    @Test
    fun `markSessionAborted mutes the completion callback for the trailing idle`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions = listOf(OpenCodeSession(id = "ses_1", title = "Main"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    onSessionIdle = { sessionId, _, _ -> completed += sessionId },
                )
            advanceUntilIdle()

            repository.markSessionRunning("ses_1")
            repository.markSessionAborted("ses_1")
            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_1"))
            advanceUntilIdle()

            assertTrue(completed.isEmpty())
            assertTrue(repository.state.value.activeSessionIds.isEmpty())
        }

    @Test
    fun `late tool events after a session error do not resurrect activity`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionError("ses_1", "API disconnected"))
            advanceUntilIdle()
            target.eventFlow.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "tool-1",
                        sessionId = "ses_1",
                        type = "tool",
                        tool = "Bash",
                    ),
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.state.value.activeSessionIds.isEmpty())
        }

    @Test
    fun `subagent activity resurrects a parent settled by local navigation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("ses_parent")
            // The user opened another chat mid-turn; the parent is still blocked on the task tool.
            repository.markSessionFinished("ses_parent", unread = true)
            assertTrue(repository.state.value.activeSessionIds.isEmpty())

            target.eventFlow.emit(
                OpenCodeEvent.SessionCreated(OpenCodeSession(id = "child_1", parentId = "ses_parent")),
            )
            target.eventFlow.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "child_1",
                    messageId = "msg_1",
                    partId = "part_1",
                    field = "text",
                    delta = "searching",
                ),
            )
            advanceUntilIdle()

            // The parent emits nothing while the subagent works, so the child's events must
            // keep it marked running instead of leaving it on the idle/completed dot.
            assertEquals(setOf("ses_parent", "child_1"), repository.state.value.activeSessionIds)
            assertTrue("ses_parent" !in repository.state.value.completedSessionIds)
            assertTrue("ses_parent" !in repository.state.value.settledSessionIds)
        }

    @Test
    fun `subagent events do not resurrect a parent the runtime reported idle`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            target.eventFlow.emit(
                OpenCodeEvent.SessionCreated(OpenCodeSession(id = "child_1", parentId = "ses_parent")),
            )
            // A background subagent outlives its parent's turn; once the runtime declares the
            // parent idle, the child's events must not wind the parent back up.
            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_parent"))
            advanceUntilIdle()
            target.eventFlow.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "child_1",
                    messageId = "msg_1",
                    partId = "part_1",
                    field = "text",
                    delta = "still working",
                ),
            )
            advanceUntilIdle()

            assertTrue("ses_parent" !in repository.state.value.activeSessionIds)
            assertEquals(setOf("child_1"), repository.state.value.activeSessionIds)
        }

    @Test
    fun `subagent going idle keeps the parent running until its own idle`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.state.value = RuntimeState.Connected("1.0")
            target.sessions = listOf(OpenCodeSession(id = "ses_parent", title = "Main"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                    onSessionIdle = { sessionId, _, _ -> completed += sessionId },
                )
            advanceUntilIdle()

            repository.markSessionRunning("ses_parent")
            target.eventFlow.emit(
                OpenCodeEvent.SessionCreated(OpenCodeSession(id = "child_1", parentId = "ses_parent")),
            )
            target.eventFlow.emit(OpenCodeEvent.SessionStatusChanged("child_1", "busy"))
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("child_1"))
            advanceUntilIdle()

            // The parent resumes its own loop after the task tool returns; only its own idle
            // ends the turn. The subagent's idle also raises no completion notification.
            assertEquals(setOf("ses_parent"), repository.state.value.activeSessionIds)
            assertTrue(completed.isEmpty())

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("ses_parent"))
            advanceUntilIdle()

            assertTrue(repository.state.value.activeSessionIds.isEmpty())
            assertEquals(listOf("ses_parent"), completed)
        }

    @Test
    fun `parent link falls back to the runtime when the creation event was missed`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            target.sessions =
                listOf(OpenCodeSession(id = "child_1", parentId = "ses_parent", title = "Explore"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository = RuntimeActivityRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.markSessionRunning("ses_parent")
            repository.markSessionFinished("ses_parent", unread = false)
            target.eventFlow.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "part_1",
                        sessionId = "child_1",
                        messageId = "msg_1",
                        type = "text",
                        text = "searching",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(setOf("ses_parent", "child_1"), repository.state.value.activeSessionIds)
        }

    @Test
    fun `unresolved subagent parent does not raise a completion callback`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val completed = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onSessionIdle = { sessionId, _, _ -> completed += sessionId },
            )
            advanceUntilIdle()

            target.eventFlow.emit(OpenCodeEvent.SessionIdle("child_1"))
            advanceUntilIdle()

            assertTrue(completed.isEmpty())
        }

    @Test
    fun `a running session that goes quiet is reported once, with a reason`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            // The watchdog polls for as long as a session runs, so the scope it lives in is
            // cancelled by hand at the end rather than drained by runTest's own cleanup.
            val scope = TestScope(dispatcher)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val stalled = mutableListOf<Pair<String, StallReason>>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = scope,
                    onSessionStalled = { sessionId, _, diagnosis, _ -> stalled += sessionId to diagnosis.reason },
                    stallThresholdMillis = 1_000L,
                    stallCheckIntervalMillis = 100L,
                    now = { testScheduler.currentTime },
                )
            try {
                // Only advance in bounded steps: the watchdog polls for as long as a session runs.
                advanceTimeBy(200L)
                runCurrent()

                repository.markSessionRunning("ses_quiet")
                advanceTimeBy(2_000L)
                runCurrent()

                assertEquals(listOf("ses_quiet" to StallReason.NO_OUTPUT), stalled)

                // One dead run is announced once, not on every tick.
                advanceTimeBy(2_000L)
                runCurrent()

                assertEquals(1, stalled.size)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a quiet session whose turn had in fact finished is completed rather than flagged`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val target =
                FakeTarget(requireConnected = false).apply {
                    sessions = listOf(OpenCodeSession(id = "ses_done", title = "Ship it"))
                    messages =
                        listOf(
                            OpenCodeMessage(
                                info =
                                    OpenCodeMessageInfo(
                                        id = "m1",
                                        sessionId = "ses_done",
                                        role = "assistant",
                                        time = OpenCodeTime(created = 1L, completed = 2L),
                                    ),
                            ),
                        )
                }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val stalled = mutableListOf<String>()
            val completed = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = scope,
                    onSessionIdle = { sessionId, _, _ -> completed += sessionId },
                    onSessionStalled = { sessionId, _, _, _ -> stalled += sessionId },
                    stallThresholdMillis = 1_000L,
                    stallCheckIntervalMillis = 100L,
                    now = { testScheduler.currentTime },
                )
            try {
                advanceTimeBy(200L)
                runCurrent()

                repository.markSessionRunning("ses_done")
                advanceTimeBy(2_000L)
                runCurrent()

                assertTrue(stalled.isEmpty())
                assertEquals(listOf("ses_done"), completed)
                assertTrue("ses_done" !in repository.state.value.activeSessionIds)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `events keep a working session off the watchdog`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val stalled = mutableListOf<String>()
            RuntimeActivityRepository(
                registry = registry,
                scope = scope,
                onSessionStalled = { sessionId, _, _, _ -> stalled += sessionId },
                stallThresholdMillis = 1_000L,
                stallCheckIntervalMillis = 100L,
                now = { testScheduler.currentTime },
            )
            try {
                advanceTimeBy(200L)
                runCurrent()

                repeat(6) {
                    target.eventFlow.emit(
                        OpenCodeEvent.MessagePartDelta(
                            sessionId = "ses_busy",
                            messageId = "m1",
                            partId = "p1",
                            field = "text",
                            delta = "still going",
                        ),
                    )
                    advanceTimeBy(400L)
                    runCurrent()
                }

                assertTrue(stalled.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a quiet subagent is not announced on its own`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val target =
                FakeTarget(requireConnected = false).apply {
                    sessions = listOf(OpenCodeSession(id = "child_1", parentId = "ses_parent", title = "Child"))
                }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val stalled = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = scope,
                    onSessionStalled = { sessionId, _, _, _ -> stalled += sessionId },
                    stallThresholdMillis = 1_000L,
                    stallCheckIntervalMillis = 100L,
                    now = { testScheduler.currentTime },
                )
            try {
                advanceTimeBy(200L)
                runCurrent()

                repository.markSessionRunning("child_1")
                advanceTimeBy(2_000L)
                runCurrent()

                // Its parent is wedged on it and is reported instead; two notices for one stall
                // would only be the same news twice.
                assertTrue(stalled.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a diagnosis that throws does not take the event stream down with it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val target = FakeTarget(requireConnected = false)
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = scope,
                    // Posting the notification is the step most likely to throw in the real app.
                    onSessionStalled = { _, _, _, _ -> throw IllegalStateException("notification failed") },
                    stallThresholdMillis = 1_000L,
                    stallCheckIntervalMillis = 100L,
                    now = { testScheduler.currentTime },
                )
            try {
                advanceTimeBy(200L)
                runCurrent()

                repository.markSessionRunning("ses_quiet")
                advanceTimeBy(2_000L)
                runCurrent()

                // The watchdog shares its scope with the event stream, so a thrown diagnosis must
                // not be allowed to cancel the collector alongside it.
                target.eventFlow.emit(OpenCodeEvent.ServerConnected)
                advanceTimeBy(100L)
                runCurrent()

                assertTrue(repository.state.value.logs.any { it.title == "Event connection" })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a parent waiting on a subagent is kept alive by the child's events`() =
        runTest {
            // A parent blocked on the task tool emits nothing of its own for the subagent's whole
            // run, which is exactly what a stalled session looks like from the outside.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val target =
                FakeTarget(requireConnected = false).apply {
                    sessions =
                        listOf(
                            OpenCodeSession(id = "ses_parent", title = "Parent"),
                            OpenCodeSession(id = "child_1", parentId = "ses_parent", title = "Child"),
                        )
                }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = target.id),
                    localTarget = target,
                    remoteFactory = { error("unused") },
                )
            val stalled = mutableListOf<String>()
            val repository =
                RuntimeActivityRepository(
                    registry = registry,
                    scope = scope,
                    onSessionStalled = { sessionId, _, _, _ -> stalled += sessionId },
                    stallThresholdMillis = 1_000L,
                    stallCheckIntervalMillis = 100L,
                    now = { testScheduler.currentTime },
                )
            try {
                advanceTimeBy(200L)
                runCurrent()
                repository.markSessionRunning("ses_parent")

                repeat(6) {
                    target.eventFlow.emit(
                        OpenCodeEvent.MessagePartDelta(
                            sessionId = "child_1",
                            messageId = "m1",
                            partId = "p1",
                            field = "text",
                            delta = "subagent working",
                        ),
                    )
                    advanceTimeBy(400L)
                    runCurrent()
                }

                assertTrue(stalled.isEmpty())
                assertTrue("ses_parent" in repository.state.value.activeSessionIds)
            } finally {
                scope.cancel()
            }
        }

    private class FakeUnreadStore(
        override var unreadSessionIds: Set<String>,
    ) : UnreadSessionStore

    private class FakeStore(
        override var selectedRuntimeId: String?,
    ) : RuntimeConnectionStore {
        override fun connections(): List<ConnectionProfile> = emptyList()

        override fun upsertConnection(profile: ConnectionProfile) = Unit

        override fun deleteConnection(id: String) = Unit
    }

    private class FakeTarget(
        override val id: String = "local-android",
        private val requireConnected: Boolean = true,
    ) : RuntimeTarget {
        override val displayName: String = "このAndroid端末"
        override val kind: BackendKind = BackendKind.LOCAL
        override val type: RuntimeType = RuntimeType.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
        val eventFlow = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 8)
        val eventFlows = ArrayDeque<Flow<OpenCodeEvent>>()
        var eventCalls = 0

        override suspend fun connect(): Result<OpenCodeHealth> = Result.failure(IllegalStateException("not connected"))

        override fun disconnect() = Unit

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        var health: OpenCodeHealth = OpenCodeHealth(healthy = true, version = "test")

        override suspend fun health(): OpenCodeHealth = health

        var sessions: List<OpenCodeSession> = emptyList()

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = sessions

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        var messages: List<OpenCodeMessage> = emptyList()

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = messages

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> {
            if (requireConnected) check(state.value is RuntimeState.Connected) { "runtime is not connected" }
            eventCalls++
            return eventFlows.removeFirstOrNull() ?: eventFlow
        }
    }
}
