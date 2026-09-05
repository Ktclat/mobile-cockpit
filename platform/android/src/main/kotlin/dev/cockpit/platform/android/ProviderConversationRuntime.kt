package dev.cockpit.platform.android

import dev.cockpit.application.AppendConversationAgentMessage
import dev.cockpit.application.AgentPromptBuilder
import dev.cockpit.application.ConversationMutationCoordinator
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.ProviderConfigurationRepository
import dev.cockpit.persistence.api.ProviderConfigurationSnapshot
import dev.cockpit.persistence.api.ProviderProfilePersistenceState
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.BoundProviderProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection
import dev.cockpit.projection.model.ProviderReplyErrorProjection
import dev.cockpit.projection.model.StreamingReplyProjection
import dev.cockpit.provider.api.NormalizedProviderRequest
import dev.cockpit.provider.api.ProviderError
import dev.cockpit.provider.api.ProviderErrorCode
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderMessage
import dev.cockpit.provider.api.ProviderMessageRole
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.runtime.coordinator.ProviderInvocationGate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ProviderReplyState(
    val invocationId: ProviderInvocationId,
    val providerName: String,
    val text: String,
    val inProgress: Boolean,
    val error: ProviderError?,
)

private data class ActiveProviderInvocation(
    val invocationId: ProviderInvocationId,
    val kind: ProviderKind,
    val job: Job,
)

