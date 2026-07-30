package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
class CodeViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads file from workspace`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "fun main() = Unit"))
            val viewModel = CodeViewerViewModel(backend, "/repo path", "src/Main.kt")

            advanceUntilIdle()

            assertEquals("/repo path", backend.directory)
            assertEquals("src/Main.kt", backend.path)
            assertEquals("fun main() = Unit", viewModel.state.value.content)
            assertFalse(viewModel.state.value.isLoading)
            assertFalse(viewModel.state.value.isBinary)
        }

    @Test
    fun `marks binary content without exposing it as text`() =
        runTest(dispatcher) {
            val viewModel =
                CodeViewerViewModel(
                    FakeBackend(OpenCodeFileContent("text", "AAEC", encoding = "base64")),
                    "/repo",
                    "image.png",
                )

            advanceUntilIdle()

            assertTrue(viewModel.state.value.isBinary)
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `reports read failures and stops loading`() =
        runTest(dispatcher) {
            val viewModel = CodeViewerViewModel(FakeBackend(null), "/repo", "missing.kt")

            advanceUntilIdle()

            assertEquals("file unavailable", viewModel.state.value.error)
            assertFalse(viewModel.state.value.isLoading)
        }

    private class FakeBackend(
        private val file: OpenCodeFileContent?,
    ) : OpenCodeBackend {
        override val id = "fake"
        override val displayName = "Fake"
        override val kind = BackendKind.REMOTE
        var directory: String? = null
        var path: String? = null

        override suspend fun health() = OpenCodeHealth(true, "1.18.5")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders() = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun readFile(
            directory: String,
            path: String,
        ): OpenCodeFileContent {
            this.directory = directory
            this.path = path
            return file ?: error("file unavailable")
        }

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String) = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ) = true

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
