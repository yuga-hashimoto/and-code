package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchSubmatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchText
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTodo
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.WorkspaceRef
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceExplorerViewModelTest {
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
    fun `initial load shows root files and git changes`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = WorkspaceExplorerViewModel(backend, workspace())

            advanceUntilIdle()

            assertEquals(listOf("src", "README.md"), viewModel.state.value.files.map { it.name })
            assertEquals("main", viewModel.state.value.vcsInfo?.branch)
            assertEquals("src/Main.kt", viewModel.state.value.changes.single().displayPath)
            assertFalse(viewModel.state.value.isLoadingFiles)
            assertFalse(viewModel.state.value.isLoadingChanges)
        }

    @Test
    fun `opening a directory changes the listed path`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = WorkspaceExplorerViewModel(backend, workspace())
            advanceUntilIdle()

            viewModel.open(backend.rootFiles.first { it.type == "directory" })
            advanceUntilIdle()
            assertEquals("src", viewModel.state.value.currentPath)
            assertEquals(listOf("Main.kt"), viewModel.state.value.files.map { it.name })
        }

    @Test
    fun `search combines content and file path results`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = WorkspaceExplorerViewModel(backend, workspace())
            advanceUntilIdle()

            viewModel.search("main")
            advanceUntilIdle()

            assertEquals("src/Main.kt", viewModel.state.value.textMatches.single().path.text)
            assertEquals(listOf("src/Main.kt"), viewModel.state.value.fileMatches)
            assertFalse(viewModel.state.value.isSearching)
        }

    private fun workspace() = WorkspaceRef("/repo", "repo", "/repo")

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE

        val rootFiles =
            listOf(
                OpenCodeFileNode("README.md", "README.md", "/repo/README.md", "file"),
                OpenCodeFileNode("src", "src/", "/repo/src", "directory"),
            )

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "1.18.3")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun listFiles(
            directory: String,
            path: String,
        ): List<OpenCodeFileNode> =
            when (path) {
                "." -> rootFiles
                "src" -> listOf(OpenCodeFileNode("Main.kt", "src/Main.kt", "/repo/src/Main.kt", "file"))
                else -> emptyList()
            }

        override suspend fun readFile(
            directory: String,
            path: String,
        ): OpenCodeFileContent = OpenCodeFileContent("text", "fun main() = Unit", mimeType = "text/x-kotlin")

        override suspend fun searchText(
            directory: String,
            pattern: String,
        ): List<OpenCodeSearchMatch> =
            listOf(
                OpenCodeSearchMatch(
                    path = OpenCodeSearchText("src/Main.kt"),
                    lines = OpenCodeSearchText("fun main() = Unit"),
                    lineNumber = 1,
                    absoluteOffset = 0,
                    submatches = listOf(OpenCodeSearchSubmatch(OpenCodeSearchText("main"), 4, 8)),
                ),
            )

        override suspend fun findFiles(
            directory: String,
            query: String,
            includeDirectories: Boolean?,
            type: String?,
            limit: Int?,
        ): List<String> = listOf("src/Main.kt")

        override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = OpenCodeVcsInfo("main", "main")

        override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> =
            listOf(
                OpenCodeFileChange(file = "src/Main.kt", additions = 2.0, deletions = 1.0, status = "modified"),
            )

        override suspend fun vcsDiff(
            directory: String,
            mode: String,
            context: Int?,
        ): List<OpenCodeFileChange> =
            listOf(
                OpenCodeFileChange(
                    file = "src/Main.kt",
                    patch = "@@ -1 +1 @@\n-old\n+new",
                    additions = 2.0,
                    deletions = 1.0,
                    status = "modified",
                ),
            )

        override suspend fun sessionTodo(
            sessionId: String,
            directory: String?,
        ): List<OpenCodeTodo> = emptyList()

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

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
