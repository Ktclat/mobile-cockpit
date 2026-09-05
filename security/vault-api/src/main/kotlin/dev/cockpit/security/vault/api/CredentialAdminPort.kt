package dev.cockpit.security.vault.api

import dev.cockpit.domain.credential.CredentialReference
import java.util.concurrent.atomic.AtomicBoolean

enum class DeviceAuthPolicy { DEVICE_UNLOCKED }

class NewCredential(
    val label: String,
    secret: CharArray,
) {
    private val value = secret.copyOf()
    private val consumed = AtomicBoolean(false)

    fun consume(consumer: (CharArray) -> Unit): Boolean {
        if (!consumed.compareAndSet(false, true)) return false
        val copy = value.copyOf()
        return try {
            consumer(copy)
            true
        } finally {
            copy.fill('\u0000')
            value.fill('\u0000')
        }
    }

    override fun toString(): String = "NewCredential(label=$label, secret=redacted)"
}

data class CredentialMetadata(
    val reference: CredentialReference,
    val label: String,
    val rotation: Long,
)

interface CredentialAdminPort {
    suspend fun create(
        input: NewCredential,
        authPolicy: DeviceAuthPolicy = DeviceAuthPolicy.DEVICE_UNLOCKED,
    ): CredentialMetadata

    suspend fun metadata(reference: CredentialReference): CredentialMetadata?

    suspend fun rotate(
        reference: CredentialReference,
        replacement: NewCredential,
    ): CredentialMetadata?

    suspend fun delete(reference: CredentialReference)
}
