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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenCodeApiClient(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = defaultHttpClient(profile),
    private val json: Json = defaultJson,
    private val eventParser: OpenCodeEventParser = OpenCodeEventParser(json),
) {
    // Resolved lazily: constructing a client must never throw. Clients are built while assembling
    // the runtime target list, which happens on the main thread at app start and whenever a
    // connection is saved, so an endpoint the current rules reject has to surface as a failed
    // request rather than as an exception escaping into a UI callback.
    private val baseUrl: HttpUrl by lazy { OpenCodeUrl.normalize(profile.baseUrl).getOrThrow() }

    @Volatile
    private var eventPath: String = GLOBAL_EVENT_PATH

    private val providerAuthHttpClient: OkHttpClient =
        httpClient.newBuilder()
            .readTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

    suspend fun health(): OpenCodeHealth = get("global/health")

    suspend fun sessions(directory: String? = null): List<OpenCodeSession> = getList("session", query("directory" to directory))

    suspend fun session(sessionId: String): OpenCodeSession = get("session/${encodePath(sessionId)}")

    suspend fun createSession(
        title: String? = null,
        directory: String? = null,
    ): OpenCodeSession {
        val body =
            buildJsonObject {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            }
        return post(
            "session",
            body,
            query("directory" to directory),
        )
    }

    suspend fun messages(sessionId: String): List<OpenCodeMessage> = getList("session/${encodePath(sessionId)}/message")

    suspend fun providers(): ProviderCatalog = get("provider")

    suspend fun agents(): List<OpenCodeAgent> = getList("agent")

    suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        withContext(Dispatchers.IO) {
            execute(requestBuilder("provider/auth").get().build()) { body ->
                json.decodeFromString<Map<String, List<ProviderAuthMethod>>>(body)
            }
        }

    suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String> = emptyMap(),
    ): ProviderAuthAuthorization =
        post(
            "provider/${encodePath(providerId)}/oauth/authorize",
            buildJsonObject {
                put("method", methodIndex)
                if (inputs.isNotEmpty()) {
                    put(
                        "inputs",
                        buildJsonObject {
                            inputs.forEach { (key, value) -> put(key, value) }
                        },
                    )
                }
            },
        )

    suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap(),
    ): Boolean =
        put(
            "auth/${encodePath(providerId)}",
            buildJsonObject {
                put("type", "api")
                put("key", apiKey)
                if (metadata.isNotEmpty()) {
                    put(
                        "metadata",
                        buildJsonObject {
                            metadata.forEach { (key, value) -> put(key, value) }
                        },
                    )
                }
            },
        )

    suspend fun removeProviderAuth(providerId: String): Boolean = delete("auth/${encodePath(providerId)}")

    suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val body =
                buildJsonObject {
                    put("method", methodIndex)
                    code?.takeIf { it.isNotBlank() }?.let { put("code", it) }
                }
            val request =
                requestBuilder("provider/${encodePath(providerId)}/oauth/callback")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request, providerAuthHttpClient) { responseBody ->
                json.decodeFromString<Boolean>(responseBody)
            }
        }

    suspend fun projects(directory: String? = null): List<OpenCodeProject> = getList("project", query("directory" to directory))

    suspend fun currentProject(directory: String? = null): OpenCodeProject = get("project/current", query("directory" to directory))

    suspend fun pathInfo(directory: String? = null): OpenCodePathInfo = get("path", query("directory" to directory))

    suspend fun files(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = getList("file", query("directory" to directory, "path" to path))

    suspend fun fileContent(
        directory: String,
        path: String,
    ): OpenCodeFileContent =
        get(
            "file/content",
            query("directory" to directory, "path" to path),
        )

    suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = getList("file/status", query("directory" to directory))

    suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = getList("find", query("directory" to directory, "pattern" to pattern))

    suspend fun findFiles(
        directory: String,
        queryText: String,
        includeDirectories: Boolean? = null,
        type: String? = null,
        limit: Int? = null,
    ): List<String> =
        getList(
            "find/file",
            query(
                "directory" to directory,
                "query" to queryText,
                "dirs" to includeDirectories?.toString(),
                "type" to type,
                "limit" to limit?.toString(),
            ),
        )

    suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = get("vcs", query("directory" to directory))

    suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = getList("vcs/status", query("directory" to directory))

    suspend fun vcsDiff(
        directory: String,
        mode: String = "git",
        context: Int? = null,
    ): List<OpenCodeFileChange> =
        getList(
            "vcs/diff",
            query("directory" to directory, "mode" to mode, "context" to context?.toString()),
        )

    suspend fun sessionDiff(
        sessionId: String,
        directory: String? = null,
        messageId: String? = null,
    ): List<OpenCodeFileChange> =
        getList(
            "session/${encodePath(sessionId)}/diff",
            query("directory" to directory, "messageID" to messageId),
        )

    suspend fun sessionTodo(
        sessionId: String,
        directory: String? = null,
    ): List<OpenCodeTodo> =
        getList(
            "session/${encodePath(sessionId)}/todo",
            query("directory" to directory),
        )

    suspend fun promptAsync(
        sessionId: String,
        request: PromptRequest,
    ) {
        val body =
            buildJsonObject {
                request.agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
                if (!request.providerId.isNullOrBlank() && !request.modelId.isNullOrBlank()) {
                    put(
                        "model",
                        buildJsonObject {
                            put("providerID", request.providerId)
                            put("modelID", request.modelId)
                        },
                    )
                }
                request.variant?.takeIf { it.isNotBlank() }?.let { put("variant", it) }
                if (request.noReply) put("noReply", true)
                put(
                    "parts",
                    buildJsonArray {
                        if (request.text.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", request.text)
                                },
                            )
                        }
                        request.attachments.forEach { attachment ->
                            add(
                                buildJsonObject {
                                    put("type", "file")
                                    put("mime", attachment.mime)
                                    put("filename", attachment.filename)
                                    put("url", attachment.url)
                                },
                            )
                        }
                    },
                )
            }
        postWithoutResponse("session/${encodePath(sessionId)}/prompt_async", body)
    }

    suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean =
        post(
            "session/${encodePath(sessionId)}/summarize",
            buildJsonObject {
                put("providerID", providerId)
                put("modelID", modelId)
            },
        )

    suspend fun abortSession(sessionId: String): Boolean = post("session/${encodePath(sessionId)}/abort", JsonObject(emptyMap()))

    suspend fun renameSession(
        sessionId: String,
        title: String,
        directory: String? = null,
    ): OpenCodeSession {
        val body = buildJsonObject { put("title", title) }
        return patch(
            "session/${encodePath(sessionId)}",
            body,
            query("directory" to directory),
        )
    }

    suspend fun deleteSession(
        sessionId: String,
        directory: String? = null,
    ): Boolean =
        delete(
            "session/${encodePath(sessionId)}",
            query("directory" to directory),
        )

    suspend fun archiveSession(
        sessionId: String,
        directory: String? = null,
    ): OpenCodeSession {
        val body = buildJsonObject { put("archive", true) }
        return patch(
            "session/${encodePath(sessionId)}",
            body,
            query("directory" to directory),
        )
    }

    suspend fun mcpServers(): List<McpServer> =
        withContext(Dispatchers.IO) {
            execute(requestBuilder("mcp").get().build()) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                root.entries.map { (name, value) ->
                    val serverObj = value.jsonObject
                    val tools =
                        serverObj["tools"]?.let { toolsElement ->
                            when (toolsElement) {
                                is JsonArray ->
                                    toolsElement
                                        .mapNotNull { (it as? JsonPrimitive)?.content }
                                is JsonObject -> toolsElement.keys.toList()
                                else -> emptyList()
                            }
                        }.orEmpty()
                    val serverWithoutTools =
                        JsonObject(
                            serverObj.filterKeys { it != "tools" } + ("name" to JsonPrimitive(name)),
                        )
                    json.decodeFromJsonElement(McpServer.serializer(), serverWithoutTools).copy(tools = tools)
                }
            }
        }

    suspend fun addMcpServer(body: JsonObject): McpServer {
        val statuses: Map<String, McpAuthStatus> = post("mcp", body)
        val name = (body["name"] as? JsonPrimitive)?.content ?: error("MCP server name is missing")
        val status = statuses[name]
        return McpServer(name = name, status = status?.status, error = status?.error)
    }

    suspend fun connectMcpServer(name: String): Boolean = post("mcp/${encodePath(name)}/connect", JsonObject(emptyMap()))

    suspend fun disconnectMcpServer(name: String): Boolean = post("mcp/${encodePath(name)}/disconnect", JsonObject(emptyMap()))

    suspend fun removeMcpAuth(name: String): McpAuthRemoval = delete("mcp/${encodePath(name)}/auth")

    suspend fun mcpAuth(name: String): McpAuthStart = post("mcp/${encodePath(name)}/auth", JsonObject(emptyMap()))

    suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus {
        val body = buildJsonObject { put("code", code) }
        return post("mcp/${encodePath(name)}/auth/callback", body)
    }

    suspend fun config(): JsonElement =
        withContext(Dispatchers.IO) {
            execute(requestBuilder("config").get().build()) { body ->
                json.parseToJsonElement(body)
            }
        }

    suspend fun updateConfig(patch: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder("config")
                    .patch(patch.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { body ->
                json.parseToJsonElement(body)
            }
        }

    suspend fun configProviders(): List<ConfiguredProvider> = getList("config/providers")

    suspend fun commands(): List<OpenCodeCommand> = getList("command")

    suspend fun skills(): List<OpenCodeSkill> = getList("skill")

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean = false,
    ): Boolean {
        val apiResponse = if (remember && response == "once") "always" else response
        val body =
            buildJsonObject {
                put("response", apiResponse)
            }
        postWithoutResponse(
            "session/${encodePath(sessionId)}/permissions/${encodePath(permissionId)}",
            body,
        )
        return true
    }

    /**
     * Questions are answered on the request itself, not through the session that asked. Like
     * `/event`, the question routes resolve to a single OpenCode instance — the one rooted at
     * [directory], defaulting to the server's own working directory — so a request raised in any
     * other workspace is invisible without it and the reply comes back as
     * `QuestionNotFoundError`. Pass the directory the question was asked in.
     */
    suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null,
    ): Boolean {
        val body =
            buildJsonObject {
                put(
                    "answers",
                    buildJsonArray {
                        answers.forEach { answerGroup ->
                            add(buildJsonArray { answerGroup.forEach { add(JsonPrimitive(it)) } })
                        }
                    },
                )
            }
        postWithoutResponse(
            "question/${encodePath(requestId)}/reply",
            body,
            query("directory" to directory),
        )
        return true
    }

    /** Declines a question outright, which fails the waiting tool call without killing the turn. */
    suspend fun rejectQuestion(
        requestId: String,
        directory: String? = null,
    ): Boolean =
        post(
            "question/${encodePath(requestId)}/reject",
            JsonObject(emptyMap()),
            query("directory" to directory),
        )

    /**
     * Questions that are still waiting for an answer. They reach a client only through the event
     * stream, so anything asked while this client was away — a reconnect, a restart, a session
     * opened after the fact — has to be recovered here or the turn stays blocked with nothing on
     * screen to unblock it.
     */
    suspend fun pendingQuestions(directory: String? = null): List<QuestionRequest> =
        getList<QuestionRequest>("question", query("directory" to directory))
            .map { request -> request.copy(directory = request.directory ?: directory) }

    /**
     * `GET /event` is scoped to a single OpenCode instance: the one rooted at the request's
     * `directory` query parameter, which falls back to the server's own working directory. A
     * session created in any other workspace therefore emits nothing on that stream — no reply
     * text, no tool output, and no permission request, so a run that needs approval waits
     * forever on a request that never reaches the client. Subscribe to the cross-instance
     * `/global/event` stream instead, and fall back only for servers that predate it.
     */
    fun events(): Flow<OpenCodeEvent> =
        flow { emitAll(singleEventStream(eventPath)) }.retryWhen { cause, attempt ->
            if (
                eventPath == GLOBAL_EVENT_PATH &&
                cause is OpenCodeApiException &&
                cause.statusCode in GLOBAL_EVENT_UNSUPPORTED_CODES
            ) {
                eventPath = INSTANCE_EVENT_PATH
                return@retryWhen true
            }

            val retryable = cause !is OpenCodeApiException || cause.statusCode >= 500
            if (!retryable) return@retryWhen false

            val backoffMillis =
                (500L * (1L shl attempt.toInt().coerceAtMost(5)))
                    .coerceAtMost(15_000L)
            delay(backoffMillis)
            true
        }

    private fun singleEventStream(path: String): Flow<OpenCodeEvent> =
        channelFlow {
            val eventClient =
                httpClient.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val request =
                requestBuilder(path)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
            val call = eventClient.newCall(request)
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
                                                send(eventParser.parse(data.toString()))
                                                data.setLength(0)
                                            }
                                        }
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n')
                                            data.append(line.removePrefix("data:").removePrefix(" "))
                                        }
                                    }
                                }
                                if (data.isNotEmpty()) send(eventParser.parse(data.toString()))
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

    private suspend inline fun <reified T> get(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, queryParameters).get().build()) { body -> json.decodeFromString<T>(body) }
        }

    private suspend inline fun <reified T> getList(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): List<T> =
        withContext(Dispatchers.IO) {
            execute(requestBuilder(path, queryParameters).get().build()) { body ->
                json.decodeFromString<List<T>>(body)
            }
        }

    private suspend inline fun <reified T> post(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { responseBody -> json.decodeFromString<T>(responseBody) }
        }

    private suspend inline fun <reified T> put(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { responseBody -> json.decodeFromString<T>(responseBody) }
        }

    private suspend inline fun <reified T> patch(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            execute(request) { responseBody -> json.decodeFromString<T>(responseBody) }
        }

    private suspend inline fun <reified T> delete(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                requestBuilder(path, queryParameters)
                    .delete()
                    .build()
            execute(request) { responseBody -> json.decodeFromString<T>(responseBody) }
        }

    private suspend fun postWithoutResponse(
        path: String,
        body: JsonObject,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val request =
            requestBuilder(path, queryParameters)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        execute(request) { Unit }
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
                    message =
                        formatHttpError(
                            statusCode = response.code,
                            body = bodyText,
                            sensitive = request.isProviderAuthRequest(),
                        ),
                )
            }
            return parse(bodyText)
        }
    }

    private fun formatHttpError(
        statusCode: Int,
        body: String,
        sensitive: Boolean = false,
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
                profile.password?.takeIf { it.isNotBlank() }?.let { password ->
                    header("Authorization", Credentials.basic(profile.username.ifBlank { "opencode" }, password))
                }
            }
    }

    private fun Request.isProviderAuthRequest(): Boolean {
        val path = url.encodedPath
        return path.startsWith("/auth/") ||
            path == "/provider/auth" ||
            path.contains("/oauth/")
    }

    private fun query(vararg parameters: Pair<String, String?>): List<Pair<String, String>> =
        parameters.mapNotNull { (name, value) ->
            value?.takeIf { it.isNotBlank() }?.let { name to it }
        }

    private fun encodePath(value: String): String = value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_CHARS = 240
        private const val EVENT_BUFFER_CAPACITY = 512
        private const val PROVIDER_AUTH_TIMEOUT_MINUTES = 6L
        private const val GLOBAL_EVENT_PATH = "global/event"
        private const val INSTANCE_EVENT_PATH = "event"
        private val GLOBAL_EVENT_UNSUPPORTED_CODES = setOf(400, 404, 405, 501)

        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        fun defaultHttpClient(profile: ConnectionProfile? = null): OkHttpClient {
            val builder =
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
            val pin = profile?.pinSha256
            if (!pin.isNullOrBlank() && profile.baseUrl.startsWith("https://", ignoreCase = true)) {
                val host =
                    profile.baseUrl.toHttpUrlOrNull()?.host
                        ?: profile.baseUrl.removePrefix("https://").substringBefore("/")
                builder.certificatePinner(
                    CertificatePinner.Builder()
                        .add(host, "sha256/$pin")
                        .build(),
                )
            }
            return builder.build()
        }
    }
}