internal class ProviderConversationRuntime(
    private val conversations: ConversationRepository,
    private val providers: ProviderConfigurationRepository,
    private val invocationGate: ProviderInvocationGate,
    private val responder: ConversationTextResponder?,
    private val appendAgentMessage: AppendConversationAgentMessage,
    private val mutations: ConversationMutationCoordinator,
    private val processScope: CoroutineScope,
) {
    val replies = MutableStateFlow<Map<ConversationId, ProviderReplyState>>(emptyMap())
    private val active = ConcurrentHashMap<ConversationId, ActiveProviderInvocation>()

    suspend fun startAfterAccepted(
        destination: ConversationMessageDestination,
        originalText: String,
    ) {
        if (active.containsKey(destination.conversationId)) return
        val snapshot = conversations.load(destination.conversationId) ?: return
        val persistedProfile = providers.profileForConversation(
            destination.conversationId,
            snapshot.conversation.conversation.agentId,
        )
        if (persistedProfile == null) {
            startDebugReply(destination, originalText)
            return
        }
        val profile = runCatching { persistedProfile.toProviderProfile() }.getOrNull()
        if (profile == null) {
            showError(
                destination.conversationId,
                ProviderInvocationId(UUID.randomUUID().toString()),
                persistedProfile.displayName,
                ProviderError(
                    ProviderErrorCode.INVALID_REQUEST,
                    "The bound provider profile is invalid. Edit and save it again.",
                    retryable = false,
                ),
            )
            return
        }
        val request = NormalizedProviderRequest(
            invocationId = ProviderInvocationId(UUID.randomUUID().toString()),
            profile = profile,
            systemInstruction = AgentPromptBuilder.build(
                persona = snapshot.persona.persona,
                conversationText = snapshot.messages.sortedBy { it.ordinal }.map { it.message.text },
            ).systemInstruction,
            messages = snapshot.messages.sortedBy { it.ordinal }.map {
                ProviderMessage(
                    role = when (it.role) {
                        MessageRole.USER -> ProviderMessageRole.USER
                        MessageRole.AGENT -> ProviderMessageRole.ASSISTANT
                        MessageRole.SYSTEM -> ProviderMessageRole.SYSTEM
                    },
                    text = it.message.text,
                )
            },
        )
        launchProvider(destination, request)
    }

    suspend fun retry(conversationId: ConversationId): Boolean {
        if (active.containsKey(conversationId)) return false
        val snapshot = conversations.load(conversationId) ?: return false
        val last = snapshot.messages.maxByOrNull { it.ordinal } ?: return false
        if (last.role != MessageRole.USER) return false
        val current = snapshot.conversation.conversation.revision.value
        if (current <= 0L) return false
        startAfterAccepted(
            ConversationMessageDestination(
                conversationId,
                ConversationRevision(current - 1L),
            ),
            last.message.text,
        )
        return active.containsKey(conversationId)
    }

    suspend fun cancel(conversationId: ConversationId): Boolean {
        var invocation: ActiveProviderInvocation? = null
        val accepted = mutations.submit(conversationId) {
            val current = active.remove(conversationId) ?: return@submit false
            invocation = current
            showError(
                conversationId,
                current.invocationId,
                replies.value[conversationId]?.providerName ?: "Provider",
                ProviderError(
                    ProviderErrorCode.CANCELLED,
                    "The response was stopped. Partial text was not saved as a message.",
                    retryable = true,
                ),
            )
            true
        }
        if (!accepted) return false
        val cancelled = requireNotNull(invocation)
        invocationGate.cancel(cancelled.kind, cancelled.invocationId)
        cancelled.job.cancel()
        return true
    }

    private fun launchProvider(
        destination: ConversationMessageDestination,
        request: NormalizedProviderRequest,
    ) {
        val conversationId = destination.conversationId
        val job = processScope.launch(start = CoroutineStart.LAZY) {
            val buffer = StringBuilder()
            try {
                invocationGate.stream(request).collect { event ->
                    if (!isCurrent(conversationId, request.invocationId)) return@collect
                    when (event) {
                        is ProviderStreamEvent.TextDelta -> {
                            if (event.text.length > MAX_STREAMED_REPLY_CHARACTERS - buffer.length) {
                                invocationGate.cancel(request.profile.kind, request.invocationId)
                                release(conversationId, request.invocationId)
                                showError(
                                    conversationId,
                                    request.invocationId,
                                    request.profile.displayName,
                                    ProviderError(
                                        ProviderErrorCode.MALFORMED_STREAM,
                                        "The provider response exceeded the local safety limit.",
                                        retryable = false,
                                    ),
                                    buffer.toString(),
                                )
                                return@collect
                            }
                            buffer.append(event.text)
                            updateReply(
                                conversationId,
                                request.invocationId,
                                request.profile.displayName,
                                buffer.toString(),
                                inProgress = true,
                                error = null,
                            )
                        }
                        is ProviderStreamEvent.ToolProposalDelta -> Unit
                        is ProviderStreamEvent.Failed -> {
                            release(conversationId, request.invocationId)
                            showError(
                                conversationId,
                                request.invocationId,
                                request.profile.displayName,
                                event.error,
                                buffer.toString(),
                            )
                        }
                        is ProviderStreamEvent.Completed -> {
                            if (buffer.isEmpty()) {
                                release(conversationId, request.invocationId)
                                showError(
                                    conversationId,
                                    request.invocationId,
                                    request.profile.displayName,
                                    ProviderError(
                                        ProviderErrorCode.MALFORMED_STREAM,
                                        "The provider completed without a text response.",
                                        retryable = true,
                                    ),
                                )
                            } else {
                                val saved = mutations.submit(conversationId) {
                                    if (!isCurrent(conversationId, request.invocationId)) {
                                        false
                                    } else {
                                        val appended = appendAgentMessage(
                                            destination,
                                            buffer.toString(),
                                            MessageSource.RUNTIME,
                                        )
                                        if (appended) {
                                            release(conversationId, request.invocationId)
                                            replies.update { it - conversationId }
                                        }
                                        appended
                                    }
                                }
                                if (!saved) {
                                    release(conversationId, request.invocationId)
                                    showError(
                                        conversationId,
                                        request.invocationId,
                                        request.profile.displayName,
                                        ProviderError(
                                            ProviderErrorCode.UNKNOWN_PROVIDER_ERROR,
                                            "The response arrived, but the conversation changed before it could be saved.",
                                            retryable = false,
                                        ),
                                        buffer.toString(),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (isCurrent(conversationId, request.invocationId)) {
                    release(conversationId, request.invocationId)
                    showError(
                        conversationId,
                        request.invocationId,
                        request.profile.displayName,
                        ProviderError(
                            ProviderErrorCode.CANCELLED,
                            "The response was interrupted. Partial text was not saved as a message.",
                            retryable = true,
                        ),
                        buffer.toString(),
                    )
                }
            } finally {
                active.computeIfPresent(conversationId) { _, current ->
                    if (current.invocationId == request.invocationId) null else current
                }
            }
        }
        val invocation = ActiveProviderInvocation(request.invocationId, request.profile.kind, job)
        if (active.putIfAbsent(conversationId, invocation) == null) {
            updateReply(
                conversationId,
                request.invocationId,
                request.profile.displayName,
                text = "",
                inProgress = true,
                error = null,
            )
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun startDebugReply(
        destination: ConversationMessageDestination,
        originalText: String,
    ) {
        val debugResponder = responder ?: return
        processScope.launch {
            debugResponder.replyTo(originalText)?.let { reply ->
                mutations.submit(destination.conversationId) {
                    appendAgentMessage(destination, reply, MessageSource.DEBUG)
                }
            }
        }
    }

    private fun isCurrent(conversationId: ConversationId, invocationId: ProviderInvocationId): Boolean =
        active[conversationId]?.invocationId == invocationId

    private fun release(conversationId: ConversationId, invocationId: ProviderInvocationId) {
        active.computeIfPresent(conversationId) { _, current ->
            if (current.invocationId == invocationId) null else current
        }
    }

    private fun showError(
        conversationId: ConversationId,
        invocationId: ProviderInvocationId,
        providerName: String,
        error: ProviderError,
        partialText: String = replies.value[conversationId]?.text.orEmpty(),
    ) = updateReply(
        conversationId,
        invocationId,
        providerName,
        partialText,
        inProgress = false,
        error = error,
    )

    private fun updateReply(
        conversationId: ConversationId,
        invocationId: ProviderInvocationId,
        providerName: String,
        text: String,
        inProgress: Boolean,
        error: ProviderError?,
    ) {
        replies.update {
            it + (conversationId to ProviderReplyState(
                invocationId,
                providerName,
                text,
                inProgress,
                error,
            ))
        }
    }

    private companion object {
        const val MAX_STREAMED_REPLY_CHARACTERS = 2_000_000
    }
}

internal class ProviderAwareAgentConversationQueries(
    private val delegate: AgentConversationQueryPort,
    private val configurations: Flow<ProviderConfigurationSnapshot>,
    private val replies: Flow<Map<ConversationId, ProviderReplyState>>,
) : AgentConversationQueryPort {
    override fun home(): Flow<HomeProjection> = combine(delegate.home(), configurations) { home, config ->
        home.copy(
            agents = home.agents.map { agent ->
                agent.copy(providerName = config.resolvedProfile(agent.id)?.first?.displayName)
            },
        )
    }

    override fun agent(id: AgentId): Flow<AgentDetailProjection> =
        combine(delegate.agent(id), configurations) { agent, config ->
            agent.copy(provider = config.resolvedProfile(id)?.let { (profile, usesDefault) ->
                profile.toBoundProjection(usesDefault)
            })
        }

    override fun conversation(id: ConversationId): Flow<ConversationProjection> =
        combine(delegate.conversation(id), configurations, replies) { conversation, config, replyMap ->
            val reply = replyMap[id]
            conversation.copy(
                provider = config.resolvedConversationProfile(id, conversation.agentId)?.let { (profile, usesDefault) ->
                    profile.toBoundProjection(usesDefault)
                },
                streamingReply = reply?.let {
                    StreamingReplyProjection(
                        invocationId = it.invocationId.value,
                        providerName = it.providerName,
                        text = it.text,
                        inProgress = it.inProgress,
                    )
                },
                providerError = reply?.error?.let {
                    ProviderReplyErrorProjection(it.code.name, it.safeMessage, it.retryable)
                },
            )
        }

    private fun ProviderConfigurationSnapshot.resolvedConversationProfile(
        conversationId: ConversationId,
        agentId: AgentId,
    ): Pair<ProviderProfilePersistenceState, Boolean>? {
        val lockedRoute = conversationRoutes.firstOrNull { it.conversationId == conversationId }
        return if (lockedRoute == null) {
            resolvedProfile(agentId)
        } else {
            resolvedRoute(
                connectionId = lockedRoute.providerProfileId,
                modelId = lockedRoute.modelId,
                usesDefault = false,
            )
        }
    }

    private fun ProviderConfigurationSnapshot.resolvedProfile(
        agentId: AgentId,
    ): Pair<ProviderProfilePersistenceState, Boolean>? {
        val binding = bindings.firstOrNull { it.agentId == agentId }
        val route = if (binding == null) {
            globalDefaultRoute?.let { Triple(it.connectionId, it.modelId, true) }
        } else {
            val profile = profiles.firstOrNull { it.id == binding.providerProfileId }
            val modelId = binding.modelId ?: profile?.preferredModelId
            modelId?.let { Triple(binding.providerProfileId, it, false) }
        } ?: return null
        return resolvedRoute(route.first, route.second, route.third)
    }

    private fun ProviderConfigurationSnapshot.resolvedRoute(
        connectionId: String,
        modelId: String,
        usesDefault: Boolean,
    ): Pair<ProviderProfilePersistenceState, Boolean>? {
        val profile = profiles.firstOrNull { it.id == connectionId && it.enabled } ?: return null
        val model = models.firstOrNull {
            it.id == modelId && it.connectionId == profile.id && it.enabled
        } ?: return null
        return profile.copy(model = model.remoteModelId, preferredModelId = model.id) to usesDefault
    }

    private fun ProviderProfilePersistenceState.toBoundProjection(usesDefault: Boolean) = BoundProviderProjection(
        id = id,
        displayName = displayName,
        model = model,
        available = lastProbedAtEpochMillis != null && lastProbeErrorCode == null,
        usesDefault = usesDefault,
        modelId = preferredModelId,
    )
}
