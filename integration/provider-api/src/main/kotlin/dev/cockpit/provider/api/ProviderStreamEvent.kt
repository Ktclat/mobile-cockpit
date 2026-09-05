package dev.cockpit.provider.api

import dev.cockpit.domain.bytes.ImmutableBytes

data class ProviderUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

sealed interface ProviderStreamEvent {
    val invocationId: ProviderInvocationId

    data class TextDelta(
        override val invocationId: ProviderInvocationId,
        val ordinal: Long,
        val text: String,
    ) : ProviderStreamEvent

    data class ToolProposalDelta(
        override val invocationId: ProviderInvocationId,
        val callId: String,
        val bytes: ImmutableBytes,
    ) : ProviderStreamEvent

    data class Completed(
        override val invocationId: ProviderInvocationId,
        val usage: ProviderUsage?,
    ) : ProviderStreamEvent

    data class Failed(
        override val invocationId: ProviderInvocationId,
        val error: ProviderError,
    ) : ProviderStreamEvent
}
