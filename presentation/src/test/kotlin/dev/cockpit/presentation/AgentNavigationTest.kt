package dev.cockpit.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.AgentSummaryProjection
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.HomeProjection
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CockpitShellTestActivity>()

    @Test
    fun homeAgentsDetailAndSwitcherAreReachable() {
        val currentAgent = AgentId("agent-current")
        val otherAgent = AgentId("agent-other")
        val firstConversation = ConversationId("conversation-first")
        val secondConversation = ConversationId("conversation-second")
        val otherConversation = ConversationId("conversation-other")
        val home = HomeProjection(
            agents = listOf(
                AgentSummaryProjection(currentAgent, "Ada", revision = 2),
                AgentSummaryProjection(otherAgent, "Bert", revision = 3),
            ),
        )
        val currentAgentDetail = AgentDetailProjection(
            id = currentAgent,
            name = "Ada",
            revision = 2,
            archiveState = ArchiveProjectionState.ACTIVE,
            conversations = listOf(
                ConversationSummaryProjection(
                    id = firstConversation,
                    revision = ConversationRevision(4),
                    archiveState = ArchiveProjectionState.ACTIVE,
                ),
                ConversationSummaryProjection(
                    id = secondConversation,
                    revision = ConversationRevision(5),
                    archiveState = ArchiveProjectionState.ACTIVE,
                ),
            ),
        )
        val conversations = mapOf(
            firstConversation to conversation(firstConversation, currentAgent, ConversationRevision(4)),
            secondConversation to conversation(secondConversation, currentAgent, ConversationRevision(5)),
            otherConversation to conversation(otherConversation, otherAgent, ConversationRevision(1)),
        )

        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = home,
                agentDetail = { agentId -> if (agentId == currentAgent.value) currentAgentDetail else null },
                conversation = { conversationId -> conversations[ConversationId(conversationId)] },
            )
        }

        composeRule.onNodeWithContentDescription("Open Agents").performClick()
        composeRule.onNodeWithContentDescription("Open Activity").performClick()
        composeRule.onNodeWithText("Activity is not configured yet").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Settings are not configured yet").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Agents").performClick()
        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation conversation-first").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open conversation conversation-second").performClick()

        composeRule.onNodeWithText("Conversation conversation-second").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Root navigation").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Navigate up").performClick()

        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation conversation-first").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open conversation conversation-second").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Open conversation conversation-other").assertCountEquals(0)
    }

    @Test
    fun opaqueIdsRoundTripThroughNavigationRoutes() {
        assertOpaqueRouteRoundTrip(
            agentIdValue = "agent /?#% \u65e5\u672c\u8a9e",
            conversationIdValue = "conversation /?#% \u6703\u8a71",
        )
    }

    @Test
    fun emptyIdsRoundTripThroughNavigationRoutes() {
        assertOpaqueRouteRoundTrip(agentIdValue = "", conversationIdValue = "")
    }

    @Test
    fun routeTokensAreCanonicalAndFailClosed() {
        listOf("", "agent /?#% \u65e5\u672c\u8a9e").forEach { value ->
            assertEquals(value, NavigationRouteToken.decode(NavigationRouteToken.encode(value)))
        }
        assertNull(NavigationRouteToken.decode("v1_!"))
        assertNull(NavigationRouteToken.decode("v2_YQ"))
    }

    private fun assertOpaqueRouteRoundTrip(
        agentIdValue: String,
        conversationIdValue: String,
    ) {
        val agentId = AgentId(agentIdValue)
        val conversationId = ConversationId(conversationIdValue)
        val agentName = "Opaque agent"
        val detail = AgentDetailProjection(
            id = agentId,
            name = agentName,
            revision = 1,
            archiveState = ArchiveProjectionState.ACTIVE,
            conversations = listOf(
                ConversationSummaryProjection(
                    id = conversationId,
                    revision = ConversationRevision(1),
                    archiveState = ArchiveProjectionState.ACTIVE,
                ),
            ),
        )
        val expectedConversation = conversation(conversationId, agentId, ConversationRevision(1))
        var requestedAgentId: String? = null
        var requestedConversationId: String? = null

        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agentId, agentName, revision = 1))),
                agentDetail = { requested ->
                    requestedAgentId = requested
                    detail.takeIf { requested == agentIdValue }
                },
                conversation = { requested ->
                    requestedConversationId = requested
                    expectedConversation.takeIf { requested == conversationIdValue }
                },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent $agentName").performClick()
        composeRule.waitForIdle()
        assertEquals(agentIdValue, requestedAgentId)
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation $conversationIdValue").performClick()
        composeRule.waitForIdle()
        assertEquals(conversationIdValue, requestedConversationId)
        composeRule.onNodeWithText("Conversation $conversationIdValue").assertIsDisplayed()
    }

    private fun conversation(
        id: ConversationId,
        agentId: AgentId,
        revision: ConversationRevision,
    ) = ConversationProjection(
        id = id,
        agentId = agentId,
        revision = revision,
        messageDestination = ConversationMessageDestination(id, revision),
        archiveState = ArchiveProjectionState.ACTIVE,
        drafts = emptyList(),
        timeline = emptyList(),
    )
}
