package dev.cockpit.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.HomeProjection
import dev.cockpit.projection.model.MessageRoleProjection
import dev.cockpit.projection.model.TimelineItemProjection
import kotlinx.coroutines.launch

@Composable
fun CockpitRoot(
    appName: String,
    agentApplicationPortHandle: Any,
    conversationApplicationPortHandle: Any,
    agentConversationQueryPortHandle: Any,
) {
    val agents = requireNotNull(agentApplicationPortHandle as? AgentApplicationPort)
    val conversations = requireNotNull(conversationApplicationPortHandle as? ConversationApplicationPort)
    val queries = requireNotNull(agentConversationQueryPortHandle as? AgentConversationQueryPort)
    CockpitAppearance {
        val home by queries.home().collectAsState(HomeProjection(emptyList()))
        val navigation = rememberNavController()
        val entry by navigation.currentBackStackEntryAsState()
        Column(Modifier.fillMaxSize()) {
            NavHost(navigation, LiveRoute.Home, Modifier.weight(1f)) {
                composable(LiveRoute.Home) { LiveHome(appName, home, navigation) }
                composable(LiveRoute.Agents) { LiveHome("Agents", home, navigation) }
                composable(LiveRoute.CreateAgent) {
                    CreateAgentScreen(agents) { navigation.navigate(LiveRoute.agent(it.value)) }
                }
                composable(LiveRoute.Activity) { LiveEmptyState("Activity is not configured yet") }
                composable(LiveRoute.Settings) { LiveEmptyState("Settings are not configured yet") }
                composable(LiveRoute.AgentPattern) { route ->
                    NavigationRouteToken.decode(route.arguments?.getString(LiveRoute.AgentIdArgument))
                        ?.let { LiveAgentDetail(AgentId(it), queries, conversations, navigation) }
                        ?: LiveEmptyState("Agent is not available")
                }
                composable(LiveRoute.ConversationPattern) { route ->
                    NavigationRouteToken.decode(route.arguments?.getString(LiveRoute.ConversationIdArgument))
                        ?.let { LiveConversation(ConversationId(it), queries, conversations, navigation) }
                        ?: LiveEmptyState("Conversation is not available")
                }
            }
            if (entry?.destination?.route in LiveRoute.RootRoutes) LiveRootNavigation(navigation)
        }
    }
}

@Composable
private fun LiveHome(appName: String, home: HomeProjection, navigation: NavHostController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CockpitText(appName)
        if (home.agents.isEmpty()) {
            NavigationAction("Create your first Agent", "Create your first Agent") {
                navigation.navigate(LiveRoute.CreateAgent)
            }
        } else {
            home.agents.forEach { agent ->
                NavigationAction(agent.name, "Open agent ${agent.name}") {
                    navigation.navigate(LiveRoute.agent(agent.id.value))
                }
            }
        }
    }
}

