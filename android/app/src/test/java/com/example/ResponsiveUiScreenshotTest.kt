package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.components.OpusBottomNav
import com.example.ui.components.OpusHeader
import com.example.ui.components.OpusNavTab
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveUiScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h568dp-xxhdpi", sdk = [34])
    fun compact_phone_keeps_workflow_chrome_visible() {
        renderWorkflowChrome()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/ui-compact-phone.png")
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xxhdpi", sdk = [34])
    fun modern_phone_keeps_workflow_chrome_visible() {
        renderWorkflowChrome()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/ui-modern-phone.png")
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi", sdk = [34])
    fun large_android_keeps_workflow_chrome_visible() {
        renderWorkflowChrome()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/ui-large-android.png")
    }

    private fun renderWorkflowChrome() {
        composeTestRule.setContent {
            MyApplicationTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    OpusHeader(
                        onApiKeyClick = {},
                        hasCustomApiKey = true
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OpusBottomNav(
                        currentTab = OpusNavTab.HOME,
                        onTabSelected = {}
                    )
                }
            }
        }
    }
}
