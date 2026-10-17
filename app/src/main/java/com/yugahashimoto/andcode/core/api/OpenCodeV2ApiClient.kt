package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.core.security.OpenCodeUrl
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the OpenCode V2 HTTP/SSE API (under the `api/` prefix). Every response is mapped onto the existing app
 * DTOs, so the backends and ViewModels above [OpenCodeApi] are protocol-agnostic.
 *
 * V2 differs from V1 in ways this client hides: responses are wrapped in `{data}`, sessions are
 * cursor-paged, model and agent are set by separate calls instead of the prompt body, questions are
 * "forms", and the message transcript is a union of typed rows rather than role/parts pairs.
 */
class OpenCodeV2ApiClient(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = OpenCodeApiClient.defaultHttpClient(profile),
    private val json: Json = OpenCodeApiClient.defaultJson,
    private val formRegistry: OpenCodeV2FormRegistry = OpenCodeV2FormRegistry(),
) : OpenCodeApi {
    // Resolved lazily for the same reason as the V1 client: construction must never throw.
    private val baseUrl: HttpUrl by lazy { OpenCodeUrl.normalize(profile.baseUrl).getOrThrow() }

    private val providerAuthHttpClient: OkHttpClient =
        httpClient.newBuilder()
            .readTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

    /**
     * Serialises "apply agent and model, then send the prompt" per session. V2 takes the agent and
     * model as separate calls, so two concurrent prompts on one session must not interleave: the
     * second could otherwise switch the model under the first's prompt.
     *
     * There is deliberately no cache of the last applied selection. The session can be switched by
     * another client (the web UI, another phone), and a cache would then skip applying the user's
     * choice. The calls are cheap, so every prompt re-applies what it asks for.
     */
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /** Provider id to the auth methods offered to the user, in the order the UI indexes them. */
    private val integrationMethods = ConcurrentHashMap<String, List<V2IntegrationMethod>>()

    /** Provider id to the OAuth attempt started for it; the completion call needs the attempt id. */
    private val oauthAttempts = ConcurrentHashMap<String, String>()

    override suspend fun health(): OpenCodeHealth =
        withContext(Dispatchers.IO) {
            execute(requestBuilder("api/info").get().build()) { body ->
                val version =
                    json.parseToJsonElement(body).jsonObject.textOf("version")
                        ?: error("OpenCode V2 info response had no version")
                OpenCodeHealth(healthy = true, version = version)
            }
        }

    /**
     * Active (non-archived) sessions. V2 cannot store `time.archived`, so archiving writes a timestamp
     * into `metadata` instead, and this filter reads both markers.
     */
    override suspend fun sessions(directory: String?): List<OpenCodeSession> =
        pagedList<V2SessionInfo>(
            SESSIONS_PATH,
            query("directory" to directory, "order" to "desc", "limit" to PAGE_LIMIT),
        ).map { it.toSession() }.filter { it.time.archived == null }

    override suspend fun session(sessionId: String): OpenCodeSession = rawSession(sessionId).toSession()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val body =
            buildJsonObject {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                directory?.takeIf { it.isNotBlank() }?.let { put("location", buildJsonObject { put("directory", it) }) }
            }
        return postData<V2SessionInfo>(SESSIONS_PATH, body).toSession()
    }

    override suspend fun messages(sessionId: String): List<OpenCodeMessage> =
        messagePages(sessionId)
            .mapNotNull { it as? JsonObject }
            .flatMap { it.toMessages(sessionId, json) }

    /**
     * V2 has no message delete. The app gates the action with `editMessages = false` on V2 targets,
     * so this is only reached if a caller ignores that capability.
     */
    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = throw UnsupportedOperationException("OpenCode V2 cannot delete messages")

    /**
     * Instantiated providers (those with a model) plus every integration that can be authenticated.
     * The settings screen lists `all` to offer credentials, so an integration such as OpenAI must
     * appear even before it has any models. Models are only attached to providers that have them,
     * and the chat picker skips providers with no models, so the extra entries stay out of it.
     *
     * `connected` is the set the chat may use: providers with an active integration connection, or
     * with an enabled provider activation, and a provider that has models. Integrations that merely
     * have an authentication method are not connected.
     */
    override suspend fun providers(): ProviderCatalog {
        val providers = getData<List<V2Provider>>("api/provider")
        val models = getData<List<V2Model>>("api/model").filter { it.enabled != false }
        val integrations = runCatching { integrations() }.getOrDefault(emptyList())
        // The default is advisory: a server with no usable default must still produce a catalog.
        val defaultModel =
            try {
                getData<V2Model>("api/model/default")
            } catch (error: OpenCodeApiException) {
                null
            }

        val modelsByProvider = models.groupBy { it.providerID }
        val integrationsById = integrations.associateBy { it.id }
        // Providers and models come first; authenticable integrations are appended after them.
        val providerIds =
            (
                providers.map { it.id } +
                    modelsByProvider.keys.filterNot { id -> providers.any { it.id == id } } +
                    integrations.filter { it.methods.any { method -> method.isSupportedAuthMethod() } }.map { it.id }
            ).distinct()
        val all =
            providerIds.map { id ->
                OpenCodeProvider(
                    id = id,
                    name = providers.firstOrNull { it.id == id }?.name ?: integrationsById[id]?.name ?: id,
                    models =
                        modelsByProvider[id].orEmpty().associate { model ->
                            model.id to model.toOpenCodeModel()
                        },
                )
            }
        val enabledProviders = providers.filter { it.activation == "enabled" }.map { it.id }
        val connectedByIntegration = integrations.filter { it.connections.isNotEmpty() }.map { it.id }
        return ProviderCatalog(
            all = all,
            default = defaultModel?.let { mapOf(it.providerID to it.id) }.orEmpty(),
            connected = (enabledProviders + connectedByIntegration).distinct(),
        )
    }

    /**
     * Internal V2 agents (compaction, title, summary) are reported as `mode: primary` with
     * `hidden: true`. V2 does not copy the flag onto [OpenCodeAgent]; it drops those agents here so
     * they stay out of the mode picker and settings. Only `true` hides: a missing or `null` flag
     * (as plugin agents send it) is visible.
     */
    override suspend fun agents(): List<OpenCodeAgent> =
        getData<List<V2Agent>>("api/agent").filterNot { it.hidden == true }.map { agent ->
            OpenCodeAgent(
                name = agent.id,
                description = agent.description,
                mode = agent.mode,
            )
        }

    /**
     * Maps the integration's key and OAuth methods to the V1 shape, with each method's form fields
     * as prompts. Env, command and external methods have no equivalent in the settings UI and are
     * left out, so a method's list position is the index the UI passes back to [authorizeProvider].
     * A method whose form cannot be represented is left out too, rather than offered half-working.
     */
    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> {
        val result = LinkedHashMap<String, List<ProviderAuthMethod>>()
        integrations().forEach { integration ->
            val methods = integration.methods.filter { it.isSupportedAuthMethod() && it.representable(json) }
            integrationMethods[integration.id] = methods
            if (methods.isNotEmpty()) {
                result[integration.id] = methods.map { it.toAuthMethod(json) }
            }
        }
        return result
    }

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization {
        val method = authMethodAt(providerId, methodIndex)
        val methodId = method.id ?: error("OpenCode OAuth method has no id")
        require(method.type == "oauth") { "OpenCode auth method $methodIndex is not OAuth" }
        val attempt =
            postData<V2OAuthAttempt>(
                "api/integration/${encodePath(providerId)}/connect/oauth",
                buildJsonObject {
                    put("methodID", methodId)
                    answerFor(method.form, inputs)?.let { put("answer", it) }
                },
            )
        oauthAttempts[providerId] = attempt.attemptID
        return ProviderAuthAuthorization(url = attempt.url, method = attempt.mode, instructions = attempt.instructions)
    }

    /**
     * Connects a key. The dialog's collected prompt inputs travel as the form `answer`, which is how
     * V2 receives per-provider settings such as a base URL. The API key itself is the `key`.
     */
    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean {
        val method = integrationMethods[providerId]?.firstOrNull { it.type == "key" }
        val body =
            buildJsonObject {
                put("key", apiKey)
                answerFor(method?.form, metadata)?.let { put("answer", it) }
            }
        sendVoid(
            requestBuilder("api/integration/${encodePath(providerId)}/connect/key").post(jsonBody(body)),
        )
        return true
    }

    /**
     * V2 credentials are listed globally, so removal finds every credential that belongs to the
     * provider and deletes each one.
     */
    override suspend fun removeProviderAuth(providerId: String): Boolean {
        getData<List<V2Credential>>("api/credential")
            .filter { it.integrationID == providerId }
            .forEach { credential -> sendVoid(requestBuilder("api/credential/${encodePath(credential.id)}").delete()) }
        return true
    }

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean {
        val attemptId =
            oauthAttempts[providerId] ?: throw IllegalStateException("OpenCode OAuth attempt was not started")
        val body =
            buildJsonObject {
                code?.takeIf { it.isNotBlank() }?.let { put("code", it) }
            }
        sendVoid(
            requestBuilder("api/integration/${encodePath(providerId)}/connect/oauth/${encodePath(attemptId)}/complete")
                .post(jsonBody(body)),
            providerAuthHttpClient,
        )
        oauthAttempts.remove(providerId)
        return true
    }

    override suspend fun projects(directory: String?): List<OpenCodeProject> =
        getData<List<V2Project>>("api/project").map { project ->
            OpenCodeProject(id = project.id, worktree = project.canonical.orEmpty(), name = project.name)
        }

    override suspend fun currentProject(directory: String?): OpenCodeProject {
        val location = getData<V2LocationInfo>("api/location", locationQuery(directory))
        val project = location.project
        return OpenCodeProject(
            id = project?.id ?: "global",
            worktree = project?.canonical ?: location.directory,
        )
    }

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo {
        val location = getData<V2LocationInfo>("api/location", locationQuery(directory))
        return OpenCodePathInfo(
            home = "",
            state = "",
            config = "",
            worktree = location.project?.canonical ?: location.directory,
            directory = location.directory,
        )
    }

    override suspend fun files(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> {
        val entries =
            getData<List<V2FsEntry>>(
                "api/fs/list",
                locationQuery(directory) + query("path" to path.ifBlank { "." }),
            )
        return entries.map { entry ->
            val relative = entry.path.trimEnd('/')
            OpenCodeFileNode(
                name = relative.substringAfterLast('/'),
                path = relative,
                absolute = "${directory.trimEnd('/')}/$relative",
                type = entry.type,
            )
        }
    }

    /**
     * V2 serves file bodies raw, not in a JSON envelope. Text is returned as text; anything else is
     * base64-encoded with `type = "binary"`, which is what the code viewer already checks for.
     */
    override suspend fun fileContent(
        directory: String,
        path: String,
    ): OpenCodeFileContent =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder("api/fs/read/${encodeRelativePath(path)}", locationQuery(directory)).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw OpenCodeApiException(
                        statusCode = response.code,
                        message = "OpenCode request failed (HTTP ${response.code})",
                    )
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (isTextual(mime, bytes)) {
                    OpenCodeFileContent(type = "text", content = String(bytes, Charsets.UTF_8), mimeType = mime)
                } else {
                    OpenCodeFileContent(
                        type = "binary",
                        content = Base64.getEncoder().encodeToString(bytes),
                        encoding = "base64",
                        mimeType = mime,
                    )
                }
            }
        }

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> =
        getData<List<V2FileChange>>("api/vcs/status", locationQuery(directory)).map { it.toFileChange() }

    /**
     * V2 has no grep endpoint. Callers already wrap this in `runCatching`, so the explorer shows
     * file matches without text matches.
     */
    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = throw UnsupportedOperationException("OpenCode V2 has no text search")

    override suspend fun findFiles(
        directory: String,
        queryText: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> {
        // V1 `dirs=false` meant files only; V2 has no such flag, so it maps to a type filter.
        val effectiveType = type ?: if (includeDirectories == false) "file" else null
        return getData<List<V2FsEntry>>(
            "api/fs/find",
            locationQuery(directory) +
                query(
                    "query" to queryText,
                    "type" to effectiveType,
                    "limit" to limit?.toString(),
                ),
        ).map { it.path }
    }

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo {
        val info = getData<V2VcsInfo>("api/vcs", locationQuery(directory))
        // The default branch is a second call, so a failure here must not hide the branch.
        val base =
            try {
                getData<JsonElement>("api/vcs/base", locationQuery(directory)).let { element ->
                    (element as? JsonObject)?.let { json.decodeFromJsonElement<V2VcsBase>(it) }
                }
            } catch (error: OpenCodeApiException) {
                null
            }
        return OpenCodeVcsInfo(
            branch = info.branch?.current,
            defaultBranch = info.branch?.defaultBranch ?: base?.name,
        )
    }

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> =
        getData<List<V2FileChange>>("api/vcs/status", locationQuery(directory)).map { it.toFileChange() }

    /** V1 `git` was the whole-tree diff, which is V2 `working`. `context` has no V2 equivalent. */
    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> =
        getData<List<V2FileChange>>(
            "api/vcs/diff",
            locationQuery(directory) + query("mode" to v2DiffMode(mode)),
        ).map { it.toFileChange() }

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = getData<List<V2FileChange>>("api/session/${encodePath(sessionId)}/diff").map { it.toFileChange() }

    /** V2 has no todo endpoint or event; todos are only visible inside tool output. */
    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = emptyList()

    /**
     * Agent and model are applied through their own calls, and only when they differ from what this
     * session last received. The prompt itself then carries the text and attachments, with
     * `resume` set from [PromptRequest.noReply].
     */
    override suspend fun promptAsync(
        sessionId: String,
        request: PromptRequest,
    ) {
        sessionLocks.getOrPut(sessionId) { Mutex() }.withLock {
            request.agent?.takeIf { it.isNotBlank() }?.let { applyAgent(sessionId, it) }
            applyModel(sessionId, request.providerId, request.modelId, request.variant)
            sendPrompt(sessionId, request)
        }
    }

    private suspend fun sendPrompt(
        sessionId: String,
        request: PromptRequest,
    ) {
        val body =
            buildJsonObject {
                put("text", request.text)
                if (request.attachments.isNotEmpty()) {
                    put(
                        "files",
                        buildJsonArray {
                            request.attachments.forEach { attachment ->
                                add(
                                    buildJsonObject {
                                        put("uri", attachment.url)
                                        put("name", attachment.filename)
                                        put(
                                            "mention",
                                            buildJsonObject {
                                                put("start", 0)
                                                put("end", 0)
                                                put("text", "@${attachment.filename}")
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
                put("resume", !request.noReply)
            }
        sendVoid(requestBuilder("api/session/${encodePath(sessionId)}/prompt").post(jsonBody(body)))
    }

    /** V2 compacts with the session's current model, so [providerId] and [modelId] are not sent. */
    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean {
        sendVoid(requestBuilder("api/session/${encodePath(sessionId)}/compact").post(jsonBody(JsonObject(emptyMap()))))
        return true
    }

    /** Returns the server's `interrupted` flag; V2 reports `true` even for an idle session. */
    override suspend fun abortSession(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            execute(
                requestBuilder("api/session/${encodePath(sessionId)}/interrupt")
                    .post(jsonBody(JsonObject(emptyMap())))
                    .build(),
            ) { body ->
                (json.parseToJsonElement(body).jsonObject["interrupted"] as? JsonPrimitive)?.booleanOrNull ?: true
            }
        }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
        directory: String?,
    ): OpenCodeSession {
        sendVoid(
            requestBuilder("api/session/${encodePath(sessionId)}")
                .patch(jsonBody(buildJsonObject { put("title", title) })),
        )
        return session(sessionId)
    }

    override suspend fun deleteSession(
        sessionId: String,
        directory: String?,
    ): Boolean {
        sendVoid(requestBuilder("api/session/${encodePath(sessionId)}").delete())
        sessionLocks.remove(sessionId)
        return true
    }

    /**
     * V2 silently ignores `time.archived` on PATCH, so archiving writes a timestamp into `metadata`
     * while keeping the existing entries. [sessions] reads that key back as the archive marker.
     */
    override suspend fun archiveSession(
        sessionId: String,
        directory: String?,
    ): OpenCodeSession {
        val current = rawSession(sessionId)
        val metadata =
            JsonObject(
                (current.metadata ?: emptyMap()) +
                    (ARCHIVED_METADATA_KEY to JsonPrimitive(System.currentTimeMillis())),
            )
        sendVoid(
            requestBuilder("api/session/${encodePath(sessionId)}")
                .patch(jsonBody(buildJsonObject { put("metadata", metadata) })),
        )
        return session(sessionId)
    }

    override suspend fun mcpServers(): List<McpServer> =
        getData<List<V2McpServer>>("api/mcp").map { server ->
            val status = server.status as? JsonObject
            McpServer(
                name = server.name,
                status = status?.textOf("status") ?: (server.status as? JsonPrimitive)?.contentOrNull,
                error = status?.textOf("error"),
            )
        }

    /** V2 registers an MCP server with a PUT of its `config`; the V1 body carried `name` next to it. */
    override suspend fun addMcpServer(body: JsonObject): McpServer {
        val name = (body["name"] as? JsonPrimitive)?.contentOrNull ?: error("MCP server name is missing")
        val config = body["config"] ?: JsonObject(body.filterKeys { it != "name" })
        sendVoid(
            requestBuilder("api/experimental/mcp/${encodePath(name)}")
                .put(jsonBody(buildJsonObject { put("config", config) })),
        )
        return McpServer(name = name)
    }

    override suspend fun connectMcpServer(name: String): Boolean {
        sendVoid(requestBuilder("api/experimental/mcp/${encodePath(name)}/connect").post(jsonBody(JsonObject(emptyMap()))))
        return true
    }

    override suspend fun disconnectMcpServer(name: String): Boolean {
        sendVoid(requestBuilder("api/experimental/mcp/${encodePath(name)}/disconnect").post(jsonBody(JsonObject(emptyMap()))))
        return true
    }

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = throw UnsupportedOperationException(MCP_AUTH_UNSUPPORTED)

    override suspend fun mcpAuth(name: String): McpAuthStart = throw UnsupportedOperationException(MCP_AUTH_UNSUPPORTED)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = throw UnsupportedOperationException(MCP_AUTH_UNSUPPORTED)

    /**
     * V2 returns configuration as a list of documents, not one object. The documents are merged
     * shallowly in order, so a later document overrides an earlier one, and only `info` is kept.
     */
    override suspend fun config(): JsonElement {
        val documents = getData<JsonElement>("api/config")
        val merged = linkedMapOf<String, JsonElement>()
        (documents as? JsonArray).orEmpty().forEach { document ->
            val entry = document as? JsonObject ?: return@forEach
            if (entry.textOf("type") == "document") {
                (entry["info"] as? JsonObject)?.let { merged.putAll(it) }
            }
        }
        return JsonObject(merged)
    }

    /**
     * V2 accepts only `{shell}` on its config patch endpoint, so a general config write (custom
     * providers, arbitrary keys) cannot be applied remotely. Failing here, with a message the
     * settings screens can show, is safer than sending a patch that the server silently drops.
     */
    override suspend fun updateConfig(patch: JsonObject): JsonElement =
        throw UnsupportedOperationException(
            "This OpenCode server is V2, which only accepts shell settings over the API. " +
                "Edit opencode.json on the server to change this configuration.",
        )

    override suspend fun configProviders(): List<ConfiguredProvider> {
        val catalog = providers()
        return catalog.all.map { provider ->
            ConfiguredProvider(
                id = provider.id,
                name = provider.name,
                defaultModel = catalog.default[provider.id],
                connected = provider.id in catalog.connected,
            )
        }
    }

    override suspend fun commands(): List<OpenCodeCommand> =
        getData<List<V2Command>>("api/command").map { OpenCodeCommand(name = it.name, description = it.description) }

    override suspend fun skills(): List<OpenCodeSkill> =
        getData<List<V2Skill>>("api/skill").map { OpenCodeSkill(name = it.id, description = it.description) }

    /** Applies the agent and model first, since V2 commands carry neither. [model] is `provider/model`. */
    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
        agent: String?,
        model: String?,
        variant: String?,
    ) {
        sessionLocks.getOrPut(sessionId) { Mutex() }.withLock {
            agent?.takeIf { it.isNotBlank() }?.let { applyAgent(sessionId, it) }
            val providerAndModel = model?.split("/", limit = 2)
            if (providerAndModel?.size == 2) {
                applyModel(sessionId, providerAndModel[0], providerAndModel[1], variant)
            }
            sendVoid(
                requestBuilder("api/session/${encodePath(sessionId)}/command").post(
                    jsonBody(
                        buildJsonObject {
                            put("name", command)
                            put("text", arguments)
                        },
                    ),
                ),
            )
        }
    }

    /** V1 `always` is requested by [remember]; the V2 decision enum takes the same values. */
    override suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean,
    ): Boolean {
        val decision = if (remember && response == "once") "always" else response
        sendVoid(
            requestBuilder("api/session/${encodePath(sessionId)}/permission/${encodePath(permissionId)}/reply")
                .post(jsonBody(buildJsonObject { put("decision", decision) })),
        )
        return true
    }

    /**
     * Questions are V2 forms. The answer list is positional: one entry per field, and an empty entry
     * leaves that field out so the server can apply its own required check.
     */
    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean {
        val form = form(requestId, directory)
        val answer =
            buildJsonObject {
                form.fields.forEachIndexed { index, field ->
                    val values = answers.getOrNull(index).orEmpty()
                    if (values.isNotEmpty()) put(field.key, field.answerValue(values))
                }
            }
        sendVoid(
            requestBuilder("api/session/${encodePath(form.sessionId)}/form/${encodePath(form.id)}/reply")
                .post(jsonBody(buildJsonObject { put("answer", answer) })),
        )
        formRegistry.forget(form.id)
        return true
    }

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean {
        val form = form(requestId, directory)
        sendVoid(requestBuilder("api/session/${encodePath(form.sessionId)}/form/${encodePath(form.id)}").delete())
        formRegistry.forget(form.id)
        return true
    }

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> =
        pendingForms(directory).map { it.toQuestionRequest(directory) }

    /**
     * Streams `api/event`. A V2 stream has no `event:` or `id:` lines and no replay, so a reconnect
     * relies on the caller re-reading the transcript. Retry rules match the V1 client.
     */
    override fun events(): Flow<OpenCodeEvent> =
        flow { emitAll(singleEventStream()) }.retryWhen { cause, attempt ->
            val retryable = cause !is OpenCodeApiException || cause.statusCode >= 500
            if (!retryable) return@retryWhen false
            delay(backoffMillis(attempt))
            true
        }

    /**
     * One SSE connection. Each `data:` payload goes through a translator created for this connection
     * only, because the translator keeps per-stream state such as text ordinals and part ids.
     */
    private fun singleEventStream(): Flow<OpenCodeEvent> =
        channelFlow {
            val eventClient =
                httpClient.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val request =
                requestBuilder(EVENT_PATH)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
            val call = eventClient.newCall(request)
            val translator = OpenCodeV2EventTranslator(json, formRegistry)
            val readerJob =
                launch(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                throw OpenCodeApiException(
                                    statusCode = response.code,
                                    message = "OpenCode event stream failed (HTTP ${response.code})",
                                )
                            }
                            val body = requireNotNull(response.body) { "OpenCode event stream had no body" }
                            body.source().use { source ->
                                val data = StringBuilder()
                                while (isActive) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.isEmpty() -> {
                                            if (data.isNotEmpty()) {
                                                val events = translator.translate(data.toString())
                                                events.forEach { send(it) }
                                                // A V2 stream has no replay, so state that began before this connection
                                                // is pulled in once, right after the server confirms the connection.
                                                if (events.any { it == OpenCodeEvent.ServerConnected }) {
                                                    reconcile(translator) { send(it) }
                                                }
                                                data.setLength(0)
                                            }
                                        }
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n')
                                            data.append(line.removePrefix("data:").removePrefix(" "))
                                        }
                                    }
                                }
                                if (data.isNotEmpty()) translator.translate(data.toString()).forEach { send(it) }
                            }
                            throw IOException("OpenCode event stream closed")
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        close(error)
                    }
                }
            awaitClose {
                call.cancel()
                readerJob.cancel()
            }
        }.buffer(EVENT_BUFFER_CAPACITY)

    private suspend fun rawSession(sessionId: String): V2SessionInfo = getData("api/session/${encodePath(sessionId)}")

    /**
     * Re-emits state that a fresh SSE connection cannot replay: pending permissions, pending forms
     * (questions), and sessions that are still running. Each step is independent and failures are
     * swallowed: reconciliation is best-effort, and it must never end the live stream.
     *
     * Running sessions also get their in-flight tool calls seeded into [translator], so that the
     * next `tool.progress` or `tool.success` keeps the call's name and input instead of blanking them.
     */
    private suspend fun reconcile(
        translator: OpenCodeV2EventTranslator,
        emit: suspend (OpenCodeEvent) -> Unit,
    ) {
        runCatching {
            getData<List<V2PermissionRequest>>("api/permission/request").forEach { request ->
                emit(OpenCodeEvent.PermissionAsked(request.toPermissionRequest()))
            }
        }
        runCatching {
            pendingForms(null).forEach { form ->
                emit(OpenCodeEvent.QuestionAsked(form.toQuestionRequest(null)))
            }
        }
        runCatching {
            val active = getData<Map<String, JsonElement>>("api/session/active")
            active.keys.forEach { sessionId ->
                emit(OpenCodeEvent.SessionStatusChanged(sessionId, "busy"))
                runCatching { seedRunningTools(sessionId, translator) }
            }
        }
    }

    /** Seeds the calls of a running session's latest assistant turn; older turns are already settled. */
    private suspend fun seedRunningTools(
        sessionId: String,
        translator: OpenCodeV2EventTranslator,
    ) {
        val latest =
            execute(
                requestBuilder(
                    "api/session/${encodePath(sessionId)}/message",
                    query("order" to "desc", "limit" to "20"),
                ).get().build(),
            ) { body -> json.parseToJsonElement(body).jsonObject.arrOf("data").orEmpty() }
        latest.mapNotNull { it as? JsonObject }
            .firstOrNull { it.textOf("type") == "assistant" }
            ?.arrOf("content")
            ?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                if (item.textOf("type") != "tool") return@forEach
                val callId = item.textOf("id") ?: return@forEach
                val input = (item["state"] as? JsonObject)?.get("input")
                translator.seedTool(callId, item.textOf("name").orEmpty(), input)
            }
    }

    /** Looks a form up in the cache first, then in the server's pending list, which covers a form seen before this process started. */
    private suspend fun form(
        formId: String,
        directory: String?,
    ): V2Form =
        formRegistry.get(formId)
            ?: pendingForms(directory).firstOrNull { it.id == formId }
            ?: throw OpenCodeApiException(statusCode = 404, message = "OpenCode question was not found")

    private suspend fun pendingForms(directory: String?): List<V2Form> =
        getData<List<V2Form>>("api/form", locationQuery(directory)).onEach(formRegistry::remember)

    private suspend fun applyAgent(
        sessionId: String,
        agent: String,
    ) {
        sendVoid(
            requestBuilder("api/session/${encodePath(sessionId)}/agent")
                .post(jsonBody(buildJsonObject { put("agent", agent) })),
        )
    }

    private suspend fun applyModel(
        sessionId: String,
        providerId: String?,
        modelId: String?,
        variant: String?,
    ) {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return
        val body =
            buildJsonObject {
                put(
                    "model",
                    buildJsonObject {
                        put("id", modelId)
                        put("providerID", providerId)
                        variant?.takeIf { it.isNotBlank() }?.let { put("variant", it) }
                    },
                )
            }
        sendVoid(requestBuilder("api/session/${encodePath(sessionId)}/model").post(jsonBody(body)))
    }

    /** Every integration the server knows about. Read fresh each time; the list is small and changes when credentials do. */
    private suspend fun integrations(): List<V2Integration> = getData("api/integration")

    private suspend fun authMethodAt(
        providerId: String,
        methodIndex: Int,
    ): V2IntegrationMethod {
        val methods =
            integrationMethods[providerId]
                ?: integrations()
                    .firstOrNull { it.id == providerId }
                    ?.let { integration -> integration.methods.filter { it.isSupportedAuthMethod() && it.representable(json) } }
                    ?.also { integrationMethods[providerId] = it }
                ?: throw IllegalArgumentException("OpenCode provider $providerId has no auth methods")
        return methods.getOrNull(methodIndex)
            ?: throw IllegalArgumentException("OpenCode provider $providerId has no auth method $methodIndex")
    }

    private suspend inline fun <reified T> getData(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, query).get().build()) { body ->
                json.decodeFromJsonElement<T>(unwrapData(json.parseToJsonElement(body)))
            }
        }

    private suspend inline fun <reified T> postData(
        path: String,
        body: JsonObject,
        query: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, query).post(jsonBody(body)).build()) { raw ->
                json.decodeFromJsonElement<T>(unwrapData(json.parseToJsonElement(raw)))
            }
        }

    private suspend inline fun <reified T> pagedList(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): List<T> = pagedElements(path, query).map { json.decodeFromJsonElement<T>(it) }

    /**
     * Reads every page of a cursor-paged list, newest first, and returns the records in the order
     * they were read. Callers that need chronological order reverse the result themselves.
     *
     * The list is walked newest-first on purpose: when the hard cap is reached, the records that are
     * kept are the ones the user is looking at, not the oldest history. A cursor that repeats is a
     * server loop, so it stops the walk instead of spinning until the cap.
     */
    private suspend fun pagedElements(
        path: String,
        query: List<Pair<String, String>>,
    ): List<JsonElement> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<JsonElement>()
            val seenCursors = HashSet<String>()
            var cursor: String? = null
            var pages = 0
            do {
                // The cursor already carries the walk's direction; the server rejects a later page
                // that names `order` again (400 InvalidCursorError), so it only goes on the first.
                val pageQuery =
                    if (cursor == null) query else query.filterNot { it.first == "order" } + ("cursor" to cursor)
                val root =
                    execute(requestBuilder(path, pageQuery).get().build()) { body ->
                        json.parseToJsonElement(body).jsonObject
                    }
                root.arrOf("data")?.let { items.addAll(it) }
                val next = root.objOf("cursor")?.textOf("next")
                cursor = next?.takeIf { seenCursors.add(it) }
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            items
        }

    /**
     * Messages newest-first (so a cap keeps the recent turns), then reversed into chronological order
     * for the transcript. The reversal is applied to the whole walk, never to a single page, so the
     * order across page boundaries is correct too.
     */
    private suspend fun messagePages(sessionId: String): List<JsonElement> =
        pagedElements(
            "api/session/${encodePath(sessionId)}/message",
            query("order" to "desc", "limit" to PAGE_LIMIT),
        ).asReversed()

    private suspend fun sendVoid(
        builder: Request.Builder,
        client: OkHttpClient = httpClient,
    ) {
        withContext(Dispatchers.IO) {
            execute(builder.build(), client) { }
        }
    }

    private fun <T> execute(
        request: Request,
        client: OkHttpClient = httpClient,
        parse: (String) -> T,
    ): T {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeApiException(
                    statusCode = response.code,
                    message = formatHttpError(response.code, bodyText, request.isSensitive()),
                )
            }
            return parse(bodyText)
        }
    }

    private fun formatHttpError(
        statusCode: Int,
        body: String,
        sensitive: Boolean,
    ): String {
        if (sensitive || statusCode == 401 || statusCode == 403) {
            return "OpenCode request failed (HTTP $statusCode)"
        }
        val snippet =
            body
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString(" ")
                .take(MAX_ERROR_BODY_CHARS)
        return if (snippet.isBlank()) {
            "OpenCode request failed (HTTP $statusCode)"
        } else {
            "OpenCode request failed (HTTP $statusCode): $snippet"
        }
    }

    private fun requestBuilder(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): Request.Builder {
        val resolved =
            baseUrl.resolve(path.removePrefix("/"))
                ?: throw IllegalArgumentException("Invalid OpenCode API path")
        val url =
            resolved.newBuilder().apply {
                queryParameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                // V2 accepts only the username `opencode`; a profile username is ignored on purpose.
                profile.password?.takeIf { it.isNotBlank() }?.let { password ->
                    header("Authorization", Credentials.basic(USERNAME, password))
                }
            }
    }

    private fun jsonBody(body: JsonElement): RequestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun Request.isSensitive(): Boolean {
        val path = url.encodedPath
        return path.startsWith("/api/integration") || path.startsWith("/api/credential")
    }

    private fun query(vararg parameters: Pair<String, String?>): List<Pair<String, String>> =
        parameters.mapNotNull { (name, value) ->
            value?.takeIf { it.isNotBlank() }?.let { name to it }
        }

    private fun locationQuery(directory: String?): List<Pair<String, String>> = query("location[directory]" to directory)

    private fun encodePath(value: String): String = value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    private fun encodeRelativePath(path: String): String =
        path.trim('/').split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }

    private fun v2DiffMode(mode: String): String = if (mode in V2_DIFF_MODES) mode else "working"

    private fun isTextual(
        mime: String?,
        bytes: ByteArray,
    ): Boolean {
        // Without a usable type, a NUL byte is the only cheap evidence that the body is binary.
        if (mime == null || mime == "application/octet-stream") return bytes.none { it == 0.toByte() }
        return mime.startsWith("text/") || mime in TEXT_MIME_TYPES || mime.endsWith("+json") || mime.endsWith("+xml")
    }

    private fun backoffMillis(attempt: Long): Long = (500L * (1L shl attempt.toInt().coerceAtMost(5))).coerceAtMost(15_000L)

    companion object {
        /** V2 ignores any other username, so the Basic auth identity is fixed. */
        internal const val USERNAME = "opencode"
        private const val SESSIONS_PATH = "api/session"
        private const val EVENT_PATH = "api/event"
        private const val PAGE_LIMIT = "200"

        /** 100 pages of 200 is 20,000 records: generous for a chat, and still a bound on a broken cursor. */
        private const val MAX_PAGES = 100
        private const val MAX_ERROR_BODY_CHARS = 240
        private const val EVENT_BUFFER_CAPACITY = 512
        private const val PROVIDER_AUTH_TIMEOUT_MINUTES = 6L
        private const val MCP_AUTH_UNSUPPORTED = "OpenCode V2 does not expose MCP OAuth over REST"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val V2_DIFF_MODES = setOf("working", "branch", "committed")
        private val TEXT_MIME_TYPES =
            setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/typescript",
                "application/yaml",
                "application/toml",
                "application/x-sh",
                "application/sql",
            )
    }
}