@Composable
private fun CreateAgentScreen(agents: AgentApplicationPort, onCreated: (AgentId) -> Unit) {
    var identity by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = LocalCockpitPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CockpitText("Create Agent")
        BasicTextField(
            identity,
            { identity = it },
            Modifier
                .fillMaxWidth()
                .background(palette.field)
                .semantics { contentDescription = "Agent name" }
                .padding(12.dp),
            textStyle = TextStyle(color = palette.foreground),
            cursorBrush = SolidColor(palette.action),
        )
        NavigationAction("Create Agent", "Create agent") {
            scope.launch {
                agents.createAgent(identity)?.let(onCreated) ?: run { failed = true }
            }
        }
        if (failed) {
            CockpitText(
                "An Agent name is required.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun LiveAgentDetail(
    agentId: AgentId,
    queries: AgentConversationQueryPort,
    actions: ConversationApplicationPort,
    navigation: NavHostController,
) {
    val detail by queries.agent(agentId).collectAsState(null)
    val scope = rememberCoroutineScope()
    val current = detail ?: return LiveEmptyState("Agent is not available")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CockpitText(current.name)
        CockpitText("Agent identity")
        CockpitText("No model provider configured")
        NavigationAction("New Conversation", "Create new conversation") {
            scope.launch {
                actions.createConversation(current.id)?.let {
                    navigation.navigate(LiveRoute.conversation(it.value))
                }
            }
        }
        current.conversations
            .filter { it.archiveState == ArchiveProjectionState.ACTIVE }
            .forEach { conversation ->
                NavigationAction("Continue ${conversation.visibleLabel()}", "Open conversation") {
                    navigation.navigate(LiveRoute.conversation(conversation.id.value))
                }
            }
        current.conversations
            .filter { it.archiveState == ArchiveProjectionState.ARCHIVED }
            .forEach { conversation ->
                NavigationAction("Restore archived ${conversation.visibleLabel()}", "Restore conversation") {
                    scope.launch {
                        if (actions.restoreConversation(conversation.id)) {
                            navigation.navigate(LiveRoute.conversation(conversation.id.value))
                        }
                    }
                }
            }
        NavigationAction("Configure a model provider", "Configure a model provider") {
            scope.launch {
                actions.configureModelProvider()
                navigation.navigate(LiveRoute.Settings)
            }
        }
    }
}

@Composable
private fun LiveConversation(
    conversationId: ConversationId,
    queries: AgentConversationQueryPort,
    actions: ConversationApplicationPort,
    navigation: NavHostController,
) {
    val projection by queries.conversation(conversationId).collectAsState(null)
    val scope = rememberCoroutineScope()
    val current = projection ?: return LiveEmptyState("Conversation is not available")
    val detail by queries.agent(current.agentId).collectAsState(null)
    val agentName = detail?.name ?: "Agent"
    var switcherOpen by remember(current.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        CockpitText(agentName)
        CockpitText(current.summary().visibleLabel())
        CockpitText("No workspace")
        CockpitText("No model provider configured")
        NavigationAction("Agent Detail", "Open agent detail") {
            navigation.navigate(LiveRoute.agent(current.agentId.value))
        }
        NavigationAction("Conversations", "Open conversation switcher") {
            switcherOpen = !switcherOpen
        }
        if (switcherOpen) {
            detail?.conversations
                ?.filter { it.id != current.id }
                ?.forEach { summary ->
                    val archived = summary.archiveState == ArchiveProjectionState.ARCHIVED
                    NavigationAction(
                        if (archived) "Archived ${summary.visibleLabel()}" else summary.visibleLabel(),
                        if (archived) "Open archived conversation" else "Open conversation",
                    ) {
                        navigation.navigate(LiveRoute.conversation(summary.id.value))
                    }
                }
            CockpitText(
                "Current ${current.summary().visibleLabel()} (${current.archiveState.name.lowercase()})",
                Modifier.semantics { contentDescription = "Current conversation" },
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = "Conversation timeline" },
        ) {
            items(current.timeline, key = { (it as TimelineItemProjection.MessageItem).message.id }) { item ->
                val message = (item as TimelineItemProjection.MessageItem).message
                val role = when (message.role) {
                    MessageRoleProjection.USER -> "You"
                    MessageRoleProjection.AGENT -> agentName
                    MessageRoleProjection.SYSTEM -> "System"
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics {
                            contentDescription =
                                "$role message, ${message.status.name.lowercase()}"
                        },
                ) {
                    CockpitText("$role · ${message.status.name.lowercase()}")
                    CockpitText(message.text)
                }
            }
        }
        NavigationAction("Configure a model provider", "Configure a model provider") {
            scope.launch {
                actions.configureModelProvider()
                navigation.navigate(LiveRoute.Settings)
            }
        }
        if (current.archiveState == ArchiveProjectionState.ACTIVE) {
            NavigationAction("Archive Conversation", "Archive conversation") {
                scope.launch {
                    if (actions.archiveConversation(current.id)) {
                        navigation.navigate(LiveRoute.agent(current.agentId.value)) {
                            launchSingleTop = true
                        }
                    }
                }
            }
            ConversationComposer(
                current,
                agentName,
                actions::saveDraft,
                actions::sendMessage,
                navigation::navigateUp,
            )
        } else {
            CockpitText("Conversation is archived")
            NavigationAction("Restore Conversation", "Restore conversation") {
                scope.launch { actions.restoreConversation(current.id) }
            }
        }
    }
}

@Composable
private fun LiveEmptyState(message: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CockpitText(message)
    }
}

@Composable
private fun LiveRootNavigation(navigation: NavHostController) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Root navigation" }
            .padding(4.dp),
    ) {
        LiveRoute.RootRoutes.forEach { route ->
            NavigationAction(route, "Open $route", Modifier.weight(1f)) {
                navigation.navigate(route) { launchSingleTop = true }
            }
        }
    }
}

private fun ConversationSummaryProjection.visibleLabel(): String =
    "Conversation ${id.value.take(8)}"

private fun ConversationProjection.summary() =
    ConversationSummaryProjection(id, revision, archiveState)

private object LiveRoute {
    const val Home = "Home"
    const val Agents = "Agents"
    const val Activity = "Activity"
    const val Settings = "Settings"
    const val CreateAgent = "create-agent"
    const val AgentIdArgument = "agentId"
    const val ConversationIdArgument = "conversationId"
    const val AgentPattern = "agent/{$AgentIdArgument}"
    const val ConversationPattern = "conversation/{$ConversationIdArgument}"
    val RootRoutes = listOf(Home, Agents, Activity, Settings)

    fun agent(id: String) = "agent/${NavigationRouteToken.encode(id)}"
    fun conversation(id: String) = "conversation/${NavigationRouteToken.encode(id)}"
}
