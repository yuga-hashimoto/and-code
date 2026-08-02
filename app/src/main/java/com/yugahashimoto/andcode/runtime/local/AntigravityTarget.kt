package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.*
import com.yugahashimoto.andcode.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

class AntigravityTarget(internal val runtime: AntigravityRuntime) : RuntimeTarget {
    override val id = LocalAgent.ANTIGRAVITY.targetId
    override val displayName = "Antigravity · Local"
    override val agent = LocalAgent.ANTIGRAVITY
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL
    override val capabilities =
        // The one-shot --print bridge now reads `--output-format stream-json`, so the reply streams
        // in and tool calls surface as parts; toolEvents advertises exactly that. Permission prompts
        // and questions are still no-ops (respond()/answer() return false), so those stay off rather
        // than make the UI offer interactions the process cannot answer. forcesQueue is set because
        // each turn is a fresh one-shot `agy` process (see AntigravityRuntime.send): sending while one
        // is still running would "interrupt" it by killing that process outright, which surfaced to
        // the user as "agy exited with 137" instead of a clean cancellation.
        RuntimeCapabilities(toolEvents = true, forcesQueue = true)
    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    private val files =
        ClaudeWorkspaceFiles(
            workspaceHostDir = File(runtime.runtimeDirectory, "workspace"),
            rootfsHostDir = File(runtime.runtimeDirectory, "environment/rootfs"),
        )
    private val titleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val auth get() = runtime.auth()

    private val modeFile = File(runtime.runtimeDirectory, "antigravity-permission-mode")
    private val mutableDefaultPermissionMode =
        MutableStateFlow(AntigravityPermissionMode.fromCliValue(runCatching { modeFile.readText().trim() }.getOrNull()))
    val defaultPermissionMode: StateFlow<AntigravityPermissionMode> = mutableDefaultPermissionMode.asStateFlow()

    /** Applies [mode] to new sessions, and to [sessionId] when one is given - same as Claude Code. */
    fun setPermissionMode(
        mode: AntigravityPermissionMode,
        sessionId: String? = null,
    ) {
        mutableDefaultPermissionMode.value = mode
        runCatching {
            modeFile.parentFile?.mkdirs()
            modeFile.writeText(mode.cliValue)
        }
        if (sessionId != null) runtime.setSessionMode(sessionId, mode)
    }

    override suspend fun connect(): Result<OpenCodeHealth> =
        runCatching {
            val version = runtime.version() ?: error("Antigravity is not installed or incompatible with this ABI")
            mutableState.value = RuntimeState.Connected(version)
            OpenCodeHealth(true, version)
        }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Antigravity unavailable") }

