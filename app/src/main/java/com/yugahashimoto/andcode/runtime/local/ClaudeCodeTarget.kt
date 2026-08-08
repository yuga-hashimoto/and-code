package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.OpenCodeTodo
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/** Persisted alongside the session so a reopened chat keeps the permissions the user chose. */
@Serializable
private data class ClaudeSessionRecord(
    @SerialName("session") val session: OpenCodeSession,
    @SerialName("permissionMode") val permissionMode: String = ClaudePermissionMode.DEFAULT.cliValue,
    @SerialName("model") val model: String = ClaudeModels.DEFAULT_MODEL,
    @SerialName("effort") val effort: String? = null,
)

/** Exposes the Android-local Claude Code agent as a selectable runtime. */
class ClaudeCodeTarget(
    private val runtime: ClaudeCodeRuntime,
    private val messages: ClaudeMessages = ClaudeMessages,
) : RuntimeTarget {
    override val id = LocalAgent.CLAUDE_CODE.targetId
    override val displayName = "Claude Code"
    override val agent = LocalAgent.CLAUDE_CODE
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    override val capabilities: RuntimeCapabilities
        get() {
            val bridge = runtime.permissionBridgeReady()
            return RuntimeCapabilities(
                permissions = bridge,
                questions = bridge,
                toolEvents = true,
                resume = true,
            )
        }

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private companion object {
        const val DEFAULT_TITLE = "Claude Code"
        const val TITLE_LENGTH = 40
        const val MCP_TIMEOUT_SECONDS = 120L
        const val WORKSPACE_ROOT = "/workspace"
    }

    private val sessionsFile = File(runtime.runtimeDirectory, "claude-sessions.json")
    private val modeFile = File(runtime.runtimeDirectory, "claude-permission-mode")
    private val records =
        linkedMapOf<String, ClaudeSessionRecord>().apply {
            runCatching {
                json.decodeFromString<List<ClaudeSessionRecord>>(sessionsFile.readText())
                    .forEach { put(it.session.id, it) }
            }
        }

    /**
     * Mode applied to sessions created from now on.
     *
     * Existing sessions keep the mode they were created with, because changing it mid-conversation
     * would silently widen what Claude may do to work the user already approved.
     */
    private val mutableDefaultPermissionMode =
        MutableStateFlow(
            ClaudePermissionMode.fromCliValue(runCatching { modeFile.readText().trim() }.getOrNull()),
        )
    val defaultPermissionMode: StateFlow<ClaudePermissionMode> = mutableDefaultPermissionMode.asStateFlow()

    /**
     * Applies [mode] to new sessions, and to [sessionId] when one is given.
     *
     * Picking a mode from an open chat is an explicit choice about that conversation, so it takes
     * effect there on the next message rather than only on the next session.
     */
    fun setPermissionMode(
        mode: ClaudePermissionMode,
        sessionId: String? = null,
    ) {
        mutableDefaultPermissionMode.value = mode
        runCatching {
            modeFile.parentFile?.mkdirs()
            modeFile.writeText(mode.cliValue)
        }
        val record = sessionId?.let(records::get) ?: return
        records[sessionId] = record.copy(permissionMode = mode.cliValue)
        persist()
    }

    private val titleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files =
        ClaudeWorkspaceFiles(
            workspaceHostDir = File(runtime.runtimeDirectory, "workspace"),
            rootfsHostDir = File(runtime.runtimeDirectory, "environment/rootfs"),
        )

    val auth: ClaudeAuthCoordinator get() = runtime.auth

    fun isInstalled(): Boolean = runtime.isInstalled()

    fun version(): String? = runtime.version()

    /** Installs the package and moves the target into whichever state the result implies. */
    fun install(onStep: (ClaudeCodeInstaller.Step) -> Unit = {}): Result<String> {
        mutableState.value = RuntimeState.Connecting
        return runCatching {
            runtime.install(onStep)
            runtime.version() ?: error("Claude Code did not report a version after installation")
        }.onSuccess { version ->
            mutableState.value = RuntimeState.Connected(version)
        }.onFailure { error ->
            mutableState.value = RuntimeState.Failed(error.message ?: messages.installFailed)
        }
    }

    /**
     * Upgrades the installed package, reporting the version on both sides of the attempt.
     *
     * The version is read before the upgrade rather than taken from the UI so the comparison
     * reflects what is on disk, not what a screen last happened to render.
     */
    fun update(): Result<ClaudeUpdateResult> =
        runCatching {
            val before = runtime.version()
            runtime.update()
            val after = runtime.version() ?: error("Claude Code did not report a version after the update")
            claudeUpdateResult(before, after)
        }.onSuccess { result -> mutableState.value = RuntimeState.Connected(result.version) }

    override suspend fun connect(): Result<OpenCodeHealth> =
        runCatching {
            val version = runtime.version() ?: error("Claude Code is not installed")
            mutableState.value = RuntimeState.Connected(version)
            OpenCodeHealth(true, version)
        }.onFailure { error ->
            mutableState.value = RuntimeState.Unavailable(error.message ?: "Claude Code is unavailable")
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth =
        withContext(Dispatchers.IO) {
            val version = runtime.version()
            mutableState.value =
                if (version.isNullOrBlank()) {
                    RuntimeState.Unavailable("Claude Code is not installed")
                } else {
                    RuntimeState.Connected(version)
                }
            OpenCodeHealth(!version.isNullOrBlank(), version.orEmpty())
        }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        records.values
            .map(ClaudeSessionRecord::session)
            .filter { directory == null || it.directory == directory }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val now = System.currentTimeMillis()
        // Claude Code's --session-id only accepts a UUID, and reusing the app's session id keeps the
        // two session stores aligned.
        val session =
            OpenCodeSession(
                id = UUID.randomUUID().toString(),
                directory = directory ?: "/workspace",
                title = title ?: DEFAULT_TITLE,
                time = OpenCodeTime(now, now),
            )
        records[session.id] = ClaudeSessionRecord(session, mutableDefaultPermissionMode.value.cliValue)
        persist()
        return session
    }

    /**
     * The transcript, with each assistant turn marked with the model this session uses.
     *
     * Reopening a chat restores its model from the newest message that names one, and Claude Code's
     * stream names the resolved build ("claude-sonnet-4-5-...") rather than the id the picker offers
     * ("sonnet"), which the picker cannot match. The session's own remembered model is that id, so
     * it fills the gap here - including for chats that were held before this existed.
     */
    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
        val messages = runtime.listMessages(sessionId)
        val model = records[sessionId]?.model ?: return messages
        val reference = OpenCodeModelReference(ClaudeModels.PROVIDER_ID, model)
        return messages.map { message ->
            if (message.info.role == "assistant" && message.info.model == null) {
                message.copy(info = message.info.copy(model = reference))
            } else {
                message
            }
        }
    }

    override suspend fun listProviders() = ClaudeModels.catalog(runtime.resolvedModels().value)

    override suspend fun listAgents() = listOf(OpenCodeAgent("claude", "Claude Code", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = records[sessionId] ?: error("Claude Code session not found")
        // Claude Code does not name sessions, so every chat would sit in the drawer as
        // "Claude Code". The first prompt stands in, the way OpenCode summarises its own.
        if (record.session.title == DEFAULT_TITLE) {
            // The prompt stands in immediately so the drawer is never left saying "Claude Code";
            // the summarised name replaces it once Claude answers.
            titleFromPrompt(request.text)?.let { renameSession(sessionId, it) }
            titleScope.launch {
                val summary = withContext(Dispatchers.IO) { runtime.summarizeTitle(request.text) }
                if (summary != null && records[sessionId] != null) renameSession(sessionId, summary)
            }
        }
        val model = request.modelId ?: record.model
        val effort = request.variant ?: record.effort
        // Remembered so reopening the chat keeps what the user last picked.
        if (model != record.model || effort != record.effort) {
            records[sessionId] = record.copy(model = model, effort = effort)
            persist()
        }
        withContext(Dispatchers.IO) {
            runtime.send(
                sessionId = sessionId,
                directory = record.session.directory ?: "/workspace",
                prompt = request.text,
                permissionMode = ClaudePermissionMode.fromCliValue(record.permissionMode),
                model = model,
                effort = effort,
                attachments = request.attachments,
            ).getOrThrow()
        }
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        withContext(Dispatchers.IO) { runtime.stop(sessionId) }
        return true
    }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession {
        val record = records[sessionId] ?: error("Claude Code session not found")
        val renamed =
            record.session.copy(
                title = title,
                time = record.session.time.copy(updated = System.currentTimeMillis()),
            )
        records[sessionId] = record.copy(session = renamed)
        persist()
        return renamed
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        val removed = records.remove(sessionId) != null
        if (removed) {
            persist()
        }
        withContext(Dispatchers.IO) { runtime.deleteSessionData(sessionId) }
        return removed
    }

    /**
     * Answers a PermissionRequest that the guest hook parked on the file bridge.
     *
     * Returns false when the bridge has no matching request (already timed out or unknown id).
     */
    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = withContext(Dispatchers.IO) { runtime.respondToPermission(permissionId, response, remember) }

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = withContext(Dispatchers.IO) { runtime.answerQuestion(requestId, answers) }

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    /**
     * Working-tree diff for the session's workspace.
     *
     * Claude Code has no server-side sessionDiff; the workspace git diff is the equivalent surface
     * OpenCode exposes for local review.
     */
    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> {
        val dir = directory ?: records[sessionId]?.session?.directory ?: WORKSPACE_ROOT
        return vcsDiff(dir, mode = "unified", context = 3)
    }

    // OpenCode answers these over HTTP. Claude Code has no server, but /workspace is a real
    // directory on the device, so they are read from disk instead of throwing "unsupported".
    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(Dispatchers.IO) { files.read(directory, path) }

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = withContext(Dispatchers.IO) { files.find(directory, query, includeDirectories, limit) }

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = withContext(Dispatchers.IO) { files.search(directory, pattern) }

    // Claude Code has no VCS protocol, but the sandbox has git and the workspace is mounted into it.
    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo =
        withContext(Dispatchers.IO) {
            val output =
                runtime.runInWorkspace(directory, ClaudeWorkspaceGit.INFO_SCRIPT)
                    ?: error("$directory is not a git repository")
            ClaudeWorkspaceGit.parseInfo(output)
        }

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> =
        withContext(Dispatchers.IO) {
            val status = runtime.runInWorkspace(directory, ClaudeWorkspaceGit.STATUS_SCRIPT).orEmpty()
            // Counts come from a second command because `git status` does not carry them; a
            // repository with no commits has nothing to diff against, hence the empty fallback.
            val counts =
                ClaudeWorkspaceGit.parseNumstat(runtime.runInWorkspace(directory, ClaudeWorkspaceGit.NUMSTAT_SCRIPT).orEmpty())
            ClaudeWorkspaceGit.parseStatus(status, counts).map { change ->
                // An untracked file has nothing to diff against, so git reports no counts for it.
                // Its own length is the addition, which is what OpenCode's server reports too.
                if (change.status != "added" || change.added > 0) return@map change
                val lines = change.path?.let { files.countLines(directory, it) } ?: return@map change
                change.copy(added = lines, additions = lines.toDouble())
            }
        }

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> =
        withContext(Dispatchers.IO) {
            ClaudeWorkspaceGit.parseDiff(runtime.runInWorkspace(directory, ClaudeWorkspaceGit.diffScript(context)).orEmpty())
        }

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = vcsStatus(directory)

    /**
     * The plan Claude is working to, from its own TodoWrite calls.
     *
     * [directory] is ignored: a Claude Code todo list belongs to the session, not the workspace.
     */
    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = runtime.todos(sessionId)

    /**
     * Slash commands from the workspace and the sandbox home.
     *
     * The CLI also announces its own list when a session starts; anything it named that has no file
     * behind it — built-ins, or a plugin's — is added so the list is not narrower than reality.
     */
    override suspend fun commands(): List<OpenCodeCommand> =
        withContext(Dispatchers.IO) {
            val fromDisk = ClaudeCommandCatalog.commands(runtime.catalogRoots(defaultDirectory()))
            val known = fromDisk.map(OpenCodeCommand::name).toSet()
            fromDisk + runtime.announcedCommands().filterNot { it.trimStart('/') in known }.map { OpenCodeCommand(it.trimStart('/')) }
        }

    override suspend fun skills(): List<OpenCodeSkill> =
        withContext(Dispatchers.IO) {
            val fromDisk = ClaudeCommandCatalog.skills(runtime.catalogRoots(defaultDirectory()))
            val known = fromDisk.map(OpenCodeSkill::name).toSet()
            fromDisk + runtime.announcedSkills().filterNot { it in known }.map { OpenCodeSkill(it) }
        }

    override suspend fun mcpServers(): List<McpServer> =
        withContext(Dispatchers.IO) {
            ClaudeMcpParser.parseList(
                runtime.runInWorkspace(defaultDirectory(), ClaudeMcpParser.LIST_SCRIPT, MCP_TIMEOUT_SECONDS).orEmpty(),
            )
        }

    /**
     * Adds a server through `claude mcp add`.
     *
     * The dialog collects the same fields for either runtime, so the OpenCode-shaped body is
     * translated here rather than giving Claude Code its own dialog.
     */
    override suspend fun addMcpServer(body: JsonObject): McpServer {
        val name = body.text("name")?.takeIf(String::isNotBlank) ?: error("An MCP server needs a name")
        val script =
            ClaudeMcpParser.addScript(name, body.text("url"), body.text("command"))
                ?: error("An MCP server needs a command or a URL")
        withContext(Dispatchers.IO) {
            runtime.runInWorkspace(defaultDirectory(), script, MCP_TIMEOUT_SECONDS)
                ?: error("Claude Code could not add the MCP server")
        }
        return mcpServers().firstOrNull { it.name == name } ?: McpServer(name = name)
    }

    /**
     * Deletes the server's configuration.
     *
     * Claude Code has no notion of connecting and disconnecting a configured server — it connects to
     * every one it knows about — so removal is the only meaningful operation, and the UI must say
     * so rather than offering a "disconnect" that silently deletes.
     */
    suspend fun removeMcpServer(name: String): Boolean =
        withContext(Dispatchers.IO) {
            runtime.runInWorkspace(defaultDirectory(), ClaudeMcpParser.removeScript(name), MCP_TIMEOUT_SECONDS) != null
        }

    override suspend fun removeMcpAuth(name: String): com.yugahashimoto.andcode.core.api.McpAuthRemoval =
        withContext(Dispatchers.IO) {
            com.yugahashimoto.andcode.core.api.McpAuthRemoval(
                runtime.runInWorkspace(defaultDirectory(), ClaudeMcpParser.logoutScript(name), MCP_TIMEOUT_SECONDS) != null,
            )
        }

    /** Where workspace-scoped questions are asked when the caller names no session. */
    private fun defaultDirectory(): String = records.values.lastOrNull()?.session?.directory ?: WORKSPACE_ROOT

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * Folders the agent can be pointed at.
     *
     * Past sessions are not the whole answer: a project cloned into the workspace but never opened
     * in a Claude chat would be missing from the picker, which is exactly when the user wants to
     * select it. The directories actually on disk are listed too.
     */
    override suspend fun listWorkspaces(): List<WorkspaceRef> {
        val fromSessions = records.values.mapNotNull { it.session.directory }
        val onDisk =
            withContext(Dispatchers.IO) {
                val root = File(runtime.runtimeDirectory, "workspace")
                listOf(WORKSPACE_ROOT) +
                    root.listFiles().orEmpty()
                        .filter { it.isDirectory && !it.isHidden }
                        .sortedBy { it.name.lowercase() }
                        .map { "$WORKSPACE_ROOT/${it.name}" }
            }
        return (fromSessions + onDisk).distinct().map { path ->
            WorkspaceRef(
                id = path,
                name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                path = path,
            )
        }
    }

    private fun titleFromPrompt(prompt: String): String? {
        val firstLine = prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        return if (firstLine.length <= TITLE_LENGTH) firstLine else firstLine.take(TITLE_LENGTH).trimEnd() + "…"
    }

    private fun persist() {
        runCatching {
            sessionsFile.parentFile?.mkdirs()
            sessionsFile.writeText(json.encodeToString(records.values.toList()))
        }
    }
}
