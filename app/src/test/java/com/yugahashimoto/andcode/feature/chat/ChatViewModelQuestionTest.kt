package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionOption
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class ChatViewModelQuestionTest {
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
    fun `question event is shown only for active session`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()

            backend.events.emit(OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-2")))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
        }

    @Test
    fun `single select answer replaces prior selection`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        options = listOf("src", "docs"),
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.selectQuestionAnswer("q-1", 0, "docs")

            assertEquals(listOf("docs"), viewModel.uiState.value.pendingQuestions.single().selectedAnswers.single())
        }

    @Test
    fun `successful answer removes pending question`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        options = listOf("src", "docs"),
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("src")), backend.answeredQuestions.single().answers)
            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
        }

    @Test
    fun `failed answer keeps question and trims fallback input on submit`() =
        runTest(dispatcher) {
            val backend = FakeBackend(answerResult = false)
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        question = "Type a folder",
                        options = emptyList(),
                        placeholder = "src/main",
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "   src/main   ")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("src/main")), backend.answeredQuestions.single().answers)
            val pending = viewModel.uiState.value.pendingQuestions.single()
            assertEquals(listOf("   src/main   "), pending.selectedAnswers.single())
            assertEquals("OpenCode question failed", pending.error)
            assertFalse(pending.isSubmitting)
        }

    @Test
    fun `typed answer keeps the spaces between words`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        options = listOf("src", "docs"),
                    ),
                ),
            )
            advanceUntilIdle()

            "hello world".indices.forEach { end ->
                viewModel.selectQuestionAnswer("q-1", 0, "hello world".substring(0, end + 1))
            }

            assertEquals(
                listOf("hello world"),
                viewModel.uiState.value.pendingQuestions.single().selectedAnswers.single(),
            )

            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("hello world")), backend.answeredQuestions.single().answers)
        }

    @Test
    fun `free text answer is accepted even when the question offers options`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        options = listOf("src", "docs"),
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "neither, use scripts/")
            assertTrue(viewModel.uiState.value.pendingQuestions.single().canSubmit)

            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("neither, use scripts/")), backend.answeredQuestions.single().answers)
            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
        }

    @Test
    fun `dismissing a question only hides the card and leaves the turn running`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1")),
            )
            advanceUntilIdle()

            viewModel.dismissQuestion("q-1")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
            assertTrue(backend.abortedSessions.isEmpty())
            assertTrue(backend.answeredQuestions.isEmpty())
        }

    @Test
    fun `cancelling a question declines it instead of killing the turn`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1", directory = "/workspace/repo")),
            )
            advanceUntilIdle()
            assertEquals("q-1", viewModel.uiState.value.pendingQuestions.single().request.id)

            viewModel.cancelQuestion("q-1")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
            assertEquals(listOf("q-1" to "/workspace/repo"), backend.rejectedQuestions)
            assertTrue(backend.abortedSessions.isEmpty())
            assertTrue(backend.answeredQuestions.isEmpty())
        }

    @Test
    fun `answering is scoped to the workspace the question came from`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1", directory = "/workspace/repo")),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals("/workspace/repo", backend.answeredQuestions.single().directory)
        }

    @Test
    fun `multi select prompts keep every chosen option`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(
                        id = "q-1",
                        sessionId = "session-1",
                        options = listOf("src", "docs"),
                        multiple = true,
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.selectQuestionAnswer("q-1", 0, "docs")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("src", "docs")), backend.answeredQuestions.single().answers)
        }

    @Test
    fun `a prompt that refuses custom answers submits only its own options`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(
                    request(id = "q-1", sessionId = "session-1", options = listOf("src"), custom = false),
                ),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "somewhere else")
            assertFalse(viewModel.uiState.value.pendingQuestions.single().canSubmit)

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf(listOf("src")), backend.answeredQuestions.single().answers)
        }

    @Test
    fun `a question asked before the session was open is recovered`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    pending =
                        listOf(
                            request(id = "q-1", sessionId = "session-1"),
                            request(id = "q-2", sessionId = "session-2"),
                        ),
                )
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()

            // Only the open session's question, and only because it was fetched: no event carried it.
            assertEquals(listOf("q-1"), viewModel.uiState.value.pendingQuestions.map { it.request.id })
        }

    @Test
    fun `a recovered question is not duplicated by its event`() =
        runTest(dispatcher) {
            val backend = FakeBackend(pending = listOf(request(id = "q-1", sessionId = "session-1")))
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1")))
            advanceUntilIdle()

            assertEquals(listOf("q-1"), viewModel.uiState.value.pendingQuestions.map { it.request.id })
        }

    @Test
    fun `recovery asks for the directory of the session being opened`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    pending = listOf(request(id = "q-1", sessionId = "session-1")),
                    sessionDirectory = "/workspace/repo",
                )
            val viewModel = ChatViewModel(backend)

            // The composer's workspace belongs to another project; it must not scope the query.
            viewModel.selectWorkspace("/workspace/other")
            viewModel.openSession("session-1")
            advanceUntilIdle()

            assertEquals(listOf("q-1"), viewModel.uiState.value.pendingQuestions.map { it.request.id })
            assertEquals(listOf("/workspace/repo"), backend.pendingQuestionDirectories)
        }

    @Test
    fun `a recovered question is answered against the session's own workspace`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    pending = listOf(request(id = "q-1", sessionId = "session-1")),
                    sessionDirectory = "/workspace/repo",
                )
            val viewModel = ChatViewModel(backend)

            viewModel.selectWorkspace("/workspace/other")
            viewModel.openSession("session-1")
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals("/workspace/repo", backend.answeredQuestions.single().directory)
        }

    @Test
    fun `a dismissed question stays hidden when the stream reconnects`() =
        runTest(dispatcher) {
            val backend = FakeBackend(pending = listOf(request(id = "q-1", sessionId = "session-1")))
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            assertEquals(listOf("q-1"), viewModel.uiState.value.pendingQuestions.map { it.request.id })

            viewModel.dismissQuestion("q-1")
            backend.events.emit(OpenCodeEvent.ServerConnected)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingQuestions.isEmpty())
        }

    @Test
    fun `reopening the session offers a dismissed question again`() =
        runTest(dispatcher) {
            val backend = FakeBackend(pending = listOf(request(id = "q-1", sessionId = "session-1")))
            val viewModel = ChatViewModel(backend)

            viewModel.openSession("session-1")
            advanceUntilIdle()
            viewModel.dismissQuestion("q-1")
            viewModel.openSession("session-2")
            advanceUntilIdle()

            viewModel.openSession("session-1")
            advanceUntilIdle()

            assertEquals(listOf("q-1"), viewModel.uiState.value.pendingQuestions.map { it.request.id })
        }

    @Test
    fun `answering a question reports it as resolved`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val resolved = mutableListOf<String>()
            val viewModel = ChatViewModel(backend, onQuestionResolved = { resolved += it })

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1", options = listOf("src"))),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf("q-1"), resolved)
        }

    @Test
    fun `cancelling a question reports it as resolved`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val resolved = mutableListOf<String>()
            val viewModel = ChatViewModel(backend, onQuestionResolved = { resolved += it })

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1")))
            advanceUntilIdle()

            viewModel.cancelQuestion("q-1")
            advanceUntilIdle()

            assertEquals(listOf("q-1"), resolved)
        }

    @Test
    fun `a failed answer does not report the question as resolved`() =
        runTest(dispatcher) {
            val backend = FakeBackend(answerResult = false)
            val resolved = mutableListOf<String>()
            val viewModel = ChatViewModel(backend, onQuestionResolved = { resolved += it })

            viewModel.openSession("session-1")
            advanceUntilIdle()
            backend.events.emit(
                OpenCodeEvent.QuestionAsked(request(id = "q-1", sessionId = "session-1", options = listOf("src"))),
            )
            advanceUntilIdle()

            viewModel.selectQuestionAnswer("q-1", 0, "src")
            viewModel.submitQuestion("q-1")
            advanceUntilIdle()

            assertTrue(resolved.isEmpty())
        }

    private fun request(
        id: String,
        sessionId: String,
        question: String = "Pick a folder",
        options: List<String> = listOf("src"),
        placeholder: String? = null,
        multiple: Boolean = false,
        custom: Boolean = true,
        directory: String? = null,
    ) = QuestionRequest(
        id = id,
        sessionId = sessionId,
        directory = directory,
        questions =
            listOf(
                QuestionPrompt(
                    question = question,
                    header = "Folder",
                    options = options.map(::QuestionOption),
                    placeholder = placeholder,
                    multiple = multiple,
                    custom = custom,
                ),
            ),
    )

    private class FakeBackend(
        private val answerResult: Boolean = true,
        private val pending: List<QuestionRequest> = emptyList(),
        private val sessionDirectory: String? = null,
    ) : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        val answeredQuestions = mutableListOf<AnswerRecord>()
        val rejectedQuestions = mutableListOf<Pair<String, String?>>()
        val abortedSessions = mutableListOf<String>()
        val pendingQuestionDirectories = mutableListOf<String?>()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun session(sessionId: String): OpenCodeSession =
            OpenCodeSession(
                id = sessionId,
                directory = sessionDirectory,
                title = "",
                time = OpenCodeTime(created = 1),
            )

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession =
            OpenCodeSession(
                id = "session-1",
                directory = directory,
                title = title.orEmpty(),
                time = OpenCodeTime(created = 1),
            )

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

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
            response: com.yugahashimoto.andcode.runtime.PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override suspend fun answerQuestion(
            requestId: String,
            answers: List<List<String>>,
            directory: String?,
        ): Boolean {
            answeredQuestions += AnswerRecord(requestId, answers, directory)
            return answerResult
        }

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean {
            rejectedQuestions += requestId to directory
            return true
        }

        override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> {
            pendingQuestionDirectories += directory
            return pending
        }

        override fun events(): Flow<OpenCodeEvent> = events
    }

    private data class AnswerRecord(
        val requestId: String,
        val answers: List<List<String>>,
        val directory: String?,
    )
}
