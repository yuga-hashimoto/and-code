package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression test for issue #418: a fenced code block exposes its own copy action. */
@RunWith(AndroidJUnit4::class)
class ChatCodeBlockCopyInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun codeBlockShowsCopyButton() {
        composeRule.setContent {
            TimelineEntryRow(
                TimelineEntry.Body(
                    "b1",
                    "m1",
                    ChatPart.Text("p1", "Run this:\n\n```sh\nls -la\n```"),
                ),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ls -la").assertExists()
        composeRule.onNodeWithTag("chat-code-block-copy").assertExists().assertHasClickAction()
    }
}
