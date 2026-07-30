package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme

/** Voice settings with explicit wake-word capability status. */
@Composable
fun VoiceSettingsScreen(
    ttsEnabled: Boolean,
    ttsProvider: String = "android",
    ttsAndroidEngine: String? = null,
    androidTtsEngines: List<Pair<String, String>> = emptyList(),
    ttsOpenAiApiKey: String = "",
    ttsOpenAiVoice: String = "alloy",
    ttsOpenAiModel: String = "gpt-4o-mini-tts",
    ttsElevenLabsApiKey: String = "",
    ttsElevenLabsVoiceId: String = "",
    ttsElevenLabsModel: String = "eleven_multilingual_v2",
    continuousConversation: Boolean,
    wakeWordEnabled: Boolean,
    wakeWordModel: String = "hey_mycroft",
    availableWakeWordModels: List<String> = emptyList(),
    assistantRuntimeId: String? = null,
    availableRuntimes: List<Pair<String, String>> = emptyList(),
    assistantWorkspacePath: String = "",
    onTtsChange: (Boolean) -> Unit,
    onTtsProviderChange: (String) -> Unit = {},
    onTtsAndroidEngineChange: (String?) -> Unit = {},
    onTtsOpenAiApiKeyChange: (String) -> Unit = {},
    onTtsOpenAiVoiceChange: (String) -> Unit = {},
    onTtsOpenAiModelChange: (String) -> Unit = {},
    onTtsElevenLabsApiKeyChange: (String) -> Unit = {},
    onTtsElevenLabsVoiceIdChange: (String) -> Unit = {},
    onTtsElevenLabsModelChange: (String) -> Unit = {},
    onContinuousChange: (Boolean) -> Unit,
    onWakeWordChange: (Boolean) -> Unit,
    onWakeWordModelChange: (String) -> Unit = {},
    onAssistantRuntimeChange: (String) -> Unit = {},
    onAssistantWorkspaceChange: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    stringResource(R.string.voice_settings_row),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
            },
            colors =
                TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.voice_settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SettingsGroup(title = stringResource(R.string.voice_conversation_section)) {
                    VoiceToggleRow(
                        icon = Icons.Default.RecordVoiceOver,
                        title = stringResource(R.string.voice_response),
                        description = stringResource(R.string.voice_response_description),
                        checked = ttsEnabled,
                        onCheckedChange = onTtsChange,
                    )
                    if (ttsEnabled) {
                        VoiceDivider()
                        ChoiceDropdown(
                            label = stringResource(R.string.tts_provider_label),
                            selected = ttsProvider,
                            options =
                                listOf(
                                    "android" to stringResource(R.string.tts_provider_android),
                                    "openai" to "OpenAI",
                                    "elevenlabs" to "ElevenLabs",
                                ),
                            onSelect = onTtsProviderChange,
                        )
                        when (ttsProvider) {
                            "android" -> {
                                VoiceDivider()
                                ChoiceDropdown(
                                    label = stringResource(R.string.tts_engine_label),
                                    selected = ttsAndroidEngine.orEmpty(),
                                    options = listOf("" to stringResource(R.string.tts_engine_system_default)) + androidTtsEngines,
                                    onSelect = { onTtsAndroidEngineChange(it.takeIf(String::isNotBlank)) },
                                )
                            }
                            "openai" -> {
                                VoiceDivider()
                                SecretTextField(
                                    label = stringResource(R.string.tts_api_key_label),
                                    value = ttsOpenAiApiKey,
                                    onValueChange = onTtsOpenAiApiKeyChange,
                                )
                                VoiceTextField(
                                    label = stringResource(R.string.tts_voice_label),
                                    value = ttsOpenAiVoice,
                                    onValueChange = onTtsOpenAiVoiceChange,
                                )
                                VoiceTextField(
                                    label = stringResource(R.string.tts_model_label),
                                    value = ttsOpenAiModel,
                                    onValueChange = onTtsOpenAiModelChange,
                                )
                            }
                            "elevenlabs" -> {
                                VoiceDivider()
                                SecretTextField(
                                    label = stringResource(R.string.tts_api_key_label),
                                    value = ttsElevenLabsApiKey,
                                    onValueChange = onTtsElevenLabsApiKeyChange,
                                )
                                VoiceTextField(
                                    label = stringResource(R.string.tts_voice_id_label),
                                    value = ttsElevenLabsVoiceId,
                                    onValueChange = onTtsElevenLabsVoiceIdChange,
                                )
                                VoiceTextField(
                                    label = stringResource(R.string.tts_model_label),
                                    value = ttsElevenLabsModel,
                                    onValueChange = onTtsElevenLabsModelChange,
                                )
                            }
                        }
                    }
                    VoiceDivider()
                    VoiceToggleRow(
                        icon = Icons.Default.Mic,
                        title = stringResource(R.string.continuous_conversation),
                        description = stringResource(R.string.auto_start_mic),
                        checked = continuousConversation,
                        onCheckedChange = onContinuousChange,
                    )
                }
            }

            item {
                SettingsGroup(title = stringResource(R.string.wake_word_section_title)) {
                    VoiceToggleRow(
                        icon = Icons.Default.VoiceChat,
                        title = stringResource(R.string.settings_wake_word_row),
                        description = stringResource(R.string.wake_word_description),
                        checked = wakeWordEnabled,
                        onCheckedChange = onWakeWordChange,
                    )
                    if (wakeWordEnabled && availableWakeWordModels.isNotEmpty()) {
                        VoiceDivider()
                        WakeWordModelDropdown(
                            selected = wakeWordModel,
                            models = availableWakeWordModels,
                            onSelect = onWakeWordModelChange,
                        )
                    }
                }
            }

            if (availableRuntimes.isNotEmpty()) {
                item {
                    SettingsGroup(title = stringResource(R.string.assistant_target_section)) {
                        AgentDropdown(
                            selectedRuntimeId = assistantRuntimeId,
                            runtimes = availableRuntimes,
                            onSelect = onAssistantRuntimeChange,
                        )
                        VoiceDivider()
                        WorkspaceTextField(
                            value = assistantWorkspacePath,
                            onValueChange = onAssistantWorkspaceChange,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun VoiceTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        singleLine = true,
    )
}

@Composable
private fun SecretTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentDropdown(
    selectedRuntimeId: String?,
    runtimes: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = runtimes.firstOrNull { it.first == selectedRuntimeId }?.second ?: "Auto (current agent)"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.assistant_agent_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.assistant_agent_auto)) },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            runtimes.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTextField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.assistant_workspace_label)) },
        placeholder = { Text("/workspace") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeWordModelDropdown(
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selected.replace('_', ' ').replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Wake word model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column { content() }
    }
}

@Composable
private fun VoiceToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.cd_voice_setting),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun VoiceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 36.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
    )
}

@Preview(showBackground = true)
@Composable
private fun VoiceSettingsScreenPreview() {
    AndCodeTheme {
        VoiceSettingsScreen(
            ttsEnabled = true,
            continuousConversation = false,
            wakeWordEnabled = false,
            onTtsChange = {},
            onContinuousChange = {},
            onWakeWordChange = {},
            onBack = {},
        )
    }
}
