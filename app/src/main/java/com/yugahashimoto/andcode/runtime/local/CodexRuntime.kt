package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs Codex inside the shared Alpine/PRoot sandbox, over its `app-server` JSON-RPC protocol.
 *
 * Unlike [ClaudeCodeRuntime] (one process per chat, restarted with `--resume`), Codex's app-server
 * is a single long-lived process that multiplexes every open thread over one stdio connection - the
 * shape this app's `OpenCodeBackend` interface already assumes for a *remote* OpenCode server, just
 * reached over a local pipe instead of HTTP. A thread's own id doubles as this app's session id: it
 * is already a stable, server-issued identifier, so - unlike Claude Code - no separate resume-id
 * mapping is needed.
 *
 * Verified against the real protocol schema and a live, unauthenticated `codex app-server` process
 * for every shape used here that does not require a signed-in account; see docs/CODEX.md.
 */
class CodexRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val messages: CodexMessages = CodexMessages.Default,
    private val githubToken: () -> String? = { null },
) {
    private val json = defaultCodexJson
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val itemParser = CodexItemParser()
    private val messageStore = ClaudeMessageStore(File(runtimeDirectory, "codex-messages.json"), json)
    private val serverLock = Mutex()

    /**
     * Sessions with a turn in flight, each mapped to that turn's own id, so [onServerEnded] knows
     * which ones to settle - in both [messageStore] and [itemParser] - if the shared app-server
     * process dies mid-turn. `turn/start` itself returns as soon as the turn is accepted, with the
     * actual work streamed afterward via item and `turn/completed` notifications, so a dead process
     * between those two points would otherwise leave a `commandExecution`/`fileChange` tool part
     * rendered as "running" forever, the same failure mode [ClaudeCodeRuntime] guards against with
     * `messageStore.settleRunningTools` - and, without also evicting the turn id from [itemParser],
     * leak that turn's in-progress assistant message there for the rest of the app's life.
     */
    private val sessionsWithTurnInFlight = ConcurrentHashMap<String, String>()

    private class ServerProcess(
        val process: Process,
        val client: CodexJsonRpcClient,
        val readerJob: Job,
    )

    @Volatile private var server: ServerProcess? = null

    /** An approval request Codex is waiting on, keyed by the id this app hands out for it. */
    private data class PendingApproval(val rpcId: JsonElement, val kind: ApprovalKind)

    private enum class ApprovalKind { COMMAND, FILE_CHANGE }

    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    private val logins = CodexLoginTracker()

    private val mutableNeedsForeground = MutableStateFlow(false)

    /**
     * True while a ChatGPT sign-in is waiting on the browser or a turn is in flight: the two times
     * Codex must keep talking to the network with the app out of the foreground. The application
     * runs [CodexKeepAliveService] for exactly as long as this is true.
     */
    val needsForeground: StateFlow<Boolean> = mutableNeedsForeground.asStateFlow()

    private fun refreshForegroundNeed() {
        mutableNeedsForeground.value = sessionsWithTurnInFlight.isNotEmpty() || logins.pending
    }

    fun events(): Flow<OpenCodeEvent> = events

    /** Tells listeners Codex became usable, the event a chat that could not reach it waits for. */
    fun announceConnected() {
        events.tryEmit(OpenCodeEvent.ServerConnected)
    }

    fun isInstalled(): Boolean {
        val rootfs = installedRuntimeProvider()?.rootfs ?: return false
        return CodexInstaller.isInstalledIn(rootfs)
    }

    suspend fun install(abi: String): String {
        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        cachedVersion = null
        return runCatching {
            CodexInstaller.install(
                rootfs = runtime.rootfs,
                abi = abi,
                runtimeDirectory = runtimeDirectory,
                accessCoordinator = accessCoordinator,
            )
        }.getOrElse { throw IllegalStateException(messages.installFailed, it) }
    }

    /** A `codex` invocation in the shared sandbox, common to every process this runtime starts. */
    private fun buildCodexProcess(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        arguments: List<String>,
    ): ProcessBuilder =
        ProcessBuilder(
            CodexSandboxLauncher.command(
                runtime = runtime,
                workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() }.absolutePath,
                arguments = arguments,
            ),
        ).directory(runtimeDirectory)
            .apply {
                environment().clear()
                environment().putAll(
                    CodexSandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }, githubToken()),
                )
            }

    /** Cached the same way [ClaudeCodeRuntime.version] is: cheap to call, expensive to compute. */
    @Volatile private var cachedVersion: String? = null

    fun version(): String? {
        cachedVersion?.let { return it }
        if (!isInstalled()) return null
        val result = runCommand("${CodexSandboxLauncher.CODEX_BINARY} --version", timeoutSeconds = 30)
        if (result.exitCode != 0) return null
        return result.output.lineSequence().map(
            String::trim,
        ).firstOrNull(String::isNotEmpty)?.let(::parseVersionLine)?.also { cachedVersion = it }
    }

    /**
     * Signs in with an API key: `codex login --with-api-key` reading the key from stdin, run as a
     * one-shot process (verified against `codex login --help`: `--with-api-key` "Read the API key
     * from stdin"). The running app-server, if any, is stopped afterwards so the next call starts a
     * fresh process that picks up the new credentials - Codex does not expose a "reload auth" RPC.
     */
    suspend fun loginWithApiKey(apiKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
                require(CodexInstaller.isInstalledIn(runtime.rootfs)) { messages.notInstalled }
                // Output goes to a file, not a piped InputStream read with a coroutine timeout
                // wrapped around it: a plain blocking readText() has no suspension point, so
                // withTimeoutOrNull cannot actually interrupt it if the process hangs (a stalled
                // network call, on a flaky mobile connection) - it would wait for the read to finish
                // on its own regardless of the "timeout". Process.waitFor(timeout, unit) below is a
                // real, interruptible native timeout, the same pattern
                // ClaudeCodeRuntime.runInWorkspace uses.
                val outputFile = File.createTempFile("codex-login-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
                try {
                    val process =
                        buildCodexProcess(runtime, listOf("login", "--with-api-key"))
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                            .start()
                    process.outputStream.use { it.write((apiKey.trim() + "\n").toByteArray()) }
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error(messages.loginFailed)
                    }
                    if (process.exitValue() != 0) error("${messages.loginFailed}: ${outputFile.readText().trim()}")
                } finally {
                    outputFile.delete()
                }
                stopServer()
            }
        }

    suspend fun logout() {
        val runtime = installedRuntimeProvider() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                buildCodexProcess(runtime, listOf("logout")).redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS)
            }
        }
        stopServer()
    }

    /** A ChatGPT browser sign-in that has been started: [authUrl] is what to open, [loginId] what to poll. */
    data class ChatgptLogin(val loginId: String, val authUrl: String)

    /**
     * Starts Codex's ChatGPT browser sign-in (`account/login/start`, `type: chatgpt`).
     *
     * Codex serves its own OAuth callback on a local port, which the browser reaches through the
     * device's loopback because PRoot shares the host network - so, unlike the paste-a-code flows,
     * nothing has to be copied back by the user. Completion is read with [chatgptLoginOutcome].
     */
    suspend fun startChatgptLogin(): ChatgptLogin {
        val result = call("account/login/start", buildJsonObject { put("type", JsonPrimitive("chatgpt")) })
        // Shown in the sign-in dialog, so the translated message rather than a protocol detail.
        val loginId = result.string("loginId") ?: error(messages.loginFailed)
        val authUrl = result.string("authUrl") ?: error(messages.loginFailed)
        logins.begin(loginId)
        refreshForegroundNeed()
        return ChatgptLogin(loginId, authUrl)
    }

    /** How [loginId] ended, or null while the browser round trip is still in progress. */
    fun chatgptLoginOutcome(loginId: String): CodexLoginTracker.Outcome? = logins.outcome(loginId)

    /** Drops the recorded outcome once the UI has consumed it. */
    fun forgetChatgptLogin(loginId: String) {
        logins.forget(loginId)
        refreshForegroundNeed()
    }

    suspend fun cancelChatgptLogin(loginId: String) {
        logins.forget(loginId)
        refreshForegroundNeed()
        runCatching { call("account/login/cancel", buildJsonObject { put("loginId", JsonPrimitive(loginId)) }) }
    }

    suspend fun isSignedIn(): Boolean = runCatching { hasSignedInAccount(call("account/read")) }.getOrDefault(false)

    suspend fun modelList(): JsonObject = call("model/list")

    /** The configured MCP servers, from `codex mcp list --json`. */
    suspend fun mcpServers(): List<McpServer> =
        withContext(Dispatchers.IO) {
            if (!isInstalled()) return@withContext emptyList()
            CodexMcp.parseList(runCommand(CodexMcp.LIST_SCRIPT, timeoutSeconds = MCP_TIMEOUT_SECONDS).output)
        }

    /** Adds a server with `codex mcp add`: a streamable-HTTP [url], or a local [command] line. */
    suspend fun addMcpServer(
        name: String,
        url: String?,
        command: String?,
    ) {
        val script = CodexMcp.addScript(name, url, command) ?: error("An MCP server needs a command or a URL")
        withContext(Dispatchers.IO) {
            val result = runCommand(script, timeoutSeconds = MCP_TIMEOUT_SECONDS)
            check(result.exitCode == 0) { result.output.trim().ifBlank { "codex mcp add failed" } }
        }
        reloadMcpServers()
    }

    /** Deletes a server with `codex mcp remove`. */
    suspend fun removeMcpServer(name: String): Boolean {
        // A failure throws with Codex's own output, like addMcpServer, so the screen shows why rather
        // than just refreshing a list that still has the server in it.
        withContext(Dispatchers.IO) {
            val result = runCommand(CodexMcp.removeScript(name), timeoutSeconds = MCP_TIMEOUT_SECONDS)
            check(result.exitCode == 0) { result.output.trim().ifBlank { "codex mcp remove failed" } }
        }
        reloadMcpServers()
        return true
    }

    /**
     * Asks a running app-server to re-read its MCP configuration after `codex mcp` changed it, so the
     * next turn sees the change without restarting the process (and cutting short any turn in
     * flight). `config/mcpServer/reload` takes a `null` params value per the protocol schema. With no
     * server running there is nothing to reload: the next one reads the file when it starts.
     */
    private suspend fun reloadMcpServers() {
        val running = server?.takeIf { it.process.isAlive } ?: return
        runCatching { running.client.call("config/mcpServer/reload", JsonNull) }
    }

    suspend fun listSessions(): List<OpenCodeSession> {
        val result = call("thread/list")
        val threads = result["data"] as? JsonArray ?: return emptyList()
        return threads.mapNotNull { it as? JsonObject }.map(::threadToSession)
    }

    /** A single thread by id, without paging through [listSessions] - `thread/list` may not return every thread. */
    suspend fun session(sessionId: String): OpenCodeSession {
        val result = call("thread/read", buildJsonObject { put("threadId", JsonPrimitive(sessionId)) })
        return threadToSession(requireNotNull(result["thread"]?.jsonObject) { "Codex thread not found: $sessionId" })
    }

    suspend fun createSession(
        title: String?,
        directory: String,
    ): OpenCodeSession {
        val params =
            buildJsonObject {
                put("cwd", JsonPrimitive(directory))
            }
        val result = call("thread/start", params)
        val thread = result["thread"]?.jsonObject ?: error("Codex did not return a thread")
        val session = threadToSession(thread)
        val requestedTitle = title?.takeIf(String::isNotBlank)
        if (requestedTitle == null) return session
        // Applied to the returned object directly rather than re-fetching the thread: the rename
        // above already tells us what the title now is when it succeeds, and callers (matching
        // ClaudeCodeTarget/AntigravityTarget's createSession) expect the session they get back to
        // already carry the title they asked for.
        return runCatching { renameSession(session.id, requestedTitle) }
            .fold(onSuccess = { session.copy(title = requestedTitle) }, onFailure = { session })
    }

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ) {
        call(
            "thread/name/set",
            buildJsonObject {
                put("threadId", JsonPrimitive(sessionId))
                put("name", JsonPrimitive(title))
            },
        )
    }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messageStore.list(sessionId)

    suspend fun deleteSession(sessionId: String): Boolean =
        runCatching {
            call("thread/delete", buildJsonObject { put("threadId", JsonPrimitive(sessionId)) })
            messageStore.remove(sessionId)
            true
        }.getOrDefault(false)

    suspend fun archiveSession(sessionId: String): OpenCodeSession {
        call("thread/archive", buildJsonObject { put("threadId", JsonPrimitive(sessionId)) })
        return session(sessionId)
    }

    /**
     * Starts a turn. Image [attachments] are carried as Codex `image` inputs, an inline base64
     * `data:` URL per the app-server's `UserInput` schema (see [codexTurnInput]): the app-server
     * rejects remote HTTP(S) image URLs, and `UserInput` has no document/file variant, so any
     * non-image attachment is left out rather than sent in a shape Codex would reject.
     *
     * The prompt is not recorded locally the way [ClaudeCodeRuntime.recordUserMessage] does: unlike
     * Claude Code's stream, Codex's own `item/started`/`item/completed` echo the prompt straight
     * back as a `userMessage` item (see [CodexItemParser.handleUserMessage] and the fixture in
     * `CodexItemParserTest`), so recording it here too showed the user's own message twice.
     */
    suspend fun send(
        sessionId: String,
        prompt: String,
        model: String?,
        attachments: List<PromptAttachment> = emptyList(),
    ) {
        val params =
            buildJsonObject {
                put("threadId", JsonPrimitive(sessionId))
                put("input", codexTurnInput(prompt, attachments))
                model?.takeIf(String::isNotBlank)?.let { put("model", JsonPrimitive(it)) }
            }
        runCatching { call("turn/start", params) }
            .onSuccess { result ->
                // Recorded by turn id, not just session id, so onServerEnded can evict exactly this
                // turn's in-progress assistant message from CodexItemParser if the process dies
                // before turn/completed ever arrives - without the turn id there is nothing to pass
                // to itemParser.forgetTurn, and the entry would leak for the rest of the app's life.
                result["turn"]?.jsonObject?.string("id")?.let { turnId -> sessionsWithTurnInFlight[sessionId] = turnId }
                refreshForegroundNeed()
            }.onFailure { error ->
                events.tryEmit(OpenCodeEvent.SessionError(sessionId, error.message))
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
            }
    }

    suspend fun abort(sessionId: String): Boolean =
        runCatching {
            call("turn/interrupt", buildJsonObject { put("threadId", JsonPrimitive(sessionId)) })
            true
        }.getOrDefault(false)

    /** Answers an approval request raised by [handleServerRequest], by this app's own request id. */
    suspend fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean {
        val pending = pendingApprovals.remove(permissionId) ?: return false
        val decision =
            when {
                response == PermissionResponse.REJECT -> "decline"
                response == PermissionResponse.ALWAYS || remember -> "acceptForSession"
                else -> "accept"
            }
        val server = server ?: return false
        return runCatching {
            server.client.respond(pending.rpcId, buildJsonObject { put("decision", JsonPrimitive(decision)) })
            true
        }.getOrDefault(false)
    }

    fun stopAll() {
        scope.launch { stopServer() }
    }

    private suspend fun stopServer() = serverLock.withLock { stopServerLocked() }

    /** Assumes [serverLock] is already held - [ensureServer] calls this directly to avoid deadlocking on its own lock. */
    private fun stopServerLocked() {
        server?.let { current ->
            current.readerJob.cancel()
            if (current.process.isAlive) current.process.destroyForcibly()
            // Unlike onServerEnded's crash path, a deliberate stop skips launchCodexReaderLoop's
            // onEnded entirely (see its own doc comment), so nothing else fails a call left
            // suspended in CodexJsonRpcClient.pending - without this, a coroutine awaiting a reply
            // mid-stop (e.g. abort()'s turn/interrupt racing logout()) would hang forever instead of
            // failing.
            current.client.failPending(IllegalStateException("Codex was stopped"))
            // A deliberate stop (sign-out, API-key sign-in, disconnect) ends whatever the process
            // was doing just as a crash does, so it has to settle the same state - otherwise a turn
            // or sign-in it cut short kept needsForeground true, and CodexKeepAliveService and its
            // notification stayed up until the process died.
            settleServerGone(messages.stopped)
        }
        server = null
    }

    private suspend fun call(
        method: String,
        params: JsonElement? = null,
    ): JsonObject = ensureServer().call(method, params)

    private suspend fun ensureServer(): CodexJsonRpcClient =
        serverLock.withLock {
            val existing = server
            if (existing != null && existing.process.isAlive) return existing.client

            val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
            require(CodexInstaller.isInstalledIn(runtime.rootfs)) { messages.notInstalled }

            val stderrLog = File(runtimeDirectory, "logs/codex-stderr.log").also { it.parentFile?.mkdirs() }
            val process =
                buildCodexProcess(runtime, listOf("app-server"))
                    .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                    .start()

            lateinit var client: CodexJsonRpcClient
            client =
                CodexJsonRpcClient(
                    output = process.outputStream,
                    onNotification = ::handleNotification,
                    onServerRequest = { id, method, params -> handleServerRequest(client, id, method, params) },
                )
            val readerJob =
                launchCodexReaderLoop(
                    scope = scope,
                    lines = process.inputStream.bufferedReader().lineSequence(),
                    client = client,
                    onEnded = { failure -> onServerEnded(process, client, failure) },
                )
            val created = ServerProcess(process, client, readerJob)
            server = created
            runCatching {
                client.call(
                    "initialize",
                    buildJsonObject {
                        put(
                            "clientInfo",
                            buildJsonObject {
                                put("name", JsonPrimitive("and-code"))
                                put("title", JsonPrimitive("AndCode"))
                                put("version", JsonPrimitive(version() ?: "0"))
                            },
                        )
                    },
                )
            }.onFailure {
                stopServerLocked()
                throw it
            }
            client
        }

    private fun onServerEnded(
        process: Process,
        client: CodexJsonRpcClient,
        failure: Throwable?,
    ) {
        val error = messages.processExited(runCatching { process.exitValue() }.getOrNull(), failure?.message)
        client.failPending(failure ?: IllegalStateException(error))
        // This only runs for a process that ended on its own (launchCodexReaderLoop skips onEnded
        // for a deliberate stop), so any session still marked in-flight here had a turn genuinely
        // cut short - the same "settle what a dead process left running" step ClaudeCodeRuntime
        // takes, needed because turn/start itself already returned before the actual work streamed
        // in via later notifications.
        settleServerGone(error)
        scope.launch {
            serverLock.withLock {
                if (server?.process === process) server = null
            }
        }
    }

    /** Settles everything that was waiting on an app-server that is no longer running. */
    private fun settleServerGone(error: String) {
        sessionsWithTurnInFlight.toMap().forEach { (sessionId, turnId) ->
            sessionsWithTurnInFlight -= sessionId
            itemParser.forgetTurn(turnId)
            messageStore.settleRunningTools(sessionId, error)
                .flatMap { it.parts }
                .filter { it.type == "tool" }
                .forEach { part -> events.tryEmit(OpenCodeEvent.MessagePartUpdated(part)) }
            events.tryEmit(OpenCodeEvent.SessionError(sessionId, error))
            events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        }
        // Every pending approval was waiting on a reply from this process: its rpcId means nothing
        // to a server that isn't running any more (respondToPermission already no-ops once `server`
        // is null, but without this the map entry itself would otherwise sit forever, since nothing
        // else ever removes an entry the user never actually answered).
        pendingApprovals.clear()
        // A sign-in that was waiting on this process can no longer complete.
        logins.abandon()
        refreshForegroundNeed()
    }

    private fun handleNotification(
        method: String,
        params: JsonElement?,
    ) {
        val body = params as? JsonObject ?: return
        if (method == LOGIN_COMPLETED_METHOD) {
            logins.onCompleted(body)
            refreshForegroundNeed()
            return
        }
        val sessionId = body.string("threadId") ?: return
        val parsed = itemParser.handleNotification(sessionId, method, body)
        if (parsed.events.any { it is OpenCodeEvent.SessionIdle }) {
            // itemParser already forgets its own in-progress message when the notification body
            // carries a turnId, but a terminal error can arrive without one (TurnErrorNotification's
            // schema does not require it) - fall back to the turnId this runtime captured from
            // turn/start's own response so that case cannot leak an assistantMessages entry either.
            sessionsWithTurnInFlight.remove(sessionId)?.let(itemParser::forgetTurn)
            refreshForegroundNeed()
        }
        parsed.messages.forEach { message -> messageStore.upsert(sessionId, message) }
        parsed.events.forEach(events::tryEmit)
        if (parsed.events.any { it is OpenCodeEvent.SessionIdle }) messageStore.flush()
    }

    private fun handleServerRequest(
        client: CodexJsonRpcClient,
        id: JsonElement,
        method: String,
        params: JsonElement?,
    ) {
        val body = params as? JsonObject
        val sessionId = body?.string("threadId")
        val approvalKind =
            when (method) {
                "item/commandExecution/requestApproval" -> ApprovalKind.COMMAND
                "item/fileChange/requestApproval" -> ApprovalKind.FILE_CHANGE
                else -> null
            }
        // sessionId is derived from body?.string(...), so sessionId != null already implies body is
        // non-null; an explicit `body != null` check here was flagged as always true.
        if (approvalKind != null && sessionId != null) {
            emitApproval(id, sessionId, approvalKind, body)
            return
        }
        // Every unhandled server-initiated request reaches here: either a method with no UI wired up
        // yet (permissions policy, MCP elicitation, tool calls, ChatGPT token refresh), or an
        // approval request missing a field this app relies on (threadId, params) - an unverified
        // shape per docs/CODEX.md. Either way, refusing loudly is safer than silently hanging the
        // server for a reply that will never come and leaving that thread's turn stuck forever.
        val reason = if (approvalKind != null) "Malformed $method request" else "Method not supported: $method"
        scope.launch { runCatching { client.respondError(id, -32601, reason) } }
    }

    private fun emitApproval(
        rpcId: JsonElement,
        sessionId: String,
        kind: ApprovalKind,
        body: JsonObject,
    ) {
        val requestId = "codex-approval-${rpcId.jsonPrimitive.contentOrNull ?: rpcId}"
        pendingApprovals[requestId] = PendingApproval(rpcId, kind)
        val request: PermissionRequest =
            when (kind) {
                ApprovalKind.COMMAND -> CodexItemParser.commandApprovalToPermissionRequest(sessionId, requestId, body)
                ApprovalKind.FILE_CHANGE -> CodexItemParser.fileChangeApprovalToPermissionRequest(sessionId, requestId, body)
            }
        events.tryEmit(OpenCodeEvent.PermissionAsked(request))
    }

    private fun threadToSession(thread: JsonObject): OpenCodeSession {
        val id = thread.string("id") ?: error("Codex thread is missing an id")
        // Confirmed live (docs/CODEX.md): a real `thread/start` returned "createdAt": 1789837435 -
        // ten digits, i.e. Unix seconds - unlike every millisecond field this protocol names with an
        // explicit `Ms` suffix (`startedAtMs`, `completedAtMs`, `emittedAtMs`). Multiplying by 1000
        // here converts to the epoch-ms this app's OpenCodeTime expects everywhere else.
        val createdAt = (thread["createdAt"] as? JsonPrimitive)?.longOrNullCompat()?.times(1000) ?: System.currentTimeMillis()
        val updatedAt = (thread["updatedAt"] as? JsonPrimitive)?.longOrNullCompat()?.times(1000) ?: createdAt
        return OpenCodeSession(
            id = id,
            directory = thread.string("cwd"),
            title = thread.string("preview")?.takeIf(String::isNotBlank) ?: DEFAULT_TITLE,
            time = OpenCodeTime(createdAt, updatedAt),
        )
    }

    private fun runCommand(
        command: String,
        timeoutSeconds: Long,
    ): LocalRuntimeCommandResult =
        LocalRuntimeCommandRunner(
            runtimeDirectory = runtimeDirectory,
            installedRuntimeProvider = installedRuntimeProvider,
            accessCoordinator = accessCoordinator,
            timeoutSeconds = timeoutSeconds,
        ).runShell(command, timeoutSeconds)

    private companion object {
        const val DEFAULT_TITLE = "Codex"
        const val LOGIN_COMPLETED_METHOD = "account/login/completed"
        const val MCP_TIMEOUT_SECONDS = 30L

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonPrimitive.longOrNullCompat(): Long? = runCatching { long }.getOrNull()
    }
}

