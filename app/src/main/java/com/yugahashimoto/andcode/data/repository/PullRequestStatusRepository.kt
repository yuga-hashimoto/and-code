package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.core.api.MAX_TRACKED_PULL_REQUESTS
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.core.api.PullRequestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * Remembers which pull requests each chat linked so the drawer can badge a conversation it has not
 * opened this run. Implemented by the settings repository; when absent (the default) the
 * association stays in memory only, so tests can opt out or inject a fake.
 */
interface PullRequestSessionStore {
    /** Chat id to the pull requests linked in its transcript, newest first. */
    var sessionPullRequests: Map<String, List<PullRequestRef>>
}

/**
 * Keeps the live state of the pull requests linked in a conversation.
 *
 * State is cached per pull request rather than per chat, so returning to a chat shows the badge
 * immediately and only refetches once the cache goes stale. A failed fetch keeps the previous
 * state - a flaky network must not blank a badge - but still counts as an attempt, so an
 * unreachable pull request is not retried on every streamed token.
 *
 * The repository also remembers which pull requests belong to which chat. The drawer reads that
 * association to show a chat's pull request state without opening it, and [sessionStore] keeps it
 * across restarts so the badge survives the app being closed.
 */
class PullRequestStatusRepository(
    private val api: GitHubApiClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionStore: PullRequestSessionStore? = null,
) {
    private val _statuses = MutableStateFlow<Map<String, PullRequestStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PullRequestStatus>> = _statuses.asStateFlow()

    private val sessionRefs = MutableStateFlow(sessionStore?.sessionPullRequests.orEmpty())

    /**
     * The pull request state of each chat, newest first. A chat with no linked pull request is
     * absent, and a state still in flight is omitted rather than guessed, so the drawer may fall
     * back to an older pull request of the same chat until the newest one has been fetched.
     */
    val sessionStatuses: StateFlow<Map<String, List<PullRequestStatus>>> =
        combine(sessionRefs, _statuses) { refsBySession, byKey ->
            refsBySession.mapValues { (_, refs) -> refs.mapNotNull { byKey[it.key] } }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        // A chat remembered from an earlier run has its badge ready the moment the drawer opens.
        track(sessionRefs.value.values.flatten())
    }

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

    /**
     * Records the pull requests linked in [sessionId] and starts tracking their state.
     *
     * Called for every message change, but a chat that links none must not erase what it linked
     * before: the transcript is briefly empty while a chat is loading, and an old chat's link is
     * still what the drawer should show.
     */
    fun trackSession(
        sessionId: String,
        refs: List<PullRequestRef>,
    ) {
        if (sessionId.isBlank() || refs.isEmpty()) return
        val tracked = refs.distinct().take(MAX_TRACKED_PULL_REQUESTS)
        if (sessionRefs.value[sessionId] == tracked) {
            track(tracked)
            return
        }
        // Merge with what is on disk: the chat screen and the drawer can each hold a repository
        // instance in the same process, and neither may drop a chat the other just remembered.
        val remembered = sessionStore?.sessionPullRequests ?: sessionRefs.value
        val updated = remembered + (sessionId to tracked)
        sessionRefs.value = updated
        sessionStore?.sessionPullRequests = updated
        track(tracked)
    }

    /** Refetches the remembered pull requests whose cached state has gone stale. */
    fun refreshTrackedSessions() {
        // Another repository instance in the same process may have remembered a chat since this
        // one was built, so take the store's copy before refreshing it.
        sessionStore?.let { sessionRefs.value = it.sessionPullRequests }
        track(sessionRefs.value.values.flatten())
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

/**
 * Serializes the chat-to-pull-request map as one `chatId<TAB>owner/repo#number` line per link.
 *
 * A chat id and a pull request key never contain a tab or a newline, so line-based storage keeps
 * the format obvious and trivially backwards-compatible if it ever needs to change.
 */
internal fun encodeSessionPullRequests(sessions: Map<String, List<PullRequestRef>>): String =
    sessions.entries
        .sortedBy { it.key }
        .flatMap { (sessionId, refs) -> refs.map { ref -> "$sessionId\t${ref.key}" } }
        .joinToString("\n")

/** Reads back [encodeSessionPullRequests], skipping any line it cannot parse. */
internal fun decodeSessionPullRequests(raw: String?): Map<String, List<PullRequestRef>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val sessionId = line.substring(0, tab)
        val ref = parsePullRequestKey(line.substring(tab + 1)) ?: return@mapNotNull null
        sessionId to ref
    }.groupBy({ it.first }, { it.second }).mapValues { (_, refs) -> refs.distinct() }
}

private fun parsePullRequestKey(key: String): PullRequestRef? {
    val hash = key.lastIndexOf('#')
    if (hash <= 0) return null
    val parts = key.substring(0, hash).split('/')
    if (parts.size != 2 || parts.any { it.isBlank() }) return null
    val number = key.substring(hash + 1).toIntOrNull() ?: return null
    return PullRequestRef(parts[0], parts[1], number)
}
