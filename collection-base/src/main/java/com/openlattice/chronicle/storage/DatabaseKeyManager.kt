package com.openlattice.chronicle.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_ALIAS = "chronicle_db_key"
private const val RECOVERY_KEYSTORE_ALIAS = "chronicle_recovery_key_v1"
private const val PREFS_FILE = "chronicle_db_key_prefs"
private const val PREF_ENCRYPTED_PASSPHRASE = "encrypted_passphrase"
private const val PREF_IV = "passphrase_iv"
private const val AES_GCM_TAG_LENGTH = 128
private val RECOVERY_FILE_MAGIC = "CHRREC1".toByteArray(Charsets.US_ASCII)

object DatabaseKeyManager {

    /**
     * Returns the existing passphrase, or creates one only for a genuinely new store.
     * Existing ciphertext must never be paired with replacement key material.
     */
    fun getPassphrase(context: Context, allowCreate: Boolean): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val storedPassphrase = prefs.getString(PREF_ENCRYPTED_PASSPHRASE, null)
        val storedIv = prefs.getString(PREF_IV, null)

        if (storedPassphrase != null || storedIv != null) {
            if (storedPassphrase == null || storedIv == null) {
                throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.INVALID_KEY_MATERIAL
                )
            }
            try {
                return decryptPassphrase(
                    Base64.decode(storedPassphrase, Base64.NO_WRAP),
                    Base64.decode(storedIv, Base64.NO_WRAP)
                )
            } catch (e: Exception) {
                throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.INVALID_KEY_MATERIAL,
                    e
                )
            }
        }

        if (!allowCreate) {
            throw LocalStoreRecoveryRequiredException(
                LocalStoreRecoveryReason.MISSING_KEY_MATERIAL
            )
        }

        val passphrase = ByteArray(32)
        java.security.SecureRandom().nextBytes(passphrase)

        try {
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)

            val encrypted = cipher.doFinal(passphrase)
            val iv = cipher.iv

            val committed = prefs.edit()
                .putString(PREF_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
            if (!committed) {
                throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED
                )
            }
        } catch (e: Exception) {
            passphrase.fill(0)
            if (e is LocalStoreRecoveryRequiredException) throw e
            throw LocalStoreRecoveryRequiredException(
                LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED,
                e
            )
        }

        return passphrase
    }

    /**
     * Encrypts one recovery artifact with a non-exportable, device-bound Android Keystore key.
     * The returned digest covers the original plaintext and is verified by decrypting the newly
     * written artifact before callers are allowed to reset the live store.
     */
    internal fun encryptAndVerifyRecoveryArtifact(source: File, destination: File): String {
        destination.parentFile?.mkdirs()
        val key = getOrCreateRecoveryKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val header = DataOutputStream(output)
                    header.write(RECOVERY_FILE_MAGIC)
                    header.writeByte(cipher.iv.size)
                    header.write(cipher.iv)
                    header.flush()
                    CipherOutputStream(output, cipher).use { encrypted ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            encrypted.write(buffer, 0, count)
                        }
                    }
                }
            }

            val expected = digest.digest()
            check(verifyRecoveryArtifact(destination, key).contentEquals(expected)) {
                "Encrypted recovery artifact verification failed"
            }
            return expected.toHex()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    internal fun clearStoredPassphrase(context: Context) {
        check(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Failed to clear encrypted database key metadata"
        }
    }

    internal fun keyMetadataFile(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_FILE.xml")

    private fun decryptPassphrase(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val keystoreKey = getExistingKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getExistingKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null)
            ?: throw LocalStoreRecoveryRequiredException(
                LocalStoreRecoveryReason.MISSING_KEY_MATERIAL
            )
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw LocalStoreRecoveryRequiredException(
                LocalStoreRecoveryReason.INVALID_KEY_MATERIAL
            )
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun getOrCreateRecoveryKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.getEntry(RECOVERY_KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.INVALID_KEY_MATERIAL
                )
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                RECOVERY_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun verifyRecoveryArtifact(artifact: File, key: SecretKey): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(artifact).use { fileInput ->
            val header = DataInputStream(fileInput)
            val magic = ByteArray(RECOVERY_FILE_MAGIC.size)
            header.readFully(magic)
            check(magic.contentEquals(RECOVERY_FILE_MAGIC)) { "Invalid recovery artifact header" }
            val ivLength = header.readUnsignedByte()
            check(ivLength in 12..32) { "Invalid recovery artifact IV" }
            val iv = ByteArray(ivLength)
            header.readFully(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            CipherInputStream(fileInput, cipher).use { decrypted ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = decrypted.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
