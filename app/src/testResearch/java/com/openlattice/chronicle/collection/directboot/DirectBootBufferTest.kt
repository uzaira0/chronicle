package com.openlattice.chronicle.collection.directboot

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.UserManager
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.SettingsApplicationOutcome
import com.openlattice.chronicle.collection.state.ResearchErasureTestFixture
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.receivers.lifecycle.LockedBootReceiver
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.SensorSampleEntry
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStoreException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/** Locked-boot collection: snapshot-gated start, one encrypted file, consent-gated drain at unlock. */
class DirectBootBufferTest : ResearchErasureTestFixture() {
    private val module = CollectionModuleId.SENSOR_ACCELEROMETER

    private fun sample(id: String, type: AndroidSensorType = AndroidSensorType.accelerometer) = SensorSampleEntry(id, type.name,
        "2026-07-15T18:18:09.282Z", "UTC", 0.1f, -9.8f, 0.02f, null, 3)

    private fun quarantined(): List<File> = directBootBufferDir(context).listFiles().orEmpty()
        .filter { it.name.startsWith("corrupt-") }

    /** Unlocked refresh, then a locked runtime write through the service's direct-boot sink. */
    private fun collectWhileLocked(vararg ids: String): File {
        SensorSettings(context).applyResolvedSensors(mapOf(AndroidSensorType.accelerometer to
            AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))))
        DirectBootSnapshotWriter.refresh(context)
        val owner = DirectBootSensorSnapshot(context).owner()!!
        val sink = HardwareSensorService.directBootSink(DirectBootSampleBuffer(context, owner))
        ids.forEach { assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample(it)))) }
        return File(directBootBufferDir(context), "buffer-$owner.bin").also { assertTrue(it.length() > 0) }
    }

    private fun lockedBootStarts(): Int {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        var starts = 0
        val counting = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? { starts++; return intent.component }
        }
        try {
            LockedBootReceiver().onReceive(counting, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        } finally {
            shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        }
        return starts
    }

    @Test fun lockedBootThenUnlockQueuesTheBufferedRows() {
        val file = collectWhileLocked("a", "b")
        assertEquals(1, lockedBootStarts())
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals(2, db.sensorSampleDao().count())
        assertFalse("a drained buffer is deleted", file.exists())
    }

    @Test fun configuredSensorsKeepTheStudyRequestedSetBeforeHardwareFiltering() {
        val requested = AndroidDataCollectionSetting(modules = mapOf(
            module to CollectionModuleSetting(enabled = true),
            CollectionModuleId.SENSOR_GYROSCOPE to
                CollectionModuleSetting(enabled = true)))
        assertEquals(SettingsApplicationOutcome.APPLIED, onPersistenceWorker {
            coordinator.applyFetchedSettings(owner, fence.settingsGeneration(), requested)
        })
        assertEquals(setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            SensorSettings(context).getConfiguredSensors())
    }

    @Test fun disabledSensorKeepsPendingSamplesUntilCollectionResumes() {
        val file = collectWhileLocked("held")
        consent(module, ParticipantDecision.ACCEPTED, enabled = false)
        assertFalse(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertTrue(file.exists())
        assertEquals(0, db.sensorSampleDao().count())
        consent(module, ParticipantDecision.ACCEPTED)
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals(listOf("held"), db.sensorSampleDao().getOldest(10).map { it.id })
    }

    @Test fun anUnknownSensorIsRetainedUntilItsDispositionCanBeDetermined() {
        val file = collectWhileLocked("known")
        DirectBootSampleBuffer(file, KeystoreDirectBootRecordCipher()).append(
            listOf(sample("future").copy(sensorType = "future_sensor")))
        assertFalse(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertTrue(file.exists())
    }

    @Test fun sensorErasureDeletesUnreadableArchivesContainingItsSamples() {
        val file = collectWhileLocked("private")
        file.appendBytes(byteArrayOf(0, 0, 0, 3, 1, 2, 3))
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals(1, quarantined().size)
        decide(module, false)
        assertTrue(quarantined().isEmpty())
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun noSnapshotOrWithdrawnConsentCollectsNothing() {
        assertEquals("no snapshot, no locked-boot start", 0, lockedBootStarts())
        val file = collectWhileLocked("a")
        consent(module, ParticipantDecision.DECLINED)
        DirectBootSnapshotWriter.refresh(context)
        assertEquals("withdrawn consent, no locked-boot start", 0, lockedBootStarts())
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals("the drain refuses samples of a withdrawn sensor", 0, db.sensorSampleDao().count())
        assertFalse(file.exists())
    }

    @Test fun sensorErasureKeepsOtherSensorsAndWithdrawalDeletesTheFile() {
        val file = collectWhileLocked("a")
        assertEquals(ModuleResult.Ok(1), DirectBootSampleBuffer(file, KeystoreDirectBootRecordCipher())
            .append(listOf(sample("g", AndroidSensorType.gyroscope))))
        eraseAndReaccept(module)
        assertTrue("erasing one sensor keeps the other sensors' pre-unlock samples", file.exists())
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals("re-consent does not restore the erased sensor's samples", listOf("g"),
            db.sensorSampleDao().getOldest(10).map { it.id })
        val withdrawn = collectWhileLocked("b")
        assertTrue(clearDirectBootSensorBuffer(context))
        assertFalse("withdrawal and recovery erase every pre-unlock file", withdrawn.exists())
    }

    @Test fun corruptTailStillDrainsEarlierRecords() {
        val file = collectWhileLocked("a", "b")
        file.appendBytes(byteArrayOf(0, 0, 0, 64, 1, 2, 3))
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals(2, db.sensorSampleDao().count())
        assertFalse(file.exists())
    }

    @Test fun unreadableMiddleRecordKeepsEveryByte() {
        val file = collectWhileLocked("a")
        file.appendBytes(byteArrayOf(0, 0, 0, 3, 1, 2, 3))
        collectWhileLocked("b")
        val bytes = file.readBytes()
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals(1, db.sensorSampleDao().count())
        assertArrayEquals("the unreadable record and every record after it are kept", bytes, quarantined().single().readBytes())
        assertFalse("the next locked boot starts a fresh buffer", file.exists())
    }

    @Test fun capacityDropsAndSetAsideRecordsReachTheUploadDiagnostics() {
        val file = collectWhileLocked("a")
        RandomAccessFile(file, "rw").use { it.setLength(DirectBootSampleBuffer.MAX_BUFFER_BYTES.toLong()) }
        val buffer = DirectBootSampleBuffer(file, KeystoreDirectBootRecordCipher())
        assertEquals(ModuleResult.Ok(0), buffer.append(listOf(sample("b"), sample("c"))))
        assertEquals(ModuleResult.Ok(0), buffer.append(listOf(sample("d"))))
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals("records before the unreadable one still drain", 1, db.sensorSampleDao().count())
        val issues = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).recent() }
            .filter { it.moduleFamily == LocalUploadModuleFamily.SENSOR.name }
            .associate { it.issue to it.count }
        assertEquals(mapOf(LocalOperationalIssue.DIRECT_BOOT_CAPACITY_DROPPED.name to 3,
            LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD.name to 1), issues)
        assertTrue(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertEquals("a reported loss is reported once", 1, onPersistenceWorker {
            LocalUploadDiagnosticsStore.of(context).recent()
        }.single { it.issue == LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD.name }.count)
    }

    @Test fun missingKeyKeepsTheBuffer() {
        val file = collectWhileLocked("a")
        val bytes = file.readBytes()
        val missingKey = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("unused")
            override fun decrypt(blob: ByteArray): ByteArray = throw KeyStoreException("Direct-boot buffer key is missing")
        }
        assertTrue(DirectBootSampleBuffer(file, missingKey).drain({ _, _ -> }) { error("nothing is readable without the key") })
        assertArrayEquals(bytes, quarantined().single().readBytes())
    }

    @Test fun refusedStorageKeepsTheFileForARetry() {
        val file = collectWhileLocked("a")
        assertFalse(onPersistenceWorker { StorageAdmission.allowed(context, 0) })
        assertFalse(onPersistenceWorker { DirectBootDrainWorker.drain(context) })
        assertTrue("an unpersisted buffer survives for the next drain", file.length() > 0)
    }
}
