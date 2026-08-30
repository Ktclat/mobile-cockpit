package dev.cockpit.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.AgentSummaryProjection
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.DraftProjection
import dev.cockpit.projection.model.HomeProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationComposerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CockpitShellTestActivity>()

    @Test
    fun navigationCannotRedirectDraft() {
        val agent = AgentId("agent-ada")
        val conversationA = ConversationId("conversation-a")
        val conversationB = ConversationId("conversation-b")
        val destinationA = ConversationMessageDestination(conversationA, ConversationRevision(3))
        val destinationB = ConversationMessageDestination(conversationB, ConversationRevision(7))
        val saved = mutableListOf<Pair<ConversationMessageDestination, String>>()
        val sent = mutableListOf<Pair<ConversationMessageDestination, String>>()
        var rejectA = true
        var projections by mutableStateOf(
            mapOf(
                conversationA to projection(conversationA, agent, destinationA),
                conversationB to projection(conversationB, agent, destinationB),
            ),
        )
        val detail = AgentDetailProjection(
            id = agent,
            name = "Ada",
            revision = 1,
            archiveState = ArchiveProjectionState.ACTIVE,
            conversations = listOf(
                ConversationSummaryProjection(conversationA, destinationA.expectedConversationRevision, ArchiveProjectionState.ACTIVE),
                ConversationSummaryProjection(conversationB, destinationB.expectedConversationRevision, ArchiveProjectionState.ACTIVE),
            ),
        )

        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agent, "Ada", revision = 1))),
                agentDetail = { requested -> detail.takeIf { requested == agent.value } },
                conversation = { id -> projections[ConversationId(id)] },
                onSaveDraft = { destination, text ->
                    saved += destination to text
                    projections = projections + (destination.conversationId to projections.getValue(destination.conversationId).copy(
                        drafts = projections.getValue(destination.conversationId).drafts.filterNot { it.destination == destination } + DraftProjection(destination, text),
                    ))
                    true
                },
                onSendMessage = { destination, text ->
                    sent += destination to text
                    !(destination == destinationA && rejectA).also { if (destination == destinationA) rejectA = false }
                },
            )
        }

        openConversation("Ada", conversationA)
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("draft for A")
        composeRule.onNodeWithContentDescription("Navigate up").performClick()

        assertEquals(listOf(destinationA to "draft for A"), saved)
        assertFalse(sent.isNotEmpty())

        switchConversation(conversationB)
        composeRule.onAllNodesWithText("draft for A").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("draft for B")
        composeRule.onNodeWithContentDescription("Save draft").performClick()
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertEquals(destinationB to "draft for B", saved.last())
        assertEquals(destinationB to "draft for B", sent.single())

        composeRule.onNodeWithContentDescription("Navigate up").performClick()
        switchConversation(conversationA)
        composeRule.onNodeWithText("draft for A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertEquals(destinationA to "draft for A", sent.last())
        composeRule.onNodeWithText("draft for A").assertIsDisplayed()

        composeRule.runOnIdle {
            projections = projections + (conversationA to projections.getValue(conversationA).copy(
                revision = ConversationRevision(4),
                messageDestination = ConversationMessageDestination(conversationA, ConversationRevision(4)),
            ))
        }

        composeRule.onNodeWithText("Draft destination is stale. Send is disabled.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        assertFalse(sent.any { it.first == ConversationMessageDestination(conversationA, ConversationRevision(4)) })
    }

    private fun openConversation(agentName: String, conversation: ConversationId) {
        composeRule.onNodeWithContentDescription("Open agent $agentName").performClick()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation ${conversation.value}").performClick()
    }

    private fun switchConversation(conversation: ConversationId) {
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation ${conversation.value}").performClick()
    }

    private fun projection(
        id: ConversationId,
        agent: AgentId,
        destination: ConversationMessageDestination,
    ) = ConversationProjection(
        id = id,
        agentId = agent,
        revision = destination.expectedConversationRevision,
        messageDestination = destination,
        archiveState = ArchiveProjectionState.ACTIVE,
        drafts = emptyList(),
        timeline = emptyList(),
    )
}
