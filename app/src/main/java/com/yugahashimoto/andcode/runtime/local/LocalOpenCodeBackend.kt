package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ConfiguredProvider
import com.yugahashimoto.andcode.core.api.McpAuthRemoval
import com.yugahashimoto.andcode.core.api.McpAuthStart
import com.yugahashimoto.andcode.core.api.McpAuthStatus
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePathInfo
import com.yugahashimoto.andcode.core.api.OpenCodeProject
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.OpenCodeTodo
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderAuthAuthorization
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class LocalOpenCodeBackend(
    private val portProvider: () -> Int?,
    private val backendFactory: (ConnectionProfile) -> RemoteOpenCodeBackend = { profile ->
        RemoteOpenCodeBackend(profile)
    },
) : OpenCodeBackend {
    constructor(runtimeManager: LocalRuntimeManager) : this(
        portProvider = runtimeManager::installedPort,
    )

    override val id: String = "local-android"
    override val displayName: String = "Android local"
    override val kind: BackendKind = BackendKind.LOCAL

    @Volatile
    private var cached: CachedDelegate? = null
    private val lock = Any()

    internal fun delegate(): RemoteOpenCodeBackend {
        val port =
            portProvider()
                ?: error("Android local OpenCode runtime is not installed")
        cached?.takeIf { it.port == port }?.let { return it.backend }

        synchronized(lock) {
            cached?.takeIf { it.port == port }?.let { return it.backend }
            val backend =
                backendFactory(
                    ConnectionProfile(
                        id = id,
                        name = displayName,
                        baseUrl = "http://127.0.0.1:$port/",
                        username = "opencode",
                        allowInsecureLan = true,
                    ),
                )
            cached = CachedDelegate(port, backend)
            return backend
        }
    }

    fun invalidate() {
        cached = null
    }

    override suspend fun health(): OpenCodeHealth = delegate().health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = delegate().listSessions(directory)

    override suspend fun session(sessionId: String): OpenCodeSession = delegate().session(sessionId)

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = delegate().createSession(title, directory)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = delegate().listMessages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = delegate().deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = delegate().listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = delegate().listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = delegate().providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = delegate().authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = delegate().setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = delegate().removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = delegate().completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = delegate().listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = delegate().currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = delegate().pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = delegate().listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = delegate().readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = delegate().fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = delegate().searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = delegate().findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = delegate().vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = delegate().vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = delegate().vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = delegate().sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = delegate().sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = delegate().sendMessage(sessionId, request)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = delegate().summarizeSession(sessionId, providerId, modelId)

    override suspend fun abortSession(sessionId: String): Boolean = delegate().abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = delegate().renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = delegate().deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = delegate().respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = delegate().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = delegate().rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = delegate().pendingQuestions(directory)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = delegate().archiveSession(sessionId)

    override suspend fun mcpServers(): List<McpServer> = delegate().mcpServers()

    override suspend fun addMcpServer(body: JsonObject): McpServer = delegate().addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = delegate().connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = delegate().disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = delegate().removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): McpAuthStart = delegate().mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = delegate().mcpAuthCallback(name, code)

    override suspend fun config(): JsonElement = delegate().config()

    override suspend fun updateConfig(patch: JsonObject): JsonElement = delegate().updateConfig(patch)

    override suspend fun configProviders(): List<ConfiguredProvider> = delegate().configProviders()

    override suspend fun commands(): List<OpenCodeCommand> = delegate().commands()

    override suspend fun skills(): List<OpenCodeSkill> = delegate().skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) = delegate().executeCommand(sessionId, command, arguments)

    override fun events(): Flow<OpenCodeEvent> = delegate().events()

    private data class CachedDelegate(
        val port: Int,
        val backend: RemoteOpenCodeBackend,
    )
}
