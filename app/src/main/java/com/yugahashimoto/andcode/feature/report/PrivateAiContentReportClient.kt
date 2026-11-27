package com.yugahashimoto.andcode.feature.report

import com.yugahashimoto.andcode.core.security.SecretRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Sends only a user-reviewed excerpt; credentials and conversation history are never attached. */
class PrivateAiContentReportClient(
    url: String,
    client: OkHttpClient = OkHttpClient(),
    private val knownCredential: () -> String? = { null },
) {
    private val endpoint = url.toHttpUrl()
    private val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        require(endpoint.isHttps || endpoint.host in setOf("127.0.0.1", "localhost", "::1")) { "Report endpoint must use HTTPS" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null) {
            "Report endpoint must not contain credentials, a query, or a fragment"
        }
        require(endpoint.encodedPath == "/v1/reports") { "Invalid report endpoint path" }
    }

    suspend fun submit(
        reason: AiReportReason,
        excerpt: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val reviewed = excerpt.trim()
            val credential = knownCredential()?.takeIf { it.isNotBlank() }
            val invalidLength = reviewed.isEmpty() || reviewed.length > AiContentReportClient.MAX_EXCERPT_LENGTH
            val containsCredential = credential != null && reviewed.contains(credential)
            val containsDetectedSecret = SecretRedaction.redact(reviewed) != reviewed
            if (invalidLength || containsCredential || containsDetectedSecret) {
                return@withContext Result.failure(IllegalArgumentException("Invalid excerpt"))
            }
            val body = JSONObject().put("reason", reason.name).put("excerpt", reviewed).toString()
            val request = Request.Builder().url(endpoint).post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            try {
                noRedirectClient.newCall(request).execute().use { response ->
                    if (response.code == 201) Result.success(Unit) else Result.failure(IOException("Report submission failed"))
                }
            } catch (error: IOException) {
                Result.failure(error)
            }
        }
}
