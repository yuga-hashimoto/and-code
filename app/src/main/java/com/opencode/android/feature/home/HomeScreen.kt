package com.opencode.android.feature.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.ui.components.OpenCodeBrand
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip
import com.opencode.android.ui.theme.OpenCodeSuccess

@Composable
fun HomeScreen(
    state: HomeUiState,
    onNewChat: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSession: (String, String) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeBrand(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
        }

        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector =
                            when (state.runtimeType) {
                                RuntimeType.LOCAL -> Icons.Default.Android
                                RuntimeType.REMOTE -> Icons.Default.Computer
                                null -> Icons.Default.Computer
                            },
                        contentDescription = stringResource(R.string.cd_runtime_type),
                        tint = if (state.connected) OpenCodeSuccess else MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.runtimeName.ifBlank { stringResource(R.string.select_a_runtime) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = runtimeDescription(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(
                        text =
                            if (state.connected) {
                                stringResource(
                                    R.string.connected_label,
                                )
                            } else {
                                stringResource(R.string.disconnected_label)
                            },
                        active = state.connected,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onNewChat,
                    modifier = Modifier.weight(1f),
                    enabled = state.runtimeId != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.cd_new_chat))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.new_chat))
                }
                FilledTonalButton(
                    onClick = onOpenWorkspaces,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.cd_workspaces))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.nav_workspaces))
                }
            }
        }

        item {
            Text(
                stringResource(R.string.current_configuration),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                ConfigurationRow(
                    stringResource(R.string.workspace_folder_label),
                    state.workspace?.path ?: stringResource(R.string.not_selected),
                )
                Spacer(Modifier.height(12.dp))
                ConfigurationRow(stringResource(R.string.model), state.modelId ?: stringResource(R.string.opencode_default_value))
                Spacer(Modifier.height(12.dp))
                ConfigurationRow(stringResource(R.string.agent), state.agentId ?: "build")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = stringResource(R.string.cd_configuration),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.padding(horizontal = 5.dp))
                    Text(
                        text = state.providerId ?: stringResource(R.string.no_ai_service_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.error?.let { error ->
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.connection_or_fetch_failed),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recent_sessions),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                androidx.compose.material3.TextButton(onClick = onOpenActivity) {
                    Text(stringResource(R.string.view_all))
                }
            }
        }

        if (state.sessions.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.no_sessions), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.sessions.take(4), key = { it.id }) { session ->
                SectionCard(
                    modifier = Modifier.clickable { onOpenSession(session.id, session.title) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = stringResource(R.string.cd_session),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.title.ifBlank { session.slug ?: session.id }, fontWeight = FontWeight.Medium)
                            Text(
                                session.directory.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun runtimeDescription(state: HomeUiState): String =
    when {
        state.connected -> stringResource(R.string.capability_version, state.version.orEmpty())
        state.isRefreshing -> stringResource(R.string.checking_connection_status)
        state.runtimeState is RuntimeState.Unavailable -> state.runtimeState.reason
        state.runtimeState is RuntimeState.Failed -> state.runtimeState.message
        state.runtimeId == null -> stringResource(R.string.choose_local_or_pc_runtime)
        else -> stringResource(R.string.not_connected_to_opencode)
    }
