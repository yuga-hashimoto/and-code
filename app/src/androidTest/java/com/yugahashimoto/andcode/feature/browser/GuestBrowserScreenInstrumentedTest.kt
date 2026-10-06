package com.yugahashimoto.andcode.feature.browser

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestBrowserScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun webViewDoesNotPaintOverTheAddressBarInLightTheme() {
        checkAddressBar(dark = false)
    }

    @Test
    fun webViewDoesNotPaintOverTheAddressBarInDarkTheme() {
        checkAddressBar(dark = true)
    }

    private fun checkAddressBar(dark: Boolean) {
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                GuestBrowserScreen(initialUrl = "", onBack = {})
            }
        }

        lateinit var webView: WebView
        composeRule.runOnIdle {
            webView = requireNotNull(composeRule.activity.window.decorView.findWebView())
            webView.setBackgroundColor(Color.MAGENTA)
            webView.loadDataWithBaseURL(null, "<html style='background:#ff00ff'></html>", "text/html", "UTF-8", null)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            var loaded = false
            composeRule.runOnIdle { loaded = webView.progress == 100 }
            loaded
        }

        val addressBar = composeRule.onNode(hasSetTextAction())
        addressBar.assertIsDisplayed().performClick().performTextReplacement("https://example.com/")
        addressBar.assertIsFocused()
        val goLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.guest_browser_go)
        composeRule.onNodeWithText(goLabel).assertIsDisplayed()

        // A semantic visibility assertion alone misses #370: the input still exists and takes
        // focus, while the native WebView paints over it. Sample the actual device framebuffer.
        val pageLocation = IntArray(2)
        var pageCenterX = 0
        composeRule.runOnIdle {
            webView.getLocationOnScreen(pageLocation)
            pageCenterX = pageLocation[0] + webView.width / 2
            webView.invalidate()
        }
        composeRule.waitForIdle()
        val bounds = addressBar.fetchSemanticsNode().boundsInWindow
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            assertEquals(
                "The test page must actually be drawn before checking for overlap",
                Color.MAGENTA,
                screenshot.getPixel(pageCenterX, pageLocation[1] + 4),
            )
            assertNotEquals(
                "The WebView background must not cover the editable address bar",
                Color.MAGENTA,
                screenshot.getPixel((bounds.right - 12).toInt(), bounds.center.y.toInt()),
            )
        } finally {
            screenshot.recycle()
        }
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findWebView()?.let { return it }
            }
        }
        return null
    }
}
