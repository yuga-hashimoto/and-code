package com.opencode.android.feature.chat

import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodeMessageInfo
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.core.api.OpenCodeTime
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.core.api.PromptRequest
import com.opencode.android.core.api.ProviderCatalog
import com.opencode.android.runtime.BackendKind
import com.opencode.android.runtime.OpenCodeBackend
import com.opencode.android.runtime.PermissionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
    fun `sending blank input does nothing`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)

        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(0, backend.createSessionCalls)
        assertEquals(0, backend.sentPrompts.size)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun `sending text creates a session and shows user message immediately`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertEquals(1, backend.createSessionCalls)
        assertEquals("s1", viewModel.uiState.value.sessionId)
        assertEquals("Hello", viewModel.uiState.value.messages.single().text)
        assertTrue(viewModel.uiState.value.messages.single().isUser)
        assertEquals("Hello", backend.sentPrompts.single().second.text)
    }

    @Test
    fun `selected workspace is used when creating a new session`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)

        viewModel.selectWorkspace("/root/demo")
        viewModel.sendMessage("Work here")
        advanceUntilIdle()

        assertEquals("/root/demo", backend.lastCreateDirectory)
        assertEquals("/root/demo", viewModel.uiState.value.selectedWorkspacePath)
    }

    @Test
    fun `streamed text is finalized when session becomes idle`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "p1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "text",
                    text = "Hi from OpenCode"
                )
            )
        )
        advanceUntilIdle()

        val streaming = viewModel.uiState.value.messages.last()
        assertFalse(streaming.isUser)
        assertTrue(streaming.isStreaming)
        assertEquals("Hi from OpenCode", streaming.text)

        backend.events.emit(OpenCodeEvent.SessionIdle("s1"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.messages.last().isStreaming)
        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun `multiple streamed text parts are combined into one assistant message`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "part-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "text",
                    text = "First paragraph."
                )
            )
        )
        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "part-2",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "text",
                    text = "\nSecond paragraph."
                )
            )
        )
        advanceUntilIdle()

        val assistantMessages = viewModel.uiState.value.messages.filterNot { it.isUser }
        assertEquals(1, assistantMessages.size)
        assertEquals("First paragraph.\nSecond paragraph.", assistantMessages.single().text)
    }

    @Test
    fun `tool part transitions from pending to running to completed`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Run a command")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "tool-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "tool",
                    tool = "bash",
                    state = mapOf("status" to "pending", "input" to mapOf("command" to "ls -la"))
                )
            )
        )
        advanceUntilIdle()
        var toolPart = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Tool
        assertEquals(ToolStatus.PENDING, toolPart.status)
        assertEquals("ls -la", toolPart.input)

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "tool-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "tool",
                    tool = "bash",
                    state = mapOf("status" to "running", "input" to mapOf("command" to "ls -la"))
                )
            )
        )
        advanceUntilIdle()
        toolPart = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Tool
        assertEquals(ToolStatus.RUNNING, toolPart.status)

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "tool-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "tool",
                    tool = "bash",
                    state = mapOf(
                        "status" to "completed",
                        "input" to mapOf("command" to "ls -la"),
                        "output" to "file1\nfile2"
                    )
                )
            )
        )
        advanceUntilIdle()
        toolPart = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Tool
        assertEquals(ToolStatus.COMPLETED, toolPart.status)
        assertEquals("file1\nfile2", toolPart.output)
    }

    @Test
    fun `reasoning delta appends to existing reasoning part`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Think about it")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "reason-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "reasoning",
                    text = "Thinking"
                )
            )
        )
        backend.events.emit(
            OpenCodeEvent.MessagePartDelta(
                sessionId = "s1",
                messageId = "m-assistant",
                partId = "reason-1",
                field = "text",
                delta = " more."
            )
        )
        advanceUntilIdle()

        val reasoning = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Reasoning
        assertEquals("Thinking more.", reasoning.text)
    }

    @Test
    fun `mixed order parts are preserved in arrival order`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Do work")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "reason-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "reasoning",
                    text = "Planning"
                )
            )
        )
        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "tool-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "tool",
                    tool = "bash",
                    state = mapOf("status" to "running")
                )
            )
        )
        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "text-1",
                    sessionId = "s1",
                    messageId = "m-assistant",
                    type = "text",
                    text = "Done."
                )
            )
        )
        advanceUntilIdle()

        val parts = viewModel.uiState.value.messages.last().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is ChatPart.Reasoning)
        assertTrue(parts[1] is ChatPart.Tool)
        assertTrue(parts[2] is ChatPart.Text)
    }

    @Test
    fun `permission event becomes approval card and successful response removes it`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Check git")
        advanceUntilIdle()

        backend.events.emit(
            OpenCodeEvent.PermissionAsked(
                PermissionRequest(
                    id = "perm1",
                    sessionId = "s1",
                    permission = "bash",
                    patterns = listOf("git status")
                )
            )
        )
        advanceUntilIdle()
        assertEquals("perm1", viewModel.uiState.value.permissions.single().id)

        viewModel.respondToPermission("perm1", PermissionResponse.ONCE, remember = false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.permissions.isEmpty())
        assertEquals(PermissionResponse.ONCE, backend.permissionResponses.single().third)
    }

    @Test
    fun `history load maps tool parts alongside text parts`() = runTest(dispatcher) {
        val backend = FakeBackend()
        backend.historyMessages = listOf(
            OpenCodeMessage(
                info = OpenCodeMessageInfo(
                    id = "hist-1",
                    sessionId = "s1",
                    role = "assistant",
                    time = OpenCodeTime(created = 1)
                ),
                parts = listOf(
                    OpenCodePart(
                        id = "p-tool",
                        sessionId = "s1",
                        messageId = "hist-1",
                        type = "tool",
                        tool = "bash",
                        state = mapOf("status" to "completed", "output" to "ok")
                    ),
                    OpenCodePart(
                        id = "p-text",
                        sessionId = "s1",
                        messageId = "hist-1",
                        type = "text",
                        text = "Here is the result."
                    )
                )
            )
        )
        val viewModel = ChatViewModel(backend)

        viewModel.openSession("s1")
        advanceUntilIdle()

        val message = viewModel.uiState.value.messages.single()
        assertEquals(2, message.parts.size)
        assertTrue(message.parts[0] is ChatPart.Tool)
        assertTrue(message.parts[1] is ChatPart.Text)
        assertEquals("Here is the result.", message.text)
    }

    @Test
    fun `abort stops current session and clears running state`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend)
        viewModel.sendMessage("Long task")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.abort()
        advanceUntilIdle()

        assertEquals(listOf("s1"), backend.abortedSessions)
        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun `assistant progress is polled in even when no stream events arrive`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)

        viewModel.sendMessage("Hello")
        runCurrent()
        assertTrue(viewModel.uiState.value.isRunning)

        // Nothing is emitted on the event stream at all — the chat must still catch up.
        backend.historyMessages = listOf(
            userMessage("m-user", "Hello"),
            assistantMessage("m-assistant", "Working on it", completed = null)
        )
        advanceTimeBy(POLL_MILLIS + 1)
        runCurrent()

        val assistant = viewModel.uiState.value.messages.last()
        assertFalse(assistant.isUser)
        assertEquals("Working on it", assistant.text)
        assertTrue(assistant.isStreaming)
        assertTrue(viewModel.uiState.value.isRunning)
    }

    @Test
    fun `run ends when the server reports completion without a session idle event`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)

        viewModel.sendMessage("Hello")
        runCurrent()

        backend.historyMessages = listOf(
            userMessage("m-user", "Hello"),
            assistantMessage("m-assistant", "All done", completed = 42L)
        )
        advanceTimeBy(POLL_MILLIS + 1)
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isRunning)
        assertFalse(state.isThinking)
        assertFalse(state.messages.last().isStreaming)
        assertEquals("All done", state.messages.last().text)
    }

    @Test
    fun `polled history replaces the optimistic user bubble instead of duplicating it`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)

        viewModel.sendMessage("Hello")
        runCurrent()

        backend.historyMessages = listOf(
            userMessage("m-user", "Hello"),
            assistantMessage("m-assistant", "Hi", completed = 42L)
        )
        advanceTimeBy(POLL_MILLIS + 1)
        runCurrent()

        val userMessages = viewModel.uiState.value.messages.filter { it.isUser }
        assertEquals(1, userMessages.size)
        assertEquals("Hello", userMessages.single().text)
    }

    @Test
    fun `a delta arriving before its part is still rendered`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)
        viewModel.sendMessage("Hello")
        runCurrent()

        // Mirrors a stream that reconnected midway through a message: the part.updated event
        // that would have created this part was delivered while we were disconnected.
        backend.events.emit(
            OpenCodeEvent.MessagePartDelta(
                sessionId = "s1",
                messageId = "m-assistant",
                partId = "part-1",
                field = "text",
                delta = "recovered text"
            )
        )
        runCurrent()

        assertEquals("recovered text", viewModel.uiState.value.messages.last().text)
    }

    @Test
    fun `parts of the user message are not echoed back as an assistant bubble`() = runTest(dispatcher) {
        val backend = FakeBackend()
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)
        viewModel.sendMessage("Hello")
        runCurrent()

        backend.events.emit(
            OpenCodeEvent.MessageUpdated(
                OpenCodeMessageInfo(id = "m-user", sessionId = "s1", role = "user")
            )
        )
        backend.events.emit(
            OpenCodeEvent.MessagePartUpdated(
                OpenCodePart(
                    id = "p-user",
                    sessionId = "s1",
                    messageId = "m-user",
                    type = "text",
                    text = "Hello"
                )
            )
        )
        runCurrent()

        assertEquals(1, viewModel.uiState.value.messages.size)
        assertTrue(viewModel.uiState.value.messages.single().isUser)
    }

    @Test
    fun `opening a session that is still running keeps it live`() = runTest(dispatcher) {
        val backend = FakeBackend()
        backend.historyMessages = listOf(
            userMessage("m-user", "Hello"),
            assistantMessage("m-assistant", "Still thinking", completed = null)
        )
        val viewModel = ChatViewModel(backend, syncIntervalMillis = POLL_MILLIS)

        viewModel.openSession("s1")
        runCurrent()

        assertTrue(viewModel.uiState.value.isRunning)
        assertTrue(viewModel.uiState.value.messages.last().isStreaming)

        backend.historyMessages = listOf(
            userMessage("m-user", "Hello"),
            assistantMessage("m-assistant", "Still thinking, and here is the answer", completed = 9L)
        )
        advanceTimeBy(POLL_MILLIS + 1)
        runCurrent()

        assertFalse(viewModel.uiState.value.isRunning)
        assertEquals(
            "Still thinking, and here is the answer",
            viewModel.uiState.value.messages.last().text
        )
    }

    private fun userMessage(id: String, text: String) = OpenCodeMessage(
        info = OpenCodeMessageInfo(
            id = id,
            sessionId = "s1",
            role = "user",
            time = OpenCodeTime(created = 1, completed = 1)
        ),
        parts = listOf(
            OpenCodePart(id = "$id-p", sessionId = "s1", messageId = id, type = "text", text = text)
        )
    )

    private fun assistantMessage(id: String, text: String, completed: Long?) = OpenCodeMessage(
        info = OpenCodeMessageInfo(
            id = id,
            sessionId = "s1",
            role = "assistant",
            time = OpenCodeTime(created = 2, completed = completed)
        ),
        parts = listOf(
            OpenCodePart(id = "$id-p", sessionId = "s1", messageId = id, type = "text", text = text)
        )
    )

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var createSessionCalls = 0
        var lastCreateDirectory: String? = null
        var historyMessages: List<OpenCodeMessage> = emptyList()
        val sentPrompts = mutableListOf<Pair<String, PromptRequest>>()
        val permissionResponses = mutableListOf<PermissionRecord>()
        val abortedSessions = mutableListOf<String>()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")
        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()
        override suspend fun createSession(title: String?, directory: String?): OpenCodeSession {
            createSessionCalls++
            lastCreateDirectory = directory
            return OpenCodeSession(
                id = "s1",
                title = title ?: "",
                directory = directory,
                time = OpenCodeTime(created = 1)
            )
        }
        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = historyMessages
        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()
        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()
        override suspend fun sendMessage(sessionId: String, request: PromptRequest) {
            sentPrompts += sessionId to request
        }
        override suspend fun abortSession(sessionId: String): Boolean {
            abortedSessions += sessionId
            return true
        }
        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean
        ): Boolean {
            permissionResponses += PermissionRecord(sessionId, permissionId, response, remember)
            return true
        }
        override fun events(): Flow<OpenCodeEvent> = events
    }

    private companion object {
        const val POLL_MILLIS = 100L
    }

    private data class PermissionRecord(
        val sessionId: String,
        val permissionId: String,
        val third: PermissionResponse,
        val remember: Boolean
    )
}
