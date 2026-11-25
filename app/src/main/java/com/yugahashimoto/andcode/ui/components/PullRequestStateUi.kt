package com.yugahashimoto.andcode.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors

/**
 * GitHub's merged purple. The palette has no equivalent, and merged is the one state a git user
 * reads by colour alone, so it is worth matching the colour they already know.
 */
private val MergedPurple = Color(0xFF8957E5)

/** The tint that makes a pull request's state readable at a glance, shared by the chip and the drawer. */
@Composable
fun PullRequestState?.color(): Color {
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

/** The icon that stands for a pull request's state; null reads as an open one. */
fun PullRequestState?.icon(): ImageVector =
    when (this) {
        PullRequestState.MERGED -> Icons.AutoMirrored.Filled.CallMerge
        PullRequestState.CLOSED -> Icons.Default.Cancel
        PullRequestState.CONFLICT -> Icons.Default.Warning
        PullRequestState.DRAFT -> Icons.Default.Edit
        PullRequestState.OPEN, null -> Icons.AutoMirrored.Filled.CallSplit
    }

/** Human-readable name of the state, for content descriptions and chips. */
fun PullRequestState.labelRes(): Int =
    when (this) {
        PullRequestState.OPEN -> R.string.pr_state_open
        PullRequestState.DRAFT -> R.string.pr_state_draft
        PullRequestState.MERGED -> R.string.pr_state_merged
        PullRequestState.CLOSED -> R.string.pr_state_closed
        PullRequestState.CONFLICT -> R.string.pr_state_conflict
    }
