package com.yugahashimoto.andcode.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Legal & Privacy screen must be reachable and every document on it must render from the
 * bundled app asset - no network call is made anywhere in this path, so this doubles as coverage
 * that the documents are available offline.
 */
@RunWith(AndroidJUnit4::class)
class LegalScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun tappingEachRowOpensItsDocument() {
        var opened: LegalDocument? = null
        composeRule.setContent {
            LegalScreen(onOpenDocument = { opened = it }, onBack = {})
        }

        LegalDocument.entries.forEach { document ->
            opened = null
            composeRule.onNodeWithText(string(document.titleRes)).performClick()
            composeRule.runOnIdle { assertTrue(opened == document) }
        }
    }

    @Test
    fun privacyPolicyDocumentLoadsFromBundledAssetOffline() {
        composeRule.setContent {
            LegalDocumentScreen(document = LegalDocument.PRIVACY_POLICY, onBack = {})
        }
        composeRule.onNodeWithText(string(R.string.legal_doc_privacy_policy)).assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("AndCode", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun ossLicensesDocumentLoadsFromBundledAssetOffline() {
        composeRule.setContent {
            LegalDocumentScreen(document = LegalDocument.OSS_LICENSES, onBack = {})
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("PRoot", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun noticeAggregateDocumentLoadsFromBundledAssetOffline() {
        composeRule.setContent {
            LegalDocumentScreen(document = LegalDocument.NOTICE_AGGREGATE, onBack = {})
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Apache Software Foundation", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
