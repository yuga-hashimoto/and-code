package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.feature.chat.ModelAndRuntimePickerSheet
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
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
    runtimeTargets: List<RuntimeTarget> = emptyList(),
    workspaces: List<WorkspaceRef> = emptyList(),
    assistantProviderId: String? = null,
    assistantModelId: String? = null,
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
    onAssistantModelChange: (String, String) -> Unit = { _, _ -> },
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
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
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

            if (runtimeTargets.isNotEmpty()) {
                item {
                    SettingsGroup(title = stringResource(R.string.assistant_target_section)) {
                        AssistantTargetSection(
                            runtimeTargets = runtimeTargets,
                            workspaces = workspaces,
                            assistantRuntimeId = assistantRuntimeId,
                            assistantProviderId = assistantProviderId,
                            assistantModelId = assistantModelId,
                            assistantWorkspacePath = assistantWorkspacePath,
                            onRuntimeChange = onAssistantRuntimeChange,
                            onModelChange = onAssistantModelChange,
                            onWorkspaceChange = onAssistantWorkspaceChange,
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

/**
 * Agent, model and workspace the wake word / assistant session starts with.
 *
 * The agent used to be the only thing selectable here: the model came from whatever the chat was
 * using, which is wrong as soon as the assistant points at a different agent, and the workspace was
 * a free-text path nobody could be expected to type correctly. Both are pickers over what the
 * chosen agent actually offers now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantTargetSection(
    runtimeTargets: List<RuntimeTarget>,
    workspaces: List<WorkspaceRef>,
    assistantRuntimeId: String?,
    assistantProviderId: String?,
    assistantModelId: String?,
    assistantWorkspacePath: String,
    onRuntimeChange: (String) -> Unit,
    onModelChange: (String, String) -> Unit,
    onWorkspaceChange: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val selectableTargets = assistantTargets(runtimeTargets)
    val selectedTarget = selectableTargets.firstOrNull { it.id == assistantRuntimeId }

    /*
     * The assistant's catalog belongs to its selected agent, not necessarily to the runtime open in
     * the chat. Fetching it here also makes a model choice possible for Claude Code and Antigravity,
     * whose catalogues are not part of the chat runtime's state.
     */
    var targetWorkspaces by remember { mutableStateOf<List<WorkspaceRef>>(emptyList()) }
    var targetProviders by remember { mutableStateOf<List<OpenCodeProvider>>(emptyList()) }
    LaunchedEffect(selectedTarget?.id) {
        val target = selectedTarget
        targetWorkspaces = emptyList()
        targetProviders = emptyList()
        if (target != null) {
            targetWorkspaces = runCatching { target.listWorkspaces() }.getOrDefault(emptyList())
            targetProviders = loadAssistantProviders(target)
        }
    }
    val workspaceOptions = assistantWorkspaceOptions(targetWorkspaces, workspaces)
    val selectedModelName =
        targetProviders.firstOrNull { it.id == assistantProviderId }?.models?.get(assistantModelId)?.name

    SelectionRow(
        icon = runtimeAgentIcon(selectedTarget?.agent),
        label = stringResource(R.string.assistant_agent_label),
        value =
            selectedTarget?.agent?.let { stringResource(it.displayNameRes) }
                ?: stringResource(R.string.assistant_agent_auto),
        onClick = { showPicker = true },
    )

    SelectionRow(
        icon = runtimeAgentIcon(selectedTarget?.agent),
        label = stringResource(R.string.assistant_model_label),
        value = selectedModelName ?: stringResource(R.string.assistant_model_unset),
        onClick = { showPicker = true },
    )

    VoiceDivider()

    WorkspaceDropdown(
        selectedPath = assistantWorkspacePath,
        workspaces = workspaceOptions,
        onSelect = onWorkspaceChange,
    )

    if (showPicker) {
        ModelAndRuntimePickerSheet(
            sheetState = sheetState,
            runtimeTargets = selectableTargets,
            selectedRuntimeId = assistantRuntimeId,
            onSelectRuntime = onRuntimeChange,
            providers = targetProviders,
            selectedProviderId = assistantProviderId,
            selectedModelId = assistantModelId,
            showLocalSuffix = false,
            onSelectModel = { providerId, modelId ->
                onModelChange(providerId, modelId)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun SelectionRow(
    icon: Int,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceDropdown(
    selectedPath: String,
    workspaces: List<WorkspaceRef>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName =
        workspaces.firstOrNull { it.path == selectedPath }?.name
            ?: selectedPath.takeIf(String::isNotBlank)
            ?: stringResource(R.string.assistant_workspace_auto)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.assistant_workspace_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = selectedName, style = MaterialTheme.typography.bodyLarge)
                    selectedPath.takeIf(String::isNotBlank)?.let { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.assistant_workspace_label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.assistant_workspace_auto)) },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            if (workspaces.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_workspaces_title)) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(workspace.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                workspace.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(workspace.path)
                        expanded = false
                    },
                )
            }
        }
    }
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
