package dev.cockpit.security.vault.api

import dev.cockpit.provider.api.ProviderAuthorizationHandle
import dev.cockpit.provider.api.ProviderInvocationAuthority

interface ProviderCredentialLeasePort {
    suspend fun acquire(authority: ProviderInvocationAuthority): ProviderAuthorizationHandle?
}
