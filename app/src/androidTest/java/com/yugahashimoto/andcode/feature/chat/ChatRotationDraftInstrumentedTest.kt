package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A prompt that has been typed but not sent must survive the Activity being recreated, which is
 * what a rotation, a dark-mode switch or a font-scale change does (issue #355).
 */
@RunWith(AndroidJUnit4::class)
class ChatRotationDraftInstrumentedTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun typedDraftSurvivesActivityRecreation() {
        composeTestRule.onNodeWithTag("chat-message-input")
            .performTextInput("keep this prompt")

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chat-message-input")
            .assertTextContains("keep this prompt")
    }
}
