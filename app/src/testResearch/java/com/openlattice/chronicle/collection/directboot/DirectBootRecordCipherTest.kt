package com.openlattice.chronicle.collection.directboot

import android.app.Application
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.KeyStoreSpi
import java.security.GeneralSecurityException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decrypting with no key fails; only the write path may create the key. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DirectBootRecordCipherTest {
    @Test fun decryptWithoutTheKeyFailsAndCreatesNoKey() {
        Security.insertProviderAt(EmptyAndroidKeyStore(), 1)
        try {
            generatedKeys = 0
            val blob = byteArrayOf(1) + ByteArray(12) + ByteArray(32)
            assertThrows(GeneralSecurityException::class.java) { KeystoreDirectBootRecordCipher().decrypt(blob) }
            assertEquals("a read never mints a replacement key", 0, generatedKeys)
        } finally {
            Security.removeProvider("AndroidKeyStore")
        }
    }

    /** An AndroidKeyStore with no entries that counts key generation. */
    class EmptyAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "empty test keystore") {
        init {
            put("KeyStore.AndroidKeyStore", EmptyKeyStore::class.java.name)
            put("KeyGenerator.AES", CountingKeyGenerator::class.java.name)
        }
    }

    class EmptyKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.emptyEnumeration()
        override fun engineContainsAlias(alias: String?) = false
        override fun engineSize() = 0
        override fun engineIsKeyEntry(alias: String?) = false
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class CountingKeyGenerator : KeyGeneratorSpi() {
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey { generatedKeys++; return SecretKeySpec(ByteArray(32), "AES") }
    }

    companion object { var generatedKeys = 0 }
}
