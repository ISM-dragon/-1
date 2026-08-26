package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Clip
import com.example.data.repository.OpusRepository
import com.example.ui.components.VideoSimPlayer
import com.example.ui.screens.ApiManagementSettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class CoreComposeScreenshotTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val repository: OpusRepository by lazy {
    OpusRepository(ApplicationProvider.getApplicationContext())
  }

  @Test
  fun home_screen_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          HomeScreen(
            repository = repository,
            onProjectCreated = {},
            onOpenProject = {},
            onUploadLocalVideo = {},
            onOpenApiKeySettings = {}
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(
      filePath = "src/test/screenshots/home_screen.png"
    )
  }

  @Test
  fun video_player_screenshot() {
    val clip = Clip(
      id = 101L,
      projectId = 1L,
      title = "Launch day highlight",
      startTimeSec = 12,
      endTimeSec = 48,
      durationSec = 36,
      viralityScore = 92,
      hookScore = 94,
      retentionScore = 90,
      emotionalScore = 88,
      shareabilityScore = 91,
      punchlineScore = 87,
      hookExplanation = "Strong opening hook",
      transcript = "The best ideas become clear when the story starts with the result.",
      animatedCaptionsJson = "[]",
      bRollPromptsJson = "[]",
      socialCopyJson = "[]"
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          VideoSimPlayer(
            clip = clip,
            selectedCaptionTheme = "Opus Neon",
            layoutType = "9:16 Full Screen",
            onLayoutChange = {}
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(
      filePath = "src/test/screenshots/video_player.png"
    )
  }

  @Test
  fun settings_screen_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          ApiManagementSettingsScreen(
            repository = repository,
            onBack = {}
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(
      filePath = "src/test/screenshots/settings_screen.png"
    )
  }
}
