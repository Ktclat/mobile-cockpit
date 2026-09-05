package dev.cockpit.security.vault

import dev.cockpit.domain.credential.CredentialReference
import dev.cockpit.provider.api.ProviderAuthorizationHandle
import dev.cockpit.provider.api.ProviderAuthorizationSink
import dev.cockpit.provider.api.ProviderCredentialPurpose
import dev.cockpit.provider.api.ProviderInvocationAuthority
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderAuthenticationType
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.security.vault.api.CredentialAdminPort
import dev.cockpit.security.vault.api.CredentialMetadata
import dev.cockpit.security.vault.api.DeviceAuthPolicy
import dev.cockpit.security.vault.api.NewCredential
import dev.cockpit.security.vault.api.ProviderCredentialLeasePort
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class EncryptedPayload(val ciphertext: ByteArray, val nonce: ByteArray) {
    fun ciphertextCopy(): ByteArray = ciphertext.copyOf()
    fun nonceCopy(): ByteArray = nonce.copyOf()
}

data class EncryptedCredentialRecord(
    val reference: CredentialReference,
    val label: String,
    val rotation: Long,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
) {
    fun ciphertextCopy(): ByteArray = ciphertext.copyOf()
    fun nonceCopy(): ByteArray = nonce.copyOf()
}

interface EnvelopeCipher {
    fun encrypt(plaintext: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): ByteArray
}

interface CredentialRecordStore {
    fun write(record: EncryptedCredentialRecord)
    fun read(reference: CredentialReference): EncryptedCredentialRecord?
    fun delete(reference: CredentialReference)
}

class CredentialVault(
    private val store: CredentialRecordStore,
    private val cipher: EnvelopeCipher,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val nextReference: () -> String = { UUID.randomUUID().toString() },
) : CredentialAdminPort, ProviderCredentialLeasePort {
    override suspend fun create(
        input: NewCredential,
        authPolicy: DeviceAuthPolicy,
    ): CredentialMetadata {
        require(authPolicy == DeviceAuthPolicy.DEVICE_UNLOCKED)
        val reference = CredentialReference(nextReference())
        val encrypted = consumeAndEncrypt(input)
        store.write(
            EncryptedCredentialRecord(
                reference,
                input.label,
                1L,
                encrypted.ciphertextCopy(),
                encrypted.nonceCopy(),
            ),
        )
        return CredentialMetadata(reference, input.label, 1L)
    }

    override suspend fun metadata(reference: CredentialReference): CredentialMetadata? =
        store.read(reference)?.let { CredentialMetadata(it.reference, it.label, it.rotation) }

    override suspend fun rotate(
        reference: CredentialReference,
        replacement: NewCredential,
    ): CredentialMetadata? {
        val current = store.read(reference) ?: return null
        if (current.rotation == Long.MAX_VALUE) return null
        val encrypted = consumeAndEncrypt(replacement)
        val updated = EncryptedCredentialRecord(
            reference,
            replacement.label,
            current.rotation + 1,
            encrypted.ciphertextCopy(),
            encrypted.nonceCopy(),
        )
        store.write(updated)
        return CredentialMetadata(reference, updated.label, updated.rotation)
    }

    override suspend fun delete(reference: CredentialReference) {
        store.delete(reference)
    }

    override suspend fun acquire(
        authority: ProviderInvocationAuthority,
    ): ProviderAuthorizationHandle? {
        if (
            authority.invocationId.value.isBlank() ||
            authority.profileId.value.isBlank() ||
            (authority.model.isBlank() && authority.purpose != ProviderCredentialPurpose.MODEL_DISCOVERY) ||
            authority.ownerEpoch.isBlank() ||
            authority.expiresAtEpochMillis <= clockMillis() ||
            authority.purpose !in setOf(
                ProviderCredentialPurpose.MODEL_INVOCATION,
                ProviderCredentialPurpose.CAPABILITY_PROBE,
                ProviderCredentialPurpose.MODEL_DISCOVERY,
            )
        ) return null

        val record = store.read(authority.credentialReference) ?: return null
        if (
            record.rotation != authority.credentialRotation ||
            record.label != "Provider ${authority.profileId.value}"
        ) return null
        val bytes = try {
            cipher.decrypt(EncryptedPayload(record.ciphertextCopy(), record.nonceCopy()))
        } catch (_: Exception) {
            return null
        }
        val secret = try {
            decodeUtf8(bytes)
        } finally {
            bytes.fill(0)
        }
        return OneUseProviderAuthorizationHandle(authority, secret)
    }

    private fun consumeAndEncrypt(input: NewCredential): EncryptedPayload {
        var encrypted: EncryptedPayload? = null
        check(input.consume { chars ->
            val bytes = encodeUtf8(chars)
            try {
                require(bytes.isNotEmpty()) { "Credential must not be empty" }
                encrypted = cipher.encrypt(bytes)
            } finally {
                bytes.fill(0)
            }
        }) { "Credential input has already been consumed" }
        return requireNotNull(encrypted)
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        return CharArray(decoded.remaining()).also(decoded::get)
    }
}

private class OneUseProviderAuthorizationHandle(
    private val authority: ProviderInvocationAuthority,
    private val secret: CharArray,
) : ProviderAuthorizationHandle {
    private val consumed = AtomicBoolean(false)
    override val invocationId: ProviderInvocationId = authority.invocationId

    override fun authorize(sink: ProviderAuthorizationSink): Boolean {
        if (!consumed.compareAndSet(false, true)) return false
        val headerName: String
        val headerValue: CharArray
        if (authority.authenticationType == ProviderAuthenticationType.X_API_KEY) {
            headerName = "x-api-key"
            headerValue = secret.copyOf()
        } else {
            headerName = "Authorization"
            val prefix = "Bearer ".toCharArray()
            headerValue = CharArray(prefix.size + secret.size)
            prefix.copyInto(headerValue)
            secret.copyInto(headerValue, prefix.size)
            prefix.fill('\u0000')
        }
        return try {
            sink.setHeader(headerName, headerValue)
            true
        } finally {
            headerValue.fill('\u0000')
            close()
        }
    }

    override fun close() {
        consumed.set(true)
        secret.fill('\u0000')
    }
}
