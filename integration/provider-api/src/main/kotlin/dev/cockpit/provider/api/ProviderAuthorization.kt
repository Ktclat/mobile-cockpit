package dev.cockpit.provider.api

import dev.cockpit.domain.credential.CredentialReference

enum class ProviderCredentialPurpose { MODEL_INVOCATION, CAPABILITY_PROBE, MODEL_DISCOVERY }

data class ProviderInvocationAuthority(
    val invocationId: ProviderInvocationId,
    val profileId: ProviderProfileId,
    val providerKind: ProviderKind,
    val authenticationType: ProviderAuthenticationType,
    val model: String,
    val credentialReference: CredentialReference,
    val credentialRotation: Long,
    val ownerEpoch: String,
    val purpose: ProviderCredentialPurpose,
    val expiresAtEpochMillis: Long,
)

fun interface ProviderAuthorizationSink {
    fun setHeader(name: String, value: CharArray)
}

/** One-use, invocation-bound operation. It never returns credential text to its caller. */
interface ProviderAuthorizationHandle : AutoCloseable {
    val invocationId: ProviderInvocationId

    fun authorize(sink: ProviderAuthorizationSink): Boolean

    override fun close()
}
