package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Highest number of pull requests kept from one conversation, newest first. */
const val MAX_TRACKED_PULL_REQUESTS = 5

/**
 * Matches the pull request links agents print - `gh pr create` output, markdown links, and plain
 * URLs alike. The number is required, so the "create a pull request" hint `git push` prints
 * (`.../pull/new/<branch>`) is not mistaken for an existing pull request.
 */
private val PULL_REQUEST_URL =
    Regex("""https?://(?:www\.)?github\.com/([\w.-]+)/([\w.-]+)/pull/(\d+)""")

/** A pull request mentioned in a conversation, identified by the parts of its URL. */
data class PullRequestRef(
    val owner: String,
    val repo: String,
    val number: Int,
) {
    /** Stable identity for caching; two links to the same pull request share it. */
    val key: String get() = "$owner/$repo#$number"

    val url: String get() = "https://github.com/$owner/$repo/pull/$number"

    /** The "Files changed" tab, so tapping the diff opens the changed files on GitHub. */
    val filesUrl: String get() = "$url/files"
}

/** What the pull request badge shows at a glance. */
enum class PullRequestState {
    OPEN,
    DRAFT,
    MERGED,
    CLOSED,
    CONFLICT,
}

/** A pull request's live state on GitHub, as of the last successful fetch. */
data class PullRequestStatus(
    val ref: PullRequestRef,
    val state: PullRequestState,
    val additions: Int = 0,
    val deletions: Int = 0,
)

/**
 * Collapses GitHub's separate flags into the single state the badge shows.
 *
 * Order matters: a merged pull request also reports `state = closed`, and a conflicted one is still
 * open, so the most decisive fact wins. Conflicts outrank draft because they are the thing that
 * needs attention.
 */
internal fun pullRequestState(
    state: String,
    draft: Boolean,
    merged: Boolean,
    mergeableState: String?,
): PullRequestState =
    when {
        merged -> PullRequestState.MERGED
        state.equals("closed", ignoreCase = true) -> PullRequestState.CLOSED
        mergeableState.equals("dirty", ignoreCase = true) -> PullRequestState.CONFLICT
        draft -> PullRequestState.DRAFT
        else -> PullRequestState.OPEN
    }

/**
 * Every distinct pull request linked in [text], in the order the links appear.
 *
 * The cheap `contains` check comes first because this runs over the whole transcript every time a
 * streaming message grows.
 */
fun parsePullRequestRefs(text: String): List<PullRequestRef> {
    if (!text.contains("github.com")) return emptyList()
    return PULL_REQUEST_URL.findAll(text)
        .mapNotNull { match ->
            val (owner, repo, number) = match.destructured
            number.toIntOrNull()?.let { PullRequestRef(owner, repo, number) }
        }
        .distinct()
        .toList()
}

/**
 * The one pull request [text] names, or null when it names none - or several.
 *
 * A piece of text that links several at once is listing them (release notes, `gh pr list`), not
 * reporting one, so only a lone link counts as "this part opened a pull request".
 */
fun singlePullRequestIn(text: String): PullRequestRef? = parsePullRequestRefs(text).singleOrNull()

/** The one pull request a tool call reports in its output or error, or null when it reports none. */
fun singlePullRequestInTool(
    output: String?,
    error: String?,
): PullRequestRef? =
    (parsePullRequestRefs(output.orEmpty()) + parsePullRequestRefs(error.orEmpty()))
        .distinct()
        .singleOrNull()

/**
 * Every distinct pull request linked in [messages]' transcript, newest first.
 *
 * This is the same rule the chat's own badge uses, applied to a transcript that was never opened:
 * the drawer scans recent chats so a badge does not have to wait for the user to visit one.
 */
fun pullRequestRefsIn(messages: List<OpenCodeMessage>): List<PullRequestRef> {
    val refs = LinkedHashSet<PullRequestRef>()
    messages.forEach { message ->
        message.parts.forEach { part -> part.openedPullRequest()?.let(refs::add) }
    }
    return refs.toList().takeLast(MAX_TRACKED_PULL_REQUESTS).reversed()
}

private fun OpenCodePart.openedPullRequest(): PullRequestRef? =
    when (type) {
        "text" -> singlePullRequestIn(text.orEmpty())
        "tool" ->
            singlePullRequestInTool(
                output = state?.get("output")?.jsonPrimitiveOrNull(),
                error = state?.get("error")?.jsonPrimitiveOrNull(),
            )
        else -> null
    }

private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
