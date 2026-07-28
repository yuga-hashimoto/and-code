package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AntigravitySessionRecord(
    val appSessionId: String,
    val conversationId: String? = null,
    val workspace: String,
    val lastStep: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    /** Null until the user picks one; a null model omits `--model`/`--effort` and lets agy decide. */
    val model: String? = null,
    val variant: String? = null,
    val permissionMode: String = AntigravityPermissionMode.DEFAULT.cliValue,
    /**
     * The chat's name in the drawer, or null while it has never been named.
     *
     * The CLI does not name its conversations, so this is the app's own - null is what tells
     * [AntigravityTarget] a session is still unnamed and its first prompt should name it.
     */
    val title: String? = null,
)

class AntigravityRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val githubToken: () -> String? = { null },
) {
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 128)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val recordsFile = File(runtimeDirectory, "antigravity-sessions.json")
    private val messagesFile = File(runtimeDirectory, "antigravity-messages.json")
    private val records = linkedMapOf<String, AntigravitySessionRecord>()
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()
    private val processes = linkedMapOf<String, Process>()

    @Volatile private var cachedVersion: String? = null

    @Volatile private var cachedModels: List<AntigravityModels.Entry>? = null
    private val adapter = AntigravityTranscriptAdapter(json)
    private val authCoordinator = AntigravityAuthCoordinator(runtimeDirectory, installedRuntimeProvider, githubToken)

    init {
        load()
    }

    fun events(): Flow<OpenCodeEvent> = events

    fun auth() = authCoordinator

    fun isInstalled(): Boolean =
        installedRuntimeProvider()?.let { runtime ->
            (runtime.antigravityRootfs ?: runtime.rootfs).resolve("usr/local/bin/agy").canExecute()
        } == true

    /** The rootfs `mcp_config.json` and other guest-side files live in, or null when not installed. */
    fun currentRootfs(): File? = installedRuntimeProvider()?.let { it.antigravityRootfs ?: it.rootfs }

    /**
     * The installed version, without launching the CLI.
     *
     * `agy --version` is not a cheap probe: it boots the whole bundled language server before it
     * prints anything, and on device it took over a minute and overlapped the `models` call that the
     * model picker actually needs. The version is not in question either - [AntigravityInstaller]
     * writes exactly [AntigravityManifest.VERSION] and verifies its SHA-256 first - so reporting the
     * pinned version for a present, executable binary says the same thing at no cost. Whether the CLI
     * *works* is answered by [models], which the app needs to run anyway.
     */
    fun version(): String? = AntigravityManifest.VERSION.takeIf { isInstalled() }

    /** Kept for call sites that invalidate health after an install or update; see [version]. */
    fun invalidateVersion() {
        cachedVersion = null
    }

    /**
     * The models the signed-in account can use, from `agy models` (cached until [invalidateModels]).
     *
     * Runs a one-shot `agy models` the first time this is called after sign-in; a failure (not
     * signed in yet, network error) returns an empty list rather than throwing, which
     * [AntigravityModels.catalog] turns into the same single placeholder model shown before this
     * existed.
     */
    fun models(): List<AntigravityModels.Entry> =
        cachedModels ?: runCatching {
            val runtime = installedRuntimeProvider() ?: return@runCatching emptyList()
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            AntigravityProcessGate.exclusive {
                val result =
                    with(AntigravityProcessGate) {
                        ProcessBuilder(AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("models"), false))
                            .redirectErrorStream(true)
                            .withoutStdin()
                            .apply {
                                environment().putAll(
                                    AntigravitySandboxLauncher.environment(
                                        runtime,
                                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                        githubToken(),
                                    ),
                                )
                            }
                            .start()
                    }
                val output = AntigravityProcessGate.readWithTimeout(result, MODELS_READ_TIMEOUT_MS)
                if (output == null || !result.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    terminate(result)
                    return@exclusive emptyList()
                }
                if (result.exitValue() != 0) emptyList() else AntigravityModels.parse(output)
            }.orEmpty()
        }.getOrDefault(emptyList())
            // Only a real answer is cached. An empty result means the call failed - not signed in
            // yet, contended, timed out - and caching that would be permanent: `cachedModels` is
            // non-null from then on, so the elvis above never retries and the picker is stuck
            // showing the placeholder model forever, which is exactly what happened on device.
            .also { if (it.isNotEmpty()) cachedModels = it }

    /** Clears the cached model list; called after sign-in and sign-out so a switched account is picked up. */
    fun invalidateModels() {
        cachedModels = null
    }

    suspend fun send(
        sessionId: String,
        workspace: String,
        prompt: String,
        conversationId: String?,
        model: String? = null,
        variant: String? = null,
        permissionMode: AntigravityPermissionMode = AntigravityPermissionMode.DEFAULT,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
                require(isInstalled()) { "Antigravity is not installed" }
                AntigravityGuestSettings.repair(runtime)
                processes.remove(sessionId)?.let { terminate(it) }
                // `--print` takes the prompt as its value, so it must come last with the prompt
                // immediately after it. Every other flag goes first: with `--print` leading, the CLI
                // took the *next* token as the prompt, so a message sent with any flag set asked the
                // model about "--conversation" or "--mode" instead of what the user typed.
                val args =
                    buildList {
                        if (conversationId != null) {
                            add("--conversation")
                            add(conversationId)
                        }
                        addAll(AntigravityModels.cliArgs(model, variant))
                        addAll(permissionMode.cliArgs)
                        add("--print")
                        add(prompt)
                    }
                val process =
                    ProcessBuilder(
                        AntigravitySandboxLauncher.command(
                            runtime,
                            File(runtimeDirectory, "workspace").apply {
                                mkdirs()
                            }.absolutePath,
                            args,
                            false,
                        ),
                    )
                        .directory(runtimeDirectory).redirectErrorStream(true).apply {
                            environment().putAll(
                                AntigravitySandboxLauncher.environment(
                                    runtime,
                                    File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                    githubToken(),
                                ),
                            )
                        }.start()
                processes[sessionId] = process
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                processes.remove(sessionId)
                require(process.exitValue() == 0) { output.ifBlank { "agy exited with ${process.exitValue()}" } }
                val record = records[sessionId] ?: AntigravitySessionRecord(sessionId, null, workspace)
                val discoveredConversationId = CONVERSATION_ID_PATTERN.find(output)?.groupValues?.getOrNull(1)
                records[sessionId] =
                    record.copy(
                        // Never invent a conversation id: --conversation must only be used with
                        // an id emitted by the official CLI, otherwise a cold resume can attach
                        // to an unrelated conversation.
                        conversationId = discoveredConversationId ?: record.conversationId,
                        updatedAt = System.currentTimeMillis(),
                        lastStep = record.lastStep + 1,
                    )
                val now = System.currentTimeMillis()
                val userId = "$sessionId-user-${record.lastStep}"
                val assistantId = "$sessionId-assistant-${record.lastStep}"
                messages.getOrPut(sessionId) { mutableListOf() }.apply {
                    add(
                        OpenCodeMessage(
                            OpenCodeMessageInfo(userId, sessionId, "user", OpenCodeTime(now, now, now)),
                            // A part without an id is dropped when the chat maps the transcript for
                            // display, so an id-less part is invisible however well the run went -
                            // and the empty result then replaced the whole transcript on screen.
                            listOf(OpenCodePart(id = "$userId-text", type = "text", text = prompt)),
                        ),
                    )
                    add(
                        OpenCodeMessage(
                            // `completed` is how the chat decides a turn is over: it polls the
                            // transcript and only stops on an assistant message that carries one.
                            // Leaving it null kept the composer on "thinking" until the two minute
                            // timeout even though the reply was already written and on disk.
                            // `--print` is one-shot, so the reply is complete the moment it lands.
                            // The model is recorded on the message because that is where the chat
                            // reads it back when a session is reopened - it takes the newest message
                            // that names one. Without it, reopening any Antigravity chat showed
                            // whatever model happened to be selected globally.
                            OpenCodeMessageInfo(
                                assistantId,
                                sessionId,
                                "assistant",
                                OpenCodeTime(now, now, now),
                                agent = "antigravity",
                                model = model?.let { OpenCodeModelReference(AntigravityModels.PROVIDER_ID, it) },
                            ),
                            listOf(OpenCodePart(id = "$assistantId-text", type = "text", text = output)),
                        ),
                    )
                }
                persist()
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                output
            }.onFailure {
                events.tryEmit(OpenCodeEvent.SessionError(sessionId, it.message))
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
            }
        }

    fun abort(sessionId: String) {
        processes[sessionId]?.let { process ->
            runCatching {
                process.outputStream.write(27)
                process.outputStream.flush()
            }
            Thread.sleep(2000)
            if (process.isAlive) terminate(process)
        }
    }

    fun listSessions(directory: String?): List<AntigravitySessionRecord> =
        records.values.filter {
            directory == null || it.workspace == directory
        }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messages[sessionId].orEmpty().toList()

    fun remove(sessionId: String): Boolean {
        abort(sessionId)
        val removed = records.remove(sessionId) != null
        messages.remove(sessionId)
        persist()
        return removed
    }

    fun create(
        sessionId: String,
        workspace: String,
        title: String? = null,
    ) {
        records[sessionId] = AntigravitySessionRecord(sessionId, null, workspace, title = title)
        persist()
    }

    /** Remembers the model/variant a session's next message should use. */
    fun setSessionModel(
        sessionId: String,
        model: String?,
        variant: String?,
    ) {
        val record = records[sessionId] ?: return
        records[sessionId] = record.copy(model = model, variant = variant)
        persist()
    }

    /** Renames a session; see [AntigravitySessionRecord.title]. */
    fun setSessionTitle(
        sessionId: String,
        title: String,
    ) {
        val record = records[sessionId] ?: return
        records[sessionId] = record.copy(title = title, updatedAt = System.currentTimeMillis())
        persist()
    }

    /**
     * Asks the model for a short name for a new chat, or null if it could not be produced.
     *
     * Mirrors [ClaudeCodeRuntime.summarizeTitle], with two differences the guest CLI forces. It runs
     * through [AntigravityProcessGate] and refuses while any send is in flight, because two `agy`
     * processes racing against the same rootfs hang each other; and it picks the cheapest model the
     * account actually has from [models] rather than naming one, since `--model` only accepts a slug
     * the signed-in account can use. `plan` mode keeps a naming call from touching the workspace.
     */
    fun summarizeTitle(prompt: String): String? {
        if (processes.isNotEmpty()) return null
        val runtime = installedRuntimeProvider() ?: return null
        if (!isInstalled()) return null
        val entries = cachedModels.orEmpty()
        val cheapest = entries.firstOrNull { it.base.contains("flash") && it.variant == "low" } ?: entries.firstOrNull()
        val instruction =
            "Reply with a title of at most 6 words for a chat that starts with the following " +
                "message. Reply with the title only, no quotes, no punctuation at the end, in the " +
                "same language as the message.\n\n" + prompt.take(TITLE_PROMPT_LIMIT)
        val args =
            buildList {
                addAll(AntigravityModels.cliArgs(cheapest?.slug, null))
                addAll(AntigravityPermissionMode.PLAN.cliArgs)
                add("--print")
                add(instruction)
            }
        val output =
            AntigravityProcessGate.exclusive {
                val process =
                    runCatching {
                        with(AntigravityProcessGate) {
                            ProcessBuilder(
                                AntigravitySandboxLauncher.command(
                                    runtime,
                                    File(runtimeDirectory, "workspace").apply { mkdirs() }.absolutePath,
                                    args,
                                    false,
                                ),
                            )
                                .directory(runtimeDirectory)
                                .redirectErrorStream(false)
                                .withoutStdin()
                                .apply {
                                    environment().putAll(
                                        AntigravitySandboxLauncher.environment(
                                            runtime,
                                            File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                            githubToken(),
                                        ),
                                    )
                                }
                                .start()
                        }
                    }.getOrNull() ?: return@exclusive null
                val text = AntigravityProcessGate.readWithTimeout(process, TITLE_READ_TIMEOUT_MS)
                if (text == null || !process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    terminate(process)
                    return@exclusive null
                }
                if (process.exitValue() != 0) null else text
            } ?: return null
        return output.lineSequence()
            .map { it.trim().trim('"', '\'', '。', '.') }
            .firstOrNull { it.isNotEmpty() }
            ?.take(TITLE_LENGTH)
    }

    /** Remembers the permission mode a session's next message should use. */
    fun setSessionMode(
        sessionId: String,
        mode: AntigravityPermissionMode,
    ) {
        val record = records[sessionId] ?: return
        records[sessionId] = record.copy(permissionMode = mode.cliValue)
        persist()
    }

    fun abortAll() {
        processes.keys.toList().forEach(::abort)
    }

    fun respond(
        permissionId: String,
        response: com.yugahashimoto.andcode.runtime.PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    fun answer(
        requestId: String,
        answers: List<List<String>>,
    ): Boolean = false

    /** See [killAntigravityProcessTree] for why a plain `destroy()`/`destroyForcibly()` is not enough. */
    private fun terminate(process: Process) {
        killAntigravityProcessTree(process)
        if (process.isAlive) process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private companion object {
        // Keep this deliberately narrow. A UUID-shaped value in arbitrary model text is not
        // enough to claim resume support, but this lets a future official --print transcript
        // bridge persist an explicitly emitted conversation id without fabricating one.
        val CONVERSATION_ID_PATTERN = Regex("(?:conversation(?:Id|_id)|conversation)\\s*[:=]\\s*[\\\"']?([0-9a-fA-F-]{16,})")
        val VERSION_PATTERN = Regex("\\b\\d+\\.\\d+\\.\\d+\\b")
        const val MODELS_READ_TIMEOUT_MS = 45_000L
        const val TITLE_READ_TIMEOUT_MS = 45_000L
        const val TITLE_PROMPT_LIMIT = 2_000
        const val TITLE_LENGTH = 40
    }

    private fun load() {
        runCatching {
            json.decodeFromString<List<AntigravitySessionRecord>>(
                recordsFile.readText(),
            ).forEach { records[it.appSessionId] = it }
        }
        runCatching {
            json.decodeFromString<Map<String, List<OpenCodeMessage>>>(
                messagesFile.readText(),
            ).forEach { messages[it.key] = it.value.toMutableList() }
        }
    }

    private fun persist() {
        runCatching {
            recordsFile.parentFile?.mkdirs()
            recordsFile.writeText(json.encodeToString(records.values.toList()))
            messagesFile.writeText(json.encodeToString(messages.mapValues { it.value.toList() }))
        }
    }
}
