package com.opencode.android.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeFileChange
import com.opencode.android.core.api.OpenCodeTodo
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip

@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onContinueChat: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.session.title.ifBlank { state.session.slug ?: state.session.id },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.session.directory.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.session_info_title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SessionInfoRow(stringResource(R.string.session_info_id), state.session.id)
                state.session.version?.let { SessionInfoRow(stringResource(R.string.session_info_opencode), it) }
                state.session.projectId?.let { SessionInfoRow(stringResource(R.string.session_info_project), it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onContinueChat, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.cd_continue_chat))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.continue_this_session_chat))
                }
            }
        }

        if (state.isLoading) {
            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.session_info_loading))
                    }
                }
            }
        }

        item { SectionHeading(stringResource(R.string.session_todo_title), state.todos.size) }

        if (!state.isLoading && state.todos.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.session_no_todos), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.todos, key = { "${it.status}-${it.priority}-${it.content}" }) { todo ->
                TodoCard(todo)
            }
        }

        item { SectionHeading(stringResource(R.string.session_diff_title), state.diff.size) }

        if (!state.isLoading && state.diff.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.session_no_file_changes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.diff, key = { it.displayPath.ifBlank { it.patch.orEmpty() } }) { change ->
                SessionChangeCard(change)
            }
        }

        state.error?.let { error ->
            item {
                SectionCard {
                    Text(stringResource(R.string.fetch_failed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun TodoCard(todo: OpenCodeTodo) {
    val completed = todo.status == "completed"
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector =
                    when (todo.status) {
                        "completed" -> Icons.Default.CheckCircle
                        "in_progress" -> Icons.Default.PendingActions
                        else -> Icons.Default.TaskAlt
                    },
                contentDescription = stringResource(R.string.cd_task_status),
                tint =
                    when (todo.status) {
                        "completed" -> MaterialTheme.colorScheme.secondary
                        "in_progress" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(todo.content, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(todoStatusLabel(todo.status), active = completed || todo.status == "in_progress")
                    StatusChip(todoPriorityLabel(todo.priority), active = todo.priority == "high")
                }
            }
        }
    }
}

@Composable
private fun SessionChangeCard(change: OpenCodeFileChange) {
    var expanded by remember(change.displayPath, change.patch) { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = stringResource(R.string.cd_file_change),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(change.displayPath.ifBlank { stringResource(R.string.changed_file_default) }, fontWeight = FontWeight.SemiBold)
                Text(
                    "+${change.additions.toInt()}  -${change.deletions.toInt()}  ${change.status.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!change.patch.isNullOrBlank()) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.collapse) else stringResource(R.string.diff_label))
                }
            }
        }
        if (expanded && !change.patch.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    change.patch,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    count: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.item_count, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionInfoRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun todoStatusLabel(status: String): String =
    when (status) {
        "in_progress" -> stringResource(R.string.todo_status_in_progress)
        "completed" -> stringResource(R.string.tool_status_completed)
        "cancelled" -> stringResource(R.string.todo_status_cancelled)
        else -> stringResource(R.string.todo_status_pending)
    }

@Composable
private fun todoPriorityLabel(priority: String): String =
    when (priority) {
        "high" -> stringResource(R.string.priority_high)
        "medium" -> stringResource(R.string.priority_medium)
        else -> stringResource(R.string.priority_low)
    }