/** Metadata key that carries the archive timestamp, since V2 cannot write `time.archived`. */
internal const val ARCHIVED_METADATA_KEY = "andcode.archivedAt"

private fun unwrapData(element: JsonElement): JsonElement = (element as? JsonObject)?.get("data") ?: element

private fun JsonObject.textOf(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOf(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.objOf(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrOf(key: String): JsonArray? = this[key] as? JsonArray

private fun V2IntegrationMethod.isSupportedAuthMethod(): Boolean = type == "key" || type == "oauth"

/**
 * True when the settings dialog can collect this method's form. Fields the dialog has no control
 * for (boolean, number, multiselect, external links) and conditions it cannot express make the
 * whole method unrepresentable, so it is left out instead of being offered half-working.
 */
private fun V2IntegrationMethod.representable(json: Json): Boolean {
    val fields = authFieldsOrNull(json) ?: return false
    return fields.all { it.isRepresentable() }
}

/**
 * Decodes the form fields. Returns null when any field fails to decode, so a method whose form is
 * unreadable is treated as unrepresentable rather than offered with its fields silently dropped.
 */
private fun V2IntegrationMethod.authFieldsOrNull(json: Json): List<V2AuthField>? {
    val decoded = form.orEmpty().map { element -> runCatching { json.decodeFromJsonElement<V2AuthField>(element) }.getOrNull() }
    return if (decoded.any { it == null }) null else decoded.filterNotNull()
}

private fun V2IntegrationMethod.authFields(json: Json): List<V2AuthField> = authFieldsOrNull(json).orEmpty()

private fun V2AuthField.isRepresentable(): Boolean {
    val controlSupported = type == "string" && !(hidden ?: false)
    // The dialog's `when` supports a single equality test; anything more is not representable.
    val conditionSupported = `when`.size <= 1 && `when`.all { it.op == "eq" && it.value != null }
    return controlSupported && conditionSupported
}

private fun V2IntegrationMethod.toAuthMethod(json: Json): ProviderAuthMethod {
    val prompts = authFields(json).map { it.toPrompt() }
    return if (type == "oauth") {
        ProviderAuthMethod(type = "oauth", label = label ?: "OAuth", prompts = prompts)
    } else {
        ProviderAuthMethod(type = "api", label = label ?: "API key", prompts = prompts)
    }
}

/** A `select` when the field has options, otherwise a free `text` prompt, which is what the dialog renders. */
private fun V2AuthField.toPrompt(): ProviderAuthPrompt =
    ProviderAuthPrompt(
        type = if (options.isNotEmpty()) "select" else "text",
        key = key,
        message = description ?: title ?: key,
        placeholder = placeholder,
        options = options.map { ProviderAuthOption(label = it.label ?: it.value, value = it.value, hint = it.description) },
        whenCondition = `when`.singleOrNull()?.let { ProviderAuthWhen(key = it.key, op = it.op, value = it.value.toDisplayString()) },
    )

/**
 * Builds the form `answer` for a method from the dialog's inputs. Only keys the method declares
 * are sent, so a stray input cannot reach the server. Returns null when there is nothing to send.
 */
private fun answerFor(
    form: List<JsonElement>?,
    inputs: Map<String, String>,
): JsonObject? {
    val keys = form.orEmpty().mapNotNull { element -> (element as? JsonObject)?.textOf("key") }.toSet()
    val answer = inputs.filterKeys { it in keys }.filterValues { it.isNotBlank() }
    if (answer.isEmpty()) return null
    return JsonObject(answer.mapValues { (_, value) -> JsonPrimitive(value) })
}

private fun JsonElement?.toDisplayString(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun V2SessionInfo.toSession(): OpenCodeSession {
    val archivedAt = metadata?.get(ARCHIVED_METADATA_KEY)?.let { (it as? JsonPrimitive)?.longOrNull }
    return OpenCodeSession(
        id = id,
        slug = slug,
        projectId = projectID,
        parentId = parentID,
        directory = location?.directory,
        title = title.orEmpty(),
        version = version,
        time = time.copy(archived = time.archived ?: archivedAt),
        tokens = tokens,
    )
}

private fun V2Model.toOpenCodeModel(): OpenCodeModel =
    OpenCodeModel(
        id = id,
        providerId = providerID,
        name = name ?: id,
        status = status,
        limit = limit,
        variants = variants.associate { variant -> variant.id to (variant.settings ?: JsonObject(emptyMap())) },
    )

private fun V2FileChange.toFileChange(): OpenCodeFileChange =
    OpenCodeFileChange(
        file = file,
        patch = patch,
        additions = additions,
        deletions = deletions,
        status = status,
    )

/**
 * Converts the app's answer strings into the value the form expects. The UI answers with option
 * *labels*, while the server stores option *values*, so a label that names an option is replaced
 * by that option's value. Free text that matches no option passes through unchanged, which is what
 * a custom answer needs.
 */
private fun V2FormField.answerValue(values: List<String>): JsonElement {
    fun optionValue(answer: String): String = options.firstOrNull { it.label == answer || it.value == answer }?.value ?: answer

    val first = values.first()
    return when (type) {
        "multiselect" -> JsonArray(values.map { JsonPrimitive(optionValue(it)) })
        "boolean" -> JsonPrimitive(first.equals("yes", ignoreCase = true) || first.equals("true", ignoreCase = true))
        "number" ->
            JsonPrimitive(first.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Expected a number for $key"))
        "integer" ->
            JsonPrimitive(first.trim().toLongOrNull() ?: throw IllegalArgumentException("Expected an integer for $key"))
        else -> JsonPrimitive(optionValue(first))
    }
}

/**
 * Maps one row of the V2 transcript onto the V1 message shape. The row types are the V2 union
 * `Session.Message.Info`. Only the rows that are part of the visible conversation are mapped;
 * synthetic, system, skill, compaction, agent/model selection and location rows are skipped.
 */
private fun JsonObject.toMessages(
    sessionId: String,
    json: Json,
): List<OpenCodeMessage> {
    val id = textOf("id") ?: return emptyList()
    val time = objOf("time")
    val created = time?.longOf("created") ?: 0L
    return when (textOf("type")) {
        "user" -> listOf(toUserMessage(id, sessionId, created))
        "assistant" -> listOf(toAssistantMessage(id, sessionId, time, json))
        "idle" -> listOfNotNull(toIdleMessage(id, sessionId, created))
        "shell" -> listOf(toShellMessage(id, sessionId, created))
        else -> emptyList()
    }
}

private fun JsonObject.toUserMessage(
    id: String,
    sessionId: String,
    created: Long,
): OpenCodeMessage {
    val parts = mutableListOf<OpenCodePart>()
    textOf("text")?.takeIf { it.isNotEmpty() }?.let { text ->
        parts +=
            OpenCodePart(
                id = OpenCodeV2Ids.textPartId(id, 0),
                sessionId = sessionId,
                messageId = id,
                type = "text",
                text = text,
            )
    }
    arrOf("files")?.forEachIndexed { index, element ->
        val file = element as? JsonObject ?: return@forEachIndexed
        val mime = file.textOf("mime") ?: "application/octet-stream"
        val data = file.textOf("data")
        val url = if (data != null) "data:$mime;base64,$data" else file.objOf("source")?.textOf("uri")
        parts +=
            OpenCodePart(
                id = "$id-file-$index",
                sessionId = sessionId,
                messageId = id,
                type = "file",
                mime = mime,
                filename = file.textOf("name"),
                url = url,
            )
    }
    return OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = id,
                sessionId = sessionId,
                role = "user",
                time = OpenCodeTime(created = created),
            ),
        parts = parts,
    )
}

private fun JsonObject.toAssistantMessage(
    id: String,
    sessionId: String,
    time: JsonObject?,
    json: Json,
): OpenCodeMessage {
    val parts = mutableListOf<OpenCodePart>()
    var textOrdinal = 0
    var reasoningOrdinal = 0
    arrOf("content")?.forEach { element ->
        val item = element as? JsonObject ?: return@forEach
        when (item.textOf("type")) {
            "reasoning" ->
                parts +=
                    OpenCodePart(
                        id = OpenCodeV2Ids.reasoningPartId(id, reasoningOrdinal++),
                        sessionId = sessionId,
                        messageId = id,
                        type = "reasoning",
                        text = item.textOf("text"),
                    )
            "text" ->
                parts +=
                    OpenCodePart(
                        id = OpenCodeV2Ids.textPartId(id, textOrdinal++),
                        sessionId = sessionId,
                        messageId = id,
                        type = "text",
                        text = item.textOf("text"),
                    )
            "tool" -> item.toToolPart(id, sessionId)?.let { parts += it }
        }
    }
    // Tokens are informational; a malformed block must not hide the whole assistant turn.
    val tokens = objOf("tokens")?.let { runCatching { json.decodeFromJsonElement<OpenCodeSessionTokens>(it) }.getOrNull() }
    return OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = time?.longOf("created") ?: 0L, completed = time?.longOf("completed")),
                agent = textOf("agent"),
                model =
                    objOf("model")?.let { model ->
                        OpenCodeModelReference(
                            providerId = model.textOf("providerID").orEmpty(),
                            modelId = model.textOf("id").orEmpty(),
                        )
                    },
                tokens = tokens,
                error = objOf("error")?.toMessageError(),
            ),
        parts = parts,
    )
}

