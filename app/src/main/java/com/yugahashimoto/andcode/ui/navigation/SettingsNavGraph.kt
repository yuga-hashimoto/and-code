package com.yugahashimoto.andcode.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.feature.assistant.TtsPreview
import com.yugahashimoto.andcode.feature.settings.AgentSettingsScreen
import com.yugahashimoto.andcode.feature.settings.AntigravityAgentSettingsScreen
import com.yugahashimoto.andcode.feature.settings.ClaudeCodeAgentSettingsScreen
import com.yugahashimoto.andcode.feature.settings.GitHubSettingsScreen
import com.yugahashimoto.andcode.feature.settings.ModelVisibilityScreen
import com.yugahashimoto.andcode.feature.settings.OpenCodeAgentSettingsScreen
import com.yugahashimoto.andcode.feature.settings.OpenCodeAgentSettingsViewModel
import com.yugahashimoto.andcode.feature.settings.ProviderSettingsScreen
import com.yugahashimoto.andcode.feature.settings.SettingsScreenV2
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.settings.VoiceSettingsScreen
import com.yugahashimoto.andcode.feature.support.GitHubSupportSheetHost
import com.yugahashimoto.andcode.feature.wakeword.VoskModelState
import com.yugahashimoto.andcode.feature.wakeword.WakeWordSettingsPolicy
import com.yugahashimoto.andcode.runtime.RuntimeRegistry

