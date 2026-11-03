package com.openlattice.chronicle.collection.directboot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts one direct-boot buffer record; tests swap in a JVM cipher. */
interface DirectBootRecordCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256/GCM over a non-exportable Keystore key. Device-protected storage is keyed at boot, not by the
 * user credential, so each buffered record is also encrypted with this key, which stays usable while
 * locked. Blob layout: `[1-byte version][12-byte GCM IV][ciphertext + 16-byte tag]`.
 */
class KeystoreDirectBootRecordCipher : DirectBootRecordCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_LENGTH_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        return ByteArray(1 + IV_LENGTH_BYTES + ciphertext.size).also { out ->
            out[0] = FORMAT_VERSION
            iv.copyInto(out, 1)
            ciphertext.copyInto(out, 1 + IV_LENGTH_BYTES)
        }
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= 1 + IV_LENGTH_BYTES) throw GeneralSecurityException("Record too short: ${blob.size} bytes")
        if (blob[0] != FORMAT_VERSION) throw GeneralSecurityException("Unknown record version ${blob[0]}")
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // A read never creates the key: a new key cannot open records sealed by the lost one.
        val key = existingKey() ?: throw KeyStoreException("Direct-boot buffer key is missing")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(blob, 1 + IV_LENGTH_BYTES, blob.size - 1 - IV_LENGTH_BYTES)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chronicle_direct_boot_buffer"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128

        /** Creates the key while unlocked, so the first locked boot only uses it. */
        fun ensureKey() {
            getOrCreateKey()
        }

        private fun existingKey(): SecretKey? =
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

        /** Only the write path creates the key. */
        @Synchronized
        private fun getOrCreateKey(): SecretKey {
            existingKey()?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
