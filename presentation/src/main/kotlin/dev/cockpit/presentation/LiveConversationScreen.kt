package dev.cockpit.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.domain.ConversationId
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationProviderRouteState
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.MessageProjection
import dev.cockpit.projection.model.MessageRoleProjection
import dev.cockpit.projection.model.MessageSourceProjection
import dev.cockpit.projection.model.MessageStatusProjection
import dev.cockpit.projection.model.StreamingReplyProjection
import dev.cockpit.projection.model.TimelineItemProjection
import kotlinx.coroutines.launch

@Composable
internal fun LiveConversation(
    conversationId: ConversationId,
    queries: AgentConversationQueryPort,
    actions: ConversationApplicationPort,
    navigation: NavHostController,
) {
    val projection by queries.conversation(conversationId).collectAsState(null)
    val current = projection ?: return LiveEmptyState("Conversation is not available")
    val detail by queries.agent(current.agentId).collectAsState(null)
    val agentName = detail?.name ?: "Agent"
    val conversations = detail?.conversations.orEmpty()
    var switcherOpen by remember(current.id) { mutableStateOf(false) }
    var migrationConfirmationOpen by remember(current.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val configureProvider = { navigation.navigate(LiveRoute.Models) }
    val archive = {
        scope.launch {
            if (actions.archiveConversation(current.id)) {
                navigation.navigate(LiveRoute.agent(current.agentId.value)) {
                    launchSingleTop = true
                }
            }
        }
        Unit
    }
    val restore = {
        scope.launch { actions.restoreConversation(current.id) }
        Unit
    }
    val cancelReply = {
        scope.launch { actions.cancelReply(current.id) }
        Unit
    }
    val retryReply = {
        scope.launch { actions.retryReply(current.id) }
        Unit
    }
    val migrateProviderRoute = {
        migrationConfirmationOpen = true
        Unit
    }

    if (current.archiveState == ArchiveProjectionState.ACTIVE) {
        ConversationComposerHost(
            projection = current,
            onSaveDraft = actions::saveDraft,
            onSendMessage = actions::sendMessage,
            onBack = navigation::navigateUp,
        ) { composer ->
            ConversationFrame(
                projection = current,
                agentName = agentName,
                conversations = conversations,
                switcherOpen = switcherOpen,
                onToggleSwitcher = { switcherOpen = !switcherOpen },
                onOpenConversation = {
                    navigation.navigate(LiveRoute.conversation(it.id.value))
                    switcherOpen = false
                },
                onOpenAgent = {
                    navigation.navigate(LiveRoute.agent(current.agentId.value))
                },
                onConfigureProvider = configureProvider,
                onArchive = archive,
                onRestore = restore,
                onCancelReply = cancelReply,
                onRetryReply = retryReply,
                onMigrateProviderRoute = migrateProviderRoute,
                onBack = composer.onNavigateUp,
                composer = composer,
            )
        }
    } else {
        ConversationFrame(
            projection = current,
            agentName = agentName,
            conversations = conversations,
            switcherOpen = switcherOpen,
            onToggleSwitcher = { switcherOpen = !switcherOpen },
            onOpenConversation = {
                navigation.navigate(LiveRoute.conversation(it.id.value))
                switcherOpen = false
            },
            onOpenAgent = {
                navigation.navigate(LiveRoute.agent(current.agentId.value))
            },
            onConfigureProvider = configureProvider,
            onArchive = archive,
            onRestore = restore,
            onCancelReply = cancelReply,
            onRetryReply = retryReply,
            onMigrateProviderRoute = migrateProviderRoute,
            onBack = { navigation.navigateUp() },
            composer = null,
        )
    }

    if (migrationConfirmationOpen) {
        ProviderRouteMigrationDialog(
            projection = current,
            onDismiss = { migrationConfirmationOpen = false },
            onConfirm = {
                migrationConfirmationOpen = false
                scope.launch { actions.migrateProviderRoute(current.id) }
            },
        )
    }
}

@Composable
private fun ProviderRouteMigrationDialog(
    projection: ConversationProjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val provider = projection.provider ?: return
    val acceptingRevision =
        projection.providerRouteState == ConversationProviderRouteState.REVISION_MISMATCH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                translator.text(
                    if (acceptingRevision) "API configuration changed" else "API configuration not bound",
                ),
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    translator.choose(
                        "This conversation contains existing history. After confirmation, future requests may send that history to the target below. This action only updates the conversation binding and does not send a message.",
                        "此会话包含已有历史。确认后，后续请求可能会把这些历史发送到下方目标。此操作只更新会话绑定，不会自动发送消息。",
                    ),
                )
                MigrationTargetRow(translator.choose("API configuration", "API 配置"), provider.displayName)
                MigrationTargetRow(translator.choose("Provider", "服务商"), provider.vendor)
                MigrationTargetRow(translator.choose("Target host", "目标主机"), provider.endpointOrigin)
                MigrationTargetRow(translator.choose("Protocol", "协议"), provider.protocol)
                MigrationTargetRow(translator.choose("Model", "模型"), provider.model)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    translator.choose(
                        if (acceptingRevision) {
                            "Accept the updated API configuration"
                        } else {
                            "Bind this API configuration"
                        },
                        if (acceptingRevision) {
                            "接受此 API 配置的新版本"
                        } else {
                            "绑定此 API 配置"
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(translator.choose("Cancel", "取消")) }
        },
    )
}

