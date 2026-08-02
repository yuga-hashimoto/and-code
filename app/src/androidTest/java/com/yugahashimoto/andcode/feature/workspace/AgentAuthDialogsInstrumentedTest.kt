package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the pre-login explainer dialogs added in front of Claude Code's and Antigravity's own
 * sign-in flows: AndCode must never start the CLI's sign-in on its own, only after the user taps
 * through the explainer, and cancelling must leave the existing auth flow untouched.
 */
@RunWith(AndroidJUnit4::class)
class AgentAuthDialogsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun claudeSignInShowsExplainerAndCancelDoesNotStartAuth() {
        var signInCalls = 0
        composeRule.setContent {
            ClaudeCodeCard(
                claude = ClaudeCodeUiState(installed = true),
                onInstall = {},
                onUpdate = {},
                onSelectPermissionMode = {},
                onSignIn = { signInCalls++ },
                onSubmitCode = {},
                onCancelSignIn = {},
                onSignOut = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.claude_sign_in_button)).performClick()
        composeRule.onNodeWithText(string(R.string.claude_pre_auth_dialog_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertEquals(0, signInCalls) }
    }

    @Test
    fun claudeSignInContinueStartsExistingAuthFlow() {
        var signInCalls = 0
        composeRule.setContent {
            ClaudeCodeCard(
                claude = ClaudeCodeUiState(installed = true),
                onInstall = {},
                onUpdate = {},
                onSelectPermissionMode = {},
                onSignIn = { signInCalls++ },
                onSubmitCode = {},
                onCancelSignIn = {},
                onSignOut = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.claude_sign_in_button)).performClick()
        composeRule.onNodeWithText(string(R.string.pre_auth_dialog_continue)).performClick()

        composeRule.runOnIdle { assertEquals(1, signInCalls) }
    }

    @Test
    fun antigravitySignInShowsExplainerAndCancelDoesNotStartAuth() {
        var signInCalls = 0
        composeRule.setContent {
            AntigravityCard(
                antigravity = AntigravityControllerState(installed = true),
                onInstall = {},
                onUpdate = {},
                onSelectPermissionMode = {},
                onSignIn = { signInCalls++ },
                onSubmitCode = {},
                onCancelSignIn = {},
                onSignOut = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.antigravity_sign_in_button)).performClick()
        composeRule.onNodeWithText(string(R.string.antigravity_pre_auth_dialog_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertEquals(0, signInCalls) }
    }

    @Test
    fun antigravitySignInContinueStartsExistingAuthFlow() {
        var signInCalls = 0
        composeRule.setContent {
            AntigravityCard(
                antigravity = AntigravityControllerState(installed = true),
                onInstall = {},
                onUpdate = {},
                onSelectPermissionMode = {},
                onSignIn = { signInCalls++ },
                onSubmitCode = {},
                onCancelSignIn = {},
                onSignOut = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.antigravity_sign_in_button)).performClick()
        composeRule.onNodeWithText(string(R.string.pre_auth_dialog_continue)).performClick()

        composeRule.runOnIdle { assertEquals(1, signInCalls) }
    }

    @Test
    fun claudeFullAccessShowsRiskWarningAndCancelKeepsPreviousMode() {
        var selected: com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode? = null
        composeRule.setContent {
            ClaudeCodeCard(
                claude = ClaudeCodeUiState(installed = true),
                onInstall = {},
                onUpdate = {},
                onSelectPermissionMode = { selected = it },
                onSignIn = {},
                onSubmitCode = {},
                onCancelSignIn = {},
                onSignOut = {},
                onOpenUrl = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.claude_permission_full_access)).performClick()
        composeRule.onNodeWithText(string(R.string.risk_warning_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertNull(selected) }
    }
}