/** V2 tool states are `streaming`, `running`, `completed` and `error`. Only `streaming` is renamed; the rest match V1. */
private fun JsonObject.toToolPart(
    messageId: String,
    sessionId: String,
): OpenCodePart? {
    val callId = textOf("id") ?: return null
    val state = objOf("state") ?: JsonObject(emptyMap())
    val output =
        state.arrOf("content")
            ?.mapNotNull { (it as? JsonObject)?.takeIf { part -> part.textOf("type") == "text" }?.textOf("text") }
            ?.joinToString("")
    val error =
        (state["error"] as? JsonObject)?.textOf("message")
            ?: (state["error"] as? JsonPrimitive)?.contentOrNull
    val mappedState = linkedMapOf<String, JsonElement>()
    mappedState["status"] = JsonPrimitive(mapToolStatus(state.textOf("status")))
    state["input"]?.let { mappedState["input"] = it }
    output?.takeIf { it.isNotEmpty() }?.let { mappedState["output"] = JsonPrimitive(it) }
    error?.let { mappedState["error"] = JsonPrimitive(it) }
    state["metadata"]?.let { mappedState["metadata"] = it }
    state.textOf("title")?.let { mappedState["title"] = JsonPrimitive(it) }
    return OpenCodePart(
        id = callId,
        sessionId = sessionId,
        messageId = messageId,
        type = "tool",
        tool = textOf("name"),
        callID = callId,
        state = mappedState,
    )
}