@Composable
private fun MigrationTargetRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConversationFrame(
    projection: ConversationProjection,
    agentName: String,
    conversations: List<ConversationSummaryProjection>,
    switcherOpen: Boolean,
    onToggleSwitcher: () -> Unit,
    onOpenConversation: (ConversationSummaryProjection) -> Unit,
    onOpenAgent: () -> Unit,
    onConfigureProvider: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onCancelReply: () -> Unit,
    onRetryReply: () -> Unit,
    onMigrateProviderRoute: () -> Unit,
    onBack: () -> Unit,
    composer: ConversationComposerUiState?,
) {
    val active = projection.archiveState == ArchiveProjectionState.ACTIVE
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = agentName,
            subtitle = projection.provider?.displayName,
            onBack = onBack,
        ) {
            HeaderIconButton(
                icon = Icons.Outlined.AccountCircle,
                contentDescription = "Agent detail",
                onClick = onOpenAgent,
            )
            HeaderIconButton(
                icon = if (switcherOpen) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.List,
                contentDescription = if (switcherOpen) "Close" else "Conversations",
                onClick = onToggleSwitcher,
            )
            if (active) {
                HeaderIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_archive_outline),
                    contentDescription = "Archive",
                    onClick = onArchive,
                )
            }
        }
        if (switcherOpen) {
            ConversationSwitcher(
                current = projection.summary(),
                conversations = conversations,
                onOpenConversation = onOpenConversation,
            )
        }
        ConversationTimeline(
            projection = projection,
            agentName = agentName,
            onConfigureProvider = onConfigureProvider,
            onCancelReply = onCancelReply,
            onRetryReply = onRetryReply,
            onMigrateProviderRoute = onMigrateProviderRoute,
            modifier = Modifier.weight(1f),
        )
        if (active && composer != null) {
            ConversationComposerBar(
                agentName = agentName,
                state = composer,
            )
        } else {
            ArchivedConversationBar(onRestore = onRestore)
        }
    }
}

