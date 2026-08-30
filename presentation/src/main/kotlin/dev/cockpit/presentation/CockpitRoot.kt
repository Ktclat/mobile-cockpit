package dev.cockpit.presentation

import androidx.compose.runtime.Composable
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection

@Composable
fun CockpitRoot(
    appName: String,
    homeProjection: HomeProjection = HomeProjection(emptyList()),
    agentDetail: (String) -> AgentDetailProjection? = { null },
    conversation: (String) -> ConversationProjection? = { null },
) {
    AgentNavigationRoot(appName, homeProjection, agentDetail, conversation)
}
