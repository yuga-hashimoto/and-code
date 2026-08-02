package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.core.api.parsePullRequestRefs
import com.yugahashimoto.andcode.core.api.pullRequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestLinksTest {
    @Test
    fun `reads a pull request link out of prose`() {
        val refs = parsePullRequestRefs("Opened https://github.com/yuga-hashimoto/and-code/pull/170 for review.")

        assertEquals(listOf(PullRequestRef("yuga-hashimoto", "and-code", 170)), refs)
    }

    @Test
    fun `reads a link written as markdown`() {
        val refs = parsePullRequestRefs("[#170](https://github.com/yuga-hashimoto/and-code/pull/170)")

        assertEquals(listOf(PullRequestRef("yuga-hashimoto", "and-code", 170)), refs)
    }

    @Test
    fun `reads a link that points at a tab of the pull request`() {
        val refs = parsePullRequestRefs("https://github.com/owner/repo.name/pull/8/files#diff-abc")

        assertEquals(listOf(PullRequestRef("owner", "repo.name", 8)), refs)
    }

    @Test
    fun `ignores the create-a-pull-request hint git push prints`() {
        val refs =
            parsePullRequestRefs(
                "remote: Create a pull request for 'feature' on GitHub by visiting:\n" +
                    "remote:      https://github.com/yuga-hashimoto/and-code/pull/new/feature",
            )

        assertTrue(refs.isEmpty())
    }

    @Test
    fun `ignores links to other parts of GitHub`() {
        val refs =
            parsePullRequestRefs(
                "https://github.com/yuga-hashimoto/and-code/issues/12 and https://github.com/yuga-hashimoto/and-code",
            )

        assertTrue(refs.isEmpty())
    }

    @Test
    fun `keeps one entry per pull request however often it is linked`() {
        val messages =
            listOf(
                assistantMessage("Opening https://github.com/o/r/pull/1"),
                toolMessage("https://github.com/o/r/pull/1 created"),
            )

        assertEquals(listOf(PullRequestRef("o", "r", 1)), pullRequestRefsIn(messages))
    }

    @Test
    fun `reads links out of tool output and errors as well as prose`() {
        val messages =
            listOf(
                assistantMessage("no link here"),
                toolMessage(output = "https://github.com/o/r/pull/2"),
                toolMessage(error = "conflict on https://github.com/o/r/pull/3"),
            )

        assertEquals(
            listOf(PullRequestRef("o", "r", 3), PullRequestRef("o", "r", 2)),
            pullRequestRefsIn(messages),
        )
    }

    @Test
    fun `ignores the pull requests a release notes listing links`() {
        val messages =
            listOf(
                toolMessage(output = "https://github.com/o/r/pull/182"),
                assistantMessage(
                    "## What's Changed\n" +
                        "* fix(ci) by @u in https://github.com/o/r/pull/178\n" +
                        "* Refresh README by @u in https://github.com/o/r/pull/177\n" +
                        "* feat(workspace) by @u in https://github.com/o/r/pull/180\n" +
                        "* feat: agent versions by @u in https://github.com/o/r/pull/181\n" +
                        "* chore: prepare release by @u in https://github.com/o/r/pull/182\n",
                ),
            )

        assertEquals(listOf(PullRequestRef("o", "r", 182)), pullRequestRefsIn(messages))
    }

    @Test
    fun `ignores a listing of open pull requests`() {
        val messages =
            listOf(
                toolMessage(
                    output =
                        "https://github.com/o/r/pull/12\thttps://github.com/o/r/pull/11\t" +
                            "https://github.com/o/r/pull/10",
                ),
            )

        assertTrue(pullRequestRefsIn(messages).isEmpty())
    }

    @Test
    fun `keeps only the newest few pull requests, newest first`() {
        val messages = (1..8).map { assistantMessage("https://github.com/o/r/pull/$it") }

        assertEquals(
            listOf(8, 7, 6, 5, 4).map { PullRequestRef("o", "r", it) },
            pullRequestRefsIn(messages),
        )
    }

    @Test
    fun `a merged pull request reads as merged even though GitHub also calls it closed`() {
        val state = pullRequestState(state = "closed", draft = false, merged = true, mergeableState = "unknown")

        assertEquals(PullRequestState.MERGED, state)
    }

    @Test
    fun `an unmerged closed pull request reads as closed`() {
        val state = pullRequestState(state = "closed", draft = false, merged = false, mergeableState = null)

        assertEquals(PullRequestState.CLOSED, state)
    }

    @Test
    fun `a conflicted pull request reads as conflicted, draft or not`() {
        assertEquals(
            PullRequestState.CONFLICT,
            pullRequestState(state = "open", draft = false, merged = false, mergeableState = "dirty"),
        )
        assertEquals(
            PullRequestState.CONFLICT,
            pullRequestState(state = "open", draft = true, merged = false, mergeableState = "dirty"),
        )
    }

    @Test
    fun `a draft pull request reads as draft`() {
        val state = pullRequestState(state = "open", draft = true, merged = false, mergeableState = "clean")

        assertEquals(PullRequestState.DRAFT, state)
    }

    @Test
    fun `anything else reads as open`() {
        val state = pullRequestState(state = "open", draft = false, merged = false, mergeableState = "blocked")

        assertEquals(PullRequestState.OPEN, state)
    }

    private fun assistantMessage(text: String): ChatMessage =
        ChatMessage(isUser = false, parts = listOf(ChatPart.Text(id = text, text = text)))

    private fun toolMessage(
        output: String? = null,
        error: String? = null,
    ): ChatMessage =
        ChatMessage(
            isUser = false,
            parts =
                listOf(
                    ChatPart.Tool(
                        id = "tool-${output.orEmpty()}${error.orEmpty()}",
                        name = "bash",
                        status = ToolStatus.COMPLETED,
                        output = output,
                        error = error,
                    ),
                ),
        )
}
