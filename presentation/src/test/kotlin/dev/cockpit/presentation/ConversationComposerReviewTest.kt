package dev.cockpit.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.HomeProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.AgentSummaryProjection
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationProviderRouteState
import dev.cockpit.projection.model.DraftProjection
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationComposerReviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<CockpitShellTestActivity>()

    @Test fun restorationPrefersCurrentDestinationAndInvalidFactsFailClosed() {
        val conversation = ConversationId("a")
        val current = ConversationMessageDestination(conversation, ConversationRevision(3))
        val stale = ConversationMessageDestination(conversation, ConversationRevision(2))
        val foreign = ConversationMessageDestination(ConversationId("b"), ConversationRevision(3))
        val sent = mutableListOf<ConversationMessageDestination>()
        var projection by mutableStateOf(projection(current, listOf(DraftProjection(current, "current"), DraftProjection(stale, "stale"))))
        composeRule.setContent {
            ConversationComposer(projection, "Ada", onSaveDraft = { _, _ -> true }, onSendMessage = { destination, _ -> sent += destination; true }, onBack = { false })
        }

        composeRule.onNodeWithText("current").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(current), sent)

        composeRule.runOnIdle { projection = projection.copy(drafts = listOf(DraftProjection(foreign, "foreign"), DraftProjection(foreign, "duplicate"))) }
        composeRule.onNodeWithText("Composer destination is invalid. Actions are disabled.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Save draft").assertIsNotEnabled()
        assertEquals(listOf(current), sent)
    }

    @Test fun successfulSendRebindsWhenAuthoritativeProjectionAdvances() {
        val conversation = ConversationId("a")
        val initial = ConversationMessageDestination(conversation, ConversationRevision(3))
        val advanced = ConversationMessageDestination(conversation, ConversationRevision(4))
        val sent = mutableListOf<Pair<ConversationMessageDestination, String>>()
        var projection by mutableStateOf(projection(initial, listOf(DraftProjection(initial, "first"))))
        composeRule.setContent {
            ConversationComposer(projection, "Ada", onSaveDraft = { _, _ -> true }, onSendMessage = { destination, text ->
                sent += destination to text
                projection = projection(advanced, emptyList())
                true
            }, onBack = { false })
        }

        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Compose message for Ada").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("second")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(initial to "first", advanced to "second"), sent)
    }

    @Test fun successfulHeldSendRebindsAfterProjectionAdvancesBeforeCallbackReturns() {
        val conversation = ConversationId("held-send")
        val initial = ConversationMessageDestination(conversation, ConversationRevision(3))
        val advanced = ConversationMessageDestination(conversation, ConversationRevision(4))
        val completion = CompletableDeferred<Boolean>()
        val sent = mutableListOf<Pair<ConversationMessageDestination, String>>()
        var projection by mutableStateOf(projection(initial, listOf(DraftProjection(initial, "first"))))
        composeRule.setContent {
            ConversationComposer(projection, "Ada", onSaveDraft = { _, _ -> true }, onSendMessage = { destination, text ->
                sent += destination to text
                completion.await()
            }, onBack = { false })
        }

        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.runOnIdle { projection = projection(advanced, emptyList()) }
        composeRule.waitForIdle()
        completion.complete(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Compose message for Ada").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("second")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(initial to "first", advanced to "second"), sent)
    }

    @Test fun unavailableOrChangedRouteDisablesSendButKeepsDraftSaveAvailable() {
        val conversation = ConversationId("route-guard")
        val destination = ConversationMessageDestination(conversation, ConversationRevision(3))
        var projection by mutableStateOf(
            projection(destination, listOf(DraftProjection(destination, "keep draft"))).copy(
                providerRouteState = ConversationProviderRouteState.MISSING,
            ),
        )
        var saves = 0
        var sends = 0
        composeRule.setContent {
            ConversationComposer(
                projection,
                "Ada",
                onSaveDraft = { _, _ -> saves += 1; true },
                onSendMessage = { _, _ -> sends += 1; true },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Save draft").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        composeRule.runOnIdle {
            projection = projection.copy(
                providerRouteState = ConversationProviderRouteState.REVISION_MISMATCH,
            )
        }
        composeRule.onNodeWithContentDescription("Save draft").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Save draft").performClick()
        composeRule.waitForIdle()

        assertEquals(1, saves)
        assertEquals(0, sends)
    }

    @Test fun successfulSendFollowsMultipleAdvancesOnlyUntilNewEditFreezesDestination() {
        val conversation = ConversationId("multi-advance")
        val initial = ConversationMessageDestination(conversation, ConversationRevision(3))
        val afterUser = ConversationMessageDestination(conversation, ConversationRevision(4))
        val afterAgent = ConversationMessageDestination(conversation, ConversationRevision(5))
        val externalAdvance = ConversationMessageDestination(conversation, ConversationRevision(6))
        var projection by mutableStateOf(
            projection(initial, listOf(DraftProjection(initial, "send once"))),
        )
        composeRule.setContent {
            ConversationComposer(
                projection,
                "Ada",
                onSaveDraft = { _, _ -> true },
                onSendMessage = { _, _ ->
                    projection = projection(afterUser, emptyList())
                    true
                },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { projection = projection(afterAgent, emptyList()) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Compose message for Ada").assertIsEnabled()

        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("unsent")
        composeRule.runOnIdle { projection = projection(externalAdvance, emptyList()) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("unsent").assertIsDisplayed()
        composeRule.onNodeWithText("Draft destination is stale. Send is disabled.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun conversationRouteProjectionIdentityMismatchFailsClosed() {
        val agent = AgentId("agent")
        val requested = ConversationId("requested")
        val foreign = ConversationId("foreign")
        val requestedDestination = ConversationMessageDestination(requested, ConversationRevision(3))
        val foreignDestination = ConversationMessageDestination(foreign, ConversationRevision(3))
        var saves = 0
        var sends = 0
        val detail = AgentDetailProjection(agent, "Ada", 1, ArchiveProjectionState.ACTIVE, listOf(ConversationSummaryProjection(requested, ConversationRevision(3), ArchiveProjectionState.ACTIVE)))
        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agent, "Ada", revision = 1))),
                agentDetail = { detail },
                conversation = { projection(foreignDestination, emptyList()) },
                onSaveDraft = { _, _ -> saves += 1; true },
                onSendMessage = { _, _ -> sends += 1; true },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation requested").performClick()
        composeRule.onNodeWithText("Conversation is not available").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Save draft").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Send message").assertCountEquals(0)
        assertEquals(0, saves)
        assertEquals(0, sends)
    }

    @Test fun agentRouteDetailIdentityMismatchFailsClosed() {
        val requested = AgentId("requested")
        val foreign = AgentId("foreign")
        val foreignDetail = AgentDetailProjection(foreign, "Foreign", 1, ArchiveProjectionState.ACTIVE, emptyList())
        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(requested, "Ada", revision = 1))),
                agentDetail = { foreignDetail },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithText("Agent is not available").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Open conversation switcher").assertCountEquals(0)
    }
    @Test fun rapidCompetingActionsStartOnlyOneBeforeRecomposition() {
        val gate = ConversationActionGate()
        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.finish()
        assertTrue(gate.tryBegin())
    }
    @Test fun inFlightSendDisablesConflictingActionsUntilCompletion() {
        val destination = ConversationMessageDestination(ConversationId("send"), ConversationRevision(3))
        val gate = CompletableDeferred<Boolean>()
        var sends = 0
        composeRule.setContent {
            ConversationComposer(
                projection(destination, listOf(DraftProjection(destination, "send"))),
                "Ada",
                onSaveDraft = { _, _ -> true },
                onSendMessage = { _, _ -> sends += 1; gate.await() },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.runOnIdle { assertEquals(1, sends) }
        composeRule.onNodeWithContentDescription("Save draft").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Navigate up").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()

        gate.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Send message").assertIsEnabled()
        assertEquals(1, sends)
    }

    @Test fun cancellationRethrowsAndReleasesActionGateInFinally() {
        val gate = ConversationActionGate()
        assertTrue(gate.tryBegin())

        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                gate.finishAfter { throw kotlinx.coroutines.CancellationException("cancelled") }
            }
        }

        assertTrue(gate.tryBegin())
    }

    @Test fun inFlightSaveFailurePreservesDestinationTextAndReenablesActions() {
        val destination = ConversationMessageDestination(ConversationId("save"), ConversationRevision(3))
        val gate = CompletableDeferred<Boolean>()
        var saves = 0
        composeRule.setContent {
            ConversationComposer(
                projection(destination, listOf(DraftProjection(destination, "keep"))),
                "Ada",
                onSaveDraft = { _, _ -> saves += 1; gate.await() },
                onSendMessage = { _, _ -> true },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Save draft").performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Navigate up").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Save draft").assertIsNotEnabled()

        gate.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft could not be saved. Text is preserved.").assertIsDisplayed()
        composeRule.onNodeWithText("keep").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save draft").assertIsEnabled()
        assertEquals(1, saves)
    }

    @Test fun saveExceptionPreservesDestinationTextReportsFailureAndReleasesGate() {
        val destination = ConversationMessageDestination(ConversationId("save-exception"), ConversationRevision(3))
        val saves = mutableListOf<Pair<ConversationMessageDestination, String>>()
        composeRule.setContent {
            ConversationComposer(
                projection(destination, listOf(DraftProjection(destination, "keep save"))),
                "Ada",
                onSaveDraft = { actionDestination, text ->
                    saves += actionDestination to text
                    throw IllegalStateException("storage unavailable")
                },
                onSendMessage = { _, _ -> true },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Save draft").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(destination to "keep save"), saves)
        composeRule.onNodeWithText("Draft could not be saved. Text is preserved.").assertIsDisplayed()
        composeRule.onNodeWithText("keep save").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save draft").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Navigate up").assertIsEnabled()
    }

    @Test fun sendExceptionPreservesDestinationTextReportsFailureAndReleasesGate() {
        val destination = ConversationMessageDestination(ConversationId("send-exception"), ConversationRevision(3))
        val sends = mutableListOf<Pair<ConversationMessageDestination, String>>()
        composeRule.setContent {
            ConversationComposer(
                projection(destination, listOf(DraftProjection(destination, "keep send"))),
                "Ada",
                onSaveDraft = { _, _ -> true },
                onSendMessage = { actionDestination, text ->
                    sends += actionDestination to text
                    throw IllegalStateException("transport unavailable")
                },
                onBack = { false },
            )
        }

        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(destination to "keep send"), sends)
        composeRule.onNodeWithText("Message could not be sent. Text is preserved.").assertIsDisplayed()
        composeRule.onNodeWithText("keep send").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save draft").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Navigate up").assertIsEnabled()
    }

    @Test fun failedNonemptyBackDoesNotNavigateAndPreservesDraft() {
        val agent = AgentId("agent")
        val conversation = ConversationId("back")
        val destination = ConversationMessageDestination(conversation, ConversationRevision(3))
        val gate = CompletableDeferred<Boolean>()
        var saves = 0
        val detail = AgentDetailProjection(
            id = agent,
            name = "Ada",
            revision = 1,
            archiveState = ArchiveProjectionState.ACTIVE,
            conversations = listOf(ConversationSummaryProjection(conversation, destination.expectedConversationRevision, ArchiveProjectionState.ACTIVE)),
        )
        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agent, "Ada", revision = 1))),
                agentDetail = { requested -> detail.takeIf { requested == agent.value } },
                conversation = { id -> projection(destination, emptyList()).takeIf { id == conversation.value } },
                onSaveDraft = { _, _ -> saves += 1; gate.await() },
                onSendMessage = { _, _ -> true },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation back").performClick()
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("keep")
        composeRule.onNodeWithContentDescription("Navigate up").performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
        composeRule.onNodeWithContentDescription("Save draft").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()

        gate.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft could not be saved. Text is preserved.").assertIsDisplayed()
        composeRule.onNodeWithText("keep").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Compose message for Ada").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate up").assertIsEnabled()
    }
    @Test fun emptyBackPopsExactlyOneProductionNavigationLevel() {
        val agent = AgentId("agent")
        val conversation = ConversationId("empty-back")
        val destination = ConversationMessageDestination(conversation, ConversationRevision(3))
        val detail = AgentDetailProjection(agent, "Ada", 1, ArchiveProjectionState.ACTIVE, listOf(ConversationSummaryProjection(conversation, destination.expectedConversationRevision, ArchiveProjectionState.ACTIVE)))
        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agent, "Ada", revision = 1))),
                agentDetail = { detail },
                conversation = { projection(destination, emptyList()) },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation empty-back").performClick()
        composeRule.onNodeWithContentDescription("Navigate up").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open conversation switcher").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Open agent Ada").assertCountEquals(0)
    }

    @Test fun nonemptyBackSavesOnceThenPopsExactlyOneProductionNavigationLevel() {
        val agent = AgentId("agent")
        val conversation = ConversationId("nonempty-back")
        val destination = ConversationMessageDestination(conversation, ConversationRevision(3))
        val save = CompletableDeferred<Boolean>()
        var saves = 0
        val detail = AgentDetailProjection(agent, "Ada", 1, ArchiveProjectionState.ACTIVE, listOf(ConversationSummaryProjection(conversation, destination.expectedConversationRevision, ArchiveProjectionState.ACTIVE)))
        composeRule.setContent {
            CockpitRoot(
                appName = "Cockpit",
                homeProjection = HomeProjection(listOf(AgentSummaryProjection(agent, "Ada", revision = 1))),
                agentDetail = { detail },
                conversation = { projection(destination, emptyList()) },
                onSaveDraft = { _, _ -> saves += 1; save.await() },
            )
        }

        composeRule.onNodeWithContentDescription("Open agent Ada").performClick()
        composeRule.onNodeWithContentDescription("Open conversation switcher").performClick()
        composeRule.onNodeWithContentDescription("Open conversation nonempty-back").performClick()
        composeRule.onNodeWithContentDescription("Compose message for Ada").performTextInput("keep back")
        composeRule.onNodeWithContentDescription("Navigate up").performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
        composeRule.onNodeWithContentDescription("Navigate up").assertIsNotEnabled()

        save.complete(true)
        composeRule.waitForIdle()

        assertEquals(1, saves)
        composeRule.onNodeWithContentDescription("Open conversation switcher").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Open agent Ada").assertCountEquals(0)
    }

    private fun projection(destination: ConversationMessageDestination, drafts: List<DraftProjection>) = ConversationProjection(
        id = destination.conversationId,
        agentId = AgentId("agent"),
        revision = destination.expectedConversationRevision,
        messageDestination = destination,
        archiveState = ArchiveProjectionState.ACTIVE,
        drafts = drafts,
        timeline = emptyList(),
        providerRouteState = ConversationProviderRouteState.READY,
    )
}