private fun mapToolStatus(status: String?): String =
    when (status) {
        "streaming" -> "pending"
        else -> status ?: "pending"
    }

/**
 * An idle row records how the turn ended. `succeeded` is the normal case and adds nothing to the
 * transcript. `failed` becomes an error message, and `interrupted` becomes an abort, which is not a
 * failure and is shown as one.
 */
private fun JsonObject.toIdleMessage(
    id: String,
    sessionId: String,
    created: Long,
): OpenCodeMessage? {
    val error =
        when (textOf("outcome")) {
            // A failure that the server recorded as an abort is a stop the user asked for, not an error.
            "failed" -> objOf("error")?.toMessageError() ?: OpenCodeMessageError(name = "UnknownError")
            "interrupted" -> OpenCodeMessageError(name = ABORT_ERROR_NAME)
            else -> return null
        }
    return OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = created),
                error = error,
            ),
    )
}

private fun JsonObject.toShellMessage(
    id: String,
    sessionId: String,
    created: Long,
): OpenCodeMessage {
    val status = textOf("status")
    val exit = longOf("exit")
    val toolStatus =
        when {
            status == "running" -> "running"
            status == "exited" && (exit == null || exit == 0L) -> "completed"
            else -> "error"
        }
    val callId = textOf("shellID") ?: id
    val state = linkedMapOf<String, JsonElement>()
    state["status"] = JsonPrimitive(toolStatus)
    textOf("command")?.let { state["input"] = JsonObject(mapOf("command" to JsonPrimitive(it))) }
    objOf("output")?.textOf("output")?.let { state["output"] = JsonPrimitive(it) }
    if (toolStatus == "error") state["error"] = JsonPrimitive("Shell command ended with status ${status ?: "unknown"}")
    return OpenCodeMessage(
        info =
            OpenCodeMessageInfo(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = created),
            ),
        parts =
            listOf(
                OpenCodePart(
                    id = callId,
                    sessionId = sessionId,
                    messageId = id,
                    type = "tool",
                    tool = "shell",
                    callID = callId,
                    state = state,
                ),
            ),
    )
}

