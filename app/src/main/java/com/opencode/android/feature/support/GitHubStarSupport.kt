package com.opencode.android.feature.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val REPOSITORY_PATH = "/repos/yuga-hashimoto/opencode-android"
private const val STAR_STATUS_PATH = "/user/starred/yuga-hashimoto/opencode-android"

const val GITHUB_STAR_COUNT_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
const val GITHUB_STAR_STATUS_CACHE_TTL_MS = 15 * 60 * 1000L

data class GitHubStarSnapshot(
    val stargazersCount: Int? = null,
    val starred: Boolean? = null,
)

object GitHubStarPromptPolicy {
    fun shouldShowInitial(
        onboardingCompleted: Boolean,
        promptHandled: Boolean,
    ): Boolean = !onboardingCompleted && !promptHandled

    fun shouldShowSecond(
        deferred: Boolean,
        secondPromptShown: Boolean,
        starred: Boolean?,
    ): Boolean = deferred && !secondPromptShown && starred != true
}

class GitHubStarService(
    private val client: OkHttpClient = OkHttpClient(),
    private val tokenProvider: () -> String?,
    private val apiBaseUrl: String = "https://api.github.com",
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(): GitHubStarSnapshot =
        withContext(Dispatchers.IO) {
            val token = tokenProvider()?.trim().takeUnless { it.isNullOrEmpty() }
            GitHubStarSnapshot(
                stargazersCount = fetchStarCount(),
                starred = token?.let(::fetchStarred),
            )
        }

    private fun fetchStarCount(): Int? =
        runCatching {
            val request =
                Request.Builder()
                    .url(apiUrl(REPOSITORY_PATH))
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                json.parseToJsonElement(body)
                    .jsonObject["stargazers_count"]
                    ?.jsonPrimitive
                    ?.intOrNull
            }
        }.getOrNull()

    private fun fetchStarred(token: String): Boolean? =
        runCatching {
            val request =
                Request.Builder()
                    .url(apiUrl(STAR_STATUS_PATH))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    204 -> true
                    404 -> false
                    else -> null
                }
            }
        }.getOrNull()

    private fun apiUrl(path: String): String = apiBaseUrl.trimEnd('/') + path
}
