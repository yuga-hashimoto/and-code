package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.api.McpAuthStart
import com.yugahashimoto.andcode.core.api.McpAuthStatus
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `OAuth starts once and successful callback refreshes servers`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = McpViewModel({ backend })
            advanceUntilIdle()
            var openedUrl: String? = null

            viewModel.startAuth("github", { openedUrl = it })
            viewModel.startAuth("github", { openedUrl = it })
            advanceUntilIdle()

            assertEquals(1, backend.startCalls)
            assertEquals("https://auth.example", openedUrl)
            assertEquals("github", viewModel.state.value.oauthServerName)

            viewModel.updateOAuthCode(" code-1 ")
            viewModel.completeAuth()
            advanceUntilIdle()

            assertEquals("code-1", backend.callbackCode)
            assertNull(viewModel.state.value.oauthServerName)
            assertFalse(viewModel.state.value.isAuthenticating)
            assertTrue(backend.serverLoads >= 2)
        }

    @Test
    fun `non OpenCode agents do not support OAuth`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = McpViewModel({ backend }, LocalAgent.CLAUDE_CODE)

            viewModel.startAuth("github") {}
            advanceUntilIdle()

            assertFalse(viewModel.state.value.supportsOAuth)
            assertEquals(0, backend.startCalls)
        }

    @Test
    fun `OpenCode add payload nests config and sends local command as arguments`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = McpViewModel({ backend })
            advanceUntilIdle()

            viewModel.showAddDialog()
            viewModel.updateAddName("local-tools")
            viewModel.updateAddCommand("node -e \"console.log(\\\"hi\\\")\" --stdio")
            viewModel.addServer()
            advanceUntilIdle()

            val body = requireNotNull(backend.addBody)
            assertEquals("local-tools", body["name"]!!.jsonPrimitive.content)
            val config = body["config"]!!.jsonObject
            assertEquals("local", config["type"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("node", "-e", "console.log(\"hi\")", "--stdio"),
                (config["command"] as JsonArray).map { it.jsonPrimitive.content },
            )
        }

    private class FakeBackend : OpenCodeBackend {
        override val id = "fake"
        override val displayName = "Fake"
        override val kind = BackendKind.REMOTE
        var startCalls = 0
        var callbackCode: String? = null
        var serverLoads = 0
        var addBody: JsonObject? = null

        override suspend fun health() = OpenCodeHealth(true, "1.18.5")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders() = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun mcpServers(): List<McpServer> {
            serverLoads++
            return listOf(McpServer("github", status = "needs_auth"))
        }

        override suspend fun mcpAuth(name: String): McpAuthStart {
            startCalls++
            return McpAuthStart("https://auth.example", "state-1")
        }

        override suspend fun mcpAuthCallback(
            name: String,
            code: String,
        ): McpAuthStatus {
            callbackCode = code
            return McpAuthStatus("connected")
        }

        override suspend fun addMcpServer(body: JsonObject): McpServer {
            addBody = body
            return McpServer("local-tools", status = "connected")
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
