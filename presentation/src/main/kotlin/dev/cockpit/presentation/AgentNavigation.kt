package dev.cockpit.presentation

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection

@Composable
internal fun AgentNavigationRoot(
    appName: String,
    homeProjection: HomeProjection,
    agentDetail: (String) -> AgentDetailProjection?,
    conversation: (String) -> ConversationProjection?,
    onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean,
    onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean,
) {
    val navigation = rememberNavController()
    val backStackEntry by navigation.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navigation,
            startDestination = NavigationRoute.Home,
            modifier = Modifier.fillMaxHeight(0.9f),
        ) {
            composable(NavigationRoute.Home) {
                AgentListScreen(appName, homeProjection) { navigation.navigate(NavigationRoute.agent(it)) }
            }
            composable(NavigationRoute.Agents) {
                AgentListScreen("Agents", homeProjection) { navigation.navigate(NavigationRoute.agent(it)) }
            }
            composable(NavigationRoute.Activity) { EmptyState("Activity is not configured yet") }
            composable(NavigationRoute.Settings) { EmptyState("Settings are not configured yet") }
            composable(NavigationRoute.AgentPattern) { entry ->
                val id = NavigationRouteToken.decode(entry.arguments?.getString(NavigationRoute.AgentIdArgument))
                val detail = id?.let(agentDetail)
                if (detail == null || detail.id.value != id) EmptyState("Agent is not available") else {
                    AgentDetailScreen(detail) { navigation.navigate(NavigationRoute.conversation(it)) }
                }
            }
            composable(NavigationRoute.ConversationPattern) { entry ->
                val id = NavigationRouteToken.decode(entry.arguments?.getString(NavigationRoute.ConversationIdArgument))
                val projection = id?.let(conversation)
                if (projection == null || projection.id.value != id) EmptyState("Conversation is not available") else {
                    ConversationScreen(
                        projection = projection,
                        agentName = agentDetail(projection.agentId.value)?.name ?: "Agent ${projection.agentId.value}",
                        onBack = navigation::navigateUp,
                        onSaveDraft = onSaveDraft,
                        onSendMessage = onSendMessage,
                    )
                }
            }
        }
        if (currentRoute in NavigationRoute.RootRoutes) RootNavigation(navigation)
    }
}

@Composable
private fun AgentListScreen(
    heading: String,
    home: HomeProjection,
    onAgentSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BasicText(heading)
        home.agents.forEach { agent ->
            NavigationAction(agent.name, "Open agent ${agent.name}") { onAgentSelected(agent.id.value) }
        }
    }
}

@Composable
private fun AgentDetailScreen(
    detail: AgentDetailProjection,
    onConversationSelected: (String) -> Unit,
) {
    var switcherOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BasicText(detail.name)
        BasicText("Agent identity")
        NavigationAction("Conversations", "Open conversation switcher") { switcherOpen = !switcherOpen }
        if (switcherOpen) {
            detail.conversations.forEach { summary ->
                NavigationAction(
                    "Conversation ${summary.id.value}",
                    "Open conversation ${summary.id.value}",
                ) { onConversationSelected(summary.id.value) }
            }
        }
        BasicText("Configuration summary is not configured yet")
    }
}

@Composable
private fun ConversationScreen(
    projection: ConversationProjection,
    agentName: String,
    onBack: () -> Boolean,
    onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean,
    onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BasicText(agentName)
        BasicText("Conversation ${projection.id.value}")
        BasicText("Workspace: not configured")
        BasicText("Conversation is active")
        ConversationComposer(projection, agentName, onSaveDraft, onSendMessage, onBack)
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) { BasicText(message) }
}

@Composable
private fun RootNavigation(navigation: NavHostController) {
    Row(modifier = Modifier.semantics { contentDescription = "Root navigation" }.padding(8.dp)) {
        NavigationRoute.RootRoutes.forEach { route ->
            NavigationAction(route, "Open $route") { navigation.navigate(route) { launchSingleTop = true } }
        }
    }
}

@Composable
internal fun NavigationAction(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CockpitText(
        text = label,
        action = true,
        modifier = modifier
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(8.dp),
    )
}

private object NavigationRoute {
    const val Home = "Home"
    const val Agents = "Agents"
    const val Activity = "Activity"
    const val Settings = "Settings"
    const val AgentIdArgument = "agentId"
    const val ConversationIdArgument = "conversationId"
    const val AgentPattern = "agent/{$AgentIdArgument}"
    const val ConversationPattern = "conversation/{$ConversationIdArgument}"
    val RootRoutes = listOf(Home, Agents, Activity, Settings)

    fun agent(id: String): String = "agent/${NavigationRouteToken.encode(id)}"
    fun conversation(id: String): String = "conversation/${NavigationRouteToken.encode(id)}"
}

internal object NavigationRouteToken {
    private const val VersionPrefix = "v1_"
    private val tokenPattern = Regex("v1_[A-Za-z0-9_-]*")
    private const val Base64Flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun encode(value: String): String =
        VersionPrefix + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64Flags)

    fun decode(token: String?): String? {
        if (token == null || !token.matches(tokenPattern)) return null
        return try {
            String(Base64.decode(token.removePrefix(VersionPrefix), Base64Flags), Charsets.UTF_8)
                .takeIf { encode(it) == token }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
