package com.opencode.android.feature.settings

import com.opencode.android.data.connection.SecureSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class GitHubDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("interval") val intervalSeconds: Long = 5L,
    @SerialName("expires_in") val expiresInSeconds: Long = 900L,
)

@Serializable
data class GitHubAccount(val login: String, val name: String? = null)

@Serializable
data class GitHubRepo(
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

class GitHubAuthRepository(
    private val settings: SecureSettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
    private val clientId: String,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val token: String? get() = settings.githubToken
    val accountLogin: String? get() = settings.githubLogin

    suspend fun requestDeviceCode(): GitHubDeviceCode =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "GitHub client ID is not configured" }
            // repo: git push, PR creation, private repos. workflow: push/modify .github/workflows.
            // read:org: org repo visibility. gist: log/output sharing. notifications: PR updates.
            val body =
                FormBody.Builder().add(
                    "client_id",
                    clientId,
                ).add("scope", "read:user read:org repo workflow gist notifications").build()
            executeJson("https://github.com/login/device/code", body)
        }

    suspend fun pollToken(
        deviceCode: String,
        intervalSeconds: Long,
        expiresInSeconds: Long,
    ): String =
        withContext(Dispatchers.IO) {
            require(isConfigured) { "GitHub client ID is not configured" }
            val body =
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
            var delaySeconds = intervalSeconds.coerceAtLeast(1L)
            val deadline = System.currentTimeMillis() + expiresInSeconds.coerceAtLeast(1L) * 1000L
            while (System.currentTimeMillis() < deadline) {
                val request =
                    Request.Builder().url(
                        "https://github.com/login/oauth/access_token",
                    ).post(body).header("Accept", "application/json").build()
                val responseBody =
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "GitHub request failed: ${response.code}" }
                        response.body?.string().orEmpty()
                    }
                val responseJson = json.parseToJsonElement(responseBody).jsonObject
                val accessToken = responseJson["access_token"]?.jsonPrimitive?.content
                if (!accessToken.isNullOrBlank()) {
                    return@withContext accessToken
                }
                when (responseJson["error"]?.jsonPrimitive?.content) {
                    null, "authorization_pending" -> Unit
                    "slow_down" -> delaySeconds += 5L
                    "access_denied" -> error("GitHub authorization was denied")
                    "expired_token" -> error("GitHub authorization expired")
                    else -> error("GitHub authorization failed")
                }
                delaySeconds =
                    (responseJson["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: delaySeconds)
                        .coerceAtLeast(1L)
                kotlinx.coroutines.delay(delaySeconds * 1000L)
            }
            error("GitHub authorization timed out")
        }

    suspend fun refreshAccount(accessToken: String? = token): GitHubAccount? =
        withContext(Dispatchers.IO) {
            accessToken ?: return@withContext null
            val request =
                Request.Builder()
                    .url("https://api.github.com/user")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val account = json.decodeFromString<GitHubAccount>(response.body?.string().orEmpty())
                settings.githubLogin = account.login
                account
            }
        }

    suspend fun listRepos(): List<GitHubRepo> =
        withContext(Dispatchers.IO) {
            val accessToken = token ?: return@withContext emptyList()
            val request =
                Request.Builder()
                    .url("https://api.github.com/user/repos?sort=updated&per_page=100&affiliation=owner,collaborator,organization_member")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                json.decodeFromString<List<GitHubRepo>>(response.body?.string().orEmpty())
            }
        }

    fun saveToken(value: String) {
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        if (normalized != settings.githubToken) invalidateStarVerification()
        settings.githubToken = normalized
    }

    fun disconnect() {
        settings.githubToken = null
        settings.githubLogin = null
        invalidateStarVerification()
    }

    private fun invalidateStarVerification() {
        settings.githubStarredCache = null
        settings.githubStarStatusCheckedAt = 0L
    }

    private inline fun <reified T> executeJson(
        url: String,
        body: FormBody,
    ): T {
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub request failed: ${response.code}" }
            return json.decodeFromString<T>(response.body?.string().orEmpty())
        }
    }
}
