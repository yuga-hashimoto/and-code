package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Covers the answer to "is it still working, or did it die?" for a turn that produces nothing.
 *
 * Every test sends a prompt and then lets the clock run past the post-send poll, which is what
 * leaves a real run with nothing watching it: no events, no polling, and a composer stuck on the
 * stop button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStallTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a run that produces nothing is reported as stalled`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.NO_OUTPUT, stall?.reason)
            assertTrue((stall?.silentForMillis ?: 0L) >= STALL_THRESHOLD_MS)
            assertTrue(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `a quiet run is left alone until the threshold passes`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            advanceTimeBy(STALL_THRESHOLD_MS - 1000L)
            runCurrent()
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `an unreachable runtime is named as the reason`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.healthError = IOException("connection refused")
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.RUNTIME_UNREACHABLE, stall?.reason)
            assertEquals("connection refused", stall?.detail)
        }

    @Test
    fun `a tool left running is named as what the turn is waiting on`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.historyMessages = listOf(assistantMessage(runningTool = "./gradlew test"))
            viewModel.checkForStall()
            advanceUntilIdle()

            assertEquals(StallReason.TOOL_RUNNING, viewModel.uiState.value.stall?.reason)
            assertEquals("./gradlew test", viewModel.uiState.value.stall?.detail)
        }

    @Test
    fun `an unanswered approval is named, and not painted as a failure`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(id = "perm-1", sessionId = "s1", permission = "bash"),
                ),
            )
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.AWAITING_PERMISSION, stall?.reason)
            // The turn is blocked, not dead, so the card stays out of the failure colour.
            assertFalse(stall?.isStopped ?: true)
        }

    @Test
    fun `a turn that finished unheard is settled instead of flagged`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // The idle never arrived, so only the transcript knows the turn is over.
            backend.historyMessages = listOf(assistantMessage(completed = 5L, text = "done"))
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
            assertEquals("done", viewModel.uiState.value.messages.last().text)
        }

    @Test
    fun `a provider failure ends the run and surfaces the provider's message`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.historyMessages =
                listOf(
                    assistantMessage(
                        text = "partial",
                        error =
                            OpenCodeMessageError(
                                name = "ApiError",
                                data = mapOf("message" to JsonPrimitive("rate limited")),
                            ),
                    ),
                )
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
            assertEquals("rate limited", viewModel.uiState.value.error)
        }

    @Test
    fun `progress on the session takes the warning back down`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()
            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m1",
                    partId = "p1",
                    field = "text",
                    delta = "working on it",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `the watchdog reports a quiet run on its own, without being asked`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel =
                ChatViewModel(
                    backend = backend,
                    eventFlow = backend.events,
                    monitorStalls = true,
                    now = { testScheduler.currentTime },
                ).also { advanceUntilIdle() }

            viewModel.sendMessage("Hello")
            // Only bounded steps: the watchdog polls for as long as the turn is marked running.
            advanceTimeBy(STALL_THRESHOLD_MS + STALL_CHECK_INTERVAL_MS)
            runCurrent()

            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            // Having said its piece, it must stop asking every 30 seconds: a wedged run left open
            // would otherwise spend the battery on health checks nobody is waiting for.
            val probesWhenReported = backend.listMessagesCalls
            advanceTimeBy(STALL_CHECK_INTERVAL_MS * 4)
            runCurrent()

            assertEquals(probesWhenReported, backend.listMessagesCalls)

            // The idle ends the turn, which also ends the polling this test must not leave running.
            backend.events.emit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `a session title being rewritten is not mistaken for progress`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // Metadata, not work: a runtime that kept sending these would otherwise hold the
            // watchdog off a genuinely dead turn for good.
            backend.events.emit(
                OpenCodeEvent.SessionUpdated(OpenCodeSession(id = "s1", title = "Renamed")),
            )
            advanceUntilIdle()
            viewModel.checkForStall()
            advanceUntilIdle()

            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)
        }

    @Test
    fun `a run that comes back to life mid-probe is not flagged by the verdict`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // Hold the transcript read open, so the answer arrives after the run has resumed.
            backend.listMessagesDelayMs = 5_000L
            viewModel.checkForStall()
            runCurrent()

            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m1",
                    partId = "p1",
                    field = "text",
                    delta = "back to work",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `stopping the run clears the warning`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()
            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            viewModel.abort()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
        }

    /**
     * The chat under test, reading the virtual clock so the watchdog measures the same time the
     * test advances.
     */
    private fun TestScope.viewModel(backend: FakeBackend): ChatViewModel =
        ChatViewModel(
            backend = backend,
            eventFlow = backend.events,
            now = { testScheduler.currentTime },
        ).also {
            // Let the opening health check land: a chat that has not connected queues prompts
            // offline instead of running them.
            advanceUntilIdle()
        }

    /**
     * Runs the clock past both the stall threshold and the bounded post-send poll, leaving the turn
     * exactly where a real one is abandoned: still marked running, with nothing watching it.
     */
    private fun TestScope.runSilentlyPastThePoll() {
        advanceTimeBy(STALL_THRESHOLD_MS + 1000L)
        runCurrent()
    }

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var historyMessages: List<OpenCodeMessage> = emptyList()
        var healthError: Throwable? = null

        /** Holds a transcript read open, so a test can act while a probe is in flight. */
        var listMessagesDelayMs: Long = 0L
        val abortedSessions = mutableListOf<String>()

        override suspend fun health(): OpenCodeHealth {
            healthError?.let { throw it }
            return OpenCodeHealth(true, "test")
        }

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "s1", title = title ?: "", directory = directory, time = OpenCodeTime(created = 1))

        var listMessagesCalls = 0

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
            listMessagesCalls++
            if (listMessagesDelayMs > 0L) delay(listMessagesDelayMs)
            return historyMessages
        }

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean {
            abortedSessions += sessionId
            return true
        }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> = events
    }

    private fun assistantMessage(
        text: String? = null,
        completed: Long? = null,
        runningTool: String? = null,
        error: OpenCodeMessageError? = null,
    ): OpenCodeMessage =
        OpenCodeMessage(
            info =
                OpenCodeMessageInfo(
                    id = "assistant-1",
                    sessionId = "s1",
                    role = "assistant",
                    time = OpenCodeTime(created = 2L, completed = completed),
                    error = error,
                ),
            parts =
                listOfNotNull(
                    text?.let { OpenCodePart(id = "text-1", type = "text", text = it) },
                    runningTool?.let {
                        OpenCodePart(
                            id = "tool-1",
                            type = "tool",
                            tool = "bash",
                            state = mapOf("status" to JsonPrimitive("running"), "title" to JsonPrimitive(it)),
                        )
                    },
                ),
        )
}