internal val defaultCodexJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

/**
 * The bare version from a `codex --version` line, which prints `codex-cli 0.155.1`.
 *
 * The product name is dropped because every place that shows the version already names the agent
 * (`Codex %1$s`), which otherwise read "Codex codex-cli 0.155.1".
 */
internal fun parseVersionLine(line: String): String = line.trim().removePrefix("codex-cli").trim()

/**
 * Whether an `account/read` result names an account. Signed out it is `{"account": null, ...}`, which
 * kotlinx.serialization decodes to [JsonNull] - a real element, not a missing key - so a plain `!= null`
 * check reported every signed-out install as signed in.
 */
internal fun hasSignedInAccount(accountRead: JsonObject): Boolean = accountRead["account"] is JsonObject

/**
 * The `turn/start` `input` array for [prompt] plus any image [attachments].
 *
 * Codex's `UserInput` has no document/file variant, and its app-server explicitly rejects remote
 * HTTP(S) image URLs - only an inline `data:` URL or a `localImage` path is accepted - so only
 * image MIME attachments carried as base64 data URLs are forwarded. Anything else is left out rather
 * than sent in a shape Codex would reject; the app's `AttachmentImporter` always emits data URLs.
 * Verified against the v2 `UserInput` schema (`codex app-server generate-json-schema`), where the
 * app-server's `Image` variant serializes its URL as `url` under a `"type":"image"` tag.
 */
internal fun codexTurnInput(
    prompt: String,
    attachments: List<PromptAttachment>,
): JsonArray =
    buildJsonArray {
        add(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(prompt))
            },
        )
        attachments.forEach { attachment ->
            if (attachment.mime.startsWith("image/") && attachment.url.startsWith("data:")) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("image"))
                        put("url", JsonPrimitive(attachment.url))
                    },
                )
            }
        }
    }
