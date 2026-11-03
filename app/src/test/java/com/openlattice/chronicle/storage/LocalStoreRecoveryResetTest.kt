package com.openlattice.chronicle.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.security.ProviderException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The participant confirms "reset local storage" on the recovery screen: every damaged store file is sealed with the
 * device-bound recovery key and verified before anything live is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalStoreRecoveryResetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val confirmed = LocalStoreResetConfirmation(preserveEncryptedRecoveryBundle = true, understandsReenrollmentRequired = true)
    private val database get() = context.getDatabasePath("chronicle_encrypted")
    private val keyRecord get() = context.getSharedPreferences("chronicle_db_key_prefs", Context.MODE_PRIVATE)
    private val securePrefsFile get() = File(context.applicationInfo.dataDir, "shared_prefs/chronicle_encrypted_prefs.xml")
    private val recoveryRoot get() = File(context.noBackupFilesDir, "chronicle-recovery")
    private lateinit var originals: Map<String, ByteArray>

    @Before fun damagedStore() {
        FakeAndroidKeyStore.install()
        keyRecord.edit().clear().commit()
        recoveryRoot.deleteRecursively()
        DatabaseKeyManager.getPassphrase(context, allowCreate = true)
        database.parentFile!!.mkdirs()
        database.writeBytes("damaged sqlcipher pages".toByteArray())
        File(database.path + "-wal").writeBytes("unapplied write-ahead log".toByteArray())
        val directBoot = File(context.createDeviceProtectedStorageContext().filesDir, "direct_boot_sensor_buffer/buffer.bin")
        directBoot.parentFile!!.mkdirs()
        directBoot.writeBytes("locked-boot samples".toByteArray())
        originals = mapOf(database.name to database.readBytes(), "${database.name}-wal" to File(database.path + "-wal").readBytes(),
            directBoot.name to directBoot.readBytes())
    }

    @After fun cleanUp() {
        FakeAndroidKeyStore.uninstall()
        listOf("", "-wal").forEach { File(database.path + it).delete() }
        securePrefsFile.delete()
        recoveryRoot.deleteRecursively()
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.openFailure = null
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** What the recovery key opens: the artifact's original bytes. */
    private fun opened(artifact: File): ByteArray = DataInputStream(artifact.inputStream()).use { input ->
        assertEquals("CHRREC1", String(ByteArray(7).also(input::readFully), Charsets.US_ASCII))
        val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, FakeAndroidKeyStore.keys.getValue("chronicle_recovery_key_v1"), GCMParameterSpec(128, iv))
        }.doFinal(input.readBytes())
    }

    @Test fun aConfirmedResetSealsEveryDamagedFileThenStartsAnEmptyStore() {
        val db = EnrollmentTestStores.install(context)
        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(UUID.randomUUID().toString(), "study", "participant", "device",
            "epoch", "2026-10-31", "USAGE", "TIMEOUT", 3, "2026-10-31T10:00:00Z", "2026-10-31T11:00:00Z", null, null))
        db.localDataQuarantineDao().insert(LocalDataQuarantineEntity("q1", "upload_diagnostics", "d1", null, null, null,
            byteArrayOf(1, 2, 3), "MALFORMED", "2026-10-31T10:00:00Z"))
        // The secure preferences no longer open either: they are preserved with the database.
        EnrollmentTestStores.prefs = null
        securePrefsFile.parentFile!!.mkdirs()
        securePrefsFile.writeText("<map>unreadable</map>")
        val securePrefs = securePrefsFile.readBytes()
        val backup = File(securePrefsFile.path + ".bak")
        backup.writeText("<map>unreadable backup</map>")
        val backupPrefs = backup.readBytes()
        val scope = LocalStoreRecoveryManager.scopeDigest("study", "participant")

        val bundle = LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED, confirmed, scope)
            .recoveryBundleDirectory

        val manifest = File(bundle, "manifest.txt").readLines()
        assertTrue(manifest.containsAll(listOf("format=chronicle-android-recovery-v1", "reason=DATABASE_OPEN_FAILED",
            "owner_scope_sha256=$scope", "diagnostics_separated=true", "diagnostic_artifact=diagnostic-snapshot.enc")))
        val sealed = bundle.listFiles()!!.filter { it.name.startsWith("artifact-") }
            .associateBy { it.name.substringAfter('-').substringAfter('-').removeSuffix(".enc") }
        assertEquals(setOf(database.name, "${database.name}-wal", "chronicle_db_key_prefs.xml", "buffer.bin",
            securePrefsFile.name, backup.name), sealed.keys)
        for ((name, original) in originals + mapOf(securePrefsFile.name to securePrefs, backup.name to backupPrefs)) {
            assertArrayEquals(name, original, opened(sealed.getValue(name)))
            assertTrue("manifest records the plaintext digest of $name",
                manifest.any { it.startsWith("artifact=${sealed.getValue(name).name},bytes=${original.size},sha256=${sha256(original)}") })
        }
        val diagnostics = JSONObject(String(opened(File(bundle, "diagnostic-snapshot.enc"))))
        val row = diagnostics.getJSONArray("upload_diagnostics").getJSONObject(0)
        assertEquals("TIMEOUT", row.getString("issueCode"))
        assertEquals(3L, row.getLong("count"))
        assertTrue(row.isNull("httpStatus"))
        assertEquals("AQID", diagnostics.getJSONArray("diagnostic_quarantine").getJSONObject(0).getString("rawData"))

        assertFalse("the damaged store is removed only after sealing", database.exists())
        assertFalse(File(database.path + "-wal").exists())
        assertTrue("the old key record is cleared", keyRecord.all.isEmpty())
        assertFalse("the sealed preference store is removed", securePrefsFile.exists())
        assertFalse("the sealed preference backup is removed", backup.exists())
        val recovered: SharedPreferences = Shadow.directlyOn(
            EncryptedPrefsHelper, EncryptedPrefsHelper::class.java, "getEncryptedPrefs",
            ClassParameter.from(Context::class.java, context),
        )
        assertTrue("the replacement preference store is readable and empty", recovered.all.isEmpty())
        assertArrayEquals("a new store gets a new key", DatabaseKeyManager.getPassphrase(context, allowCreate = true),
            DatabaseKeyManager.getPassphrase(context, allowCreate = false))
    }

    /** No database to read diagnostics from (the open itself failed): the bundle carries only the sealed files. */
    @Test fun aResetWithoutAReadableDatabaseStillSealsTheFiles() {
        EnrollmentTestStores.openFailure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.MISSING_KEY_MATERIAL)
        val bundle = LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.MISSING_KEY_MATERIAL, confirmed)
            .recoveryBundleDirectory
        val manifest = File(bundle, "manifest.txt").readLines()
        assertTrue(manifest.containsAll(listOf("reason=MISSING_KEY_MATERIAL", "owner_scope_sha256=unknown")))
        assertFalse(File(bundle, "diagnostic-snapshot.enc").exists())
        assertFalse(database.exists())
    }

    /** The keystore refuses the recovery key: nothing live is touched, so the participant can retry. */
    @Test fun aKeystoreThatCannotSealLeavesTheStoreAndKeyInPlace() {
        val record = keyRecord.all
        FakeAndroidKeyStore.refuseKeyGeneration = ProviderException("keystore unavailable")
        val error = assertThrows(IllegalStateException::class.java) {
            LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED, confirmed)
        }
        assertTrue(error.cause is ProviderException)
        assertArrayEquals(originals.getValue(database.name), database.readBytes())
        assertEquals(record, keyRecord.all)
    }

    @Test fun aResetNeedsBothConfirmationsAndADamagedStore() {
        for (partial in listOf(confirmed.copy(preserveEncryptedRecoveryBundle = false), confirmed.copy(understandsReenrollmentRequired = false))) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED, partial)
            }
        }
        assertTrue(database.exists())
        listOf("", "-wal").forEach { File(database.path + it).delete() }
        assertThrows(IllegalStateException::class.java) {
            LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED, confirmed)
        }
        assertFalse("no bundle without a damaged store", recoveryRoot.exists())
    }

    /** A module's pending erasure removes that owner's recovery bundles and unscoped ones; other owners' stay. */
    @Test fun aPendingErasureRemovesThatOwnersRecoveryBundles() {
        fun bundle(name: String, scope: String?) = File(recoveryRoot, name).apply {
            mkdirs(); scope?.let { File(this, "manifest.txt").writeText("owner_scope_sha256=$it\n") }
            File(this, "artifact-1-chronicle_encrypted.enc").writeText("sealed")
        }
        val mine = bundle("1", "A")
        val unscoped = bundle("2", null)
        val other = bundle("3", "B")
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, "A")
        assertFalse(mine.exists())
        assertFalse(unscoped.exists())
        assertTrue(other.exists())
    }

    /** The participant withdraws: their recovery bundles and the diagnostics kept from them are removed too. */
    @Test fun aWithdrawalRemovesTheParticipantsBundlesAndKeptDiagnostics() {
        val scope = LocalStoreRecoveryManager.scopeDigest("study", "participant")
        val kept = File(context.noBackupFilesDir, "chronicle-diagnostics-recovery/1").apply {
            mkdirs(); File(this, "manifest.txt").writeText("owner_scope_sha256=$scope\n")
            File(this, "diagnostic-snapshot.enc").writeText("sealed diagnostics")
        }
        val mine = File(recoveryRoot, "1").apply {
            mkdirs(); File(this, "manifest.txt").writeText("owner_scope_sha256=$scope\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
            File(this, "diagnostic-snapshot.enc").writeText("sealed diagnostics")
        }
        val other = File(recoveryRoot, "2").apply { mkdirs(); File(this, "manifest.txt").writeText("owner_scope_sha256=B\n") }
        LocalStoreRecoveryManager.eraseForEnrollment(context, "study", "participant")
        assertFalse(mine.exists())
        assertFalse("withdrawal keeps no diagnostics copy", kept.exists())
        assertTrue(other.exists())
    }
}
