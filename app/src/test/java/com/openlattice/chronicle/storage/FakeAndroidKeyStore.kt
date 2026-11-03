package com.openlattice.chronicle.storage

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's AndroidKeyStore for JVM tests, which Robolectric does not provide: an AES key generated from a
 * [KeyGenParameterSpec] is kept under its alias, like the device-bound key the app never sees the bytes of.
 * [keys] is the keystore's content (clearing it is a keystore wiped by the OS); [refuseKeyGeneration] makes the next
 * key generation fail as a broken keystore does.
 */
internal object FakeAndroidKeyStore {
    val keys = ConcurrentHashMap<String, SecretKey>()
    @Volatile var refuseKeyGeneration: RuntimeException? = null

    private val provider = object : Provider("AndroidKeyStore", 1.0, "JVM test stand-in for AndroidKeyStore") {
        init {
            putService(object : Service(this, "KeyStore", "AndroidKeyStore", Store::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = Store()
            })
            putService(object : Service(this, "KeyGenerator", "AES", Generator::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = Generator()
            })
        }
    }

    fun install() {
        keys.clear()
        refuseKeyGeneration = null
        Security.removeProvider(provider.name)
        Security.addProvider(provider)
    }

    fun uninstall() {
        Security.removeProvider(provider.name)
        keys.clear()
        refuseKeyGeneration = null
    }

    private class Store : KeyStoreSpi() {
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    private class Generator : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null
        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("a KeyGenParameterSpec is required")
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) { spec = params as KeyGenParameterSpec }
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("a KeyGenParameterSpec is required")
        override fun engineGenerateKey(): SecretKey {
            refuseKeyGeneration?.let { throw it }
            val spec = checkNotNull(spec)
            val bytes = ByteArray(spec.keySize / 8).also(SecureRandom()::nextBytes)
            return SecretKeySpec(bytes, "AES").also { keys[spec.keystoreAlias] = it }
        }
    }
}
