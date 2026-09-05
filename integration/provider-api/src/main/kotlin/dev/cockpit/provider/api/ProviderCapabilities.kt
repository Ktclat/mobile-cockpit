package dev.cockpit.provider.api

enum class ProviderCapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class ProviderCapabilities(
    val streaming: ProviderCapabilitySupport,
    val toolCalls: ProviderCapabilitySupport,
    val checkedAtEpochMillis: Long,
    val adapterVersion: String,
)

sealed interface ProviderProbeResult {
    data class Available(val capabilities: ProviderCapabilities) : ProviderProbeResult
    data class Unavailable(val error: ProviderError) : ProviderProbeResult
}
