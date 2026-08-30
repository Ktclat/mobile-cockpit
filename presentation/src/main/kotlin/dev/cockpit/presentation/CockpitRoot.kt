package dev.cockpit.presentation

import androidx.compose.runtime.Composable
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection

@Composable
fun CockpitRoot(
    appName: String,
    homeProjection: HomeProjection = HomeProjection(emptyList()),
    agentDetail: (String) -> AgentDetailProjection? = { null },
    conversation: (String) -> ConversationProjection? = { null },
    onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean = { _, _ -> false },
    onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean = { _, _ -> false },
) {
    AgentNavigationRoot(appName, homeProjection, agentDetail, conversation, onSaveDraft, onSendMessage)
}
