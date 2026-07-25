package com.opencode.android.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.opencode.android.MainActivity
import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.feature.chat.ChatHomeScreen
import com.opencode.android.feature.chat.ChatUiState
import com.opencode.android.feature.chat.ModelAndRuntimePickerSheet
import com.opencode.android.feature.onboarding.AndroidSetupScreen
import com.opencode.android.feature.onboarding.OnboardingChoiceScreen
import com.opencode.android.feature.settings.ProviderSettingsScreen
import com.opencode.android.feature.settings.SettingsScreenV2
import com.opencode.android.feature.settings.SettingsUiState
import com.opencode.android.feature.settings.VoiceSettingsScreen
import com.opencode.android.feature.workspace.RemoteConnectionScreen
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.components.SessionStatus
import com.opencode.android.ui.theme.OpenCodeAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@LargeTest
class UiScreenshotInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val previewAgents = listOf(OpenCodeAgent(name = "build"))
    private val previewWorkspaces =
        listOf(
            WorkspaceRef(
                id = "/workspace/project",
                name = "project",
                path = "/workspace/project",
            ),
        )
    private val previewProviders =
        listOf(
            OpenCodeProvider(
                id = "zai",
                name = "Z.ai",
                models =
                    linkedMapOf(
                        "glm-5" to
                            OpenCodeModel(
                                id = "glm-5",
                                providerId = "zai",
                                name = "GLM-5",
                            ),
                        "glm-4.5" to
                            OpenCodeModel(
                                id = "glm-4.5",
                                providerId = "zai",
                                name = "GLM-4.5",
                            ),
                    ),
            ),
        )
    private val previewRuntimeTargets =
        listOf(
            PreviewRuntimeTarget("local", "このAndroid", RuntimeType.LOCAL),
            PreviewRuntimeTarget("home-mac", "自宅のMacBook", RuntimeType.REMOTE),
        )

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun captureReviewedScreens() {
        useJapaneseResources()

        capture("01-chat-empty", {
            composeRule.onNodeWithText("チャット").assertIsDisplayed()
            composeRule.onNodeWithText("GLM-5").assertIsDisplayed()
            composeRule.onNodeWithText("project").assertIsDisplayed()
        }) { PreviewChatHome() }

        capture("02-runtime-not-ready", {
            composeRule.onNodeWithText("このAndroidをセットアップ").assertIsDisplayed()
            composeRule.onNodeWithText("PC・Macに接続").assertIsDisplayed()
            check(
                composeRule.onAllNodesWithText("OpenCodeにメッセージを送る…")
                    .fetchSemanticsNodes().isEmpty(),
            ) { "Composer must be hidden while the runtime is not ready" }
        }) {
            PreviewChatHome(
                state = ChatUiState(error = "Android local OpenCode runtime is not installed"),
            )
        }

        capture("03-drawer", {
            composeRule.onNodeWithText("最近のチャット").assertIsDisplayed()
            composeRule.onNodeWithText("設定").assertIsDisplayed()
        }) {
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Open)
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = false,
                drawerContent = {
                    ModalDrawerSheet {
                        AppDrawerContent(
                            recentSessions =
                                listOf(
                                    DrawerRecentSession(
                                        "1",
                                        "認証エラーの調査",
                                        "3時間前",
                                        status = SessionStatus.RUNNING,
                                    ),
                                    DrawerRecentSession(
                                        "2",
                                        "READMEを更新",
                                        "昨日",
                                        status = SessionStatus.COMPLETED_UNREAD,
                                    ),
                                    DrawerRecentSession("3", "テスト失敗を修正", "2日前"),
                                    DrawerRecentSession("4", "APIレスポンスを整理", "4日前"),
                                    DrawerRecentSession("5", "依存関係を更新", "1週間前"),
                                ),
                            workspaces =
                                listOf(
                                    WorkspaceRef("1", "project", "/workspace/project"),
                                ),
                            selectedWorkspacePath = "/workspace/project",
                            onNewChat = {},
                            onSelectProject = {},
                            onOpenSession = { _, _ -> },
                            onNavigate = {},
                        )
                    }
                },
            ) { PreviewChatHome() }
        }

        capture("04-settings", {
            composeRule.onNodeWithText("設定").assertIsDisplayed()
            composeRule.onNodeWithText("ウェイクワード").assertIsDisplayed()
        }) {
            SettingsScreenV2(
                assistantConfigured = true,
                notificationsEnabled = true,
                onToggleNotifications = {},
                appVersion = "0.2.0",
                onOpenDrawer = {},
                onOpenAssistantSettings = {},
                onOpenVoiceSettings = {},
                onOpenProviderSettings = {},
                onOpenLocalRuntime = {},
                onOpenRemoteConnection = {},
                onOpenWorkspaces = {},
                onOpenDiagnostics = {},
            )
        }

        capture("05-onboarding", {
            composeRule.onNodeWithText("OpenCodeへようこそ").assertIsDisplayed()
            composeRule.onNodeWithText("このAndroidで始める").assertIsDisplayed()
            composeRule.onNodeWithText("PC・Macに接続する").assertIsDisplayed()
        }) {
            OnboardingChoiceScreen(
                onSelectAndroid = {},
                onSelectRemote = {},
                onAddRemoteLater = {},
            )
        }

        capture("06-android-setup", {
            composeRule.onNodeWithText("このAndroidをセットアップ").assertIsDisplayed()
            composeRule.onNodeWithText("ランタイムをダウンロード").assertIsDisplayed()
            composeRule.onNodeWithText("ランタイムをダウンロード中").assertIsDisplayed()
        }) {
            AndroidSetupScreen(
                runtimeStatus =
                    LocalRuntimeStatus.Installing(
                        progress = 0.68f,
                        step = "ランタイムをダウンロード中",
                    ),
                onStartRuntimeSetup = {},
                settingsState = com.opencode.android.feature.settings.SettingsUiState(),
                onOpenProviderAuth = {},
                onSelectProviderAuthMethod = {},
                onProviderAuthInput = { _, _ -> },
                onProviderApiKey = {},
                onSubmitProviderAuth = {},
                onCompleteProviderOAuth = {},
                onDisconnectProvider = {},
                onDismissProviderAuth = {},
                onRefreshProviderAuth = {},
                onRefreshCatalog = {},
                onBack = {},
                onFinish = {},
            )
        }

        capture("07-remote-connection", {
            composeRule.onNodeWithText("PC・Macに接続").assertIsDisplayed()
            composeRule.onNodeWithText("接続をテスト").assertIsDisplayed()
        }) {
            RemoteConnectionScreen(
                onTestConnection = { Result.success(OpenCodeHealth(true, "1.0.0")) },
                onSaveConnection = {},
                onBack = {},
                onConnected = {},
            )
        }

        capture("08-model-runtime-picker", {
            composeRule.onNodeWithText("実行先").assertIsDisplayed()
            composeRule.onNodeWithText("このAndroid").assertIsDisplayed()
            composeRule.onNodeWithText("自宅のMacBook").assertIsDisplayed()
            check(composeRule.onAllNodesWithText("GLM-5").fetchSemanticsNodes().isNotEmpty()) {
                "Model picker must display GLM-5"
            }
        }) {
            Box(modifier = Modifier.fillMaxSize()) {
                PreviewChatHome()
                ModelAndRuntimePickerSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    runtimeTargets = previewRuntimeTargets,
                    selectedRuntimeId = "local",
                    onSelectRuntime = {},
                    providers = previewProviders,
                    selectedProviderId = "zai",
                    selectedModelId = "glm-5",
                    onSelectModel = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        capture("09-provider-settings", {
            composeRule.onNodeWithText("プロバイダ設定").assertIsDisplayed()
            composeRule.onNodeWithText("保存済みの認証情報").assertIsDisplayed()
            composeRule.onNodeWithText("APIキーを追加・更新").assertIsDisplayed()
        }) {
            ProviderSettingsScreen(
                state =
                    SettingsUiState(
                        availableProviders = previewProviders,
                        credentialStatuses = linkedMapOf("openai" to true, "anthropic" to false),
                    ),
                onOpenProviderAuth = {},
                onSelectProviderAuthMethod = {},
                onProviderAuthInput = { _, _ -> },
                onProviderApiKey = {},
                onSubmitProviderAuth = {},
                onCompleteProviderOAuth = {},
                onDisconnectProvider = {},
                onLaunchOAuthBrowser = {},
                onDismissProviderAuth = {},
                onBack = {},
            )
        }

        capture("10-voice-settings", {
            composeRule.onNodeWithText("音声設定").assertIsDisplayed()
            composeRule.onNodeWithText("ウェイクワード").assertIsDisplayed()
            composeRule.onNodeWithText("ウェイクワード用の追加パックはまだ導入されていません。").assertIsDisplayed()
        }) {
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

    @Composable
    private fun PreviewChatHome(
        state: ChatUiState =
            ChatUiState(
                isConnected = true,
                selectedWorkspacePath = "/workspace/project",
            ),
    ) {
        ChatHomeScreen(
            state = state,
            providers = previewProviders,
            agents = previewAgents,
            workspaces = previewWorkspaces,
            selectedProviderId = "zai",
            selectedModelId = "GLM-5",
            selectedAgentId = "build",
            runtimeTargets = previewRuntimeTargets,
            selectedRuntimeId = "local",
            onSelectRuntime = {},
            onSelectModel = { _, _ -> },
            onSelectAgent = {},
            onSelectWorkspace = {},
            onSelectQuestionAnswer = { _, _, _ -> },
            onSubmitQuestion = {},
            onSendMessage = {},
            onPermission = { _, _, _ -> },
            onAbort = {},
            onMic = {},
            onNewChat = {},
            onOpenHistory = {},
            onOpenLocalSetup = {},
            onOpenRemoteSetup = {},
            onOpenDrawer = {},
        )
    }

    @Suppress("DEPRECATION")
    private fun useJapaneseResources() {
        composeRule.activity.runOnUiThread {
            Locale.setDefault(Locale.JAPAN)
            val resources = composeRule.activity.resources
            val configuration =
                Configuration(resources.configuration).apply {
                    setLocale(Locale.JAPAN)
                }
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        composeRule.waitForIdle()
    }

    private fun capture(
        name: String,
        assertions: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        composeRule.activity.setContent {
            OpenCodeAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) { content() }
            }
        }
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertions()
        Thread.sleep(400)

        val bitmap =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            ) { "Unable to capture emulator screenshot: $name" }
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "ui-screenshots",
            ).apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Unable to encode emulator screenshot: $name"
            }
        }
        bitmap.recycle()
    }
}
