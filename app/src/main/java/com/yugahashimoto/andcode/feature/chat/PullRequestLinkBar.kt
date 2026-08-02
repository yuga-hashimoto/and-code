package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.core.api.PullRequestStatus
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors

/** Pull requests shown before the rest are folded behind the expand chip. */
private const val COLLAPSED_PULL_REQUEST_COUNT = 2

/**
 * GitHub's merged purple. The palette has no equivalent, and merged is the one state a git user
 * reads by colour alone, so it is worth matching the colour they already know.
 */
private val MergedPurple = Color(0xFF8957E5)

/**
 * The pull requests this chat produced, pinned above the composer.
 *
 * Each one shows its diff size and its state - draft, open, conflicted, merged, or closed - so the
 * answer to "where did that pull request end up" needs no scrolling back through the transcript.
 * The diff opens the changed files on GitHub; the state opens the pull request itself.
 *
 * One chat often opens several pull requests, but the composer is the thing the screen is for: only
 * the two newest stay visible, and the rest sit behind a chip that expands them on demand.
 */
@Composable
fun PullRequestLinkBar(
    pullRequests: List<ChatPullRequest>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pullRequests.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val hidden = (pullRequests.size - COLLAPSED_PULL_REQUEST_COUNT).coerceAtLeast(0)
    val visible = if (expanded) pullRequests else pullRequests.take(COLLAPSED_PULL_REQUEST_COUNT)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEachIndexed { index, pullRequest ->
            key(pullRequest.ref.key) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiffChip(pullRequest, onOpenUrl)
                    StateChip(pullRequest, onOpenUrl)
                    // The toggle trails the last visible row so folded pull requests stay one tap
                    // away without costing a row of their own.
                    if (hidden > 0 && index == visible.lastIndex) {
                        ExpandChip(
                            hidden = hidden,
                            expanded = expanded,
                            onClick = { expanded = !expanded },
                        )
                    }
                }
            }
        }
    }
}

/** Hidden until GitHub reports the counts, because an empty diff badge says nothing useful. */
@Composable
private fun DiffChip(
    pullRequest: ChatPullRequest,
    onOpenUrl: (String) -> Unit,
) {
    val status = pullRequest.status ?: return
    val tc = LocalThemeColors.current

    PullRequestChip(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { onOpenUrl(pullRequest.ref.filesUrl) },
        contentDescription =
            stringResource(
                R.string.cd_pull_request_files,
                pullRequest.ref.number,
                status.additions,
                status.deletions,
            ),
    ) {
        Text(
            text = stringResource(R.string.pr_diff_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "+${status.additions}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tc.success,
        )
        Text(
            text = "-${status.deletions}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tc.destructive,
        )
    }
}

/** Reads as a plain `#123` until GitHub answers, so a link never waits on the network to appear. */
@Composable
private fun StateChip(
    pullRequest: ChatPullRequest,
    onOpenUrl: (String) -> Unit,
) {
    val state = pullRequest.status?.state
    val number = pullRequest.ref.number
    val label = state?.let { stringResource(it.labelRes()) }

    PullRequestChip(
        color = state.color(),
        onClick = { onOpenUrl(pullRequest.ref.url) },
        contentDescription =
            label?.let { stringResource(R.string.cd_pull_request_link_state, number, it) }
                ?: stringResource(R.string.cd_pull_request_link, number),
    ) {
        Icon(
            imageVector = state.icon(),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "#$number",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ExpandChip(
    hidden: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    PullRequestChip(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
        contentDescription =
            if (expanded) {
                stringResource(R.string.cd_pull_request_collapse)
            } else {
                stringResource(R.string.cd_pull_request_expand, hidden)
            },
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (expanded) stringResource(R.string.pr_collapse) else "+$hidden",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PullRequestChip(
    color: Color,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(100.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun PullRequestState?.color(): Color {
    val tc = LocalThemeColors.current
    return when (this) {
        PullRequestState.OPEN -> tc.success
        PullRequestState.DRAFT -> tc.foregroundMuted
        PullRequestState.MERGED -> MergedPurple
        PullRequestState.CLOSED -> tc.destructive
        PullRequestState.CONFLICT -> tc.warning
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun PullRequestState?.icon(): ImageVector =
    when (this) {
        PullRequestState.MERGED -> Icons.AutoMirrored.Filled.CallMerge
        PullRequestState.CLOSED -> Icons.Default.Cancel
        PullRequestState.CONFLICT -> Icons.Default.Warning
        PullRequestState.DRAFT -> Icons.Default.Edit
        PullRequestState.OPEN, null -> Icons.AutoMirrored.Filled.CallSplit
    }

private fun PullRequestState.labelRes(): Int =
    when (this) {
        PullRequestState.OPEN -> R.string.pr_state_open
        PullRequestState.DRAFT -> R.string.pr_state_draft
        PullRequestState.MERGED -> R.string.pr_state_merged
        PullRequestState.CLOSED -> R.string.pr_state_closed
        PullRequestState.CONFLICT -> R.string.pr_state_conflict
    }

@Preview
@Composable
private fun PullRequestLinkBarPreview() {
    AndCodeTheme {
        PullRequestLinkBar(
            pullRequests =
                listOf(
                    previewPullRequest(172, PullRequestState.CONFLICT, additions = 88, deletions = 12),
                    previewPullRequest(171, PullRequestState.DRAFT, additions = 41, deletions = 3),
                    previewPullRequest(170, PullRequestState.MERGED, additions = 604, deletions = 92),
                ),
            onOpenUrl = {},
        )
    }
}

private fun previewPullRequest(
    number: Int,
    state: PullRequestState,
    additions: Int,
    deletions: Int,
): ChatPullRequest {
    val ref = PullRequestRef("yuga-hashimoto", "and-code", number)
    return ChatPullRequest(
        ref = ref,
        status =
            PullRequestStatus(
                ref = ref,
                state = state,
                additions = additions,
                deletions = deletions,
            ),
    )
}
