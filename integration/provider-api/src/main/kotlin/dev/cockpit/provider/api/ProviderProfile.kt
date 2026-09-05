package dev.cockpit.provider.api

import dev.cockpit.domain.credential.CredentialReference
import java.net.URI

@JvmInline
value class ProviderProfileId(val value: String)

@JvmInline
value class ProviderInvocationId(val value: String)

enum class ProviderKind {
    OPENAI_RESPONSES,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

enum class ProviderAuthenticationType {
    BEARER,
    X_API_KEY,
}

data class ProviderProfile(
    val id: ProviderProfileId,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val credentialReference: CredentialReference,
    val credentialRotation: Long,
    val maxOutputTokens: Int,
    val revision: Long,
    val authenticationType: ProviderAuthenticationType = if (kind == ProviderKind.ANTHROPIC) {
        ProviderAuthenticationType.X_API_KEY
    } else {
        ProviderAuthenticationType.BEARER
    },
    val anthropicVersion: String = "2023-06-01",
    val organizationId: String = "",
    val projectId: String = "",
    val workspaceId: String = "",
) {
    init {
        require(id.value.isNotBlank()) { "Provider profile ID is required" }
        require(displayName.isNotBlank()) { "Provider display name is required" }
        require(credentialReference.value.isNotBlank()) { "Credential reference is required" }
        require(credentialRotation > 0) { "Credential rotation must be positive" }
        require(maxOutputTokens in 1..131_072) { "Max output tokens are out of range" }
        require(revision >= 0) { "Provider revision must not be negative" }

        val endpoint = runCatching { URI(baseUrl) }.getOrNull()
        require(
            endpoint != null &&
                endpoint.scheme.equals("https", ignoreCase = true) &&
                !endpoint.host.isNullOrBlank() &&
                endpoint.rawUserInfo == null &&
                endpoint.rawFragment == null &&
                endpoint.rawQuery == null,
        ) { "Provider base URL must be an HTTPS origin or path without credentials or fragments" }
        require(anthropicVersion.none { it == '\r' || it == '\n' })
        require(organizationId.none { it == '\r' || it == '\n' })
        require(projectId.none { it == '\r' || it == '\n' })
        require(workspaceId.none { it == '\r' || it == '\n' })
    }
}
