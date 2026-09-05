package dev.cockpit.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationProviderRouteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class ConversationComposerUiState(
    val text: String,
    val enabled: Boolean,
    val saveEnabled: Boolean,
    val sendEnabled: Boolean,
    val canNavigateBack: Boolean,
    val inFlight: Boolean,
    val invalid: Boolean,
    val stale: Boolean,
    val draftSaved: Boolean,
    val failedSave: Boolean,
    val failedSend: Boolean,
    val failedNavigation: Boolean,
    val onTextChange: (String) -> Unit,
    val onNavigateUp: () -> Unit,
    val onSaveDraft: () -> Unit,
    val onSend: () -> Unit,
)

@Composable
internal fun ConversationComposerHost(
    projection: ConversationProjection,
    onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean,
    onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean,
    onBack: () -> Boolean,
    content: @Composable (ConversationComposerUiState) -> Unit,
) {
    val validProjection =
        projection.messageDestination.conversationId == projection.id &&
            projection.messageDestination.expectedConversationRevision == projection.revision
    val duplicates = projection.drafts.groupBy { it.destination }.any { it.value.size > 1 }
    val foreign = projection.drafts.any { it.destination.conversationId != projection.id }
    val restored = projection.drafts.firstOrNull {
        it.destination == projection.messageDestination
    } ?: projection.drafts.maxByOrNull {
        it.destination.expectedConversationRevision.value
    }
    val invalid = !validProjection || duplicates || foreign

    var destination by remember(projection.id) {
        mutableStateOf(restored?.destination ?: projection.messageDestination)
    }
    var text by remember(projection.id) { mutableStateOf(restored?.text.orEmpty()) }
    var completed by remember(projection.id) {
        mutableStateOf<ConversationMessageDestination?>(null)
    }
    var followAuthoritativeDestination by remember(projection.id) { mutableStateOf(false) }
    var inFlight by remember(projection.id) { mutableStateOf(false) }
    var failedSave by remember(projection.id) { mutableStateOf(false) }
    var failedSend by remember(projection.id) { mutableStateOf(false) }
    var failedNavigation by remember(projection.id) { mutableStateOf(false) }
    var draftSaved by remember(projection.id) { mutableStateOf(false) }
    val actionGate = remember(projection.id) { ConversationActionGate() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun tryBegin(): Boolean = actionGate.tryBegin().also { if (it) inFlight = true }
    fun finish() {
        actionGate.finish()
        inFlight = false
    }

    LaunchedEffect(
        projection.messageDestination,
        completed,
        followAuthoritativeDestination,
    ) {
        if (
            followAuthoritativeDestination &&
            text.isEmpty() &&
            destination != projection.messageDestination
        ) {
            destination = projection.messageDestination
            completed = null
        }
    }

    val stale = destination != projection.messageDestination
    val enabled = !invalid && !inFlight && projection.streamingReply?.inProgress != true
    val saveEnabled = enabled && text.isNotEmpty()
    val sendEnabled =
        enabled && !stale && completed == null && text.isNotBlank() &&
            projection.providerRouteState == ConversationProviderRouteState.READY

    val updateText: (String) -> Unit = { updated ->
        text = updated
        followAuthoritativeDestination = false
        draftSaved = false
        failedSave = false
        failedSend = false
    }

    val navigateUp: () -> Unit = navigate@{
        if (inFlight || !tryBegin()) return@navigate
        val actionDestination = destination
        val actionText = text

        if (actionText.isEmpty() || invalid) {
            var navigated = false
            try {
                navigated = onBack()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedNavigation = true
            } finally {
                if (!navigated) finish()
            }
        } else {
            scope.launch {
                var navigated = false
                try {
                    if (onSaveDraft(actionDestination, actionText)) {
                        navigated = onBack()
                    } else {
                        failedSave = true
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failedSave = true
                } finally {
                    if (!navigated) finish()
                }
            }
        }
    }

    val saveDraft: () -> Unit = save@{
        if (!saveEnabled || !tryBegin()) return@save
        val actionDestination = destination
        val actionText = text
        scope.launch {
            try {
                if (actionGate.finishAfter {
                        onSaveDraft(actionDestination, actionText)
                    }
                ) {
                    draftSaved = true
                    failedSave = false
                } else {
                    draftSaved = false
                    failedSave = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                draftSaved = false
                failedSave = true
            } finally {
                inFlight = false
            }
        }
    }

    val send: () -> Unit = send@{
        if (!sendEnabled || !tryBegin()) return@send
        val actionDestination = destination
        val actionText = text
        scope.launch {
            try {
                if (actionGate.finishAfter {
                        onSendMessage(actionDestination, actionText)
                    }
                ) {
                    text = ""
                    completed = actionDestination
                    followAuthoritativeDestination = true
                    failedSend = false
                    focusManager.clearFocus()
                } else {
                    failedSend = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedSend = true
            } finally {
                inFlight = false
            }
        }
    }

    content(
        ConversationComposerUiState(
            text = text,
            enabled = enabled,
            saveEnabled = saveEnabled,
            sendEnabled = sendEnabled,
            canNavigateBack = !inFlight,
            inFlight = inFlight,
            invalid = invalid,
            stale = stale,
            draftSaved = draftSaved,
            failedSave = failedSave,
            failedSend = failedSend,
            failedNavigation = failedNavigation,
            onTextChange = updateText,
            onNavigateUp = navigateUp,
            onSaveDraft = saveDraft,
            onSend = send,
        ),
    )
}

@Composable
internal fun ConversationComposer(
    projection: ConversationProjection,
    agentName: String,
    onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean,
    onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean,
    onBack: () -> Boolean,
) {
    val translator = LocalCockpitTranslator.current
    ConversationComposerHost(
        projection = projection,
        onSaveDraft = onSaveDraft,
        onSendMessage = onSendMessage,
        onBack = onBack,
    ) { state ->
        Column {
            IconButton(
                onClick = state.onNavigateUp,
                enabled = state.canNavigateBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = translator.text("Navigate up"),
                    modifier = Modifier.size(24.dp),
                )
            }
            ConversationComposerBar(agentName, state)
        }
    }
}

@Composable
internal fun ConversationComposerBar(
    agentName: String,
    state: ConversationComposerUiState,
    modifier: Modifier = Modifier,
) {
    val translator = LocalCockpitTranslator.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.invalid) {
                ComposerNotice(
                    message = "Composer destination is invalid. Actions are disabled.",
                    danger = true,
                )
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = state.onTextChange,
                enabled = state.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 56.dp)
                    .semantics {
                        contentDescription = translator.choose(
                            "Compose message for $agentName",
                            "给 $agentName 编辑消息",
                        )
                    },
                placeholder = { Text(translator.text("Message $agentName")) },
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
                isError = state.invalid || state.stale || state.failedSend,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = state.onSaveDraft,
                    enabled = state.saveEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Done,
                        contentDescription = translator.text("Save draft"),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = state.onSend,
                    enabled = state.sendEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = translator.choose("Send message", "发送消息"),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            when {
                state.draftSaved -> ComposerNotice("Draft saved")
                state.failedSave -> ComposerNotice(
                    "Draft could not be saved. Text is preserved.",
                    danger = true,
                )
                state.stale -> ComposerNotice(
                    "Draft destination is stale. Send is disabled.",
                    danger = true,
                )
                state.failedSend -> ComposerNotice(
                    "Message could not be sent. Text is preserved.",
                    danger = true,
                )
                state.failedNavigation -> ComposerNotice(
                    "Navigation could not complete.",
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun ComposerNotice(message: String, danger: Boolean = false) {
    val translator = LocalCockpitTranslator.current
    Text(
        text = translator.text(message),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (danger) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        style = MaterialTheme.typography.labelMedium,
    )
}

internal class ConversationActionGate {
    private val held = AtomicBoolean(false)

    fun tryBegin(): Boolean = held.compareAndSet(false, true)

    fun finish() {
        held.set(false)
    }

    suspend fun <T> finishAfter(action: suspend () -> T): T =
        try {
            action()
        } finally {
            finish()
        }
}
