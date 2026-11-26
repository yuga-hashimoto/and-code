package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.pullRequestRefsIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** How long a transcript read that failed is left alone before the next attempt. */
private const val FAILED_SCAN_RETRY_MILLIS = 60_000L

/**
 * Learns which pull requests a chat linked without the user opening it.
 *
 * The drawer can badge a chat from the association that was remembered while the chat was open, so
 * a chat nobody had visited since the feature shipped showed no badge. This scans the transcripts
 * of the chats the drawer is about to show and reports what they linked, so the badge is there on
 * the first look.
 *
 * Reads run one at a time and only when the session's updated time differs from the last scan, so
 * reopening the drawer costs nothing for chats that have not changed. A session that is open right
 * now is skipped: its chat view model already tracks it live.
 */
class SessionPullRequestDiscovery(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Reads a session's transcript, or returns null when it cannot be read right now. */
    private val loadMessages: suspend (RuntimeSessionRef) -> List<OpenCodeMessage>? = { null },
    /** Records the pull requests a transcript linked, as the repository's session store does. */
    private val onDiscovered: (String, List<PullRequestRef>) -> Unit = { _, _ -> },
) {
    /** Session id to the updated time its transcript was last scanned at. */
    private val scannedRevision = ConcurrentHashMap<String, Long>()

    /** Session id to the earliest time a failed read may be retried. */
    private val retryAfter = ConcurrentHashMap<String, Long>()

    private var job: Job? = null

    /** Scans [sessions]' transcripts for pull request links, newest first, one at a time. */
    fun discover(
        sessions: List<RuntimeSessionRef>,
        openSessionId: String? = null,
    ) {
        job?.cancel()
        job =
            scope.launch {
                sessions.forEach { ref -> scan(ref, openSessionId) }
            }
    }

    private suspend fun scan(
        ref: RuntimeSessionRef,
        openSessionId: String?,
    ) {
        val session = ref.session
        // A subagent conversation is shown under its parent, and the open chat already records
        // its own links while it streams.
        if (session.parentId != null || session.id == openSessionId) return
        val revision = session.time.updated ?: session.time.created
        val waitingForRetry = (retryAfter[session.id] ?: 0L) > clock()
        if (scannedRevision[session.id] == revision || waitingForRetry) return

        val messages =
            try {
                loadMessages(ref)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                retryAfter[session.id] = clock() + FAILED_SCAN_RETRY_MILLIS
                return
            } ?: return

        val refs = pullRequestRefsIn(messages)
        if (refs.isNotEmpty()) onDiscovered(session.id, refs)
        scannedRevision[session.id] = revision
    }
}