    override fun disconnect() {
        runtime.abortAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        runtime.listSessions(directory).map {
            OpenCodeSession(
                it.appSessionId,
                directory = it.workspace,
                title = it.title ?: DEFAULT_TITLE,
                time = OpenCodeTime(it.createdAt, it.updatedAt),
            )
        }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val id = UUID.randomUUID().toString()
        runtime.create(id, directory ?: "/workspace", title)
        return OpenCodeSession(
            id,
            directory = directory ?: "/workspace",
            title = title ?: DEFAULT_TITLE,
            time = OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis()),
        )
    }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession {
        runtime.setSessionTitle(sessionId, title)
        val record = runtime.listSessions(null).firstOrNull { it.appSessionId == sessionId } ?: error("Antigravity session not found")
        return OpenCodeSession(
            record.appSessionId,
            directory = record.workspace,
            title = record.title ?: DEFAULT_TITLE,
            time = OpenCodeTime(record.createdAt, record.updatedAt),
        )
    }

    /** Same as [ClaudeCodeTarget.listMessages]: names the model for chats held before it was stored. */
    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
        val messages = runtime.listMessages(sessionId)
        val model = runtime.listSessions(null).firstOrNull { it.appSessionId == sessionId }?.model ?: return messages
        val reference = OpenCodeModelReference(AntigravityModels.PROVIDER_ID, model)
        return messages.map { message ->
            if (message.info.role == "assistant" && message.info.model == null) {
                message.copy(info = message.info.copy(model = reference))
            } else {
                message
            }
        }
    }

    override suspend fun listProviders(): ProviderCatalog =
        withContext(kotlinx.coroutines.Dispatchers.IO) { AntigravityModels.catalog(runtime.models()) }

    /**
     * The permission modes, offered through the composer's mode chip.
     *
     * That chip is where OpenCode's `build`/`plan` live, so a single agent named "antigravity" there
     * told the user nothing and left no way to switch modes without opening settings.
     */
    override suspend fun listAgents(): List<OpenCodeAgent> =
        AntigravityPermissionMode.entries.map { OpenCodeAgent(it.agentId, "Antigravity", "primary", true) }

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = runtime.listSessions(null).firstOrNull { it.appSessionId == sessionId } ?: error("Antigravity session not found")
        // The CLI does not name its conversations, so every chat would sit in the drawer as
        // "Antigravity" - the same gap ClaudeCodeTarget fills, filled the same way. The prompt stands
        // in at once so the drawer is never left showing the tool's name.
        val needsTitle = record.title == null
        if (needsTitle) titleFromPrompt(request.text)?.let { runtime.setSessionTitle(sessionId, it) }
        val model = request.modelId ?: record.model
        val variant = request.variant ?: record.variant
        if (model != record.model || variant != record.variant) runtime.setSessionModel(sessionId, model, variant)
        // The chip's choice wins for this message and is remembered; falling back to the session's
        // stored mode, then to the default the settings screen shows.
        val permissionMode =
            AntigravityPermissionMode.fromAgentId(request.agent)
                ?.also { runtime.setSessionMode(sessionId, it) }
                ?: AntigravityPermissionMode.fromCliValue(record.permissionMode)
        runtime.send(
            sessionId,
            record.workspace,
            request.text,
            record.conversationId,
            model,
            variant,
            permissionMode,
            request.attachments,
        ).getOrThrow()
        // Claude Code summarises while its answer is still streaming; here it has to wait for the
        // send to finish, because two concurrent `agy` processes hang each other. Failure is silent:
        // the prompt-derived name set above is already in the drawer and simply stays.
        if (needsTitle) {
            titleScope.launch {
                val summary = withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.summarizeTitle(request.text) }
                if (summary != null) runtime.setSessionTitle(sessionId, summary)
            }
        }
    }

    /** The chat's stand-in name: the prompt's first line, shortened. */
    private fun titleFromPrompt(prompt: String): String? {
        val firstLine = prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        return if (firstLine.length <= TITLE_LENGTH) firstLine else firstLine.take(TITLE_LENGTH).trimEnd() + "…"
    }

    override suspend fun mcpServers(): List<McpServer> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: return@withContext emptyList()
            AntigravityMcp.read(rootfs)
        }

    override suspend fun addMcpServer(body: JsonObject): McpServer =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: error("Linux environment is not installed")
            AntigravityMcp.add(rootfs, body)
        }

    override suspend fun disconnectMcpServer(name: String): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: return@withContext false
            AntigravityMcp.remove(rootfs, name)
        }

    override suspend fun abortSession(sessionId: String): Boolean {
        runtime.abort(sessionId)
        return true
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        return runtime.remove(sessionId)
    }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = runtime.respond(permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = runtime.answer(requestId, answers)

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    // The merged Claude runtime added the local workspace explorer because a local agent has no
    // HTTP file API. Antigravity uses the same /workspace bind mount, so expose the same safe,
    // canonicalized read/search surface instead of falling back to OpenCodeBackend.unsupported().
    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(kotlinx.coroutines.Dispatchers.IO) { files.read(directory, path) }

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.find(directory, query, includeDirectories, limit) }

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.search(directory, pattern) }

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        runtime.listSessions(null).map {
            WorkspaceRef(it.workspace, it.workspace.substringAfterLast('/').ifBlank { it.workspace }, it.workspace)
        }.distinctBy { it.id }

    private companion object {
        /** Shown only until the chat's first prompt names it. */
        const val DEFAULT_TITLE = "Antigravity"
        const val TITLE_LENGTH = 40
    }
}
