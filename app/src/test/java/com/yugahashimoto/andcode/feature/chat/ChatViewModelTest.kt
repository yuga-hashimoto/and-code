package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
    fun `sending blank input does nothing`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("   ")
            advanceUntilIdle()

            assertEquals(0, backend.createSessionCalls)
            assertEquals(0, backend.sentPrompts.size)
            assertTrue(viewModel.uiState.value.messages.isEmpty())
        }

    @Test
    fun `sending text creates a session and shows user message immediately`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            assertEquals(1, backend.createSessionCalls)
            assertEquals("s1", viewModel.uiState.value.sessionId)
            assertEquals("Hello", viewModel.uiState.value.messages.single().text)
            assertTrue(viewModel.uiState.value.messages.single().isUser)
            assertEquals("Hello", backend.sentPrompts.single().second.text)
        }

    @Test
    fun `recovers connection after a transient send failure once the runtime is reachable again`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            backend.failCreateSession = true

            viewModel.sendMessage("Hello")
            runCurrent()

            assertEquals(
                ChatErrorKind.TRANSIENT_CONNECTION,
                classifyChatError(viewModel.uiState.value.error),
            )

            backend.failCreateSession = false
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun `keeps retrying recovery while the runtime stays unreachable`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            backend.failCreateSession = true
            backend.healthFailuresRemaining = 2

            viewModel.sendMessage("Hello")

            // Initial probe fails; the reconnecting card stays up and another probe is scheduled.
            advanceTimeBy(TRANSIENT_RECOVERY_DELAY_MS)
            assertEquals(
                ChatErrorKind.TRANSIENT_CONNECTION,
                classifyChatError(viewModel.uiState.value.error),
            )

            // Second probe fails too, still no recovery and no error masking.
            advanceTimeBy(TRANSIENT_RECOVERY_RETRY_DELAY_MS)
            assertEquals(
                ChatErrorKind.TRANSIENT_CONNECTION,
                classifyChatError(viewModel.uiState.value.error),
            )

            // Runtime comes back; the next probe succeeds and clears the error.
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun `sending attachments without text creates a prompt`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.addAttachment(PromptAttachment("photo.jpg", "image/jpeg", "data:image/jpeg;base64,/9j/4AAQ"))

            viewModel.sendMessage("")
            advanceUntilIdle()

            assertEquals(1, backend.sentPrompts.size)
            assertEquals("photo.jpg", backend.sentPrompts.single().second.attachments.single().filename)
        }

    @Test
    fun `creating a session refreshes the shell catalog`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            var refreshes = 0
            val viewModel = ChatViewModel(backend, onSessionCreated = { refreshes++ })
            advanceUntilIdle()

            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            assertEquals(1, refreshes)
        }

    @Test
    fun `selected workspace is used when creating a new session`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.selectWorkspace("/root/demo")
            viewModel.sendMessage("Work here")
            advanceUntilIdle()

            assertEquals("/root/demo", backend.lastCreateDirectory)
            assertEquals("/root/demo", viewModel.uiState.value.selectedWorkspacePath)
        }

    @Test
    fun `slash command for a known backend command is executed as a command`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("/changelog v1.0")
            advanceUntilIdle()

            assertEquals(0, backend.sentPrompts.size)
            assertEquals(1, backend.executedCommands.size)
            assertEquals("changelog", backend.executedCommands.single().command)
            assertEquals("v1.0", backend.executedCommands.single().arguments)
            assertEquals(1, backend.createSessionCalls)
            assertEquals("/changelog v1.0", viewModel.uiState.value.messages.single().text)
        }

    @Test
    fun `slash command for a known skill is executed as a command`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("/git-release")
            advanceUntilIdle()

            assertEquals(0, backend.sentPrompts.size)
            assertEquals(1, backend.executedCommands.size)
            assertEquals("git-release", backend.executedCommands.single().command)
            assertEquals("", backend.executedCommands.single().arguments)
        }

    @Test
    fun `slash command that is not known is sent as a plain prompt`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            assertEquals(1, backend.sentPrompts.size)
            assertEquals("/nonexistent", backend.sentPrompts.single().second.text)
            assertEquals(0, backend.executedCommands.size)
        }

    @Test
    fun `refresh slash catalog loads commands and skills`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.refreshSlashCatalog()
            advanceUntilIdle()

            assertEquals(listOf("changelog"), viewModel.uiState.value.slashCommands.map { it.name })
            assertEquals(listOf("git-release"), viewModel.uiState.value.slashSkills.map { it.name })
        }

    @Test
    fun `slash suggestions merge app commands with backend commands and skills`() {
        val suggestions =
            SlashCommandRegistry.suggestions(
                query = "/",
                backendCommands = listOf(OpenCodeCommand("commit", "Write commit")),
                backendSkills = listOf(OpenCodeSkill("git-release", "Create a release", "release")),
            )
        val names = suggestions.map { it.name }
        assertTrue("/new" in names)
        assertTrue("/commit" in names)
        assertTrue("/git-release" in names)
        val skill = suggestions.filterIsInstance<SlashSuggestion.Backend>().first { it.name == "/git-release" }
        assertTrue(skill.isSkill)
    }

    @Test
    fun `auto accept approves permissions without showing card`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.setAutoAcceptPermissions(true)
            viewModel.sendMessage("Check git")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = "perm1",
                        sessionId = "s1",
                        permission = "bash",
                        patterns = listOf("git status"),
                    ),
                ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.permissions.isEmpty())
            assertEquals(PermissionResponse.ONCE, backend.permissionResponses.single().third)
        }

    @Test
    fun `auto accept approves subagent permissions from a different session`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.setAutoAcceptPermissions(true)
            viewModel.sendMessage("Delegate work")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = "perm-sub",
                        sessionId = "subagent-session-42",
                        permission = "bash",
                        patterns = listOf("ls"),
                    ),
                ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.permissions.isEmpty())
            val record = backend.permissionResponses.single()
            assertEquals("subagent-session-42", record.sessionId)
            assertEquals("perm-sub", record.permissionId)
            assertEquals(PermissionResponse.ONCE, record.third)
        }

    @Test
    fun `streamed text is finalized when session becomes idle`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "p1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "text",
                        text = "Hi from OpenCode",
                    ),
                ),
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
    fun `polling refreshes changed messages while events are unavailable`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            backend.historyMessages =
                listOf(
                    OpenCodeMessage(
                        info =
                            OpenCodeMessageInfo(
                                id = "m-assistant",
                                sessionId = "s1",
                                role = "assistant",
                                time = OpenCodeTime(created = 2),
                            ),
                        parts =
                            listOf(
                                OpenCodePart(
                                    id = "p1",
                                    sessionId = "s1",
                                    messageId = "m-assistant",
                                    type = "tool",
                                    tool = "bash",
                                    state = mapOf("status" to JsonPrimitive("running")),
                                ),
                            ),
                    ),
                )
            viewModel.sendMessage("Next")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.messages.any { it.parts.any { part -> part is ChatPart.Tool } })
        }

    @Test
    fun `multiple streamed text parts are combined into one assistant message`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "part-1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "text",
                        text = "First paragraph.",
                    ),
                ),
            )
            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "part-2",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "text",
                        text = "\nSecond paragraph.",
                    ),
                ),
            )
            advanceUntilIdle()

            val assistantMessages = viewModel.uiState.value.messages.filterNot { it.isUser }
            assertEquals(1, assistantMessages.size)
            assertEquals("First paragraph.\nSecond paragraph.", assistantMessages.single().text)
        }

    @Test
    fun `tool part transitions from pending to running to completed`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
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
                        state =
                            mapOf(
                                "status" to JsonPrimitive("pending"),
                                "input" to buildJsonObject { put("command", JsonPrimitive("ls -la")) },
                            ),
                    ),
                ),
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
                        state =
                            mapOf(
                                "status" to JsonPrimitive("running"),
                                "input" to buildJsonObject { put("command", JsonPrimitive("ls -la")) },
                            ),
                    ),
                ),
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
                        state =
                            mapOf(
                                "status" to JsonPrimitive("completed"),
                                "input" to buildJsonObject { put("command", JsonPrimitive("ls -la")) },
                                "output" to JsonPrimitive("file1\nfile2"),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()
            toolPart = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Tool
            assertEquals(ToolStatus.COMPLETED, toolPart.status)
            assertEquals("file1\nfile2", toolPart.output)
        }

    @Test
    fun `reasoning delta appends to existing reasoning part`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Think about it")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "reason-1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "reasoning",
                        text = "Thinking",
                    ),
                ),
            )
            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m-assistant",
                    partId = "reason-1",
                    field = "text",
                    delta = " more.",
                ),
            )
            advanceUntilIdle()

            val reasoning = viewModel.uiState.value.messages.last().parts.single() as ChatPart.Reasoning
            assertEquals("Thinking more.", reasoning.text)
        }

    @Test
    fun `mixed order parts are preserved in arrival order`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Do work")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "reason-1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "reasoning",
                        text = "Planning",
                    ),
                ),
            )
            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "tool-1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "tool",
                        tool = "bash",
                        state = mapOf("status" to JsonPrimitive("running")),
                    ),
                ),
            )
            backend.events.emit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = "text-1",
                        sessionId = "s1",
                        messageId = "m-assistant",
                        type = "text",
                        text = "Done.",
                    ),
                ),
            )
            advanceUntilIdle()

            val parts = viewModel.uiState.value.messages.last().parts
            assertEquals(3, parts.size)
            assertTrue(parts[0] is ChatPart.Reasoning)
            assertTrue(parts[1] is ChatPart.Tool)
            assertTrue(parts[2] is ChatPart.Text)
        }

    @Test
    fun `permission event becomes approval card and successful response removes it`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Check git")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = "perm1",
                        sessionId = "s1",
                        permission = "bash",
                        patterns = listOf("git status"),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("perm1", viewModel.uiState.value.permissions.single().id)

            viewModel.respondToPermission("perm1", PermissionResponse.ONCE, remember = false)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.permissions.isEmpty())
            assertEquals(PermissionResponse.ONCE, backend.permissionResponses.single().third)
        }

    @Test
    fun `permission answered from the notification removes the chat card`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val resolved = MutableSharedFlow<String>(extraBufferCapacity = 8)
            val viewModel = ChatViewModel(backend, resolvedPermissionFlow = resolved)
            advanceUntilIdle()
            viewModel.sendMessage("Check git")
            advanceUntilIdle()

            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = "perm1",
                        sessionId = "s1",
                        permission = "bash",
                        patterns = listOf("git status"),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("perm1", viewModel.uiState.value.permissions.single().id)

            // OpenCode emits no "permission.replied" event, so the notification receiver
            // announces the resolution through this flow instead.
            resolved.emit("perm1")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.permissions.isEmpty())
            assertTrue(backend.permissionResponses.isEmpty())
        }

    @Test
    fun `abort reports the session as aborted so its trailing idle does not notify`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val aborted = mutableListOf<String>()
            val viewModel = ChatViewModel(backend, onSessionAborted = { aborted += it })
            advanceUntilIdle()
            viewModel.sendMessage("Do things")
            advanceUntilIdle()

            viewModel.abort()
            advanceUntilIdle()

            assertEquals(listOf("s1"), aborted)
        }

    @Test
    fun `history load maps tool parts alongside text parts`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            backend.historyMessages =
                listOf(
                    OpenCodeMessage(
                        info =
                            OpenCodeMessageInfo(
                                id = "hist-1",
                                sessionId = "s1",
                                role = "assistant",
                                time = OpenCodeTime(created = 1),
                            ),
                        parts =
                            listOf(
                                OpenCodePart(
                                    id = "p-tool",
                                    sessionId = "s1",
                                    messageId = "hist-1",
                                    type = "tool",
                                    tool = "bash",
                                    state = mapOf("status" to JsonPrimitive("completed"), "output" to JsonPrimitive("ok")),
                                ),
                                OpenCodePart(
                                    id = "p-text",
                                    sessionId = "s1",
                                    messageId = "hist-1",
                                    type = "text",
                                    text = "Here is the result.",
                                ),
                            ),
                    ),
                )
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.openSession("s1")
            advanceUntilIdle()

            val message = viewModel.uiState.value.messages.single()
            assertEquals(2, message.parts.size)
            assertTrue(message.parts[0] is ChatPart.Tool)
            assertTrue(message.parts[1] is ChatPart.Text)
            assertEquals("Here is the result.", message.text)
        }

    @Test
    fun `opening a session restores the model used by its messages`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            backend.historyMessages =
                listOf(
                    OpenCodeMessage(
                        info =
                            OpenCodeMessageInfo(
                                id = "hist-model",
                                sessionId = "s1",
                                role = "assistant",
                                model =
                                    com.yugahashimoto.andcode.core.api.OpenCodeModelReference(
                                        providerId = "provider-from-history",
                                        modelId = "model-from-history",
                                    ),
                                time = OpenCodeTime(created = 1),
                            ),
                        parts =
                            listOf(
                                OpenCodePart(
                                    id = "p1",
                                    sessionId = "s1",
                                    messageId = "hist-model",
                                    type = "text",
                                    text = "Done",
                                ),
                            ),
                    ),
                )
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.openSession("s1")
            advanceUntilIdle()

            assertEquals("provider-from-history", viewModel.uiState.value.selectedProviderId)
            assertEquals("model-from-history", viewModel.uiState.value.selectedModelId)
        }

    @Test
    fun `abort stops current session and clears running state`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Long task")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRunning)

            viewModel.abort()
            advanceUntilIdle()

            assertEquals(listOf("s1"), backend.abortedSessions)
            assertFalse(viewModel.uiState.value.isRunning)
        }

    /**
     * Antigravity runs each turn as a one-shot process (see AntigravityRuntime.send); the default
     * "interrupt" send behavior would kill that process mid-turn, which surfaced to the user as
     * "agy exited with 137" instead of a clean cancellation. RuntimeCapabilities.forcesQueue makes
     * such a runtime behave like the "queue" setting regardless of what sendBehavior is set to, so a
     * message sent while one is running waits instead of interrupting it.
     */
    @Test
    fun `a runtime that forces queue never interrupts a running turn`() =
        runTest(dispatcher) {
            val backend = FakeQueueForcingBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("first")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRunning)

            viewModel.sendMessage("second")
            advanceUntilIdle()

            // Still running, and the second message was queued rather than sent - it must not have
            // interrupted (killed) the first turn's process.
            assertTrue(viewModel.uiState.value.isRunning)
            assertEquals(listOf("first"), backend.sentPrompts.map { it.second.text })

            // The turn finishing drains the queue and sends the second message for real.
            backend.events.tryEmit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()

            assertEquals(listOf("first", "second"), backend.sentPrompts.map { it.second.text })
        }

    @Test
    fun `switching sessions mid-poll does not corrupt the newly opened session`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("Hello")
            // Advance only up to the poll loop's first delay() — session s1's background poll is
            // now parked mid-flight, about to fetch s1's messages once time moves forward.
            runCurrent()

            backend.historyMessagesBySession =
                mapOf(
                    "s1" to listOf(sessionAssistantMessage("s1", "m-s1", "Stale reply for the old chat")),
                    "s2" to listOf(sessionAssistantMessage("s2", "m-s2", "Reply for the other chat")),
                )
            viewModel.openSession("s2", "Other chat")
            runCurrent()

            // Let s1's stale poll loop run its course; its updates must all be no-ops now that the
            // screen has moved on to s2.
            advanceUntilIdle()

            assertEquals("s2", viewModel.uiState.value.sessionId)
            assertEquals(
                listOf("Reply for the other chat"),
                viewModel.uiState.value.messages.map { it.text },
            )
        }

    @Test
    fun `opening a different session while the previous one is running clears the stop button`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.sendMessage("Long task")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRunning)

            viewModel.openSession("s2", "Another chat")

            assertFalse(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `opening a subagent session remembers the session to return to`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Delegate this")
            advanceUntilIdle()

            viewModel.openSubagentSession("child-1", "Investigate the bug")
            advanceUntilIdle()

            assertEquals("child-1", viewModel.uiState.value.sessionId)
            assertEquals(ParentSessionRef("s1", ""), viewModel.uiState.value.parentSession)

            viewModel.openParentSession()
            advanceUntilIdle()

            assertEquals("s1", viewModel.uiState.value.sessionId)
            assertEquals("", viewModel.uiState.value.sessionTitle)
            assertNull(viewModel.uiState.value.parentSession)
        }

    @Test
    fun `session opened without a known parent resolves it from the backend`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            backend.sessionsById =
                mapOf(
                    "main" to OpenCodeSession(id = "main", title = "Main chat"),
                    "child-1" to
                        OpenCodeSession(
                            id = "child-1",
                            title = "Investigate the bug",
                            parentId = "main",
                        ),
                )
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.openSession("child-1", "Investigate the bug")
            advanceUntilIdle()

            assertEquals(ParentSessionRef("main", "Main chat"), viewModel.uiState.value.parentSession)
        }

    @Test
    fun `main session opened from history has no return target`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            backend.sessionsById = mapOf("main" to OpenCodeSession(id = "main", title = "Main chat"))
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()

            viewModel.openSession("main", "Main chat")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.parentSession)
        }

    @Test
    fun `starting a new chat clears the subagent return target`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)
            advanceUntilIdle()
            viewModel.sendMessage("Delegate this")
            advanceUntilIdle()
            viewModel.openSubagentSession("child-1", "Investigate the bug")
            advanceUntilIdle()

            viewModel.newSession()

            assertNull(viewModel.uiState.value.sessionId)
            assertNull(viewModel.uiState.value.parentSession)
        }

    private fun sessionAssistantMessage(
        sessionId: String,
        messageId: String,
        text: String,
    ) = OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = messageId,
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = 1),
            ),
        parts =
            listOf(
                OpenCodePart(
                    id = "$messageId-p",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = text,
                ),
            ),
    )

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var createSessionCalls = 0
        var lastCreateDirectory: String? = null
        var historyMessages: List<OpenCodeMessage> = emptyList()
        var historyMessagesBySession: Map<String, List<OpenCodeMessage>> = emptyMap()
        var sessionsById: Map<String, OpenCodeSession> = emptyMap()
        val sentPrompts = mutableListOf<Pair<String, PromptRequest>>()
        val permissionResponses = mutableListOf<PermissionRecord>()
        val abortedSessions = mutableListOf<String>()
        var healthFailuresRemaining = 0
        var failCreateSession = false
        val commands = mutableListOf(OpenCodeCommand("changelog", "Draft changelog", "Write the changelog for the release"))
        val skills = mutableListOf(OpenCodeSkill("git-release", "Create a release", "release"))
        val executedCommands = mutableListOf<CommandCall>()

        override suspend fun health(): OpenCodeHealth {
            if (healthFailuresRemaining > 0) {
                healthFailuresRemaining--
                throw IOException("connection refused")
            }
            return OpenCodeHealth(true, "test")
        }

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = sessionsById.values.toList()

        override suspend fun session(sessionId: String): OpenCodeSession =
            sessionsById[sessionId] ?: throw IllegalArgumentException("no session $sessionId")

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession {
            if (failCreateSession) throw IOException("connection refused")
            createSessionCalls++
            lastCreateDirectory = directory
            return OpenCodeSession(
                id = "s1",
                title = title ?: "",
                directory = directory,
                time = OpenCodeTime(created = 1),
            )
        }

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = historyMessagesBySession[sessionId] ?: historyMessages

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun commands(): List<OpenCodeCommand> = commands

        override suspend fun skills(): List<OpenCodeSkill> = skills

        override suspend fun executeCommand(
            sessionId: String,
            command: String,
            arguments: String,
        ) {
            executedCommands += CommandCall(sessionId, command, arguments)
        }

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) {
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
            remember: Boolean,
        ): Boolean {
            permissionResponses += PermissionRecord(sessionId, permissionId, response, remember)
            return true
        }

        override fun events(): Flow<OpenCodeEvent> = events
    }

    /**
     * A [RuntimeTarget] whose [RuntimeCapabilities.forcesQueue] is set - the same shape
     * [com.yugahashimoto.andcode.runtime.local.AntigravityTarget] exposes - to verify the chat
     * ViewModel queues a send instead of interrupting a running turn for such a backend, independent
     * of whatever the user's sendBehavior preference is set to.
     */
    private class FakeQueueForcingBackend : RuntimeTarget {
        override val id: String = "fake-queue-forcing"
        override val displayName: String = "Fake queue-forcing"
        override val kind: BackendKind = BackendKind.LOCAL
        override val type: RuntimeType = RuntimeType.LOCAL
        override val agent: LocalAgent? = null
        override val capabilities: RuntimeCapabilities = RuntimeCapabilities(forcesQueue = true)
        override val state: StateFlow<RuntimeState> = MutableStateFlow<RuntimeState>(RuntimeState.Connected("test")).asStateFlow()
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        val sentPrompts = mutableListOf<Pair<String, PromptRequest>>()

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "test"))

        override fun disconnect() = Unit

        override suspend fun listWorkspaces(): List<com.yugahashimoto.andcode.runtime.WorkspaceRef> = emptyList()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "s1", title = title ?: "", directory = directory, time = OpenCodeTime(created = 1))

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) {
            sentPrompts += sessionId to request
        }

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> = events
    }

    private data class PermissionRecord(
        val sessionId: String,
        val permissionId: String,
        val third: PermissionResponse,
        val remember: Boolean,
    )

    private data class CommandCall(
        val sessionId: String,
        val command: String,
        val arguments: String,
    )
}
