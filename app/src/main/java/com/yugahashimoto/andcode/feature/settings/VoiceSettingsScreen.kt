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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.yugahashimoto.andcode.feature.assistant.TtsPreviewState
import com.yugahashimoto.andcode.feature.assistant.TtsTuning
import com.yugahashimoto.andcode.feature.chat.ModelAndRuntimePickerSheet
import com.yugahashimoto.andcode.feature.wakeword.VoskModelCatalog
import com.yugahashimoto.andcode.feature.wakeword.VoskModelLanguage
import com.yugahashimoto.andcode.feature.wakeword.VoskModelState
import com.yugahashimoto.andcode.feature.wakeword.WakeWordGrammar
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.delay
import java.util.Locale

/** Voice settings with explicit wake-word capability status. */
@Composable
fun VoiceSettingsScreen(
    ttsEnabled: Boolean,
    ttsProvider: String = "android",
    ttsAndroidEngine: String? = null,
    androidTtsEngines: List<Pair<String, String>> = emptyList(),
    ttsSpeechRate: Float = TtsTuning.DEFAULT_RATE,
    ttsPitch: Float = TtsTuning.DEFAULT_PITCH,
    ttsPreviewState: TtsPreviewState = TtsPreviewState.IDLE,
    ttsOpenAiApiKey: String = "",
    ttsOpenAiVoice: String = "alloy",
    ttsOpenAiModel: String = "gpt-4o-mini-tts",
    ttsElevenLabsApiKey: String = "",
    ttsElevenLabsVoiceId: String = "",
    ttsElevenLabsModel: String = "eleven_multilingual_v2",
    ttsBargeInEnabled: Boolean = true,
    continuousConversation: Boolean,
    wakeWordEnabled: Boolean,
    wakeWordPhrase: String = WakeWordGrammar.DEFAULT_PHRASE,
    wakeWordSensitivity: Float = 0.7f,
    wakeWordModelLanguage: VoskModelLanguage = VoskModelLanguage.ENGLISH,
    wakeWordModelStates: Map<VoskModelLanguage, VoskModelState> = emptyMap(),
    assistantRuntimeId: String? = null,
    runtimeTargets: List<RuntimeTarget> = emptyList(),
    workspaces: List<WorkspaceRef> = emptyList(),
    assistantProviderId: String? = null,
    assistantModelId: String? = null,
    assistantWorkspacePath: String = "",
    onTtsChange: (Boolean) -> Unit,
    onTtsProviderChange: (String) -> Unit = {},
    onTtsAndroidEngineChange: (String?) -> Unit = {},
    onTtsSpeechRateChange: (Float) -> Unit = {},
    onTtsPitchChange: (Float) -> Unit = {},
    onTtsPreview: () -> Unit = {},
    onTtsOpenAiApiKeyChange: (String) -> Unit = {},
    onTtsOpenAiVoiceChange: (String) -> Unit = {},
    onTtsOpenAiModelChange: (String) -> Unit = {},
    onTtsElevenLabsApiKeyChange: (String) -> Unit = {},
    onTtsElevenLabsVoiceIdChange: (String) -> Unit = {},
    onTtsElevenLabsModelChange: (String) -> Unit = {},
    onTtsBargeInChange: (Boolean) -> Unit = {},
    onContinuousChange: (Boolean) -> Unit,
    onWakeWordChange: (Boolean) -> Unit,
    onWakeWordApply: (String, Float) -> Unit = { _, _ -> },
    unknownWakeWordWords: suspend (VoskModelLanguage, String) -> List<String> = { _, _ -> emptyList() },
    onWakeWordModelLanguageChange: (VoskModelLanguage) -> Unit = {},
    onWakeWordModelDownload: () -> Unit = {},
    onWakeWordModelCancel: () -> Unit = {},
    onWakeWordModelRemove: () -> Unit = {},
    onAssistantRuntimeChange: (String) -> Unit = {},
    onAssistantModelChange: (String, String) -> Unit = { _, _ -> },
    onAssistantWorkspaceChange: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    // The wake word being edited, held here rather than beside the fields: those live in a
    // LazyColumn item, and scrolling the section off screen would throw a half-typed phrase away.
    var draftPhrase by remember(wakeWordPhrase) { mutableStateOf(wakeWordPhrase) }
    var draftSensitivity by remember(wakeWordSensitivity) { mutableStateOf(wakeWordSensitivity) }
    var unknownWords by remember { mutableStateOf(emptyList<String>()) }
    var unknownAppliedWords by remember { mutableStateOf(emptyList<String>()) }
    val modelState = wakeWordModelStates[wakeWordModelLanguage] ?: VoskModelState.Missing
    LaunchedEffect(draftPhrase, wakeWordModelLanguage, modelState) {
        // Settled input only: the dictionary is megabytes, and half-typed words are all unknown.
        delay(PHRASE_CHECK_DELAY_MS)
        unknownWords = unknownWakeWordWords(wakeWordModelLanguage, draftPhrase)
    }
    // The applied phrase is checked apart from the draft because it is the one the service would
    // be handed. Editing an unusable phrase does not make it usable until the edit is applied, and
    // switching detection on before then would only start a service that stops itself again.
    LaunchedEffect(wakeWordPhrase, wakeWordModelLanguage, modelState) {
        unknownAppliedWords = unknownWakeWordWords(wakeWordModelLanguage, wakeWordPhrase)
    }
    val appliedIsUsable = unknownAppliedWords.isEmpty()

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
                        TtsProviderSection(
                            ttsProvider = ttsProvider,
                            ttsAndroidEngine = ttsAndroidEngine,
                            androidTtsEngines = androidTtsEngines,
                            ttsSpeechRate = ttsSpeechRate,
                            ttsPitch = ttsPitch,
                            ttsOpenAiApiKey = ttsOpenAiApiKey,
                            ttsOpenAiVoice = ttsOpenAiVoice,
                            ttsOpenAiModel = ttsOpenAiModel,
                            ttsElevenLabsApiKey = ttsElevenLabsApiKey,
                            ttsElevenLabsVoiceId = ttsElevenLabsVoiceId,
                            ttsElevenLabsModel = ttsElevenLabsModel,
                            onTtsAndroidEngineChange = onTtsAndroidEngineChange,
                            onTtsSpeechRateChange = onTtsSpeechRateChange,
                            onTtsPitchChange = onTtsPitchChange,
                            onTtsOpenAiApiKeyChange = onTtsOpenAiApiKeyChange,
                            onTtsOpenAiVoiceChange = onTtsOpenAiVoiceChange,
                            onTtsOpenAiModelChange = onTtsOpenAiModelChange,
                            onTtsElevenLabsApiKeyChange = onTtsElevenLabsApiKeyChange,
                            onTtsElevenLabsVoiceIdChange = onTtsElevenLabsVoiceIdChange,
                            onTtsElevenLabsModelChange = onTtsElevenLabsModelChange,
                        )
                        VoiceDivider()
                        TtsPreviewRow(state = ttsPreviewState, onPress = onTtsPreview)
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
                        description =
                            if (appliedIsUsable) {
                                stringResource(R.string.wake_word_description)
                            } else {
                                stringResource(R.string.wake_word_blocked_by_phrase)
                            },
                        checked = wakeWordEnabled,
                        enabled = appliedIsUsable,
                        onCheckedChange = onWakeWordChange,
                    )
                    VoiceDivider()
                    WakeWordSection(
                        appliedPhrase = wakeWordPhrase,
                        appliedSensitivity = wakeWordSensitivity,
                        draftPhrase = draftPhrase,
                        draftSensitivity = draftSensitivity,
                        unknownWords = unknownWords,
                        listening = wakeWordEnabled,
                        language = wakeWordModelLanguage,
                        modelState = modelState,
                        onDraftPhraseChange = { draftPhrase = it },
                        onDraftSensitivityChange = { draftSensitivity = it },
                        onApply = { onWakeWordApply(draftPhrase, draftSensitivity) },
                        onLanguageChange = onWakeWordModelLanguageChange,
                        onDownload = onWakeWordModelDownload,
                        onCancelDownload = onWakeWordModelCancel,
                        onRemove = onWakeWordModelRemove,
                    )
                    if (wakeWordEnabled) {
                        VoiceDivider()
                        VoiceToggleRow(
                            icon = Icons.Default.Stop,
                            title = stringResource(R.string.wake_word_barge_in),
                            description = stringResource(R.string.wake_word_barge_in_description),
                            checked = ttsBargeInEnabled,
                            onCheckedChange = onTtsBargeInChange,
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

/**
 * The fields belonging to the chosen speech provider.
 *
 * Rate and pitch sit under the Android engine rather than above the provider choice because
 * [com.yugahashimoto.andcode.feature.assistant.TTSProviderConfig] only carries them for that
 * engine - the cloud providers voice their own defaults, and showing sliders that quietly do
 * nothing would be worse than not offering them.
 */
@Composable
private fun TtsProviderSection(
    ttsProvider: String,
    ttsAndroidEngine: String?,
    androidTtsEngines: List<Pair<String, String>>,
    ttsSpeechRate: Float,
    ttsPitch: Float,
    ttsOpenAiApiKey: String,
    ttsOpenAiVoice: String,
    ttsOpenAiModel: String,
    ttsElevenLabsApiKey: String,
    ttsElevenLabsVoiceId: String,
    ttsElevenLabsModel: String,
    onTtsAndroidEngineChange: (String?) -> Unit,
    onTtsSpeechRateChange: (Float) -> Unit,
    onTtsPitchChange: (Float) -> Unit,
    onTtsOpenAiApiKeyChange: (String) -> Unit,
    onTtsOpenAiVoiceChange: (String) -> Unit,
    onTtsOpenAiModelChange: (String) -> Unit,
    onTtsElevenLabsApiKeyChange: (String) -> Unit,
    onTtsElevenLabsVoiceIdChange: (String) -> Unit,
    onTtsElevenLabsModelChange: (String) -> Unit,
) {
    when (ttsProvider) {
        "android" -> {
            VoiceDivider()
            ChoiceDropdown(
                label = stringResource(R.string.tts_engine_label),
                selected = ttsAndroidEngine.orEmpty(),
                options = listOf("" to stringResource(R.string.tts_engine_system_default)) + androidTtsEngines,
                onSelect = { onTtsAndroidEngineChange(it.takeIf(String::isNotBlank)) },
            )
            VoiceDivider()
            VoiceSlider(
                label = stringResource(R.string.tts_speech_rate_label),
                value = ttsSpeechRate,
                range = TtsTuning.MIN_RATE..TtsTuning.MAX_RATE,
                onValueChange = onTtsSpeechRateChange,
            )
            VoiceSlider(
                label = stringResource(R.string.tts_pitch_label),
                value = ttsPitch,
                range = TtsTuning.MIN_PITCH..TtsTuning.MAX_PITCH,
                onValueChange = onTtsPitchChange,
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

/**
 * A labelled slider showing its own value.
 *
 * The value is committed on every change rather than only when the finger lifts: the settings
 * repository is the single source the voice session reads from, and a rate that only lands after
 * an unrelated recomposition is the kind of thing that looks like the setting being ignored.
 */
@Composable
private fun VoiceSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            // 0.05 apart across the 0.5..2.0 range the engine accepts: fine enough to hear a
            // difference between neighbouring stops, coarse enough to land on one deliberately.
            steps = SLIDER_STEPS,
        )
    }
}

private const val SLIDER_STEPS = 29

/** How long typing has to settle before the phrase is looked up in the model's dictionary. */
private const val PHRASE_CHECK_DELAY_MS = 350L

/**
 * Reads a sample line back with the settings as they stand.
 *
 * Rate and pitch are hard to judge from a number, and the cloud providers need a working key and
 * voice id before they say anything at all, so this doubles as the one place those are proved
 * right without leaving the screen.
 */
@Composable
private fun TtsPreviewRow(
    state: TtsPreviewState,
    onPress: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tts_preview_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state == TtsPreviewState.FAILED) {
                Text(
                    text = stringResource(R.string.tts_preview_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        OutlinedButton(onClick = onPress) {
            Icon(
                if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(
                    if (state.isRunning) R.string.tts_preview_stop else R.string.tts_preview_play,
                ),
            )
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
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
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

/**
 * The wake word itself: what to say, how sure the recogniser must be, and the speech model that
 * does the recognising.
 *
 * The phrase is free text rather than a fixed list because the recogniser is constrained to a
 * grammar at start-up, so any phrase the model has words for works - the previous build could only
 * offer the one phrase someone had trained a network for. Which is also why what is typed is not
 * what is listening: the recogniser is built once from the phrase, so the edit and the applied
 * value are shown as two separate things rather than pretending a keystroke reaches detection.
 */
@Composable
private fun WakeWordSection(
    appliedPhrase: String,
    appliedSensitivity: Float,
    draftPhrase: String,
    draftSensitivity: Float,
    unknownWords: List<String>,
    listening: Boolean,
    language: VoskModelLanguage,
    modelState: VoskModelState,
    onDraftPhraseChange: (String) -> Unit,
    onDraftSensitivityChange: (Float) -> Unit,
    onApply: () -> Unit,
    onLanguageChange: (VoskModelLanguage) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val edited =
        WakeWordGrammar.normalize(draftPhrase) != WakeWordGrammar.normalize(appliedPhrase) ||
            draftSensitivity != appliedSensitivity
    VoiceTextField(
        label = stringResource(R.string.wake_word_phrase_label),
        value = draftPhrase,
        onValueChange = onDraftPhraseChange,
        isError = unknownWords.isNotEmpty(),
    )
    if (unknownWords.isEmpty()) {
        Text(
            text = stringResource(R.string.wake_word_phrase_hint, WakeWordGrammar.DEFAULT_PHRASE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    } else {
        // Named, not just rejected: "andcode" is one keystroke from "and code", and the failure it
        // causes is otherwise completely silent.
        Text(
            text =
                stringResource(
                    R.string.wake_word_phrase_unknown_words,
                    unknownWords.joinToString(", "),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    VoiceSlider(
        label = stringResource(R.string.wake_word_sensitivity_label),
        value = draftSensitivity,
        range = 0f..1f,
        onValueChange = onDraftSensitivityChange,
    )
    Text(
        text = stringResource(R.string.wake_word_sensitivity_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    WakeWordApplyRow(
        appliedPhrase = appliedPhrase,
        listening = listening,
        edited = edited,
        canApply = edited && unknownWords.isEmpty(),
        onApply = onApply,
    )
    VoiceDivider()
    ChoiceDropdown(
        label = stringResource(R.string.wake_word_model_language_label),
        selected = language.id,
        options =
            listOf(
                VoskModelLanguage.ENGLISH.id to stringResource(R.string.wake_word_model_language_english),
                VoskModelLanguage.JAPANESE.id to stringResource(R.string.wake_word_model_language_japanese),
            ),
        onSelect = { id -> VoskModelLanguage.fromId(id)?.let(onLanguageChange) },
    )
    WakeWordModelRow(
        language = language,
        state = modelState,
        onDownload = onDownload,
        onCancelDownload = onCancelDownload,
        onRemove = onRemove,
    )
}

/**
 * What detection is listening for right now, and the button that changes it.
 *
 * Applying is a deliberate press rather than something that follows typing: the recogniser is
 * rebuilt from scratch each time, which means reloading a 40 MB model, and a phrase is not worth
 * building one for until it is finished being typed.
 */
@Composable
private fun WakeWordApplyRow(
    appliedPhrase: String,
    listening: Boolean,
    edited: Boolean,
    canApply: Boolean,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text =
                when {
                    edited -> stringResource(R.string.wake_word_pending_changes)
                    listening -> stringResource(R.string.wake_word_listening_for, WakeWordGrammar.normalize(appliedPhrase))
                    else -> stringResource(R.string.wake_word_applied_phrase, WakeWordGrammar.normalize(appliedPhrase))
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (edited) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Button(onClick = onApply, enabled = canApply) {
            Text(stringResource(R.string.wake_word_apply))
        }
    }
}

/** The download state of the selected speech model, and the one action it currently offers. */
@Composable
private fun WakeWordModelRow(
    language: VoskModelLanguage,
    state: VoskModelState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val megabytes = VoskModelCatalog.forLanguage(language).approximateBytes / (1024 * 1024)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wake_word_model_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        when (state) {
                            VoskModelState.Missing ->
                                stringResource(R.string.wake_word_model_not_downloaded, megabytes)
                            is VoskModelState.Downloading ->
                                state.fraction
                                    ?.let { stringResource(R.string.wake_word_model_downloading_percent, (it * 100).toInt()) }
                                    ?: stringResource(R.string.wake_word_model_downloading)
                            VoskModelState.Extracting -> stringResource(R.string.wake_word_model_extracting)
                            VoskModelState.Installed -> stringResource(R.string.wake_word_model_ready)
                            is VoskModelState.Failed ->
                                state.message ?: stringResource(R.string.wake_word_model_failed)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (state is VoskModelState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            when (state) {
                is VoskModelState.Downloading, VoskModelState.Extracting ->
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.wake_word_model_cancel))
                    }
                VoskModelState.Installed ->
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.wake_word_model_remove))
                    }
                else ->
                    OutlinedButton(onClick = onDownload) {
                        Text(stringResource(R.string.wake_word_model_download))
                    }
            }
        }
        val fraction = (state as? VoskModelState.Downloading)?.fraction
        if (state is VoskModelState.Downloading || state == VoskModelState.Extracting) {
            Spacer(Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
