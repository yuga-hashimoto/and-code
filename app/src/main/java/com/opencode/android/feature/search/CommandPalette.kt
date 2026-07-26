package com.opencode.android.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenSession: (String, String) -> Unit,
    sessions: List<Pair<String, String>>,
) {
    var query by remember { mutableStateOf("") }

    val actions =
        listOf(
            PaletteAction(stringResource(R.string.palette_new_chat), Icons.Default.Add, "new_chat"),
            PaletteAction(stringResource(R.string.palette_settings), Icons.Default.Settings, "settings"),
            PaletteAction(stringResource(R.string.palette_workspaces), Icons.Default.Folder, "workspaces"),
            PaletteAction(stringResource(R.string.palette_activity), Icons.Default.History, "activity"),
        )

    val filteredActions =
        actions.filter {
            query.isBlank() || it.title.contains(query, ignoreCase = true)
        }
    val filteredSessions =
        sessions.filter { (id, title) ->
            query.isBlank() || title.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_label)) },
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (filteredActions.isNotEmpty()) {
                item { PaletteSectionHeader(stringResource(R.string.palette_section_actions)) }
                items(filteredActions, key = { "action-${it.route}" }) { action ->
                    PaletteRow(
                        icon = action.icon,
                        title = action.title,
                        onClick = {
                            onNavigate(action.route)
                            onDismiss()
                        },
                    )
                }
            }
            if (filteredSessions.isNotEmpty()) {
                item { PaletteSectionHeader(stringResource(R.string.palette_section_sessions)) }
                items(filteredSessions, key = { "session-${it.first}" }) { (id, title) ->
                    PaletteRow(
                        icon = Icons.Default.Chat,
                        title = title,
                        onClick = {
                            onOpenSession(id, title)
                            onDismiss()
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class PaletteAction(
    val title: String,
    val icon: ImageVector,
    val route: String,
)

@Composable
private fun PaletteSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaletteRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.cd_command),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
