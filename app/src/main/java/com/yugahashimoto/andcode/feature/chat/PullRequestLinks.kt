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
 * The pull requests this conversation links to, newest first.
 *
 * Assistant text carries the link an agent reports after opening a pull request, tool output
 * carries the one `gh pr create` prints, and user text carries one that was pasted in - all three
 * are worth a badge, so all three are scanned. Only the newest few are kept: the badge sits above
 * the composer, and a long conversation would otherwise push a wall of chips over the keyboard.
 */
fun pullRequestRefsIn(messages: List<ChatMessage>): List<PullRequestRef> {
    val refs = LinkedHashSet<PullRequestRef>()
    messages.forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is ChatPart.Text -> refs += parsePullRequestRefs(part.text)
                is ChatPart.Tool -> {
                    part.output?.let { refs += parsePullRequestRefs(it) }
                    part.error?.let { refs += parsePullRequestRefs(it) }
                }
                else -> Unit
            }
        }
    }
    return refs.toList().takeLast(MAX_TRACKED_PULL_REQUESTS).reversed()
}
