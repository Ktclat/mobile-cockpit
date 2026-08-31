package dev.cockpit.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.ConversationProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun ConversationComposer(projection: ConversationProjection, agentName: String, onSaveDraft: suspend (ConversationMessageDestination, String) -> Boolean, onSendMessage: suspend (ConversationMessageDestination, String) -> Boolean, onBack: () -> Boolean) {
    val validProjection = projection.messageDestination.conversationId == projection.id && projection.messageDestination.expectedConversationRevision == projection.revision
    val duplicates = projection.drafts.groupBy { it.destination }.any { it.value.size > 1 }
    val foreign = projection.drafts.any { it.destination.conversationId != projection.id }
    val restored = projection.drafts.firstOrNull { it.destination == projection.messageDestination } ?: projection.drafts.maxByOrNull { it.destination.expectedConversationRevision.value }
    val invalid = !validProjection || duplicates || foreign
    var destination by remember(projection.id) { mutableStateOf(restored?.destination ?: projection.messageDestination) }
    var text by remember(projection.id) { mutableStateOf(restored?.text.orEmpty()) }
    var completed by remember(projection.id) { mutableStateOf<ConversationMessageDestination?>(null) }
    var followAuthoritativeDestination by remember(projection.id) { mutableStateOf(false) }
    var inFlight by remember(projection.id) { mutableStateOf(false) }
    val actionGate = remember(projection.id) { ConversationActionGate() }
    fun tryBegin(): Boolean = actionGate.tryBegin().also { if (it) inFlight = true }
    fun finish() { actionGate.finish(); inFlight = false }
    var failedSave by remember(projection.id) { mutableStateOf(false) }
    var failedSend by remember(projection.id) { mutableStateOf(false) }
    var failedNavigation by remember(projection.id) { mutableStateOf(false) }
    var draftSaved by remember(projection.id) { mutableStateOf(false) }
    LaunchedEffect(projection.messageDestination, completed, followAuthoritativeDestination) {
        if (
            followAuthoritativeDestination &&
            text.isEmpty() &&
            destination != projection.messageDestination
        ) {
            destination = projection.messageDestination
            completed = null
        }
    }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val stale = destination != projection.messageDestination
    fun action(description: String, enabled: Boolean, click: () -> Unit) = Modifier
        .semantics { contentDescription = description; role = Role.Button; if (!enabled) disabled() }
        .then(if (enabled) Modifier.clickable(onClick = click) else Modifier)
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .padding(8.dp)
    val enabled = !invalid && !inFlight
    Column {
    CockpitText("Back", action("Navigate up", enabled) {
        val actionDestination = destination
        val actionText = text
        if (tryBegin()) {
            if (actionText.isEmpty()) {
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
            } else scope.launch {
                var navigated = false
                try {
                    if (onSaveDraft(actionDestination, actionText)) navigated = onBack() else failedSave = true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failedSave = true
                } finally {
                    if (!navigated) finish()
                }
            }
        }
    })
    if (invalid) {
        CockpitText("Composer destination is invalid. Actions are disabled.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        CockpitText("Save draft", action("Save draft", false) {})
        CockpitText("Send", action("Send message", false) {})
        return@Column
    }
    val palette = LocalCockpitPalette.current
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            followAuthoritativeDestination = false
            draftSaved = false
            failedSave = false
            failedSend = false
        },
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .background(palette.field)
            .semantics { contentDescription = "Compose message for $agentName" }
            .padding(8.dp),
        textStyle = TextStyle(color = palette.foreground),
        cursorBrush = SolidColor(palette.action),
    )
    CockpitText("Save draft", action("Save draft", enabled) {
        val actionDestination = destination
        val actionText = text
        if (tryBegin()) scope.launch {
            try {
                if (actionGate.finishAfter { onSaveDraft(actionDestination, actionText) }) {
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
    })
    if (draftSaved) CockpitText("Draft saved", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (failedSave) CockpitText("Draft could not be saved. Text is preserved.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    val sendEnabled = enabled && !stale && completed == null
    CockpitText("Send", action("Send message", sendEnabled) {
        val actionDestination = destination
        val actionText = text
        if (tryBegin()) scope.launch {
            try {
                if (actionGate.finishAfter { onSendMessage(actionDestination, actionText) }) {
                    text = ""
                    completed = actionDestination
                    followAuthoritativeDestination = true
                    failedSend = false
                    focusManager.clearFocus()
                } else failedSend = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedSend = true
            } finally {
                inFlight = false
            }
        }
    })
    if (failedSend) CockpitText("Message could not be sent. Text is preserved.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (failedNavigation) CockpitText("Navigation could not complete.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (stale) CockpitText("Draft destination is stale. Send is disabled.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

internal class ConversationActionGate {
    private val held = AtomicBoolean(false)
    fun tryBegin(): Boolean = held.compareAndSet(false, true)
    fun finish() { held.set(false) }
    suspend fun <T> finishAfter(action: suspend () -> T): T = try { action() } finally { finish() }
}
