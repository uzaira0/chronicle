package com.openlattice.chronicle.collection.directboot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts one direct-boot buffer record. Seam so the buffer is JVM-testable
 * without the Android Keystore.
 */
interface DirectBootRecordCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256/GCM cipher over a non-exportable Android Keystore key.
 *
 * Device-protected storage is encrypted with a boot-time device key, not the user
 * credential — weaker than the credential-encrypted storage everything else uses. Buffered
 * sensor samples are therefore additionally encrypted record-by-record with this key. The
 * key is deliberately usable while the device is locked (`unlockedDeviceRequired` stays
 * false — the whole point is writing during the pre-unlock window) and is hardware-backed
 * where the device supports it.
 *
 * Blob layout: `[1-byte version][12-byte GCM IV][ciphertext + 16-byte tag]`.
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
        require(blob.size > 1 + IV_LENGTH_BYTES) { "Record too short: ${blob.size} bytes" }
        require(blob[0] == FORMAT_VERSION) { "Unknown record version ${blob[0]}" }
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(blob, 1 + IV_LENGTH_BYTES, blob.size - 1 - IV_LENGTH_BYTES)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chronicle_direct_boot_buffer"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128

        /**
         * Creates the buffer key if missing. Called from the normal-mode snapshot rewrite so
         * the key exists before the first locked boot ever needs it (key *use* works while
         * locked; creating it ahead of time in normal mode avoids relying on keystore
         * generation semantics in direct-boot mode).
         */
        fun ensureKey() {
            getOrCreateKey()
        }

        @Synchronized
        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