/**
 * V2 names an aborted run `aborted` (for example a compaction cancelled by an interrupt). The app's
 * abort check matches on [ABORT_ERROR_NAME], so the name is translated here, or the stop would show
 * as a failure.
 */
private fun JsonObject.toMessageError(): OpenCodeMessageError {
    val type = textOf("type")
    return OpenCodeMessageError(
        name = if (type == "aborted") ABORT_ERROR_NAME else type,
        data = textOf("message")?.let { mapOf<String, JsonElement>("message" to JsonPrimitive(it)) },
    )
}

private const val ABORT_ERROR_NAME = "MessageAbortedError"

@Serializable
private data class V2Location(
    val directory: String? = null,
)

@Serializable
private data class V2LocationInfo(
    val directory: String,
    val project: V2LocationProject? = null,
)

@Serializable
private data class V2LocationProject(
    val id: String? = null,
    val canonical: String? = null,
)

@Serializable
private data class V2SessionInfo(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectID: String? = null,
    @SerialName("parentID") val parentID: String? = null,
    val title: String? = null,
    val version: String? = null,
    val location: V2Location? = null,
    val time: OpenCodeTime = OpenCodeTime(),
    val tokens: OpenCodeSessionTokens? = null,
    val metadata: JsonObject? = null,
)

