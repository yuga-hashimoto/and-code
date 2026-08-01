package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.core.api.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatImageViewerInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sentImageOpensViewerAndCanRequestDownload() {
        val attachment =
            PromptAttachment(
                "pixel.png",
                "image/png",
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
        var selected by mutableStateOf<ChatImageSource?>(null)
        var downloaded: ChatImageSource? = null

        composeRule.setContent {
            MessageBubble(ChatMessage(isUser = true, attachments = listOf(attachment))) { selected = it }
            selected?.let { source ->
                ChatImageViewerDialog(
                    source = source,
                    onDismiss = { selected = null },
                    onDownload = { downloaded = it },
                )
            }
        }

        composeRule.onNodeWithTag("chat-image-thumbnail").performClick()
        composeRule.onNodeWithTag("chat-image-viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-image-download").performClick()

        composeRule.runOnIdle { assertEquals("pixel.png", downloaded?.filename) }
    }
}
