package dev.cockpit.provider.api

import kotlinx.coroutines.flow.Flow

interface ProviderAdapter {
    val kind: ProviderKind
    val promptCapabilities: ProviderPromptCapabilities
        get() = ProviderPromptCapabilities(PromptPlacementSupport.COMPATIBILITY_DEPENDENT)

    suspend fun probe(
        profile: ProviderProfile,
        authorization: ProviderAuthorizationHandle,
    ): ProviderProbeResult

    suspend fun discoverModels(
        profile: ProviderProfile,
        authorization: ProviderAuthorizationHandle,
    ): ProviderModelDiscoveryResult

    fun startInvocation(
        request: NormalizedProviderRequest,
        authorization: ProviderAuthorizationHandle,
    ): Flow<ProviderStreamEvent>

    fun cancel(invocationId: ProviderInvocationId)
}

enum class PromptPlacementSupport {
    NATIVE,
    DEGRADED_TO_USER,
    COMPATIBILITY_DEPENDENT,
}

data class ProviderPromptCapabilities(
    val postHistorySystemPlacement: PromptPlacementSupport,
)

data class DiscoveredProviderModel(
    val remoteModelId: String,
    val displayName: String = remoteModelId,
)

sealed interface ProviderModelDiscoveryResult {
    data class Available(val models: List<DiscoveredProviderModel>) : ProviderModelDiscoveryResult
    data class Unavailable(val error: ProviderError) : ProviderModelDiscoveryResult
    data class Unsupported(val message: String) : ProviderModelDiscoveryResult
}

fun interface ProviderAdapterResolver {
    fun resolve(kind: ProviderKind): ProviderAdapter
}
