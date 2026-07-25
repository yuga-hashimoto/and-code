package com.opencode.android.core.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.opencode.android.core.security.OpenCodeUrl
import com.opencode.android.data.connection.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenCodeApiClient(
    private val profile: ConnectionProfile,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val gson: Gson = Gson(),
    private val eventParser: OpenCodeEventParser = OpenCodeEventParser(gson)
) {
    private val baseUrl: HttpUrl = OpenCodeUrl.normalize(profile.baseUrl).getOrThrow()

    @Volatile
    private var eventPath: String = GLOBAL_EVENT_PATH

    private val providerAuthHttpClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .callTimeout(PROVIDER_AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()

    suspend fun health(): OpenCodeHealth = get("global/health", OpenCodeHealth::class.java)

    suspend fun sessions(directory: String? = null): List<OpenCodeSession> =
        getList("session", query("directory" to directory))

    suspend fun createSession(
        title: String? = null,
        directory: String? = null
    ): OpenCodeSession {
        val body = JsonObject().apply {
            title?.takeIf { it.isNotBlank() }?.let { addProperty("title", it) }
        }
        return post(
            "session",
            body,
            OpenCodeSession::class.java,
            query("directory" to directory)
        )
    }

    suspend fun messages(sessionId: String): List<OpenCodeMessage> =
        getList("session/${encodePath(sessionId)}/message")

    suspend fun providers(): ProviderCatalog = get("provider", ProviderCatalog::class.java)

    suspend fun agents(): List<OpenCodeAgent> = getList("agent")

    suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        withContext(Dispatchers.IO) {
            val type = object : TypeToken<Map<String, List<ProviderAuthMethod>>>() {}.type
            execute(requestBuilder("provider/auth").get().build()) { body ->
                gson.fromJson<Map<String, List<ProviderAuthMethod>>>(body, type).orEmpty()
            }
        }

    suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String> = emptyMap()
    ): ProviderAuthAuthorization = post(
        "provider/${encodePath(providerId)}/oauth/authorize",
        JsonObject().apply {
            addProperty("method", methodIndex)
            if (inputs.isNotEmpty()) {
                add("inputs", JsonObject().apply {
                    inputs.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        },
        ProviderAuthAuthorization::class.java
    )

    suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap()
    ): Boolean = put(
        "auth/${encodePath(providerId)}",
        JsonObject().apply {
            addProperty("type", "api")
            addProperty("key", apiKey)
            if (metadata.isNotEmpty()) {
                add("metadata", JsonObject().apply {
                    metadata.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        },
        Boolean::class.java
    )

    suspend fun removeProviderAuth(providerId: String): Boolean =
        delete("auth/${encodePath(providerId)}", Boolean::class.java)

    suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("method", methodIndex)
            code?.takeIf { it.isNotBlank() }?.let { addProperty("code", it) }
        }
        val request = requestBuilder("provider/${encodePath(providerId)}/oauth/callback")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request, providerAuthHttpClient) { responseBody ->
            gson.fromJson(responseBody, Boolean::class.java)
        }
    }

    suspend fun projects(directory: String? = null): List<OpenCodeProject> =
        getList("project", query("directory" to directory))

    suspend fun currentProject(directory: String? = null): OpenCodeProject =
        get("project/current", OpenCodeProject::class.java, query("directory" to directory))

    suspend fun pathInfo(directory: String? = null): OpenCodePathInfo =
        get("path", OpenCodePathInfo::class.java, query("directory" to directory))

    suspend fun files(directory: String, path: String): List<OpenCodeFileNode> =
        getList("file", query("directory" to directory, "path" to path))

    suspend fun fileContent(directory: String, path: String): OpenCodeFileContent =
        get(
            "file/content",
            OpenCodeFileContent::class.java,
            query("directory" to directory, "path" to path)
        )

    suspend fun fileStatus(directory: String): List<OpenCodeFileChange> =
        getList("file/status", query("directory" to directory))

    suspend fun searchText(directory: String, pattern: String): List<OpenCodeSearchMatch> =
        getList("find", query("directory" to directory, "pattern" to pattern))

    suspend fun findFiles(
        directory: String,
        queryText: String,
        includeDirectories: Boolean? = null,
        type: String? = null,
        limit: Int? = null
    ): List<String> = getList(
        "find/file",
        query(
            "directory" to directory,
            "query" to queryText,
            "dirs" to includeDirectories?.toString(),
            "type" to type,
            "limit" to limit?.toString()
        )
    )

    suspend fun vcsInfo(directory: String): OpenCodeVcsInfo =
        get("vcs", OpenCodeVcsInfo::class.java, query("directory" to directory))

    suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> =
        getList("vcs/status", query("directory" to directory))

    suspend fun vcsDiff(
        directory: String,
        mode: String = "git",
        context: Int? = null
    ): List<OpenCodeFileChange> = getList(
        "vcs/diff",
        query("directory" to directory, "mode" to mode, "context" to context?.toString())
    )

    suspend fun sessionDiff(
        sessionId: String,
        directory: String? = null,
        messageId: String? = null
    ): List<OpenCodeFileChange> = getList(
        "session/${encodePath(sessionId)}/diff",
        query("directory" to directory, "messageID" to messageId)
    )

    suspend fun sessionTodo(
        sessionId: String,
        directory: String? = null
    ): List<OpenCodeTodo> = getList(
        "session/${encodePath(sessionId)}/todo",
        query("directory" to directory)
    )

    suspend fun promptAsync(sessionId: String, request: PromptRequest) {
        val json = JsonObject().apply {
            request.agent?.takeIf { it.isNotBlank() }?.let { addProperty("agent", it) }
            if (!request.providerId.isNullOrBlank() && !request.modelId.isNullOrBlank()) {
                add("model", JsonObject().apply {
                    addProperty("providerID", request.providerId)
                    addProperty("modelID", request.modelId)
                })
            }
            if (request.noReply) addProperty("noReply", true)
            add("parts", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", request.text)
                })
            })
        }
        postWithoutResponse("session/${encodePath(sessionId)}/prompt_async", json)
    }

    suspend fun abortSession(sessionId: String): Boolean =
        post("session/${encodePath(sessionId)}/abort", JsonObject(), Boolean::class.java)

    suspend fun renameSession(
        sessionId: String,
        title: String,
        directory: String? = null
    ): OpenCodeSession {
        val body = JsonObject().apply { addProperty("title", title) }
        return patch(
            "session/${encodePath(sessionId)}",
            body,
            OpenCodeSession::class.java,
            query("directory" to directory)
        )
    }

    suspend fun deleteSession(sessionId: String, directory: String? = null): Boolean =
        delete(
            "session/${encodePath(sessionId)}",
            Boolean::class.java,
            query("directory" to directory)
        )

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean = false
    ): Boolean {
        // OpenCode 1.18.x has no separate remember field; "always" persists the grant.
        val apiResponse = if (remember && response == "once") "always" else response
        val json = JsonObject().apply {
            addProperty("response", apiResponse)
        }
        return post(
            "session/${encodePath(sessionId)}/permissions/${encodePath(permissionId)}",
            json,
            Boolean::class.java
        )
    }

    /**
     * OpenCode's `/event` stream is scoped to a single instance — the one rooted at the
     * `directory` query parameter, defaulting to the server's own working directory. A session
     * created in any other workspace therefore produces no events at all on that stream, so the
     * client subscribes to the cross-instance `/global/event` stream and falls back to `/event`
     * only when the server is too old to expose it.
     */
    fun events(): Flow<OpenCodeEvent> = flow {
        emitAll(singleEventStream(eventPath))
    }.retryWhen { cause, attempt ->
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

        val backoffMillis = (500L * (1L shl attempt.toInt().coerceAtMost(5)))
            .coerceAtMost(15_000L)
        delay(backoffMillis)
        true
    }

    private fun singleEventStream(path: String): Flow<OpenCodeEvent> = callbackFlow {
        val eventClient = httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = requestBuilder(path)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = eventClient.newCall(request)
        val readerJob = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw OpenCodeApiException(
                            statusCode = response.code,
                            message = "OpenCode event stream failed (HTTP ${response.code})"
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
                                        trySend(eventParser.parse(data.toString()))
                                        data.setLength(0)
                                    }
                                }
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").removePrefix(" "))
                                }
                            }
                        }
                    }
                    if (isActive) throw IOException("OpenCode event stream closed")
                }
            } catch (error: Throwable) {
                if (isActive) close(error)
            }
        }
        awaitClose {
            call.cancel()
            readerJob.cancel()
        }
    }

    private suspend fun <T> get(
        path: String,
        clazz: Class<T>,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): T = withContext(Dispatchers.IO) {
        execute(requestBuilder(path, queryParameters).get().build()) { body -> gson.fromJson(body, clazz) }
    }

    private suspend inline fun <reified T> getList(
        path: String,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): List<T> = withContext(Dispatchers.IO) {
        execute(requestBuilder(path, queryParameters).get().build()) { body ->
            val type = object : TypeToken<List<T>>() {}.type
            gson.fromJson<List<T>>(body, type).orEmpty()
        }
    }

    private suspend fun <T> post(
        path: String,
        body: JsonObject,
        clazz: Class<T>,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): T = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, queryParameters)
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request) { responseBody -> gson.fromJson(responseBody, clazz) }
    }

    private suspend fun <T> put(
        path: String,
        body: JsonObject,
        clazz: Class<T>,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): T = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, queryParameters)
            .put(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request) { responseBody -> gson.fromJson(responseBody, clazz) }
    }

    private suspend fun <T> patch(
        path: String,
        body: JsonObject,
        clazz: Class<T>,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): T = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, queryParameters)
            .patch(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request) { responseBody -> gson.fromJson(responseBody, clazz) }
    }

    private suspend fun <T> delete(
        path: String,
        clazz: Class<T>,
        queryParameters: List<Pair<String, String>> = emptyList()
    ): T = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, queryParameters)
            .delete()
            .build()
        execute(request) { responseBody -> gson.fromJson(responseBody, clazz) }
    }

    private suspend fun postWithoutResponse(path: String, body: JsonObject) = withContext(Dispatchers.IO) {
        val request = requestBuilder(path)
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request) { Unit }
    }

    private fun <T> execute(
        request: Request,
        client: OkHttpClient = httpClient,
        parse: (String) -> T
    ): T {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeApiException(
                    statusCode = response.code,
                    message = formatHttpError(
                        statusCode = response.code,
                        body = bodyText,
                        sensitive = request.isProviderAuthRequest()
                    )
                )
            }
            return parse(bodyText)
        }
    }

    private fun formatHttpError(statusCode: Int, body: String, sensitive: Boolean = false): String {
        // Never attach bodies for auth operations or authorization failures — they may contain secrets.
        if (sensitive || statusCode == 401 || statusCode == 403) {
            return "OpenCode request failed (HTTP $statusCode)"
        }
        val snippet = body
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
        queryParameters: List<Pair<String, String>> = emptyList()
    ): Request.Builder {
        val resolved = baseUrl.resolve(path.removePrefix("/"))
            ?: throw IllegalArgumentException("Invalid OpenCode API path")
        val url = resolved.newBuilder().apply {
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

    private fun encodePath(value: String): String =
        value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_CHARS = 240
        private const val PROVIDER_AUTH_TIMEOUT_MINUTES = 6L
        private const val GLOBAL_EVENT_PATH = "global/event"
        private const val INSTANCE_EVENT_PATH = "event"
        private val GLOBAL_EVENT_UNSUPPORTED_CODES = setOf(400, 404, 405, 501)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
