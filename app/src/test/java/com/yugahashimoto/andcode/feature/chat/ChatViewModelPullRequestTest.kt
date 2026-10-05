package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The badges above the composer are a view over the transcript, so their tests drive the same
 * events a live chat produces and assert on [ChatUiState.pullRequests].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPullRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        statusScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a pull request link in the transcript becomes a badge, and dismissing it removes it`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend, pullRequestStatuses = pullRequestStatuses())
            viewModel.openSession("session-1")
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-1", "Opened https://github.com/o/r/pull/7 for review.")
            advanceUntilIdle()

            assertEquals(listOf(PullRequestRef("o", "r", 7)), viewModel.uiState.value.pullRequests.map { it.ref })

            viewModel.dismissPullRequest(PullRequestRef("o", "r", 7).key)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pullRequests.isEmpty())
        }

    /** A badge is a convenience over the transcript, so a later mention must not resurrect it. */
    @Test
    fun `a dismissed pull request stays hidden when the transcript links it again`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend, pullRequestStatuses = pullRequestStatuses())
            viewModel.openSession("session-1")
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-1", "Opened https://github.com/o/r/pull/7 for review.")
            advanceUntilIdle()
            viewModel.dismissPullRequest(PullRequestRef("o", "r", 7).key)
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-2", "Pull request https://github.com/o/r/pull/7 is ready.")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pullRequests.isEmpty())
        }

    @Test
    fun `dismissing one pull request keeps the others`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend, pullRequestStatuses = pullRequestStatuses())
            viewModel.openSession("session-1")
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-1", "Opened https://github.com/o/r/pull/7.")
            advanceUntilIdle()
            backend.emitAssistantText("session-1", "part-2", "Follow-up opened as https://github.com/o/r/pull/8.")
            advanceUntilIdle()

            assertEquals(
                listOf(PullRequestRef("o", "r", 8), PullRequestRef("o", "r", 7)),
                viewModel.uiState.value.pullRequests.map { it.ref },
            )

            viewModel.dismissPullRequest(PullRequestRef("o", "r", 8).key)
            advanceUntilIdle()

            assertEquals(listOf(PullRequestRef("o", "r", 7)), viewModel.uiState.value.pullRequests.map { it.ref })
        }

    /** The dismissal belongs to the chat it was made in, so reopening the chat offers it again. */
    @Test
    fun `reopening the chat offers a dismissed pull request again`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = ChatViewModel(backend, pullRequestStatuses = pullRequestStatuses())
            viewModel.openSession("session-1")
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-1", "Opened https://github.com/o/r/pull/7 for review.")
            advanceUntilIdle()
            viewModel.dismissPullRequest(PullRequestRef("o", "r", 7).key)
            advanceUntilIdle()

            backend.emitAssistantText("session-1", "part-2", "Also opened https://github.com/o/r/pull/9.")
            advanceUntilIdle()
            viewModel.openSession("session-1")
            backend.emitAssistantText("session-1", "part-3", "Pull request https://github.com/o/r/pull/7 is merged.")
            advanceUntilIdle()

            assertEquals(listOf(PullRequestRef("o", "r", 7)), viewModel.uiState.value.pullRequests.map { it.ref })
        }

    /**
     * The real repository is built against GitHub, but what these tests assert is the ref flow, not
     * the fetch, so a client that can never connect keeps them hermetic; a badge simply waits for
     * its state, exactly as it does while the network is down.
     */
    private fun pullRequestStatuses() =
        PullRequestStatusRepository(
            api =
                GitHubApiClient(
                    token = { null },
                    client = OkHttpClient(),
                    baseUrl = "http://127.0.0.1:1",
                ),
            scope = statusScope,
        )

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun session(sessionId: String): OpenCodeSession =
            OpenCodeSession(
                id = sessionId,
                directory = null,
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

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override suspend fun answerQuestion(
            requestId: String,
            answers: List<List<String>>,
            directory: String?,
        ): Boolean = true

        override suspend fun rejectQuestion(
            requestId: String,
            directory: String?,
        ): Boolean = true

        override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = emptyList()

        override fun events(): Flow<OpenCodeEvent> = events

        /** Streams one assistant text part into the open session, as a live answer would. */
        fun emitAssistantText(
            sessionId: String,
            partId: String,
            text: String,
        ) {
            events.tryEmit(
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = partId,
                        sessionId = sessionId,
                        messageId = "m-$partId",
                        type = "text",
                        text = text,
                    ),
                ),
            )
        }
    }
}
