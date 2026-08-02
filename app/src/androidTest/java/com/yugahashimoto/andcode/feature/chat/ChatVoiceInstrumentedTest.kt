package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatVoiceInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun listeningComposerShowsTranscriptWithoutWaveform() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenState =
            mutableStateOf(
                ChatUiState(
                    isListening = true,
                    partialText = "こんにちは",
                ),
            )

        composeRule.setContent {
            ChatHomeScreen(
                state = screenState.value,
                providers = emptyList(),
                agents = emptyList(),
                selectedProviderId = null,
                selectedModelId = null,
                selectedAgentId = null,
                runtimeTargets = emptyList(),
                selectedRuntimeId = null,
                onSelectRuntime = {},
                onSelectModel = { _, _ -> },
                onSelectAgent = {},
                onSelectQuestionAnswer = { _, _, _ -> },
                onSubmitQuestion = {},
                onSendMessage = {},
                onPermission = { _, _, _ -> },
                onAbort = {},
                onMic = {},
                onNewChat = {},
                onOpenLocalSetup = {},
                onOpenRemoteSetup = {},
                onOpenDrawer = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.voice_state_listening)).assertIsDisplayed()
        composeRule.onNodeWithTag("chat-message-input").assertTextContains("こんにちは")
        composeRule.onNodeWithTag("chat-voice-waveform").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.voice_listening_placeholder)).assertDoesNotExist()

        screenState.value = ChatUiState(isSpeechProcessing = true)

        composeRule.onNodeWithTag("chat-voice-processing").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.processing)).assertIsDisplayed()
    }
}
