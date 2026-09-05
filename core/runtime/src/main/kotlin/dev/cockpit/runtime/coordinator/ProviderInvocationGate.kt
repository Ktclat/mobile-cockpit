package dev.cockpit.runtime.coordinator

import dev.cockpit.provider.api.NormalizedProviderRequest
import dev.cockpit.provider.api.ProviderAdapterResolver
import dev.cockpit.provider.api.ProviderCredentialPurpose
import dev.cockpit.provider.api.ProviderError
import dev.cockpit.provider.api.ProviderErrorCode
import dev.cockpit.provider.api.ProviderInvocationAuthority
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderModelDiscoveryResult
import dev.cockpit.provider.api.ProviderProbeResult
import dev.cockpit.provider.api.ProviderProfile
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.security.vault.api.ProviderCredentialLeasePort
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class ProviderInvocationGate(
    private val credentialLeases: ProviderCredentialLeasePort,
    private val adapters: ProviderAdapterResolver,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val ownerEpoch: String = UUID.randomUUID().toString(),
) {
    suspend fun probe(profile: ProviderProfile): ProviderProbeResult {
        val invocationId = ProviderInvocationId(UUID.randomUUID().toString())
        val handle = credentialLeases.acquire(
            authority(profile, invocationId, ProviderCredentialPurpose.CAPABILITY_PROBE),
        ) ?: return ProviderProbeResult.Unavailable(credentialUnavailable())
        return handle.use {
            adapters.resolve(profile.kind).probe(profile, handle)
        }
    }

    suspend fun discoverModels(profile: ProviderProfile): ProviderModelDiscoveryResult {
        val invocationId = ProviderInvocationId(UUID.randomUUID().toString())
        val handle = credentialLeases.acquire(
            authority(profile, invocationId, ProviderCredentialPurpose.MODEL_DISCOVERY),
        ) ?: return ProviderModelDiscoveryResult.Unavailable(credentialUnavailable())
        return handle.use {
            adapters.resolve(profile.kind).discoverModels(profile, handle)
        }
    }

    fun stream(request: NormalizedProviderRequest): Flow<ProviderStreamEvent> = flow {
        val handle = credentialLeases.acquire(
            authority(
                request.profile,
                request.invocationId,
                ProviderCredentialPurpose.MODEL_INVOCATION,
            ),
        )
        if (handle == null) {
            emit(ProviderStreamEvent.Failed(request.invocationId, credentialUnavailable()))
            return@flow
        }
        handle.use {
            adapters.resolve(request.profile.kind)
                .startInvocation(request, handle)
                .collect { event -> emit(event) }
        }
    }

    fun cancel(kind: ProviderKind, invocationId: ProviderInvocationId) {
        adapters.resolve(kind).cancel(invocationId)
    }

    private fun authority(
        profile: ProviderProfile,
        invocationId: ProviderInvocationId,
        purpose: ProviderCredentialPurpose,
    ): ProviderInvocationAuthority {
        val now = clockMillis()
        val expiresAt = if (now > Long.MAX_VALUE - AUTHORITY_LIFETIME_MILLIS) {
            Long.MAX_VALUE
        } else {
            now + AUTHORITY_LIFETIME_MILLIS
        }
        return ProviderInvocationAuthority(
            invocationId = invocationId,
            profileId = profile.id,
            providerKind = profile.kind,
            authenticationType = profile.authenticationType,
            model = profile.model,
            credentialReference = profile.credentialReference,
            credentialRotation = profile.credentialRotation,
            ownerEpoch = ownerEpoch,
            purpose = purpose,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun credentialUnavailable() = ProviderError(
        ProviderErrorCode.AUTH,
        "The saved provider credential is unavailable. Save the API key again.",
        retryable = false,
    )

    private companion object {
        const val AUTHORITY_LIFETIME_MILLIS = 60_000L
    }
}