@Serializable
private data class V2Provider(
    val id: String,
    val name: String? = null,
    val activation: String? = null,
)

@Serializable
private data class V2Model(
    val id: String,
    @SerialName("providerID") val providerID: String,
    val name: String? = null,
    val status: String? = null,
    val enabled: Boolean? = null,
    val limit: OpenCodeModelLimit? = null,
    val variants: List<V2Variant> = emptyList(),
)

@Serializable
private data class V2Variant(
    val id: String,
    val settings: JsonElement? = null,
)

@Serializable
private data class V2Agent(
    val id: String,
    val description: String? = null,
    val mode: String? = null,
    /** Nullable: an explicit `null` must decode as visible rather than fail the whole list. */
    val hidden: Boolean? = null,
)

@Serializable
private data class V2Integration(
    val id: String,
    val name: String? = null,
    val methods: List<V2IntegrationMethod> = emptyList(),
    val connections: List<JsonElement> = emptyList(),
)

@Serializable
private data class V2IntegrationMethod(
    val type: String,
    val id: String? = null,
    val label: String? = null,
    val form: List<JsonElement>? = null,
)

/** One field of an integration's `form`; only the parts the settings dialog can represent are read. */
@Serializable
private data class V2AuthField(
    val key: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val placeholder: String? = null,
    val hidden: Boolean? = null,
    val required: Boolean? = null,
    val options: List<V2FormOption> = emptyList(),
    val `when`: List<V2AuthWhen> = emptyList(),
)

