package com.yugahashimoto.andcode.runtime

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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class BackendKind {
    LOCAL,
    REMOTE,
}

enum class PermissionResponse(val apiValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

interface OpenCodeBackend {
    val id: String
    val displayName: String
    val kind: BackendKind

    suspend fun health(): OpenCodeHealth

    suspend fun listSessions(directory: String? = null): List<OpenCodeSession>

    suspend fun session(sessionId: String): OpenCodeSession =
        listSessions().firstOrNull { it.id == sessionId }
            ?: error("Session not found: $sessionId")

    suspend fun createSession(
        title: String? = null,
        directory: String? = null,
    ): OpenCodeSession

    suspend fun listMessages(sessionId: String): List<OpenCodeMessage>

    suspend fun listProviders(): ProviderCatalog

    suspend fun listAgents(): List<OpenCodeAgent>

    suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = unsupported("provider auth methods")

    suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String> = emptyMap(),
    ): ProviderAuthAuthorization = unsupported("provider OAuth authorization")

    suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap(),
    ): Boolean = unsupported("provider API authentication")

    suspend fun removeProviderAuth(providerId: String): Boolean = unsupported("provider authentication removal")

    suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = unsupported("provider OAuth callback")

    suspend fun listProjects(directory: String? = null): List<OpenCodeProject> = unsupported("projects")

    suspend fun currentProject(directory: String? = null): OpenCodeProject = unsupported("current project")

    suspend fun pathInfo(directory: String? = null): OpenCodePathInfo = unsupported("path info")

    suspend fun listFiles(
        directory: String,
        path: String = ".",
    ): List<OpenCodeFileNode> = unsupported("file list")

    suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = unsupported("file read")

    suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = unsupported("file status")

    suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = unsupported("text search")

    suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean? = null,
        type: String? = null,
        limit: Int? = null,
    ): List<String> = unsupported("file search")

    suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = unsupported("VCS info")

    suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = unsupported("VCS status")

    suspend fun vcsDiff(
        directory: String,
        mode: String = "git",
        context: Int? = null,
    ): List<OpenCodeFileChange> = unsupported("VCS diff")

    suspend fun sessionDiff(
        sessionId: String,
        directory: String? = null,
        messageId: String? = null,
    ): List<OpenCodeFileChange> = unsupported("session diff")

    suspend fun sessionTodo(
        sessionId: String,
        directory: String? = null,
    ): List<OpenCodeTodo> = unsupported("session todo")

    suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    )

    suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = unsupported("session summarization")

    suspend fun abortSession(sessionId: String): Boolean

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = unsupported("session rename")

    suspend fun deleteSession(sessionId: String): Boolean = unsupported("session delete")

    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean

    suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = unsupported("question answers")

    suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = unsupported("question rejection")

    suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = emptyList()

    suspend fun archiveSession(sessionId: String): OpenCodeSession = unsupported("session archive")

    suspend fun mcpServers(): List<McpServer> = unsupported("MCP servers")

    suspend fun addMcpServer(body: JsonObject): McpServer = unsupported("MCP add server")

    suspend fun connectMcpServer(name: String): Boolean = unsupported("MCP connect")

    suspend fun disconnectMcpServer(name: String): Boolean = unsupported("MCP disconnect")

    suspend fun removeMcpAuth(name: String): McpAuthRemoval = unsupported("MCP remove auth")

    suspend fun mcpAuth(name: String): McpAuthStart = unsupported("MCP auth")

    suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = unsupported("MCP auth callback")

    suspend fun config(): JsonElement = unsupported("config")

    suspend fun updateConfig(patch: JsonObject): JsonElement = unsupported("config update")

    suspend fun configProviders(): List<ConfiguredProvider> = unsupported("config providers")

    suspend fun commands(): List<OpenCodeCommand> = unsupported("commands")

    suspend fun skills(): List<OpenCodeSkill> = unsupported("skills")

    fun events(): Flow<OpenCodeEvent>

    private fun unsupported(capability: String): Nothing =
        throw UnsupportedOperationException("OpenCode backend does not support $capability")
}
