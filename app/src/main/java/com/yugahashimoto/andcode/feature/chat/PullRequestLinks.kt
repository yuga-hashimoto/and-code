package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.MAX_TRACKED_PULL_REQUESTS
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestStatus
import com.yugahashimoto.andcode.core.api.parsePullRequestRefs

/** A pull request linked in the open chat, paired with its state once GitHub has answered. */
data class ChatPullRequest(
    val ref: PullRequestRef,
    val status: PullRequestStatus? = null,
)

/**
 * The pull requests this conversation opened, newest first.
 *
 * Assistant text carries the link an agent reports after opening a pull request, tool output
 * carries the one `gh pr create` prints, and user text carries one that was pasted in - all three
 * are worth a badge, so all three are scanned. Only the newest few are kept: the badge sits above
 * the composer, and a long conversation would otherwise push a wall of chips over the keyboard.
 */
fun pullRequestRefsIn(messages: List<ChatMessage>): List<PullRequestRef> {
    val refs = LinkedHashSet<PullRequestRef>()
    messages.forEach { message ->
        message.parts.forEach { part -> part.openedPullRequest()?.let(refs::add) }
    }
    return refs.toList().takeLast(MAX_TRACKED_PULL_REQUESTS).reversed()
}

/**
 * The one pull request [this] part reports, or null when it reports none - or several.
 *
 * A part that links several at once is listing them, not opening one: release notes credit every
 * pull request in the version, `gh pr list` prints the queue, a changelog names the whole history.
 * Counting those filled the badge with pull requests the conversation never touched. A part that
 * opens one names one - `gh pr create` prints a single link, and an agent announces its work as it
 * lands - so a lone link is the signal, and anything longer is a list to be read, not a badge.
 */
private fun ChatPart.openedPullRequest(): PullRequestRef? {
    val refs =
        when (this) {
            is ChatPart.Text -> parsePullRequestRefs(text)
            // A retried command can report the same pull request twice, once as output and once as
            // the error of the attempt before it, and that is still a single pull request.
            is ChatPart.Tool ->
                (parsePullRequestRefs(output.orEmpty()) + parsePullRequestRefs(error.orEmpty()))
                    .distinct()
            else -> return null
        }
    return refs.singleOrNull()
}
