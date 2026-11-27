package com.yugahashimoto.andcode.feature.report

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContentReportDialogInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onlyExplicitlyEnteredTextIsSent() {
        var sent: Pair<AiReportReason, String>? = null
        composeRule.setContent {
            MaterialTheme {
                AiContentReportDialog(
                    hasGitHubAccount = true,
                    onSubmit = { reason, excerpt ->
                        sent = reason to excerpt
                        Result.success(Unit)
                    },
                    onDismiss = {},
                )
            }
        }
        val send = composeRule.activity.getString(R.string.ai_report_send)
        composeRule.onNodeWithText(send).assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("reviewed public excerpt")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_deceptive)).performClick()
        composeRule.onNodeWithText(send).performClick()
        composeRule.waitForIdle()
        assertEquals(AiReportReason.DECEPTIVE to "reviewed public excerpt", sent)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_success)).assertExists()
    }

    @Test
    fun githubAccountIsRequiredBeforeSending() {
        var sent: String? = null
        composeRule.setContent {
            MaterialTheme {
                AiContentReportDialog(
                    hasGitHubAccount = false,
                    onSubmit = { _, excerpt ->
                        sent = excerpt
                        Result.success(Unit)
                    },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("reviewed public excerpt")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_send)).assertIsNotEnabled()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_account)).assertExists()
        assertNull(sent)
    }

    @Test
    fun privateReportingWorksWithoutGithubAccount() {
        var sent: String? = null
        composeRule.setContent {
            MaterialTheme {
                AiContentReportDialog(
                    hasGitHubAccount = false,
                    privateReportingEnabled = true,
                    onSubmit = { _, excerpt ->
                        sent = excerpt
                        Result.success(Unit)
                    },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_private_warning)).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextInput("reviewed private excerpt")
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_private_send)).performClick()
        composeRule.waitForIdle()
        assertEquals("reviewed private excerpt", sent)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ai_report_success)).assertExists()
    }
}
