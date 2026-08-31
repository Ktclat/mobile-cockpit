package dev.cockpit.mobile

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        captureEvidence("empty")
        compose.onNodeWithContentDescription("Create your first Agent").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Agent name").performTextInput("Ada")
        compose.onNodeWithContentDescription("Create agent").performClick()
        waitForDescription("Create new conversation")
        compose.onNodeWithText("Ada").assertIsDisplayed()
        captureEvidence("one-agent")

        compose.onNodeWithContentDescription("Create new conversation").performClick()
        waitForDescription("Compose message for Ada")
        compose.onNodeWithContentDescription("Compose message for Ada").performTextInput("draft only in A")
        compose.onNodeWithContentDescription("Save draft").performClick()
        waitForText("Draft saved")
        compose.onNodeWithContentDescription("Open agent detail").performClick()
        waitForDescription("Create new conversation")

        compose.onNodeWithContentDescription("Create new conversation").performClick()
        waitForDescription("Compose message for Ada")
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)
        val messageB =
            "message only in B — long content remains readable and scrollable across the persisted timeline. ".repeat(6)
        compose.onNodeWithContentDescription("Compose message for Ada").performTextInput(messageB)
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("Debug reply: $messageB")
        waitForTextDisplayed(messageB)
        compose.onNodeWithText("Debug reply: $messageB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Configure a model provider").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        captureEvidence("long-message")

        compose.onNodeWithContentDescription("Open conversation switcher").performClick()
        waitForDescription("Open conversation")
        compose.onNodeWithText("Current", substring = true).assertIsDisplayed()
        captureEvidence("multiple-conversations")
        compose.onNodeWithContentDescription("Open conversation").performClick()
        waitForText("draft only in A")
        compose.onNodeWithText("draft only in A").assertIsDisplayed()
        compose.onAllNodesWithText(messageB).assertCountEquals(0)
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("Debug reply: draft only in A")
        waitForTextDisplayed("draft only in A")
        compose.onNodeWithText("Debug reply: draft only in A").performScrollTo().assertIsDisplayed()

        val recoveryDraft = "unsent draft survives recreation only in A"
        compose.onNodeWithContentDescription("Compose message for Ada").performTextInput(recoveryDraft)
        compose.onNodeWithContentDescription("Save draft").performClick()
        waitForText("Draft saved")
        compose.onNodeWithContentDescription("Archive conversation").performClick()
        waitForDescription("Restore conversation")

        compose.activityRule.scenario.recreate()
        waitForDescription("Restore conversation")
        waitForDescription("Open conversation")
        compose.onNodeWithText("Ada").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open conversation").performClick()
        waitForText("Debug reply: $messageB")
        compose.onNodeWithText(messageB).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)
        compose.onAllNodesWithText(recoveryDraft).assertCountEquals(0)

        compose.onNodeWithContentDescription("Open agent detail").performClick()
        waitForDescription("Restore conversation")
        compose.onNodeWithContentDescription("Restore conversation").performClick()
        waitForText(recoveryDraft)
        compose.onNodeWithText(recoveryDraft).assertIsDisplayed()
        compose.onNodeWithText("draft only in A").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(messageB).assertCountEquals(0)

        compose.onNodeWithContentDescription("Open conversation switcher").performClick()
        waitForDescription("Open conversation")
        compose.onNodeWithContentDescription("Open conversation").performClick()
        waitForText("Debug reply: $messageB")
        compose.onNodeWithText(messageB).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("draft only in A").assertCountEquals(0)
        compose.onAllNodesWithText(recoveryDraft).assertCountEquals(0)

        try {
            runShell("cmd uimode night yes")
            compose.activityRule.scenario.recreate()
            waitForDescription("Cockpit dark theme")
            waitForText(messageB)
            captureEvidence("dark-theme")

            runShell("cmd uimode night no")
            runShell("settings put system font_scale 2.0")
            compose.activityRule.scenario.recreate()
            waitForDescription("Cockpit light theme")
            waitForFontScale(2f)
            waitForText(messageB)
            captureEvidence("font-scale-200")
        } finally {
            runShell("cmd uimode night no")
            runShell("settings put system font_scale 1.0")
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

    private fun waitForTextDisplayed(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess
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
        runShell("cmd uimode night no")
        runShell("settings put system font_scale 1.0")
        compose.activityRule.scenario.recreate()
        waitForDescription("Cockpit light theme")
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
