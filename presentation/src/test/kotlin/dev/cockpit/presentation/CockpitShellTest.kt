package dev.cockpit.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cockpit.platform.android.CockpitProcessComponent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CockpitShellTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CockpitShellTestActivity>()

    @Test
    fun displaysAppNameThroughCompositionRoot() {
        val component = CockpitProcessComponent()

        composeRule.setContent {
            CockpitRoot(appName = component.shellAppName)
        }

        composeRule.onNodeWithText("Cockpit").assertIsDisplayed()
    }
}
