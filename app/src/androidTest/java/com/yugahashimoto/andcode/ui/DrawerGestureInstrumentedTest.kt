package com.yugahashimoto.andcode.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerGestureInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipeFromTheCenterDoesNotOpenTheDrawer() {
        setDrawerContent()

        composeRule.onNodeWithTag("screen-content").performTouchInput {
            swipe(Offset(300f, 400f), Offset(700f, 400f))
        }

        composeRule.onNodeWithTag("drawer-content").assertIsNotDisplayed()
    }

    @Test
    fun swipeFromTheLeftEdgeOpensTheDrawer() {
        setDrawerContent()

        composeRule.onNodeWithTag("screen-content").performTouchInput {
            swipe(Offset(8f, 400f), Offset(500f, 400f))
        }

        composeRule.onNodeWithTag("drawer-content").assertIsDisplayed()
    }

    private fun setDrawerContent() {
        composeRule.setContent {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            ModalNavigationDrawer(
                modifier = Modifier.onlyAllowDrawerEdgeSwipe(),
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Text("Drawer", modifier = Modifier.testTag("drawer-content"))
                    }
                },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("screen-content"),
                )
            }
        }
        composeRule.waitForIdle()
    }
}
