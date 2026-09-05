package dev.cockpit.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.cockpit.domain.credential.CredentialReference
import dev.cockpit.security.vault.CredentialRecordStore
import dev.cockpit.security.vault.CredentialVault
import dev.cockpit.security.vault.EncryptedCredentialRecord
import dev.cockpit.security.vault.EncryptedPayload
import dev.cockpit.security.vault.EnvelopeCipher
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun createAndroidProviderCredentialVault(context: Context): CredentialVault =
    CredentialVault(
        store = SharedPreferencesCredentialRecordStore(context.applicationContext),
        cipher = AndroidKeystoreEnvelopeCipher(),
    )

internal fun createEphemeralProviderCredentialVault(): CredentialVault = CredentialVault(
    store = EphemeralCredentialRecordStore(),
    cipher = EphemeralEnvelopeCipher(),
)

private class EphemeralCredentialRecordStore : CredentialRecordStore {
    private val records = ConcurrentHashMap<CredentialReference, EncryptedCredentialRecord>()

    override fun write(record: EncryptedCredentialRecord) {
        records[record.reference] = record.copy(
            ciphertext = record.ciphertextCopy(),
            nonce = record.nonceCopy(),
        )
    }

    override fun read(reference: CredentialReference): EncryptedCredentialRecord? =
        records[reference]?.let {
            it.copy(ciphertext = it.ciphertextCopy(), nonce = it.nonceCopy())
        }

    override fun delete(reference: CredentialReference) {
        records.remove(reference)
    }
}

private class EphemeralEnvelopeCipher : EnvelopeCipher {
    private val key: SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES).run {
        init(256, SecureRandom())
        generateKey()
    }

    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val operation = Cipher.getInstance(TRANSFORMATION)
        operation.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedPayload(operation.doFinal(plaintext), operation.iv.copyOf())
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val operation = Cipher.getInstance(TRANSFORMATION)
        operation.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.nonceCopy()))
        return operation.doFinal(payload.ciphertextCopy())
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private class SharedPreferencesCredentialRecordStore(context: Context) : CredentialRecordStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun write(record: EncryptedCredentialRecord) {
        val prefix = record.reference.value
        check(
            preferences.edit()
                .putString("$prefix.label", record.label)
                .putLong("$prefix.rotation", record.rotation)
                .putString("$prefix.ciphertext", encode(record.ciphertextCopy()))
                .putString("$prefix.nonce", encode(record.nonceCopy()))
                .commit(),
        ) { "Encrypted credential metadata could not be committed" }
    }

    override fun read(reference: CredentialReference): EncryptedCredentialRecord? {
        val prefix = reference.value
        val label = preferences.getString("$prefix.label", null) ?: return null
        val ciphertext = preferences.getString("$prefix.ciphertext", null)?.let(::decode) ?: return null
        val nonce = preferences.getString("$prefix.nonce", null)?.let(::decode) ?: return null
        val rotation = preferences.getLong("$prefix.rotation", 0L).takeIf { it > 0L } ?: return null
        return EncryptedCredentialRecord(reference, label, rotation, ciphertext, nonce)
    }

    override fun delete(reference: CredentialReference) {
        val prefix = reference.value
        check(
            preferences.edit()
                .remove("$prefix.label")
                .remove("$prefix.rotation")
                .remove("$prefix.ciphertext")
                .remove("$prefix.nonce")
                .commit(),
        ) { "Encrypted credential metadata could not be deleted" }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES_NAME = "cockpit-provider-vault-v1"
    }
}

private class AndroidKeystoreEnvelopeCipher : EnvelopeCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val operation = Cipher.getInstance(TRANSFORMATION)
        operation.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedPayload(
            ciphertext = operation.doFinal(plaintext),
            nonce = operation.iv.copyOf(),
        )
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val operation = Cipher.getInstance(TRANSFORMATION)
        operation.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.nonceCopy()),
        )
        return operation.doFinal(payload.ciphertextCopy())
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.cockpit.provider.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
