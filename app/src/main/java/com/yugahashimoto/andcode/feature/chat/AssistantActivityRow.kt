package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AndCodeSuccess

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
                    is ChatPart.Image -> Unit
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

@Composable
fun ReasoningCard(
    part: ChatPart.Reasoning,
    autoExpand: Boolean = false,
) {
    var expanded by remember { mutableStateOf(autoExpand) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = stringResource(R.string.cd_reasoning),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = stringResource(R.string.reasoning_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && part.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
fun ToolCard(part: ChatPart.Tool) {
    var expanded by remember { mutableStateOf(part.status == ToolStatus.RUNNING) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    toolCategoryIcon(part.name.toToolCategory()),
                    contentDescription = stringResource(R.string.cd_tool),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = part.name,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                part.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                ToolStatusChip(part.status)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_collapse),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                part.input?.let { input ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tool_input_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = input,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                part.output?.let { output ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.tool_output_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = output,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (part.outputTruncated) {
                        Text(
                            text = stringResource(R.string.tool_output_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                part.error?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ToolStatusChip(status: ToolStatus) {
    val (label, color) =
        when (status) {
            ToolStatus.PENDING -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
            ToolStatus.RUNNING -> stringResource(R.string.tool_status_running) to MaterialTheme.colorScheme.primary
            ToolStatus.COMPLETED -> stringResource(R.string.tool_status_completed) to AndCodeSuccess
            ToolStatus.ERROR -> stringResource(R.string.tool_status_error) to MaterialTheme.colorScheme.error
            ToolStatus.UNKNOWN -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun PatchCard(part: ChatPart.Patch) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = stringResource(R.string.cd_file_changes),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    stringResource(R.string.file_changes_title),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (part.files.isEmpty()) {
                Text(
                    text = stringResource(R.string.file_changes_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                part.files.forEach { file ->
                    Text(file, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun TodoTimelineCard(todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.cd_task_status),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = stringResource(R.string.todo_timeline_progress, completedCount, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                todos.forEach { todo ->
                    val isCompleted = todo.status == "completed"
                    val isInProgress = todo.status == "in_progress"
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector =
                                when {
                                    isCompleted -> Icons.Default.CheckCircle
                                    isInProgress -> Icons.Default.PendingActions
                                    else -> Icons.Default.RadioButtonUnchecked
                                },
                            contentDescription = stringResource(R.string.cd_task_status),
                            tint =
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.secondary
                                    isInProgress -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isCompleted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        )
                    }
                }
            }
        }
    }
}