@Composable
private fun ConversationSwitcher(
    current: ConversationSummaryProjection,
    conversations: List<ConversationSummaryProjection>,
    onOpenConversation: (ConversationSummaryProjection) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = translator.text("Conversations"),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            conversations.forEach { conversation ->
                val selected = conversation.id == current.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !selected) {
                            onOpenConversation(conversation)
                        }
                        .semantics {
                            contentDescription = translator.text(if (selected) {
                                "Current conversation"
                            } else if (
                                conversation.archiveState == ArchiveProjectionState.ARCHIVED
                            ) {
                                "Open archived conversation"
                            } else {
                                "Open conversation"
                            })
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (selected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    ImageVector.vectorResource(R.drawable.ic_chat_outline)
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = translator.text(conversation.visibleLabel()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = translator.text(when {
                                selected -> "Current conversation"
                                conversation.archiveState == ArchiveProjectionState.ARCHIVED ->
                                    "Archived"
                                else -> "Active"
                            }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTimeline(
    projection: ConversationProjection,
    agentName: String,
    onConfigureProvider: () -> Unit,
    onCancelReply: () -> Unit,
    onRetryReply: () -> Unit,
    onMigrateProviderRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val translator = LocalCockpitTranslator.current
    val timelineState = rememberLazyListState()
    val streamingReply = projection.streamingReply
    val providerError = projection.providerError
    val provider = projection.provider
    LaunchedEffect(projection.timeline.size, streamingReply?.text?.length) {
        if (projection.timeline.isNotEmpty()) {
            timelineState.scrollToItem(
                projection.timeline.size + if (streamingReply != null) 1 else 0,
            )
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = translator.text("Conversation timeline")
            },
        state = timelineState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (
            streamingReply?.inProgress == true ||
            providerError != null ||
            projection.providerRouteState != ConversationProviderRouteState.READY
        ) {
            item("provider-status") {
                when {
                    streamingReply?.inProgress == true -> InfoBanner(
                        title = "Receiving from ${streamingReply.providerName}",
                        tone = StatusTone.Positive,
                        actionLabel = "Stop",
                        onAction = onCancelReply,
                    )
                    projection.providerRouteState == ConversationProviderRouteState.REVISION_MISMATCH ->
                        InfoBanner(
                            title = "API configuration changed",
                            body = "This conversation's bound API configuration has changed.",
                            tone = StatusTone.Warning,
                            actionLabel = "Accept the updated API configuration",
                            onAction = onMigrateProviderRoute,
                        )
                    projection.providerRouteState == ConversationProviderRouteState.MISSING && provider != null ->
                        InfoBanner(
                            title = "API configuration not bound",
                            body = "This conversation has existing history and must be migrated explicitly.",
                            tone = StatusTone.Warning,
                            actionLabel = "Migrate this conversation to the current configuration",
                            onAction = onMigrateProviderRoute,
                        )
                    providerError != null -> InfoBanner(
                        title = when (providerError.code) {
                            "CANCELLED" -> "Response stopped"
                            "GENERATION_INTERRUPTED" -> "Response interrupted"
                            else -> "Provider response failed"
                        },
                        body = providerError.message,
                        tone = StatusTone.Warning,
                        actionLabel = if (providerError.retryable) "Retry" else "Settings",
                        onAction = if (providerError.retryable) onRetryReply else onConfigureProvider,
                    )
                    else -> InfoBanner(
                        title = "Model not connected",
                        tone = StatusTone.Warning,
                        actionLabel = "Settings",
                        onAction = onConfigureProvider,
                    )
                }
            }
        }
        if (projection.timeline.isEmpty()) {
            item("empty") {
                EmptyContentCard(
                    icon = ImageVector.vectorResource(R.drawable.ic_chat_outline),
                    title = "Start the conversation",
                )
            }
        } else {
            items(
                items = projection.timeline,
                key = { (it as TimelineItemProjection.MessageItem).message.id },
            ) { item ->
                MessageBubble(
                    message = (item as TimelineItemProjection.MessageItem).message,
                    agentName = agentName,
                )
            }
        }
        streamingReply?.let { reply ->
            if (reply.inProgress || reply.text.isNotEmpty()) {
                item("stream:${reply.invocationId}") {
                    StreamingReplyBubble(reply, agentName)
                }
            }
        }
        item("timeline-end") {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun StreamingReplyBubble(
    reply: StreamingReplyProjection,
    agentName: String,
) {
    val translator = LocalCockpitTranslator.current
    MessageBubble(
        message = MessageProjection(
            id = "stream:${reply.invocationId}",
            text = reply.text.ifEmpty { translator.text("Thinking…") },
            ordinal = Long.MAX_VALUE,
            role = MessageRoleProjection.AGENT,
            source = MessageSourceProjection.RUNTIME,
            status = if (reply.inProgress) MessageStatusProjection.ACCEPTED else MessageStatusProjection.FAILED,
        ),
        agentName = agentName,
        statusLabel = if (reply.inProgress) "Streaming…" else "Partial • not saved",
    )
}

@Composable
private fun ArchivedConversationBar(onRestore: () -> Unit) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = translator.text("Conversation is archived"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = translator.text("Restore it to write a new message."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onRestore,
                modifier = Modifier.semantics {
                    contentDescription = translator.choose(
                        "Restore conversation",
                        "恢复对话",
                    )
                },
            ) {
                Text(translator.text("Restore"))
            }
        }
    }
}

private fun ConversationProjection.summary() =
    ConversationSummaryProjection(id, revision, archiveState)
