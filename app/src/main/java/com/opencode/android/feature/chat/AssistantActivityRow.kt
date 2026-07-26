package com.opencode.android.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.R

/**
 * One collapsed line standing in for a whole run of reasoning/tool calls.
 *
 * While a step is still in flight the row names that step so the user can see what the agent is
 * doing; once the run settles it collapses to per-category counts.
 */
@Composable
fun AssistantActivityRow(
    parts: List<ChatPart>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (parts.isEmpty()) return
    val summary = summarizeActivity(parts)
    if (summary.isEmpty) return

    val running = summary.running
    val accent =
        when {
            summary.hasError -> MaterialTheme.colorScheme.error
            running != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running != null) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
            } else {
                Icon(
                    if (summary.hasError) Icons.Default.ErrorOutline else Icons.Default.AutoAwesome,
                    contentDescription = stringResource(R.string.cd_subagent_status),
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (running != null) {
                Text(
                    text = running.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = running.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ToolStatusChip(running.status)
            } else {
                Text(
                    text = activitySummaryText(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.activity_details_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Full step-by-step breakdown of one activity run. Hosted at screen level rather than inside the
 * message list item, so scrolling the message off screen cannot dismiss an open sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantActivitySheet(
    parts: List<ChatPart>,
    onDismiss: () -> Unit,
) {
    val summary = summarizeActivity(parts)
    val title = if (summary.isEmpty) stringResource(R.string.activity_details_title) else activitySummaryText(summary)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.activity_details_close))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(parts, key = { it.id }) { part ->
                when (part) {
                    is ChatPart.Reasoning -> ReasoningCard(part)
                    is ChatPart.Tool -> ToolCard(part)
                    is ChatPart.Patch -> PatchCard(part)
                    is ChatPart.Text -> Unit
                }
            }
            item { Column(modifier = Modifier.padding(bottom = 32.dp)) {} }
        }
    }
}

@Composable
private fun activitySummaryText(summary: ActivitySummary): String {
    val phrases = mutableListOf<String>()
    ToolCategory.entries.forEach { category ->
        val count = summary.counts[category] ?: return@forEach
        phrases += stringResource(category.summaryStringRes(), count)
    }
    if (phrases.isEmpty() && summary.reasoningCount > 0) {
        phrases += stringResource(R.string.activity_summary_reasoning, summary.reasoningCount)
    }
    return phrases.joinToString(stringResource(R.string.activity_summary_separator))
}

private fun ToolCategory.summaryStringRes(): Int =
    when (this) {
        ToolCategory.COMMAND -> R.string.activity_summary_commands
        ToolCategory.READ -> R.string.activity_summary_reads
        ToolCategory.EDIT -> R.string.activity_summary_edits
        ToolCategory.SUBAGENT -> R.string.activity_summary_subagents
        ToolCategory.OTHER -> R.string.activity_summary_tools
    }

internal fun toolCategoryIcon(category: ToolCategory): ImageVector =
    when (category) {
        ToolCategory.COMMAND -> Icons.Default.Terminal
        ToolCategory.READ -> Icons.Default.Visibility
        ToolCategory.EDIT -> Icons.Default.Description
        ToolCategory.SUBAGENT -> Icons.Default.Hub
        ToolCategory.OTHER -> Icons.Default.Build
    }
