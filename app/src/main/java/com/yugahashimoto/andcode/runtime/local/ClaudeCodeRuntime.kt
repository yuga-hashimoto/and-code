package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.OpenCodeTodo
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/**
 * Runs Claude Code inside the shared Alpine/PRoot sandbox.
 *
 * The CLI is driven in streaming-JSON mode (`--print --input-format stream-json --output-format
 * stream-json`), which keeps one process alive per chat session and exchanges structured messages
 * over stdin/stdout. Conversation state lives in Claude Code's own session store, so a process that
 * dies is relaunched with `--resume` and the history is preserved.
 */
class ClaudeCodeRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val messages: ClaudeMessages = ClaudeMessages,
    /**
     * The user's GitHub token, so `gh` and git-over-HTTPS work here as they do for OpenCode.
     *
     * The OpenCode server has always been launched with it; Claude Code was not, which is why `gh`
     * reported no logged-in host inside the same sandbox where OpenCode was authenticated.
     */
    private val githubToken: () -> String? = { null },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageStore = ClaudeMessageStore(File(runtimeDirectory, "claude-messages.json"), json)
    internal val permissionBridge =
        ClaudePermissionBridge(File(runtimeDirectory, ClaudePermissionBridge.HOST_DIR_NAME))
    private var bridgeWatchJob: Job? = null

    /** One live CLI process, plus what is needed to decide whether it can be reused. */
    private class SessionProcess(
        val process: Process,
        val readerJob: Job,
        val parser: ClaudeStreamJsonParser,
        val permissionMode: ClaudePermissionMode,
        val directory: String,
        val model: String?,
        val effort: String?,
    )

    private val sessions = linkedMapOf<String, SessionProcess>()

    /**
     * Claude Code's own session ids, so a relaunched process resumes rather than starts over.
     *
     * On disk, because Claude Code's session store outlives this app's process while this map used
     * not to. A first launch passes `--session-id <id>`, which *creates* that session in the CLI's
     * store; every later launch has to pass `--resume <id>` instead. After the app was restarted
     * this map was empty, so an existing chat was started with `--session-id` again and the CLI
     * refused it - "Error: Session ID ... is already in use." went to the stderr log, the process
     * exited immediately, and the chat accepted messages and never answered any of them.
     */
    private val resumeIdsFile = File(runtimeDirectory, "claude-resume-ids.json")
    private val resumeIds: MutableMap<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(resumeIdsFile.readText()).toMutableMap() }
            .getOrDefault(mutableMapOf())

    private fun persistResumeIds() {
        runCatching {
            resumeIdsFile.parentFile?.mkdirs()
            resumeIdsFile.writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), resumeIds.toMap()))
        }
    }

    /**
     * The plan Claude is working to, per session.
     *
     * Claude Code has no endpoint for this, but every TodoWrite call carries the whole list, so the
     * newest one seen is the current state. Kept in memory only: a plan belongs to the turn that
     * produced it, and a restarted app has no turn in flight.
     */
    private val todos = mutableMapOf<String, List<OpenCodeTodo>>()

    /** Slash commands and skills as the CLI announced them at the last session start. */
    private var announcedCommands: List<String> = emptyList()
    private var announcedSkills: List<String> = emptyList()

    @Synchronized
    fun todos(sessionId: String): List<OpenCodeTodo> = todos[sessionId].orEmpty()

    @Synchronized
    fun announcedCommands(): List<String> = announcedCommands

    @Synchronized
    fun announcedSkills(): List<String> = announcedSkills

    /** Directories Claude Code reads commands and skills from, personal first then per-project. */
    fun catalogRoots(directory: String): List<File> {
        val rootfs = installedRuntimeProvider()?.rootfs ?: return emptyList()
        val workspaceRoot = File(runtimeDirectory, "workspace")
        val project = directory.removePrefix("/workspace").trim('/')
        return listOfNotNull(
            File(rootfs, "root/.claude"),
            File(if (project.isEmpty()) workspaceRoot else File(workspaceRoot, project), ".claude"),
        ).filter(File::isDirectory)
    }

    /**
     * Alias to the model id Claude reported for it, so the picker can name models truthfully.
     *
     * Anthropic re-points these aliases over time, so a cached name is only trustworthy for as long
     * as the alias means what it did when it was recorded. Two things keep it honest: every run
     * overwrites the entry for the alias it used, and the whole cache is dropped when the CLI
     * version changes, which is when a re-point is most likely. An alias the user has not sent to
     * since a server-side re-point can still lag until the next message on it.
     */
    private val resolvedModelsFile = File(runtimeDirectory, "claude-resolved-models.json")
    private val resolvedModels = MutableStateFlow<Map<String, String>>(emptyMap())

    val auth = ClaudeAuthCoordinator(runtimeDirectory, installedRuntimeProvider, accessCoordinator, messages, githubToken)

    fun events(): Flow<OpenCodeEvent> = events

    /** Alias to resolved model id, learned from run output. */
    fun resolvedModels(): StateFlow<Map<String, String>> = resolvedModels.asStateFlow()

    fun isInstalled(): Boolean = installedRuntimeProvider()?.rootfs?.let(ClaudeCodeInstaller::isInstalledIn) == true

    /**
     * Cached because reading it is not cheap: it starts a ~250 MB binary under PRoot, and the UI
     * asks for it on every refresh and health check. Enough of those at once put the device under
     * memory pressure and got the OpenCode server killed alongside them. The value only changes on
     * install or update, and both clear it.
     */
    @Volatile
    private var cachedVersion: String? = null

    fun version(): String? {
        cachedVersion?.let { return it }
        if (!isInstalled()) return null
        val result = runCommand("${ClaudeCodeInstaller.CLAUDE_BINARY} --version", timeoutSeconds = 120)
        if (result.exitCode != 0) return null
        return readVersion(result.output).also { cachedVersion = it }
    }

    private fun readVersion(output: String): String? =
        output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.let { line -> VERSION.find(line)?.value ?: line }

    /** Installs Claude Code into the already-provisioned sandbox. */
    fun install(onStep: (ClaudeCodeInstaller.Step) -> Unit = {}) {
        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        cachedVersion = null
        accessCoordinator.write {
            ClaudeCodeInstaller.installInto(runtime.rootfs, runtime.commandSuite, runtimeDirectory, onStep)
        }
    }

    /** True when the PermissionRequest hook is present in the guest rootfs. */
    fun permissionBridgeReady(): Boolean {
        val rootfs = installedRuntimeProvider()?.rootfs ?: return false
        return ClaudePermissionHooks.isInstalled(rootfs)
    }

    fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = permissionBridge.respond(permissionId, response, remember)

    fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
    ): Boolean {
        val pending = permissionBridge.readPending(requestId) ?: return false
        val question = permissionBridge.toQuestionRequest(pending) ?: return false
        val mapped = linkedMapOf<String, String>()
        question.questions.forEachIndexed { index, prompt ->
            val selected = answers.getOrNull(index).orEmpty().joinToString(", ")
            if (selected.isNotBlank()) mapped[prompt.question] = selected
        }
        val questionsJson =
            runCatching {
                json.parseToJsonElement(pending.toolInputJson).jsonObject["questions"]?.toString() ?: "[]"
            }.getOrDefault("[]")
        return permissionBridge.answerQuestion(requestId, questionsJson, mapped)
    }

    /**
     * Questions still waiting for an answer, so a chat opened after the event was missed can
     * recover them.
     *
     * Only requests whose session process is alive qualify: the hook that parks a request dies
     * with the CLI process, so a file left behind by a dead session (an app restart, an aborted
     * run) can never be answered and must not be shown.
     */
    @Synchronized
    fun pendingQuestions(): List<QuestionRequest> =
        permissionBridge
            .pendingRequests()
            .filter { it.kind == ClaudePermissionBridge.Kind.QUESTION }
            .filter { sessions[it.androidSessionId]?.process?.isAlive == true }
            .mapNotNull(permissionBridge::toQuestionRequest)

    private fun ensureBridgeWatcher() {
        if (bridgeWatchJob?.isActive == true) return
        bridgeWatchJob =
            scope.launch {
                while (isActive) {
                    permissionBridge.pollPending().forEach { request ->
                        when (request.kind) {
                            ClaudePermissionBridge.Kind.QUESTION -> {
                                val question = permissionBridge.toQuestionRequest(request)
                                if (question != null) {
                                    events.tryEmit(OpenCodeEvent.QuestionAsked(question))
                                } else {
                                    events.tryEmit(
                                        OpenCodeEvent.PermissionAsked(permissionBridge.toPermissionRequest(request)),
                                    )
                                }
                            }
                            else ->
                                events.tryEmit(
                                    OpenCodeEvent.PermissionAsked(permissionBridge.toPermissionRequest(request)),
                                )
                        }
                    }
                    delay(250)
                }
            }
    }

    fun update() {
        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        cachedVersion = null
        accessCoordinator.write {
            ClaudeCodeInstaller.updateIn(runtime.rootfs, runtime.commandSuite, runtimeDirectory)
        }
    }

    @Synchronized
    fun send(
        sessionId: String,
        directory: String,
        prompt: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
        effort: String?,
        attachments: List<PromptAttachment> = emptyList(),
    ): Result<Unit> =
        runCatching {
            val session = ensureProcess(sessionId, directory.ifBlank { "/workspace" }, permissionMode, model, effort)
            session.parser.beginTurn()
            recordUserMessage(sessionId, prompt, attachments)
            session.process.outputStream.apply {
                write((json.encodeToString(JsonObject.serializer(), userMessage(prompt, attachments)) + "\n").toByteArray())
                flush()
            }
            Unit
        }.onFailure { error ->
            events.tryEmit(OpenCodeEvent.SessionError(sessionId, error.message))
            events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        }

    /** Stops the process backing [sessionId]; the next send resumes the same Claude conversation. */
    @Synchronized
    fun stop(sessionId: String) {
        sessions.remove(sessionId)?.let { session ->
            session.readerJob.cancel()
            if (session.process.isAlive) session.process.destroyForcibly()
        }
        messageStore.flush()
    }

    @Synchronized
    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
        auth.cancel()
    }

    @Synchronized
    fun listMessages(sessionId: String): List<OpenCodeMessage> {
        if (sessions[sessionId]?.process?.isAlive != true) {
            return messageStore.settleRunningTools(sessionId, INTERRUPTED_TOOL_ERROR)
        }
        return messageStore.list(sessionId)
    }

    @Synchronized
    fun deleteSessionData(sessionId: String) {
        stop(sessionId)
        resumeIds.remove(sessionId)
        persistResumeIds()
        todos.remove(sessionId)
        messageStore.remove(sessionId)
    }

    private fun ensureProcess(
        sessionId: String,
        directory: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
        effort: String?,
    ): SessionProcess {
        val existing = sessions[sessionId]
        // Permission mode, working directory and model are read once at startup, so a change to any
        // of them means the process has to be replaced rather than reused.
        if (existing != null &&
            existing.process.isAlive &&
            existing.permissionMode == permissionMode &&
            existing.directory == directory &&
            existing.model == model &&
            existing.effort == effort
        ) {
            return existing
        }
        if (existing != null) stop(sessionId)

        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        require(ClaudeCodeInstaller.isInstalledIn(runtime.rootfs)) { "Claude Code is not installed" }
        ClaudeCodeInstaller.ensureDnsPreload(runtime.rootfs)
        ensureBridgeWatcher()

        val effectiveMode =
            if (permissionMode.requiresBridge && !ClaudePermissionHooks.isInstalled(runtime.rootfs)) {
                ClaudePermissionMode.ACCEPT_EDITS
            } else {
                permissionMode
            }

        val stderrLog = File(runtimeDirectory, "logs/claude-stderr.log").also { it.parentFile?.mkdirs() }
        val process =
            ProcessBuilder(
                ClaudeSandboxLauncher.command(
                    runtime = runtime,
                    workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() },
                    workingDirectory = directory,
                    arguments = processArguments(sessionId, effectiveMode, model, effort),
                    pty = false,
                ),
            ).directory(runtimeDirectory)
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .apply {
                    environment().clear()
                    environment().putAll(
                        ClaudeSandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }, githubToken()) +
                            mapOf("ANDCODE_ANDROID_SESSION_ID" to sessionId),
                    )
                }
                .start()

        val parser = ClaudeStreamJsonParser(sessionId, json)
        val requestedModel = model
        val readerJob =
            scope.launch {
                val streamFailure =
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            handleLine(sessionId, parser, line, requestedModel)
                        }
                    }.exceptionOrNull()
                        .takeUnless { it is CancellationException }
                messageStore.flush()
                synchronized(this@ClaudeCodeRuntime) {
                    if (sessions[sessionId]?.process === process) sessions.remove(sessionId)
                }
                // stop() cancels this job; the abort path owns the state transitions, and an idle
                // emitted here would announce the killed run as completed.
                if (!isActive) return@launch
                // A CLI that exits before its result line would otherwise leave the chat spinning
                // forever; that is a failure, not a completion. A finished turn already emitted its
                // own idle, and repeating it on process exit would re-announce the old run.
                if (!parser.turnFinished) {
                    val exitCode = runCatching { process.exitValue() }.getOrNull()
                    events.tryEmit(
                        OpenCodeEvent.SessionError(sessionId, messages.processExited(exitCode, streamFailure?.message)),
                    )
                    events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                }
            }

        return SessionProcess(process, readerJob, parser, permissionMode, directory, model, effort)
            .also { sessions[sessionId] = it }
    }

    private fun processArguments(
        sessionId: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
        effort: String?,
    ): List<String> =
        buildList {
            add("--print")
            add("--input-format")
            add("stream-json")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            add("--permission-mode")
            add(permissionMode.cliValue)
            permissionMode.allowedTools.takeIf(List<String>::isNotEmpty)?.let { tools ->
                add("--allowedTools")
                addAll(tools)
            }
            ClaudeModels.cliModel(model)?.let {
                add("--model")
                add(it)
            }
            ClaudeModels.cliEffort(effort)?.let {
                add("--effort")
                add(it)
            }
            // `--session-id` *creates* the session in Claude Code's store, so it is only right the
            // first time; after that the CLI rejects it as already in use. A chat that already has
            // messages has been started before, and the app started it under this very id, so its
            // id in the CLI's store is the same one. That covers chats begun before these ids were
            // persisted, which would otherwise never be answerable again.
            val resumeId = resumeIds[sessionId] ?: sessionId.takeIf { messageStore.list(it).isNotEmpty() }
            if (resumeId != null) {
                add("--resume")
                add(resumeId)
            } else {
                add("--session-id")
                add(sessionId)
            }
        }

    /**
     * Builds one stream-json user turn.
     *
     * The content array follows the Anthropic Messages API shape the CLI expects on stdin: any
     * attachments become `image`/`document` blocks and the typed text follows them. Attachments the
     * model cannot ingest inline (anything that is not an image or a PDF) are dropped here rather than
     * sent as unusable blocks that would make the CLI reject the whole turn.
     */
    private fun userMessage(
        prompt: String,
        attachments: List<PromptAttachment>,
    ): JsonObject {
        val content =
            buildList {
                attachments.forEach { attachment -> attachmentBlock(attachment)?.let(::add) }
                // Keep the text block even when blank so an attachment-only turn is still well-formed.
                if (prompt.isNotEmpty() || isEmpty()) {
                    add(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive(prompt),
                            ),
                        ),
                    )
                }
            }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("user"),
                "message" to
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("user"),
                            "content" to JsonArray(content),
                        ),
                    ),
            ),
        )
    }

    /**
     * Turns a `data:` attachment URL into the block the Messages API inlines it as, or null when the
     * type cannot be inlined. [AttachmentImporter] always emits base64 data URLs, so this parses that
     * shape and reuses the recorded [PromptAttachment.mime].
     */
    private fun attachmentBlock(attachment: PromptAttachment): JsonObject? {
        val base64 = attachment.url.substringAfter("base64,", missingDelimiterValue = "")
        if (base64.isEmpty()) return null
        val kind =
            when {
                attachment.mime.startsWith("image/") -> "image"
                attachment.mime == "application/pdf" -> "document"
                else -> return null
            }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive(kind),
                "source" to
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("base64"),
                            "media_type" to JsonPrimitive(attachment.mime),
                            "data" to JsonPrimitive(base64),
                        ),
                    ),
            ),
        )
    }

    private fun handleLine(
        sessionId: String,
        parser: ClaudeStreamJsonParser,
        line: String,
        requestedModel: String?,
    ) {
        if (line.isBlank()) return
        val parsed = parser.parse(line)
        parsed.claudeSessionId?.let { claudeSessionId ->
            synchronized(this) {
                if (resumeIds[sessionId] != claudeSessionId) {
                    resumeIds[sessionId] = claudeSessionId
                    persistResumeIds()
                }
            }
        }
        if (requestedModel != null) parsed.resolvedModel?.let { rememberResolvedModel(requestedModel, it) }
        parsed.todos?.let { synchronized(this) { todos[sessionId] = it } }
        parsed.slashCommands?.let { synchronized(this) { announcedCommands = it } }
        parsed.skills?.let { synchronized(this) { announcedSkills = it } }
        parsed.messages.forEach { message -> messageStore.upsert(sessionId, message) }
        parsed.events.forEach(events::tryEmit)
        if (parsed.turnFinished) messageStore.flush()
    }

    /**
     * Loads the cache, discarding it when the CLI version has moved on.
     *
     * Deliberately not done in the constructor: the version check runs the CLI, which is far too
     * slow for whatever thread builds this object.
     */
    fun loadResolvedModels() {
        resolvedModels.value = readResolvedModels()
    }

    private fun readResolvedModels(): Map<String, String> {
        val stored =
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), resolvedModelsFile.readText())
            }.getOrNull() ?: return emptyMap()
        // A CLI upgrade is the most likely moment for an alias to start meaning a different model.
        return if (stored[CLI_VERSION_KEY] == version()) stored - CLI_VERSION_KEY else emptyMap()
    }

    private fun rememberResolvedModel(
        alias: String,
        resolved: String,
    ) {
        if (resolvedModels.value[alias] == resolved) return
        val updated = resolvedModels.value + (alias to resolved)
        resolvedModels.value = updated
        runCatching {
            resolvedModelsFile.parentFile?.mkdirs()
            val persisted = updated + (CLI_VERSION_KEY to version().orEmpty())
            resolvedModelsFile.writeText(
                json.encodeToString(MapSerializer(String.serializer(), String.serializer()), persisted),
            )
        }
    }

    private fun recordUserMessage(
        sessionId: String,
        prompt: String,
        attachments: List<PromptAttachment>,
    ) {
        val timestamp = System.currentTimeMillis()
        val messageId = "user-${UUID.randomUUID()}"
        val parts =
            buildList {
                add(OpenCodePart("$messageId-text", sessionId, messageId, "text", text = prompt))
                attachments.forEachIndexed { index, attachment ->
                    add(
                        OpenCodePart(
                            id = "$messageId-file-$index",
                            sessionId = sessionId,
                            messageId = messageId,
                            type = "file",
                            filename = attachment.filename,
                            mime = attachment.mime,
                            url = attachment.url,
                        ),
                    )
                }
            }
        messageStore.upsert(
            sessionId,
            OpenCodeMessage(
                info =
                    OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "user",
                        time = OpenCodeTime(timestamp, timestamp),
                    ),
                parts = parts,
            ),
        )
    }

    /**
     * Asks Claude for a short title for a new chat.
     *
     * OpenCode's server names its own sessions from the first prompt, and Claude Code does not, so
     * this mirrors it with one cheap call on the fastest model. Arguments are passed directly rather
     * than through a shell, so the prompt needs no quoting. Returns null on any failure; the caller
     * falls back to the prompt itself.
     */
    fun summarizeTitle(prompt: String): String? {
        val runtime = installedRuntimeProvider() ?: return null
        if (!ClaudeCodeInstaller.isInstalledIn(runtime.rootfs)) return null
        val instruction =
            "Reply with a title of at most 6 words for a chat that starts with the following " +
                "message. Reply with the title only, no quotes, no punctuation at the end, in the " +
                "same language as the message.\n\n" + prompt.take(TITLE_PROMPT_LIMIT)
        val process =
            runCatching {
                ProcessBuilder(
                    ClaudeSandboxLauncher.command(
                        runtime = runtime,
                        workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() },
                        workingDirectory = "/workspace",
                        arguments = listOf("--print", "--model", "haiku", "--tools", "", instruction),
                        pty = false,
                    ),
                ).directory(runtimeDirectory)
                    .redirectErrorStream(false)
                    .apply {
                        environment().clear()
                        environment().putAll(
                            ClaudeSandboxLauncher.environment(
                                runtime,
                                File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                githubToken(),
                            ),
                        )
                    }
                    .start()
            }.getOrNull() ?: return null
        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrNull()
        if (!process.waitFor(TITLE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return output
            ?.lineSequence()
            ?.map { it.trim().trim('"') }
            ?.firstOrNull { it.isNotEmpty() }
            ?.takeIf { it.length <= TITLE_MAX_LENGTH }
    }

    /**
     * Runs [script] in the sandbox with the workspace mounted, and returns its output.
     *
     * The questions the explorer and the MCP screen ask have no place in Claude Code's streaming
     * protocol, but the sandbox already contains the tools that answer them. Unlike
     * [LocalRuntimeCommandRunner] this mounts `/workspace` and keeps the whole output, which a diff
     * needs. A non-zero exit yields null rather than an error: every caller has a sensible empty
     * answer, and a workspace that is not a git repository is normal, not a failure.
     */
    fun runInWorkspace(
        directory: String,
        script: String,
        timeoutSeconds: Long = WORKSPACE_COMMAND_TIMEOUT_SECONDS,
    ): String? {
        val runtime = installedRuntimeProvider() ?: return null
        val outputFile =
            runCatching {
                File.createTempFile("claude-cmd-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
            }.getOrNull() ?: return null
        return try {
            val process =
                runCatching {
                    ProcessBuilder(
                        ClaudeSandboxLauncher.shellCommand(
                            runtime = runtime,
                            workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() },
                            workingDirectory = directory.ifBlank { "/workspace" },
                            script = script,
                        ),
                    ).directory(runtimeDirectory)
                        .redirectErrorStream(false)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(
                                ClaudeSandboxLauncher.environment(
                                    runtime,
                                    File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                    githubToken(),
                                ),
                            )
                        }
                        .start()
                }.getOrNull() ?: return null
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) null else outputFile.readText()
        } catch (error: java.io.IOException) {
            null
        } finally {
            outputFile.delete()
        }
    }

    private fun runCommand(
        command: String,
        timeoutSeconds: Long = 30,
    ): LocalRuntimeCommandResult =
        LocalRuntimeCommandRunner(
            runtimeDirectory = runtimeDirectory,
            installedRuntimeProvider = installedRuntimeProvider,
            accessCoordinator = accessCoordinator,
            timeoutSeconds = timeoutSeconds,
        ).runShell(command, timeoutSeconds)

    private companion object {
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+")

        /** Reserved key; no model alias can collide with it. */
        const val CLI_VERSION_KEY = "@cliVersion"
        const val TITLE_PROMPT_LIMIT = 500
        const val TITLE_MAX_LENGTH = 60
        const val TITLE_TIMEOUT_SECONDS = 45L
        const val WORKSPACE_COMMAND_TIMEOUT_SECONDS = 60L
        const val INTERRUPTED_TOOL_ERROR = "Claude Code session ended before the tool result was received"
    }
}
