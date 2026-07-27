package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

            assertEquals("イベント接続", repository.state.value.logs.single().title)
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
            assertEquals("イベント接続", repository.state.value.logs.single().title)
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
            val asked = mutableListOf<QuestionRequest>()
            RuntimeActivityRepository(
                registry = registry,
                scope = TestScope(dispatcher),
                onQuestionAsked = { request, _ -> asked += request },
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

            assertEquals(listOf("q-1"), asked.map { it.id })
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
        private val requireConnected: Boolean = true,
    ) : RuntimeTarget {
        override val id: String = "local-android"
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

        override suspend fun health(): OpenCodeHealth = error("unused")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

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
