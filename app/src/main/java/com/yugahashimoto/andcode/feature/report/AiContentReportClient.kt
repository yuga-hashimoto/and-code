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

/** The user's own GitHub account submits a public issue; no conversation is collected implicitly. */
enum class AiReportReason {
    HARMFUL,
    HARASSMENT,
    DECEPTIVE,
    OTHER,
}

class AiContentReportClient(
    private val token: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) {
    private val endpoint = baseUrl.toHttpUrl()
    private val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        require(endpoint.isHttps || endpoint.host in setOf("127.0.0.1", "localhost", "::1")) {
            "Report endpoint must use HTTPS"
        }
    }

    suspend fun submit(
        reason: AiReportReason,
        excerpt: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val credential =
                token()?.takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("GitHub account required"))
            val reviewed = excerpt.trim()
            if (reviewed.isEmpty() || reviewed.length > MAX_EXCERPT_LENGTH) {
                return@withContext Result.failure(IllegalArgumentException("Invalid excerpt"))
            }
            if (reviewed.contains(credential) || SecretRedaction.redact(reviewed) != reviewed) {
                return@withContext Result.failure(IllegalArgumentException("Excerpt may contain credentials"))
            }
            // Prevent user-controlled text from escaping the code block and mentioning accounts.
            val safeExcerpt = reviewed.replace("`", "\\`").replace("@", "@\u200b")
            val body =
                JSONObject()
                    .put("title", "AI content report: ${reason.name}")
                    .put("body", "Reason: ${reason.name}\n\nUser-provided excerpt:\n\n```text\n$safeExcerpt\n```")
                    .toString()
            val request =
                Request.Builder()
                    .url("$baseUrl/repos/yuga-hashimoto/and-code/issues")
                    .header("Authorization", "Bearer $credential")
                    .header("Accept", "application/vnd.github+json")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            try {
                noRedirectClient.newCall(request).execute().use { response ->
                    if (response.code == 201) Result.success(Unit) else Result.failure(IOException("Report submission failed"))
                }
            } catch (error: IOException) {
                Result.failure(error)
            }
        }

    companion object {
        const val MAX_EXCERPT_LENGTH = 4000
    }
}