fun NavGraphBuilder.settingsNavGraph(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    // Getters throughout: NavHost remembers these lambdas, so anything passed as a value is frozen
    // at the composition that built the graph and never updates again.
    notificationsEnabled: () -> Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    appVersion: String,
    onOpenDrawer: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    assistantActive: () -> Boolean,
    runtimeTargets: () -> List<com.yugahashimoto.andcode.runtime.RuntimeTarget>,
    workspaces: () -> List<com.yugahashimoto.andcode.runtime.WorkspaceRef>,
    onShowDiagnostics: () -> Unit,
    preferences: () -> AppPreferences,
    appPreferences: AppPreferencesRepository,
    runtimeRegistry: RuntimeRegistry,
    context: Context,
    hasMicrophonePermission: () -> Boolean,
    claude: () -> com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState,
    claudeActions: ClaudeSettingsActions,
    antigravity: () -> com.yugahashimoto.andcode.runtime.local.AntigravityControllerState,
    antigravityActions: AntigravitySettingsActions,
    onRequestWakeWordPermission: () -> Unit,
) {
    composable(ROUTE_SETTINGS) {
        val settingsState by settingsViewModel.state.collectAsState()
        var showSupportSheet by remember { mutableStateOf(false) }

        SettingsScreenV2(
            assistantConfigured = assistantActive(),
            notificationsEnabled = notificationsEnabled(),
            onToggleNotifications = onToggleNotifications,
            analyticsEnabled = preferences().analyticsEnabled,
            onToggleAnalytics = appPreferences::setAnalyticsEnabled,
            appVersion = appVersion,
            onOpenDrawer = onOpenDrawer,
            onOpenAssistantSettings = onOpenAssistantSettings,
            onOpenVoiceSettings = { navController.navigate(ROUTE_SETTINGS_VOICE) },
            onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
            onOpenAgentSettings = { navController.navigate(ROUTE_SETTINGS_AGENTS) },
            onOpenGitHubSettings = { navController.navigate(ROUTE_SETTINGS_GITHUB) },
            onOpenLocalRuntime = { navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE) },
            onOpenGuestBrowser = { navController.navigate(ROUTE_GUEST_BROWSER) },
            onOpenRemoteConnection = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
            onOpenWorkspaces = { navController.navigate(ROUTE_WORKSPACES) },
            onOpenDiagnostics = onShowDiagnostics,
            onOpenSupport = { showSupportSheet = true },
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP) },
            onOpenServerInfo = { navController.navigate(ROUTE_SETTINGS_SERVER_INFO) },
            onOpenLegal = { navController.navigate(ROUTE_SETTINGS_LEGAL) },
            currentTheme = preferences().theme,
            onThemeChange = { appPreferences.setTheme(it) },
            currentLanguage = preferences().language,
            onLanguageChange = { language ->
                appPreferences.setLanguage(language)
                (context as? android.app.Activity)?.recreate()
            },
            uiFontSize = preferences().uiFontSize,
            onUiFontSizeChange = { appPreferences.setUiFontSize(it) },
            codeFontSize = preferences().codeFontSize,
            onCodeFontSizeChange = { appPreferences.setCodeFontSize(it) },
            syntaxTheme = preferences().syntaxTheme,
            onSyntaxThemeChange = { appPreferences.setSyntaxTheme(it) },
            toolCallDetailLevel = preferences().toolCallDetailLevel,
            onToolCallDetailLevelChange = { appPreferences.setToolCallDetailLevel(it) },
            autoExpandReasoning = preferences().autoExpandReasoning,
            onAutoExpandReasoningChange = { appPreferences.setAutoExpandReasoning(it) },
            sendBehavior = preferences().sendBehavior,
            onSendBehaviorChange = { appPreferences.setSendBehavior(it) },
            enterToSend = preferences().enterToSend,
            onEnterToSendChange = { appPreferences.setEnterToSend(it) },
            wakeWordEnabled = settingsState.wakeWordEnabled,
        )

        if (showSupportSheet) {
            GitHubSupportSheetHost(
                appVersion = appVersion,
                onDismiss = { showSupportSheet = false },
            )
        }
    }

    composable(ROUTE_SETTINGS_VOICE) {
        val settingsState by settingsViewModel.state.collectAsState()
        val androidTtsEngines =
            remember {
                com.yugahashimoto.andcode.feature.assistant.TTSManager.availableAndroidEngines(context)
                    .map { it.packageName to it.label }
            }
        // Held for as long as the screen is: building a TTS engine is slow enough that doing it on
        // the press would put the delay between the button and the first word.
        val previewScope = rememberCoroutineScope()
        val preview = remember(previewScope) { TtsPreview(context, previewScope) }
        val previewState by preview.state.collectAsState()
        val previewSample = stringResource(R.string.tts_preview_sample)
        DisposableEffect(preview) { onDispose(preview::release) }
        // The phrase, sensitivity and model are all read when the recogniser is built, so a change
        // only takes effect once the service has been restarted with it. Which is why applying is
        // an explicit press rather than something that follows typing: restarting per keystroke
        // reloaded a 40 MB model for every character of a phrase that was not finished yet.
        val restartWakeWord = {
            if (settingsState.wakeWordEnabled) {
                val restarted =
                    com.yugahashimoto.andcode.feature.wakeword.WakeWordService.start(
                        context,
                        settingsState.wakeWordModelLanguage,
                    )
                if (!restarted) settingsViewModel.setWakeWordEnabled(false)
            }
        }
        VoiceSettingsScreen(
            ttsEnabled = settingsState.ttsEnabled,
            ttsProvider = settingsState.ttsProvider,
            ttsAndroidEngine = settingsState.ttsAndroidEngine,
            androidTtsEngines = androidTtsEngines,
            ttsSpeechRate = settingsState.ttsSpeechRate,
            ttsPitch = settingsState.ttsPitch,
            ttsPreviewState = previewState,
            ttsOpenAiApiKey = settingsState.ttsOpenAiApiKey,
            ttsOpenAiVoice = settingsState.ttsOpenAiVoice,
            ttsOpenAiModel = settingsState.ttsOpenAiModel,
            ttsElevenLabsApiKey = settingsState.ttsElevenLabsApiKey,
            ttsElevenLabsVoiceId = settingsState.ttsElevenLabsVoiceId,
            ttsElevenLabsModel = settingsState.ttsElevenLabsModel,
            ttsBargeInEnabled = settingsState.ttsBargeInEnabled,
            continuousConversation = settingsState.continuousConversation,
            wakeWordEnabled = settingsState.wakeWordEnabled,
            wakeWordPhrase = settingsState.wakeWordPhrase,
            wakeWordSensitivity = settingsState.wakeWordSensitivity,
            wakeWordModelLanguage = settingsState.wakeWordModelLanguage,
            wakeWordModelStates = settingsState.wakeWordModelStates,
            assistantRuntimeId = settingsState.assistantRuntimeId,
            runtimeTargets = runtimeTargets(),
            workspaces = workspaces(),
            assistantProviderId = settingsState.assistantProviderId,
            assistantModelId = settingsState.assistantModelId,
            assistantWorkspacePath = settingsState.assistantWorkspacePath.orEmpty(),
            onTtsChange = settingsViewModel::setTtsEnabled,
            onTtsProviderChange = settingsViewModel::setTtsProvider,
            onTtsAndroidEngineChange = settingsViewModel::setTtsAndroidEngine,
            onTtsSpeechRateChange = settingsViewModel::setTtsSpeechRate,
            onTtsPitchChange = settingsViewModel::setTtsPitch,
            onTtsPreview = { preview.press(settingsViewModel.ttsSettings(), previewSample) },
            onTtsOpenAiApiKeyChange = settingsViewModel::setTtsOpenAiApiKey,
            onTtsOpenAiVoiceChange = settingsViewModel::setTtsOpenAiVoice,
            onTtsOpenAiModelChange = settingsViewModel::setTtsOpenAiModel,
            onTtsElevenLabsApiKeyChange = settingsViewModel::setTtsElevenLabsApiKey,
            onTtsElevenLabsVoiceIdChange = settingsViewModel::setTtsElevenLabsVoiceId,
            onTtsElevenLabsModelChange = settingsViewModel::setTtsElevenLabsModel,
            onTtsBargeInChange = settingsViewModel::setTtsBargeInEnabled,
            onContinuousChange = settingsViewModel::setContinuousConversation,
            onWakeWordChange = { enabled ->
                if (enabled) {
                    val microphonePermission = hasMicrophonePermission()
                    if (!microphonePermission) {
                        onRequestWakeWordPermission()
                    } else if (!WakeWordSettingsPolicy.canEnable(microphonePermission, assistantActive())) {
                        android.widget.Toast.makeText(
                            context,
                            com.yugahashimoto.andcode.R.string.wake_word_requires_assistant,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        onOpenAssistantSettings()
                    } else if (settingsState.wakeWordModelStates[settingsState.wakeWordModelLanguage] != VoskModelState.Installed) {
                        // The model is downloaded rather than packaged, so it genuinely may not be
                        // here. Start fetching it instead of switching on a service with nothing
                        // to listen with.
                        android.widget.Toast.makeText(
                            context,
                            com.yugahashimoto.andcode.R.string.wake_word_model_required,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        settingsViewModel.downloadWakeWordModel(settingsState.wakeWordModelLanguage)
                    } else {
                        settingsViewModel.setWakeWordEnabled(true)
                        val started =
                            com.yugahashimoto.andcode.feature.wakeword.WakeWordService.start(
                                context,
                                settingsState.wakeWordModelLanguage,
                            )
                        if (!started) {
                            settingsViewModel.setWakeWordEnabled(false)
                            android.widget.Toast.makeText(
                                context,
                                com.yugahashimoto.andcode.R.string.wake_word_start_failed,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                } else {
                    settingsViewModel.setWakeWordEnabled(false)
                    com.yugahashimoto.andcode.feature.wakeword.WakeWordService.stop(context)
                }
            },
            onWakeWordApply = { phrase, sensitivity ->
                settingsViewModel.setWakeWordPhrase(phrase)
                settingsViewModel.setWakeWordSensitivity(sensitivity)
                restartWakeWord()
            },
            unknownWakeWordWords = settingsViewModel::unknownWakeWordWords,
            onWakeWordModelLanguageChange = { language ->
                settingsViewModel.setWakeWordModelLanguage(language)
                restartWakeWord()
            },
            onWakeWordModelDownload = { settingsViewModel.downloadWakeWordModel(settingsState.wakeWordModelLanguage) },
            onWakeWordModelCancel = { settingsViewModel.cancelWakeWordModelDownload(settingsState.wakeWordModelLanguage) },
            onWakeWordModelRemove = {
                // Removing the model the service is listening with would leave it running against
                // files that are no longer there.
                settingsViewModel.setWakeWordEnabled(false)
                com.yugahashimoto.andcode.feature.wakeword.WakeWordService.stop(context)
                settingsViewModel.removeWakeWordModel(settingsState.wakeWordModelLanguage)
            },
            onAssistantRuntimeChange = { runtimeId ->
                settingsViewModel.setAssistantRuntimeId(runtimeId.takeIf { it.isNotBlank() })
            },
            onAssistantModelChange = { providerId, modelId ->
                settingsViewModel.setAssistantModel(providerId, modelId)
            },
            onAssistantWorkspaceChange = { path ->
                settingsViewModel.setAssistantWorkspacePath(path)
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENTS) {
        AgentSettingsScreen(
            onOpenOpenCode = { navController.navigate(ROUTE_SETTINGS_AGENT_OPENCODE) },
            onOpenClaudeCode = { navController.navigate(ROUTE_SETTINGS_AGENT_CLAUDE) },
            onOpenAntigravity = { navController.navigate(ROUTE_SETTINGS_AGENT_ANTIGRAVITY) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_OPENCODE) {
        val app = context.applicationContext as com.yugahashimoto.andcode.AndCodeApplication
        val openCodeViewModel: OpenCodeAgentSettingsViewModel =
            androidx.lifecycle.viewmodel.compose.viewModel(
                key = "settings-agent-opencode",
                factory =
                    com.yugahashimoto.andcode.ui.ViewModelFactory {
                        OpenCodeAgentSettingsViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            updateCheckProvider = app.localRuntimeManager::checkForUpdate,
                            rollbackVersionProvider = app.localRuntimeManager::rollbackVersion,
                            freeBytesProvider = { app.filesDir.usableSpace },
                            startAction = app.localRuntimeController::start,
                            stopAction = app.localRuntimeController::stop,
                            restartAction = app.localRuntimeController::restart,
                            updateAction = app.localRuntimeController::update,
                            rollbackAction = app.localRuntimeController::rollback,
                            getString = { app.getString(it) },
                        )
                    },
            )
        val openCodeState by openCodeViewModel.state.collectAsState()
        OpenCodeAgentSettingsScreen(
            state = openCodeState,
            onStart = openCodeViewModel::start,
            onStop = openCodeViewModel::stop,
            onRestart = openCodeViewModel::restart,
            onCheckForUpdate = openCodeViewModel::checkForUpdate,
            onRequestUpdate = openCodeViewModel::requestUpdate,
            onDismissUpdate = openCodeViewModel::dismissUpdate,
            onConfirmUpdate = openCodeViewModel::confirmUpdate,
            onRequestRollback = openCodeViewModel::requestRollback,
            onDismissRollback = openCodeViewModel::dismissRollback,
            onConfirmRollback = openCodeViewModel::confirmRollback,
            onOpenSetup = { navController.navigate(ROUTE_ANDROID_SETUP) },
            onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
            onOpenModelVisibility = { navController.navigate(ROUTE_SETTINGS_MODEL_VISIBILITY) },
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MODEL_VISIBILITY) {
        val settingsState by settingsViewModel.state.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) { settingsViewModel.refreshProviderAuth() }
        ModelVisibilityScreen(
            providers = settingsState.availableProviders.filter { it.id in settingsState.connectedProviderIds },
            hiddenModelKeys = settingsState.hiddenModelKeys,
            onToggleModelVisibility = settingsViewModel::toggleModelVisibility,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_CLAUDE) {
        ClaudeCodeAgentSettingsScreen(
            claude = claude(),
            onInstall = claudeActions.onInstall,
            onUpdate = claudeActions.onUpdate,
            onSelectPermissionMode = claudeActions.onSelectPermissionMode,
            onSignIn = claudeActions.onSignIn,
            onSubmitCode = claudeActions.onSubmitCode,
            onCancelSignIn = claudeActions.onCancelSignIn,
            onSignOut = claudeActions.onSignOut,
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP_CLAUDE) },
            onOpenUrl = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_ANTIGRAVITY) {
        AntigravityAgentSettingsScreen(
            antigravity = antigravity(),
            onInstall = antigravityActions.onInstall,
            onUpdate = antigravityActions.onUpdate,
            onSelectPermissionMode = antigravityActions.onSelectPermissionMode,
            onSignIn = antigravityActions.onSignIn,
            onSubmitCode = antigravityActions.onSubmitCode,
            onCancelSignIn = antigravityActions.onCancelSignIn,
            onSignOut = antigravityActions.onSignOut,
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP_ANTIGRAVITY) },
            onOpenUrl = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_PROVIDERS) {
        val settingsState by settingsViewModel.state.collectAsState()
        // Re-read on open: the runtime that owns providers may have started since the last look.
        androidx.compose.runtime.LaunchedEffect(Unit) { settingsViewModel.refreshProviderAuth() }
        ProviderSettingsScreen(
            state = settingsState,
            onOpenProviderAuth = settingsViewModel::openProviderAuth,
            onSelectProviderAuthMethod = settingsViewModel::selectProviderAuthMethod,
            onProviderAuthInput = settingsViewModel::updateProviderAuthInput,
            onProviderApiKey = settingsViewModel::updateProviderApiKey,
            onSubmitProviderAuth = settingsViewModel::submitProviderAuth,
            onCompleteProviderOAuth = settingsViewModel::completeProviderOAuth,
            onDisconnectProvider = settingsViewModel::disconnectProvider,
            onLaunchOAuthBrowser = { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                }.onFailure { error ->
                    settingsViewModel.reportOAuthError(error.message.orEmpty())
                }
            },
            onDismissProviderAuth = settingsViewModel::dismissProviderAuth,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_GITHUB) {
        val settingsState by settingsViewModel.state.collectAsState()
        GitHubSettingsScreen(
            state = settingsState,
            onConnect = settingsViewModel::beginGitHubDeviceFlow,
            onDisconnect = settingsViewModel::disconnectGitHub,
            onOpenVerification = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    .onFailure { error -> settingsViewModel.reportOAuthError(error.message.orEmpty()) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP) {
        com.yugahashimoto.andcode.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.yugahashimoto.andcode.runtime.LocalAgent.OPEN_CODE,
            onOpenBrowser = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP_CLAUDE) {
        com.yugahashimoto.andcode.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE,
            onOpenBrowser = {},
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP_ANTIGRAVITY) {
        com.yugahashimoto.andcode.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.yugahashimoto.andcode.runtime.LocalAgent.ANTIGRAVITY,
            onOpenBrowser = {},
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_SERVER_INFO) {
        com.yugahashimoto.andcode.feature.settings.ServerInfoScreen(
            registry = runtimeRegistry,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_LEGAL) {
        com.yugahashimoto.andcode.feature.settings.LegalScreen(
            onOpenDocument = { document -> navController.navigate(settingsLegalDocumentRoute(document.name)) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(SETTINGS_LEGAL_DOCUMENT_ROUTE_PATTERN) { backStackEntry ->
        val docId = backStackEntry.arguments?.getString(SETTINGS_LEGAL_DOCUMENT_ARG_ID)
        val document = com.yugahashimoto.andcode.feature.settings.LegalDocument.fromId(docId)
        if (document != null) {
            com.yugahashimoto.andcode.feature.settings.LegalDocumentScreen(
                document = document,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Claude Code actions the settings graph forwards to its agent screen. */
data class ClaudeSettingsActions(
    val onInstall: () -> Unit,
    val onUpdate: () -> Unit,
    val onSelectPermissionMode: (com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode) -> Unit,
    val onSignIn: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
)

/** Antigravity actions the settings graph forwards to its agent screen. */
data class AntigravitySettingsActions(
    val onInstall: () -> Unit,
    val onUpdate: () -> Unit,
    val onSelectPermissionMode: (com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode) -> Unit,
    val onSignIn: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
)
