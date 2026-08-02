package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTargetResolverTest {
    private val openCodeCatalog =
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = "opencode",
                        models = mapOf("grok-code" to OpenCodeModel("grok-code", "opencode", "Grok Code")),
                    ),
                ),
            connected = listOf("opencode"),
        )

    @Test
    fun `assistant choices contain named agents and never a generic remote or local option`() {
        val targets =
            listOf(
                FakeTarget("remote", agent = null),
                FakeTarget("open-code", agent = LocalAgent.OPEN_CODE),
                FakeTarget("claude", agent = LocalAgent.CLAUDE_CODE),
                FakeTarget("antigravity", agent = LocalAgent.ANTIGRAVITY),
            )

        assertEquals(
            listOf("open-code", "claude", "antigravity"),
            assistantTargets(targets).map { it.id },
        )
    }

    @Test
    fun `assistant model options come from connected providers`() {
        val catalog =
            ProviderCatalog(
                all =
                    listOf(
                        OpenCodeProvider(
                            id = "connected",
                            models = mapOf("model-a" to OpenCodeModel("model-a", "connected", "Model A")),
                        ),
                        OpenCodeProvider(
                            id = "disconnected",
                            models = mapOf("model-b" to OpenCodeModel("model-b", "disconnected", "Model B")),
                        ),
                    ),
                connected = listOf("connected"),
            )

        assertEquals(listOf("connected"), assistantProviderOptions(catalog).map { it.id })
    }

    @Test
    fun `assistant models are fetched after connecting the agent's own runtime`() =
        runTest {
            val target = FakeTarget("open-code", agent = LocalAgent.OPEN_CODE, catalog = openCodeCatalog)

            assertEquals(listOf("opencode"), loadAssistantProviders(target).map { it.id })
        }

    @Test
    fun `assistant models stay empty when the agent's runtime cannot be reached`() =
        runTest {
            val target =
                FakeTarget(
                    "open-code",
                    agent = LocalAgent.OPEN_CODE,
                    catalog = openCodeCatalog,
                    connects = false,
                )

            assertEquals(emptyList<String>(), loadAssistantProviders(target).map { it.id })
        }

    @Test
    fun `assistant workspace options prefer the selected agent over chat fallback`() {
        val selected = WorkspaceRef("/workspace/selected", "selected", "selected")
        val fallback = WorkspaceRef("/workspace/chat", "chat", "chat")

        assertEquals(listOf(selected), assistantWorkspaceOptions(listOf(selected), listOf(fallback)))
    }

    private class FakeTarget(
        override val id: String,
        override val agent: LocalAgent?,
        private val catalog: ProviderCatalog = ProviderCatalog(),
        private val connects: Boolean = true,
    ) : RuntimeTarget {
        override val displayName: String = id
        override val type: RuntimeType = RuntimeType.LOCAL
        override val kind: BackendKind = BackendKind.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        private var connected = false

        override suspend fun connect(): Result<OpenCodeHealth> {
            if (!connects) return Result.failure(IllegalStateException("runtime is not running"))
            connected = true
            return Result.success(OpenCodeHealth(true, "test"))
        }

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "session", directory = directory, title = "session", time = OpenCodeTime(0))

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        /** A local runtime only answers once its server is up, which is what [connect] establishes. */
        override suspend fun listProviders(): ProviderCatalog {
            check(connected) { "runtime is not connected" }
            return catalog
        }

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

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
