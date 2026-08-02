package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.core.api.PullRequestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** How long a fetched state is treated as current before the next [track] refetches it. */
private const val REFRESH_INTERVAL_MILLIS = 60_000L

/**
 * Merged and closed pull requests are done: they are refetched rarely, only often enough to notice
 * a reopened one, which keeps a finished conversation from polling GitHub forever.
 */
private const val SETTLED_REFRESH_INTERVAL_MILLIS = 10 * 60_000L

/**
 * Keeps the live state of the pull requests linked in a conversation.
 *
 * State is cached per pull request rather than per chat, so returning to a chat shows the badge
 * immediately and only refetches once the cache goes stale. A failed fetch keeps the previous
 * state - a flaky network must not blank a badge - but still counts as an attempt, so an
 * unreachable pull request is not retried on every streamed token.
 */
class PullRequestStatusRepository(
    private val api: GitHubApiClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _statuses = MutableStateFlow<Map<String, PullRequestStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PullRequestStatus>> = _statuses.asStateFlow()

    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Fetches the state of every ref that has none yet or whose cached state has gone stale. */
    fun track(refs: List<PullRequestRef>) {
        refs.forEach { ref ->
            if (isStale(ref.key) && inFlight.add(ref.key)) {
                scope.launch {
                    // The attempt is recorded before the result is published, so a caller that
                    // reacts to the new state always sees a repository ready for the next refresh.
                    val status =
                        try {
                            api.getPullRequest(ref)
                        } finally {
                            fetchedAt[ref.key] = clock()
                            inFlight.remove(ref.key)
                        }
                    status?.let { fetched -> _statuses.update { it + (ref.key to fetched) } }
                }
            }
        }
    }

    private fun isStale(key: String): Boolean {
        val last = fetchedAt[key] ?: return true
        val interval =
            when (_statuses.value[key]?.state) {
                PullRequestState.MERGED, PullRequestState.CLOSED -> SETTLED_REFRESH_INTERVAL_MILLIS
                else -> REFRESH_INTERVAL_MILLIS
            }
        return clock() - last >= interval
    }
}