@Serializable
private data class V2AuthWhen(
    val key: String,
    val op: String,
    val value: JsonElement? = null,
)

@Serializable
private data class V2OAuthAttempt(
    val attemptID: String,
    val url: String,
    val instructions: String = "",
    val mode: String = "auto",
)

@Serializable
private data class V2Credential(
    val id: String,
    @SerialName("integrationID") val integrationID: String,
)

@Serializable
private data class V2Project(
    val id: String,
    val canonical: String? = null,
    val name: String? = null,
)

@Serializable
private data class V2FsEntry(
    val path: String,
    val type: String,
)

@Serializable
private data class V2FileChange(
    val file: String? = null,
    val patch: String? = null,
    val additions: Double = 0.0,
    val deletions: Double = 0.0,
    val status: String? = null,
)

@Serializable
private data class V2VcsBranch(
    val current: String? = null,
    @SerialName("default") val defaultBranch: String? = null,
)

@Serializable
private data class V2VcsInfo(
    val branch: V2VcsBranch? = null,
)

@Serializable
private data class V2VcsBase(
    val name: String,
)

@Serializable
private data class V2Command(
    val name: String,
    val description: String? = null,
)

@Serializable
private data class V2Skill(
    val id: String,
    val description: String? = null,
)

/** `Permission.Request` as V2 sends it. `action` is the tool name; `resources` are the things it touches. */
@Serializable
private data class V2PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
)

/** Maps a V2 permission onto the V1 request the approval UI already renders. */
private fun V2PermissionRequest.toPermissionRequest(): PermissionRequest =
    PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = action,
        patterns = resources,
    )

@Serializable
private data class V2McpServer(
    val name: String,
    val status: JsonElement? = null,
)
