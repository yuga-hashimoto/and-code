package com.opencode.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.ui.components.LabelValueRow
import com.opencode.android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenAssistantSettings: () -> Unit,
    onTtsChange: (Boolean) -> Unit,
    onContinuousChange: (Boolean) -> Unit,
    onDraftProviderId: (String) -> Unit = {},
    onDraftApiKey: (String) -> Unit = {},
    onSaveApiKey: () -> Unit = {},
    onClearApiKey: (String) -> Unit = {},
    onAssistantRuntime: (String?) -> Unit = {},
    onAssistantWorkspace: (String?) -> Unit = {},
    onImportWorkspace: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.openCodeVersion?.let { version ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.capability_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.home_assistant),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = stringResource(R.string.cd_assistant_settings),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_default_assistant), fontWeight = FontWeight.Medium)
                        Text(
                            text = stringResource(R.string.notifications_permission_rationale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenAssistantSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.set_default_assistant))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.request_notifications))
                }
                Spacer(Modifier.height(12.dp))
                var runtimeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = runtimeExpanded,
                    onExpandedChange = { runtimeExpanded = it },
                ) {
                    OutlinedTextField(
                        value =
                            state.runtimeOptions.firstOrNull { it.first == state.assistantRuntimeId }?.second
                                ?: state.assistantRuntimeId.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.assistant_runtime)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = runtimeExpanded) },
                        modifier =
                            Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = runtimeExpanded,
                        onDismissRequest = { runtimeExpanded = false },
                    ) {
                        state.runtimeOptions.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onAssistantRuntime(id)
                                    runtimeExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.assistantWorkspacePath.orEmpty(),
                    onValueChange = onAssistantWorkspace,
                    label = { Text(stringResource(R.string.assistant_workspace)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.voice),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                SettingSwitchRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.voice_response),
                    description = stringResource(R.string.auto_start_mic),
                    checked = state.ttsEnabled,
                    onCheckedChange = onTtsChange,
                )
                Spacer(Modifier.height(16.dp))
                SettingSwitchRow(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.continuous_conversation),
                    description = stringResource(R.string.auto_start_mic),
                    checked = state.continuousConversation,
                    onCheckedChange = onContinuousChange,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.provider_credentials),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.provider_credentials_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                state.credentialStatuses.forEach { (providerId, saved) ->
                    LabelValueRow(
                        label = providerId,
                        value =
                            if (saved) {
                                stringResource(R.string.key_saved)
                            } else {
                                stringResource(R.string.key_missing)
                            },
                    )
                    if (saved) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { onClearApiKey(providerId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.clear_api_key))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = state.draftProviderId,
                    onValueChange = onDraftProviderId,
                    label = { Text(stringResource(R.string.provider_id_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = stringResource(R.string.cd_provider_key)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.draftApiKey,
                    onValueChange = onDraftApiKey,
                    label = { Text(stringResource(R.string.api_key_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSaveApiKey, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.save_api_key))
                }
                state.credentialMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.local_runtime),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = stringResource(R.string.cd_local_runtime),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.experimental_not_installed), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.local_runtime_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImportWorkspace, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_workspace_folder))
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.model),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                LabelValueRow(
                    label = stringResource(R.string.model),
                    value = state.modelId ?: stringResource(R.string.not_set),
                )
                Spacer(Modifier.height(12.dp))
                LabelValueRow(
                    label = stringResource(R.string.agent),
                    value = state.agentId ?: "build",
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.about),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.cd_about),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text("OpenCode Android 0.1.0", fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.unofficial_client),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "MIT License",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = stringResource(R.string.cd_setting), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
