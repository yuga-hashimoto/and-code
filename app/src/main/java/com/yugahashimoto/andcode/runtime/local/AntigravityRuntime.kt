package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

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

    /** See [AntigravityAbortTracker] for why an intentional kill must not be reported as a crash. */
    private val abortTracker = AntigravityAbortTracker()

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
     * model picker actually needs. [AntigravityInstaller] verifies the release's SHA-256 and records
     * what it wrote, so the marker it leaves answers the same question at no cost.
     *
     * A sandbox provisioned before the marker existed falls back to [AntigravityManifest.VERSION] —
     * the previous assumption, and the only one available for an install whose version was never
     * written down. Whether the CLI *works* is answered by [models], which the app runs anyway.
     */
    fun version(): String? =
        currentRootfs()
            ?.takeIf { isInstalled() }
            ?.let { rootfs -> AntigravityInstaller.installedVersion(rootfs) ?: AntigravityManifest.VERSION }

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
        attachments: List<PromptAttachment> = emptyList(),
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
                require(isInstalled()) { "Antigravity is not installed" }
                AntigravityGuestSettings.repair(runtime)
                val promptWithAttachments =
                    prepareAntigravityPrompt(
                        runtimeDirectory,
                        sessionId,
                        records[sessionId]?.lastStep ?: 0,
                        prompt,
                        attachments,
                    )
                // Normally unreachable now that the app always queues a send behind a running
                // Antigravity turn (see RuntimeCapabilities.forcesQueue) - kept as a safety net for
                // e.g. two clients racing the same session. Marking the kill as intentional here is
                // what stops the superseded call's own send() below from reporting it as a crash.
                processes.remove(sessionId)?.let {
                    abortTracker.markIntentional(sessionId)
                    terminate(it)
                }
                // Concurrent agy launches hang on the PRoot/Android deployment (see
                // AntigravityProcessGate), so a send queues behind any in-flight launch and the two
                // run one at a time instead of racing each other into a deadlock.
                AntigravityProcessGate.serialize {
                    // `--print` takes the prompt as its value, so it must come last with the prompt
                    // immediately after it. Every other flag goes first: with `--print` leading, the CLI
                    // took the *next* token as the prompt, so a message sent with any flag set asked the
                    // model about "--conversation" or "--mode" instead of what the user typed.
                    val args =
                        buildList {
                            add("--output-format")
                            add("stream-json")
                            if (conversationId != null) {
                                add("--conversation")
                                add(conversationId)
                            }
                            addAll(AntigravityModels.cliArgs(model, variant))
                            addAll(permissionMode.cliArgs)
                            add("--print")
                            add(promptWithAttachments)
                        }
                    val stderrLog = File(runtimeDirectory, "logs/agy-stderr.log").also { it.parentFile?.mkdirs() }
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
                            .directory(runtimeDirectory)
                            // Diagnostics go to a log, not into stdout: a progress line on stderr would
                            // corrupt the NDJSON the parser reads line by line below.
                            .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog)).apply {
                                environment().putAll(
                                    AntigravitySandboxLauncher.environment(
                                        runtime,
                                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                        githubToken(),
                                    ),
                                )
                            }.start()
                    processes[sessionId] = process
                    val parser = AntigravityStreamJsonParser(sessionId, json)
                    val record0 = records[sessionId] ?: AntigravitySessionRecord(sessionId, null, workspace)
                    val turnNow = System.currentTimeMillis()
                    val userId = "$sessionId-user-${record0.lastStep}"
                    // Persist the user turn before the stream starts. The chat redraws from
                    // listMessages the moment it sees SessionIdle, and the composer's optimistic
                    // bubble is replaced by that snapshot - so a user turn that only lands at the
                    // end of the send would vanish from the screen (and never reappear on a failed
                    // result, which persisted nothing for it).
                    messages.getOrPut(sessionId) { mutableListOf() }.add(
                        OpenCodeMessage(
                            OpenCodeMessageInfo(userId, sessionId, "user", OpenCodeTime(turnNow, turnNow, turnNow)),
                            // A part without an id is dropped when the chat maps the transcript for
                            // display, so an id-less part is invisible however well the run went.
                            buildList {
                                add(OpenCodePart(id = "$userId-text", type = "text", text = prompt))
                                attachments.forEachIndexed { index, attachment ->
                                    add(
                                        OpenCodePart(
                                            id = "$userId-file-$index",
                                            sessionId = sessionId,
                                            messageId = userId,
                                            type = "file",
                                            filename = attachment.filename,
                                            mime = attachment.mime,
                                            url = attachment.url,
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                    persist()
                    var discoveredConversationId: String? = null
                    var turnFinished = false
                    var turnError: String? = null
                    var finalText: String? = null
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val parsed = parser.parse(line)
                        parsed.conversationId?.let { discoveredConversationId = it }
                        // Persist the assistant message before emitting its events. The chat's
                        // SessionIdle handler clears the live stream buffer and reloads the
                        // transcript with listMessages; if the turn is not on disk yet, that reload
                        // paints the previous turn and the just-streamed reply disappears - the
                        // one-turn lag seen on device. Claude Code avoids this by upserting before
                        // it emits, so the assistant reply (and its tool parts) land first here too.
                        parsed.messages.forEach { assistant ->
                            messages.getOrPut(sessionId) { mutableListOf() }.add(
                                assistant.copy(
                                    info =
                                        assistant.info.copy(
                                            model = model?.let { OpenCodeModelReference(AntigravityModels.PROVIDER_ID, it) },
                                        ),
                                ),
                            )
                            persist()
                        }
                        parsed.events.forEach(events::tryEmit)
                        if (parsed.turnFinished) {
                            turnFinished = true
                            turnError = parsed.errorMessage
                            finalText = parsed.finalText
                        }
                    }
                    process.waitFor()
                    processes.remove(sessionId)
                    // Consumed unconditionally, even when the turn actually finished cleanly (an
                    // abort can race a completion that was already on its way): leaving the flag set
                    // would make an unrelated later crash on this same session silently swallowed as
                    // "clean idle" instead of surfacing as an error.
                    val wasKilledIntentionally = abortTracker.consumeIntentional(sessionId)
                    records[sessionId] =
                        record0.copy(
                            // Never invent a conversation id: --conversation must only be used with
                            // an id emitted by the official CLI, otherwise a cold resume can attach
                            // to an unrelated conversation. The id now comes straight from the stream's
                            // top-level conversation_id rather than being scraped out of the prose.
                            conversationId = discoveredConversationId ?: record0.conversationId,
                            updatedAt = System.currentTimeMillis(),
                            lastStep = record0.lastStep + 1,
                        )
                    persist()
                    if (turnFinished) {
                        // On a failed result the parser already emitted SessionError then SessionIdle;
                        // the user turn is persisted above, and there is no assistant reply to add.
                        return@serialize finalText.orEmpty()
                    }
                    if (wasKilledIntentionally) {
                        // Killed on purpose - the stop button, or a same-session supersede above -
                        // not a crash. The process's exit code is 137 (SIGKILL) either way, so this
                        // flag is the only thing that tells the two apart; idle is the honest state.
                        events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                        return@serialize finalText.orEmpty()
                    }
                    // No result event arrived: the CLI died mid-turn. Surface that as an error unless it
                    // somehow exited cleanly, in which case idle is the honest state.
                    require(process.exitValue() == 0) { "agy exited with ${process.exitValue()}" }
                    events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                    finalText.orEmpty()
                } ?: error("Antigravity is busy and the turn could not be scheduled")
            }.onFailure {
                events.tryEmit(OpenCodeEvent.SessionError(sessionId, it.message))
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
            }
        }

    fun abort(sessionId: String) {
        processes[sessionId]?.let { process ->
            // Marked before either the graceful ESC or the SIGKILL fallback below: whichever one
            // actually ends the process, the exit code should not be read as a crash by the send()
            // call still blocked reading its stdout. See AntigravityAbortTracker.
            abortTracker.markIntentional(sessionId)
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
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(runtimeDirectory, "workspace/.andcode-attachments/$safeSession").deleteRecursively()
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
            writeAtomically(recordsFile, json.encodeToString(records.values.toList()))
            writeAtomically(messagesFile, json.encodeToString(messages.mapValues { it.value.toList() }))
        }
    }
}

private fun writeAtomically(
    destination: File,
    content: String,
) {
    val temporary = File(destination.parentFile, ".${destination.name}.tmp")
    temporary.writeText(content)
    check(temporary.renameTo(destination)) { "Cannot replace ${destination.name}" }
}

/**
 * Materializes inline attachments in the shared workspace and references them through agy's native
 * `@path` context syntax. This is the non-interactive equivalent of attaching a file in its TUI.
 */
internal fun prepareAntigravityPrompt(
    runtimeDirectory: File,
    sessionId: String,
    turn: Long,
    prompt: String,
    attachments: List<PromptAttachment>,
): String {
    if (attachments.isEmpty()) return prompt
    val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val attachmentDir =
        File(runtimeDirectory, "workspace/.andcode-attachments/$safeSession/$turn").apply {
            check(mkdirs() || isDirectory) { "Cannot create Antigravity attachment directory" }
        }
    val references =
        attachments.mapIndexed { index, attachment ->
            require(attachment.mime.startsWith("image/") || attachment.mime == "application/pdf") {
                "Antigravity cannot attach ${attachment.mime}"
            }
            val marker = ";base64,"
            val encoded = attachment.url.substringAfter(marker, missingDelimiterValue = "")
            require(attachment.url.startsWith("data:") && encoded.isNotEmpty()) {
                "Antigravity requires an inline attachment"
            }
            require(encoded.length <= MAX_ANTIGRAVITY_ATTACHMENT_BASE64_CHARS) { "Antigravity attachment is too large" }
            val safeName = attachment.filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment-$index" }
            val file = File(attachmentDir, "$index-$safeName")
            file.writeBytes(Base64.getDecoder().decode(encoded))
            "@/workspace/.andcode-attachments/$safeSession/$turn/${file.name}"
        }
    return (references + prompt).joinToString("\n")
}

private const val MAX_ANTIGRAVITY_ATTACHMENT_BASE64_CHARS = 34_952_536
