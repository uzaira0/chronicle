package com.openlattice.chronicle.storage

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.security.ProviderException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The local database key: created once for a new store, read back on every open, never replaced under old data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DatabaseKeyManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val record get() = context.getSharedPreferences("chronicle_db_key_prefs", Context.MODE_PRIVATE)

    @Before fun keystore() {
        FakeAndroidKeyStore.install()
        record.edit().clear().commit()
    }

    @After fun removeKeystore() = FakeAndroidKeyStore.uninstall()

    private fun recoveryReason(allowCreate: Boolean, on: Context = context) = assertThrows(LocalStoreRecoveryRequiredException::class.java) {
        DatabaseKeyManager.getPassphrase(on, allowCreate)
    }.recoveryReason

    @Test fun aNewStoreGetsOneKeyThatEveryLaterOpenReadsBack() {
        val created = DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        assertEquals(32, created.size)
        assertArrayEquals("a later open (no create) reads the same key", created, DatabaseKeyManager.getPassphrase(context, allowCreate = false))
        assertArrayEquals(created, DatabaseKeyManager.getPassphrase(context, allowCreate = true))
        assertEquals(setOf("chronicle_db_key"), FakeAndroidKeyStore.keys.keys)
    }

    /** The database files exist but the key record is gone: recovery, never a fresh key for the old ciphertext. */
    @Test fun aStoreWhoseKeyRecordIsGoneNeedsRecoveryInsteadOfANewKey() {
        assertEquals(LocalStoreRecoveryReason.MISSING_KEY_MATERIAL, recoveryReason(allowCreate = false))
        assertTrue("no key was created", FakeAndroidKeyStore.keys.isEmpty())
        assertTrue(record.all.isEmpty())
    }

    @Test fun aHalfWrittenKeyRecordNeedsRecovery() {
        for (only in listOf("encrypted_passphrase", "passphrase_iv")) {
            record.edit().clear().putString(only, "AAAA").commit()
            assertEquals(only, LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, recoveryReason(allowCreate = true))
            assertEquals(setOf(only), record.all.keys)
        }
    }

    /** The OS wiped the keystore (e.g. after a device credential reset): the old record is kept for recovery. */
    @Test fun aKeystoreThatLostTheKeyNeedsRecoveryAndKeepsTheRecord() {
        DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        val before = record.all
        FakeAndroidKeyStore.keys.clear()
        val error = assertThrows(LocalStoreRecoveryRequiredException::class.java) {
            DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        }
        assertEquals(LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, error.recoveryReason)
        assertEquals(LocalStoreRecoveryReason.MISSING_KEY_MATERIAL, (error.cause as LocalStoreRecoveryRequiredException).recoveryReason)
        assertEquals("the record is never replaced", before, record.all)
        assertTrue("no replacement key", FakeAndroidKeyStore.keys.isEmpty())
    }

    @Test fun aDamagedKeyRecordNeedsRecovery() {
        DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        val sealed = Base64.decode(record.getString("encrypted_passphrase", null), Base64.NO_WRAP)
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        record.edit().putString("encrypted_passphrase", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        assertEquals(LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, recoveryReason(allowCreate = true))
    }

    /** The full disk refuses the key record: the open fails as unsaved key material, and a later open starts cleanly. */
    @Test fun aKeyRecordTheFullDiskRefusesFailsAndALaterOpenSucceeds() {
        val full = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val healthy = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by healthy {
                    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by healthy.edit() {
                        override fun putString(key: String, value: String?) = this
                        override fun commit() = false
                    }
                }
            }
        }
        assertEquals(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, recoveryReason(allowCreate = true, on = full))
        assertTrue(record.all.isEmpty())
        val created = DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        assertArrayEquals(created, DatabaseKeyManager.getPassphrase(context, allowCreate = false))
    }

    @Test fun aKeystoreThatCannotCreateAKeyFailsWithoutARecord() {
        val refusal = ProviderException("keystore unavailable")
        FakeAndroidKeyStore.refuseKeyGeneration = refusal
        val error = assertThrows(LocalStoreRecoveryRequiredException::class.java) {
            DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        }
        assertEquals(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, error.recoveryReason)
        assertSame(refusal, error.cause)
        assertTrue(record.all.isEmpty())
    }
}
