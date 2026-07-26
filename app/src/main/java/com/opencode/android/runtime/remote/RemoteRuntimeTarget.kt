package com.opencode.android.runtime.remote

import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeFileChange
import com.opencode.android.core.api.OpenCodeFileContent
import com.opencode.android.core.api.OpenCodeFileNode
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodePathInfo
import com.opencode.android.core.api.OpenCodeProject
import com.opencode.android.core.api.OpenCodeSearchMatch
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.core.api.OpenCodeTodo
import com.opencode.android.core.api.OpenCodeVcsInfo
import com.opencode.android.core.api.PromptRequest
import com.opencode.android.core.api.ProviderAuthAuthorization
import com.opencode.android.core.api.ProviderAuthMethod
import com.opencode.android.core.api.ProviderCatalog
import com.opencode.android.core.api.QuestionRequest
import com.opencode.android.core.util.safeMessage
import com.opencode.android.data.connection.ConnectionProfile
import com.opencode.android.runtime.BackendKind
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.runtime.mergeSessionLists
import com.opencode.android.runtime.mergeWorkspaceRefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteRuntimeTarget(
    val profile: ConnectionProfile,
    private val backend: RemoteOpenCodeBackend = RemoteOpenCodeBackend(profile),
) : RuntimeTarget {
    override val id: String = profile.id
    override val displayName: String = profile.name
    override val type: RuntimeType = RuntimeType.REMOTE
    override val kind: BackendKind = BackendKind.REMOTE

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override suspend fun connect(): Result<OpenCodeHealth> {
        // Re-checking an already connected runtime must not flap the state, or every refresh would
        // tear down the live event stream that hangs off it.
        if (mutableState.value !is RuntimeState.Connected) {
            mutableState.value = RuntimeState.Connecting
        }
        return runCatching { backend.health() }
            .onSuccess { health ->
                mutableState.value =
                    if (health.healthy) {
                        RuntimeState.Connected(health.version)
                    } else {
                        RuntimeState.Failed("OpenCode reported an unhealthy server")
                    }
            }
            .onFailure { error ->
                mutableState.value = RuntimeState.Failed(error.safeMessage("OpenCode connection failed"))
            }
    }

    override fun disconnect() {
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun listWorkspaces(): List<WorkspaceRef> {
        val current = runCatching { backend.pathInfo().directory }.getOrNull()
        val sessions = backend.listSessions()
        val projects = runCatching { backend.listProjects() }.getOrDefault(emptyList())
        return mergeWorkspaceRefs(current, sessions, projects)
    }

    override suspend fun health(): OpenCodeHealth = backend.health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> {
        if (directory != null) return backend.listSessions(directory)
        val projects = runCatching { backend.listProjects() }.getOrDefault(emptyList())
        val directories =
            buildList<String?> {
                add(null)
                projects
                    .asSequence()
                    .filterNot { it.id == "global" }
                    .map { it.worktree }
                    .filter { it.isNotBlank() && it != "/" }
                    .forEach(::add)
            }.distinct()
        val sessionLists =
            directories.map { scopedDirectory ->
                runCatching { backend.listSessions(scopedDirectory) }.getOrDefault(emptyList())
            }
        return mergeSessionLists(sessionLists)
    }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = backend.createSession(title, directory)

    override suspend fun session(sessionId: String): OpenCodeSession = backend.session(sessionId)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = backend.listMessages(sessionId)

    override suspend fun listProviders(): ProviderCatalog = backend.listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = backend.listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = backend.providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = backend.authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = backend.setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = backend.removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = backend.completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = backend.listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = backend.currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = backend.pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = backend.listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = backend.readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = backend.fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = backend.searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = backend.findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = backend.vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = backend.vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = backend.vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = backend.sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = backend.sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = backend.sendMessage(sessionId, request)

    override suspend fun abortSession(sessionId: String): Boolean = backend.abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = backend.renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = backend.deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = backend.respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = backend.archiveSession(sessionId)

    override suspend fun mcpServers(): List<com.opencode.android.core.api.McpServer> = backend.mcpServers()

    override suspend fun addMcpServer(body: kotlinx.serialization.json.JsonObject): com.opencode.android.core.api.McpServer =
        backend.addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = backend.connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = backend.disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): Boolean = backend.removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): kotlinx.serialization.json.JsonObject = backend.mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): Boolean = backend.mcpAuthCallback(name, code)

    override suspend fun config(): kotlinx.serialization.json.JsonElement = backend.config()

    override suspend fun updateConfig(patch: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonElement =
        backend.updateConfig(patch)

    override suspend fun configProviders(): List<com.opencode.android.core.api.ConfiguredProvider> = backend.configProviders()

    override suspend fun commands(): List<com.opencode.android.core.api.OpenCodeCommand> = backend.commands()

    override suspend fun skills(): List<com.opencode.android.core.api.OpenCodeSkill> = backend.skills()

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = backend.summarizeSession(sessionId, providerId, modelId)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = backend.answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = backend.rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = backend.pendingQuestions(directory)

    override suspend fun initAgentsMd(sessionId: String): Boolean = backend.initAgentsMd(sessionId)

    override fun events(): Flow<OpenCodeEvent> = backend.events()
}
