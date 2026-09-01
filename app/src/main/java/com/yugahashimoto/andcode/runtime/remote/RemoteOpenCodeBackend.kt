package com.yugahashimoto.andcode.runtime.remote

import com.yugahashimoto.andcode.core.api.ConfiguredProvider
import com.yugahashimoto.andcode.core.api.McpAuthRemoval
import com.yugahashimoto.andcode.core.api.McpAuthStart
import com.yugahashimoto.andcode.core.api.McpAuthStatus
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeApiClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class RemoteOpenCodeBackend(
    private val profile: ConnectionProfile,
    private val client: OpenCodeApiClient = OpenCodeApiClient(profile),
) : OpenCodeBackend {
    override val id: String = profile.id
    override val displayName: String = profile.name
    override val kind: BackendKind = BackendKind.REMOTE

    override suspend fun health(): OpenCodeHealth = client.health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = client.sessions(directory)

    override suspend fun session(sessionId: String): OpenCodeSession = client.session(sessionId)

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = client.createSession(title, directory)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = client.messages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = client.deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = client.providers()

    override suspend fun listAgents(): List<OpenCodeAgent> = client.agents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = client.providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = client.authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = client.setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = client.removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = client.completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = client.projects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = client.currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = client.pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = client.files(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = client.fileContent(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = client.fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = client.searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = client.findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = client.vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = client.vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = client.vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = client.sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = client.sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = client.promptAsync(sessionId, request)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = client.summarizeSession(sessionId, providerId, modelId)

    override suspend fun abortSession(sessionId: String): Boolean = client.abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = client.renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = client.deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean =
        client.respondPermission(
            sessionId = sessionId,
            permissionId = permissionId,
            response = response.apiValue,
            remember = remember,
        )

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = client.answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = client.rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = client.pendingQuestions(directory)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = client.archiveSession(sessionId)

    override suspend fun mcpServers(): List<McpServer> = client.mcpServers()

    override suspend fun addMcpServer(body: JsonObject): McpServer = client.addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = client.connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = client.disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = client.removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): McpAuthStart = client.mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = client.mcpAuthCallback(name, code)

    override suspend fun config(): JsonElement = client.config()

    override suspend fun updateConfig(patch: JsonObject): JsonElement = client.updateConfig(patch)

    override suspend fun configProviders(): List<ConfiguredProvider> = client.configProviders()

    override suspend fun commands(): List<OpenCodeCommand> = client.commands()

    override suspend fun skills(): List<OpenCodeSkill> = client.skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) {
        client.executeCommand(
            sessionId = sessionId,
            command = command,
            arguments = arguments,
            agent = null,
            model = null,
        )
    }

    override fun events(): Flow<OpenCodeEvent> = client.events()
}
