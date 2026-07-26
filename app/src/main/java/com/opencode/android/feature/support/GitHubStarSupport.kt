package com.opencode.android.feature.support

import com.opencode.android.data.connection.SecureSettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val REPOSITORY_PATH = "/repos/yuga-hashimoto/opencode-android"
private const val STAR_STATUS_PATH = "/user/starred/yuga-hashimoto/opencode-android"
private const val USER_AGENT = "OpenCodeAndroid"

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
    private val client: OkHttpClient,
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
                    .header("User-Agent", USER_AGENT)
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
                    .header("User-Agent", USER_AGENT)
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

class GitHubStarCoordinator(
    private val settings: SecureSettingsRepository,
    private val service: GitHubStarService,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableSnapshot =
        MutableStateFlow(
            GitHubStarSnapshot(
                stargazersCount = settings.githubStarCountCache,
                starred = settings.githubStarredCache,
            ),
        )
    val snapshot: StateFlow<GitHubStarSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableSecondPromptRequested = MutableStateFlow(false)
    val secondPromptRequested: StateFlow<Boolean> = mutableSecondPromptRequested.asStateFlow()

    private val mutableThankYouRequested = MutableStateFlow(false)
    val thankYouRequested: StateFlow<Boolean> = mutableThankYouRequested.asStateFlow()

    private val repositoryCheckPending = AtomicBoolean(false)
    private var refreshJob: Job? = null

    fun shouldShowInitialPrompt(): Boolean =
        GitHubStarPromptPolicy.shouldShowInitial(
            onboardingCompleted = settings.onboardingCompleted,
            promptHandled = settings.githubStarPromptShown,
        )

    fun markInitialStarOpened() {
        settings.githubStarPromptShown = true
        settings.githubStarPromptDeferred = false
        repositoryCheckPending.set(true)
    }

    fun markInitialDeferred() {
        settings.githubStarPromptShown = true
        settings.githubStarPromptDeferred = true
    }

    fun onSessionCompleted() {
        if (
            GitHubStarPromptPolicy.shouldShowSecond(
                deferred = settings.githubStarPromptDeferred,
                secondPromptShown = settings.githubStarSecondPromptShown,
                starred = settings.githubStarredCache,
            )
        ) {
            mutableSecondPromptRequested.value = true
        }
    }

    fun markSecondPromptPresented() {
        if (mutableSecondPromptRequested.value) {
            settings.githubStarSecondPromptShown = true
        }
    }

    fun markSecondStarOpened() {
        mutableSecondPromptRequested.value = false
        repositoryCheckPending.set(true)
    }

    fun dismissSecondPrompt() {
        mutableSecondPromptRequested.value = false
    }

    fun markRepositoryOpenedFromSettings() {
        repositoryCheckPending.set(true)
    }

    fun markThankYouShown() {
        settings.githubStarThankYouShown = true
        mutableThankYouRequested.value = false
    }

    fun onAppResumed() {
        val canVerify = !settings.githubToken.isNullOrBlank()
        if (repositoryCheckPending.get() && !canVerify) repositoryCheckPending.set(false)
        refresh(force = repositoryCheckPending.get() && canVerify)
    }

    fun refresh(force: Boolean = false) {
        if (force) {
            refreshJob?.cancel()
        } else if (refreshJob?.isActive == true) {
            return
        }

        val timestamp = now()
        val countFresh =
            settings.githubStarCountCache != null &&
                settings.githubStarCountCheckedAt > 0L &&
                timestamp - settings.githubStarCountCheckedAt < GITHUB_STAR_COUNT_CACHE_TTL_MS
        val tokenAvailable = !settings.githubToken.isNullOrBlank()
        val statusFresh =
            !tokenAvailable ||
                (
                    settings.githubStarredCache != null &&
                        settings.githubStarStatusCheckedAt > 0L &&
                        timestamp - settings.githubStarStatusCheckedAt < GITHUB_STAR_STATUS_CACHE_TTL_MS
                )

        if (!force && countFresh && statusFresh) {
            mutableSnapshot.value =
                GitHubStarSnapshot(
                    stargazersCount = settings.githubStarCountCache,
                    starred = if (tokenAvailable) settings.githubStarredCache else null,
                )
            return
        }

        if (!tokenAvailable || !statusFresh) {
            mutableSnapshot.value =
                mutableSnapshot.value.copy(
                    starred = if (tokenAvailable) settings.githubStarredCache else null,
                )
        }

        refreshJob =
            scope.launch {
                val fetched = service.fetch()
                val checkedAt = now()
                fetched.stargazersCount?.let {
                    settings.githubStarCountCache = it
                    settings.githubStarCountCheckedAt = checkedAt
                }
                if (tokenAvailable) {
                    fetched.starred?.let {
                        settings.githubStarredCache = it
                        settings.githubStarStatusCheckedAt = checkedAt
                    }
                }

                mutableSnapshot.value =
                    GitHubStarSnapshot(
                        stargazersCount = fetched.stargazersCount ?: settings.githubStarCountCache,
                        starred = if (tokenAvailable) fetched.starred ?: settings.githubStarredCache else null,
                    )

                if (repositoryCheckPending.get() && fetched.starred != null) {
                    repositoryCheckPending.set(false)
                    if (fetched.starred && !settings.githubStarThankYouShown) {
                        mutableThankYouRequested.value = true
                    }
                }
            }
    }
}
