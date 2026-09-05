package dev.cockpit.mobile

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationContinuityDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchesTwoConversationsWithoutLeakage() {
        normalizeEvidenceEnvironment()
        waitForText("Create your first Agent")
        captureEvidence("empty")
        compose.onNodeWithText("Create Agent").assertIsDisplayed().performClick()
        compose.onNodeWithText("Name *").performTextInput("Ada")
        compose.onNodeWithText("Continue").performClick()
        waitForText("Definition")
        compose.onNodeWithText("Continue").performClick()
        waitForText("Preview")
        compose.onNodeWithText("Create Agent").performClick()
        waitForDescription("New Conversation")
        compose.onAllNodesWithText("Ada").assertCountEquals(2)
        captureEvidence("one-agent")

        compose.onNodeWithContentDescription("New Conversation").performClick()
        waitForDescription("Compose message for Ada")
        compose.onNodeWithContentDescription("Compose message for Ada").performTextInput("draft only in A")
        compose.onNodeWithContentDescription("Save draft").performClick()
        waitForText("Draft saved")
        compose.onNodeWithContentDescription("Agent detail").performClick()
        waitForDescription("New Conversation")

        compose.onNodeWithContentDescription("New Conversation").performClick()
        waitForDescription("Compose message for Ada")
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)
        val messageB =
            "draft only in B — long content remains readable at large font sizes. ".repeat(6)
        compose.onNodeWithContentDescription("Compose message for Ada").performTextInput(messageB)
        compose.onNodeWithText("Model not connected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Save draft").assertIsEnabled().performClick()
        waitForText("Draft saved")
        captureEvidence("long-message")

        compose.onNodeWithContentDescription("Conversations").performClick()
        waitForDescription("Open conversation")
        compose.onNodeWithText("Current", substring = true).assertIsDisplayed()
        captureEvidence("multiple-conversations")
        compose.onNodeWithContentDescription("Open conversation").performClick()
        waitForText("draft only in A")
        compose.onNodeWithText("draft only in A").assertIsDisplayed()
        compose.onAllNodesWithText(messageB).assertCountEquals(0)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Archive").performClick()
        waitForText("Restore")

        compose.activityRule.scenario.recreate()
        waitForText("Restore")
        waitForText("Ready to continue")
        compose.onAllNodesWithText("Ada").assertCountEquals(2)
        compose.onNodeWithText("Ready to continue").performClick()
        waitForText(messageB)
        compose.onNodeWithText(messageB).assertIsDisplayed()
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)

        compose.onNodeWithContentDescription("Agent detail").performClick()
        waitForText("Restore")
        compose.onNodeWithText("Restore").performClick()
        waitForText("draft only in A")
        compose.onNodeWithText("draft only in A").assertIsDisplayed()
        compose.onAllNodesWithText(messageB).assertCountEquals(0)
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Conversations").performClick()
        waitForDescription("Open conversation")
        compose.onNodeWithContentDescription("Open conversation").performClick()
        waitForText(messageB)
        compose.onNodeWithText(messageB).assertIsDisplayed()
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)

        try {
            setUiPreferences(theme = "DARK")
            compose.activityRule.scenario.recreate()
            waitForDescription("Cockpit dark theme")
            waitForText(messageB)
            captureEvidence("dark-theme")

            runShell("settings put system font_scale 2.0")
            waitForFontScale(2f)
            setUiPreferences(theme = "LIGHT")
            compose.activityRule.scenario.recreate()
            waitForDescription("Cockpit light theme")
            waitForFontScale(2f)
            waitForText(messageB)
            captureEvidence("font-scale-200")
        } finally {
            runShell("settings put system font_scale 1.0")
            runCatching { setUiPreferences(theme = "SYSTEM", language = "SYSTEM") }
        }
    }

    private fun waitForDescription(description: String, count: Int = 1) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == count
        }
    }

    private fun waitForText(text: String, count: Int = 1) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().size == count
        }
    }

    private fun waitForFontScale(expected: Float) {
        compose.waitUntil(timeoutMillis = 10_000) {
            var actual = 0f
            compose.activityRule.scenario.onActivity {
                actual = it.resources.configuration.fontScale
            }
            abs(actual - expected) < 0.01f
        }
    }

    private fun normalizeEvidenceEnvironment() {
        runShell("settings put system font_scale 1.0")
        waitForFontScale(1f)
        setUiPreferences(theme = "LIGHT")
        compose.activityRule.scenario.recreate()
        waitForDescription("Cockpit light theme")
    }

    private fun setUiPreferences(theme: String, language: String = "ENGLISH") {
        compose.activityRule.scenario.onActivity { activity ->
            check(
                activity.getSharedPreferences("cockpit_ui_preferences", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("theme", theme)
                    .putString("language", language)
                    .commit(),
            ) { "UI test preferences could not be committed" }
        }
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        runShell("mkdir -p /sdcard/Download/cockpit-evidence")
        runShell("screencap -p /sdcard/Download/cockpit-evidence/$name.png")
    }

    private fun runShell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val buffer = ByteArray(1024)
            while (stream.read(buffer) >= 0) {
                // Drain to EOF so the shell command has completed before the next assertion.
            }
        }
    }
}
