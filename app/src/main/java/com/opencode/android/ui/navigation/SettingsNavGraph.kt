package com.opencode.android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.opencode.android.data.settings.AppPreferences
import com.opencode.android.data.settings.AppPreferencesRepository
import com.opencode.android.feature.settings.ProviderSettingsScreen
import com.opencode.android.feature.settings.SettingsScreenV2
import com.opencode.android.feature.settings.SettingsUiState
import com.opencode.android.feature.settings.SettingsViewModel
import com.opencode.android.feature.settings.VoiceSettingsScreen
import com.opencode.android.feature.support.GitHubSupportSettingsButton
import com.opencode.android.runtime.RuntimeRegistry

fun NavGraphBuilder.settingsNavGraph(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    settingsState: SettingsUiState,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    appVersion: String,
    onOpenDrawer: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onShowDiagnostics: () -> Unit,
    preferences: AppPreferences,
    appPreferences: AppPreferencesRepository,
    runtimeRegistry: RuntimeRegistry,
    context: Context,
    hasMicrophonePermission: () -> Boolean,
    onRequestWakeWordPermission: () -> Unit,
) {
    composable(ROUTE_SETTINGS) {
        Box {
            SettingsScreenV2(
                assistantConfigured = settingsState.assistantRuntimeId != null,
                notificationsEnabled = notificationsEnabled,
                onToggleNotifications = onToggleNotifications,
                appVersion = appVersion,
                onOpenDrawer = onOpenDrawer,
                onOpenAssistantSettings = onOpenAssistantSettings,
                onOpenVoiceSettings = { navController.navigate(ROUTE_SETTINGS_VOICE) },
                onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
                onOpenLocalRuntime = { navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE) },
                onOpenRemoteConnection = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
                onOpenWorkspaces = { navController.navigate(ROUTE_WORKSPACES) },
                onOpenDiagnostics = onShowDiagnostics,
                onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP) },
                onOpenServerInfo = { navController.navigate(ROUTE_SETTINGS_SERVER_INFO) },
                currentTheme = preferences.theme,
                onThemeChange = { appPreferences.setTheme(it) },
                uiFontSize = preferences.uiFontSize,
                onUiFontSizeChange = { appPreferences.setUiFontSize(it) },
                codeFontSize = preferences.codeFontSize,
                onCodeFontSizeChange = { appPreferences.setCodeFontSize(it) },
                syntaxTheme = preferences.syntaxTheme,
                onSyntaxThemeChange = { appPreferences.setSyntaxTheme(it) },
                toolCallDetailLevel = preferences.toolCallDetailLevel,
                onToolCallDetailLevelChange = { appPreferences.setToolCallDetailLevel(it) },
                autoExpandReasoning = preferences.autoExpandReasoning,
                onAutoExpandReasoningChange = { appPreferences.setAutoExpandReasoning(it) },
                sendBehavior = preferences.sendBehavior,
                onSendBehaviorChange = { appPreferences.setSendBehavior(it) },
            )
            GitHubSupportSettingsButton(
                appVersion = appVersion,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
            )
        }
    }

    composable(ROUTE_SETTINGS_VOICE) {
        VoiceSettingsScreen(
            ttsEnabled = settingsState.ttsEnabled,
            continuousConversation = settingsState.continuousConversation,
            wakeWordEnabled = settingsState.wakeWordEnabled,
            onTtsChange = settingsViewModel::setTtsEnabled,
            onContinuousChange = settingsViewModel::setContinuousConversation,
            onWakeWordChange = { enabled ->
                settingsViewModel.setWakeWordEnabled(enabled)
                if (enabled) {
                    if (hasMicrophonePermission()) {
                        com.opencode.android.feature.wakeword.WakeWordService.start(context)
                    } else {
                        onRequestWakeWordPermission()
                    }
                } else {
                    com.opencode.android.feature.wakeword.WakeWordService.stop(context)
                }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_PROVIDERS) {
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
            onConnectGitHub = { settingsViewModel.beginGitHubDeviceFlow() },
            onDisconnectGitHub = settingsViewModel::disconnectGitHub,
            onOpenGitHubVerification = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { error -> settingsViewModel.reportOAuthError(error.message.orEmpty()) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP) {
        com.opencode.android.feature.settings.McpScreen(
            registry = runtimeRegistry,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_SERVER_INFO) {
        com.opencode.android.feature.settings.ServerInfoScreen(
            registry = runtimeRegistry,
            onBack = { navController.popBackStack() },
        )
    }
}
