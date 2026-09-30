package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@org.robolectric.annotation.Config(shadows = [com.openlattice.chronicle.collection.state.StorageRefusalPersistenceTest.TestDirectBootCipher::class])
@RunWith(RobolectricTestRunner::class)
class DirectBootErasureRegressionTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private val cipher = object : DirectBootRecordCipher {
        override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.reversedArray()
    }
    private val dir get() = File(temp.root, "samples").also { it.mkdirs() }
    private val admissionPrefs get() = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("direct_boot_storage_admission", Context.MODE_PRIVATE)
    private fun buffer() = DirectBootSampleBuffer(dir, cipher, NoOpCollectionLog)
    private fun configuredOwnerKey() = DirectBootSampleBuffer.ownerKey(DirectBootDiagnosticsJournal.configuredOwner(context)!!)
    private fun ownedBuffer() = DirectBootSampleBuffer(dir, cipher, NoOpCollectionLog, ownerForAppend = { configuredOwnerKey() })
    private fun sample(id: String, sensor: String = "accelerometer") = SensorSampleEntry(
        id, sensor, "2026-09-29T01:00:00Z", "UTC", 1f, 2f, 3f, null, 3,
    )
    private fun transfer(samples: List<SensorSampleEntry>) =
        DirectBootSampleBuffer.DrainTransfer(samples.mapTo(hashSetOf()) { it.id })
    private fun state(sensor: AndroidSensorType, enabled: Boolean) {
        if (!enabled) pendingPrefs.edit().putStringSet("sensor_types",
            pendingPrefs.getStringSet("sensor_types", emptySet()).orEmpty() + sensor.name).commit()
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            SensorCollectionModules.moduleFor(sensor).id, enabled, "ACCEPTED", null,
            false, 1, null, if (enabled) null else "DISCARD_AND_STOP",
        )))
    }
    private fun journalFile() = File(temp.root, "diagnostics.bin")
    private fun journal() = DirectBootDiagnosticsJournal(journalFile(), cipher)
    private fun journalState() = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
        cipher.decrypt(journalFile().readBytes()).toString(Charsets.UTF_8),
    )!!
    private fun owner(epoch: String) = DirectBootDiagnosticsJournal.Owner("study", "participant", "device", epoch)
    private fun writeJournal(owner: DirectBootDiagnosticsJournal.Owner) {
        journalFile().writeBytes(cipher.encrypt(JsonSerializer.toJson(
            DirectBootDiagnosticsJournal.State(owner),
        ).toByteArray()))
    }

    @Before fun setUp() {
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).build())
        }
        admissionPrefs.edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        val prefs = context.getSharedPreferences("direct-boot-regression", Context.MODE_PRIVATE)
        val study = "11111111-1111-1111-1111-111111111111"
        prefs.edit().clear().putString(STUDY_ID, study).putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = study,
            participantId = "participant", sourceDeviceId = "device",
        ))
    }

    @After fun tearDown() {
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        db.close()
    }

    private val pendingPrefs get() = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("direct_boot_pending_sensor_erasures", Context.MODE_PRIVATE)

    @Test fun closedPoliciesRetainAcceptedDirectBootSamples() {
        for ((decision, disposition) in listOf("ACCEPTED" to "HOLD_PENDING", "ACCEPTED" to "FLUSH_THEN_STOP", "UNDECIDED" to null)) {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
                SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer).id,
                decision == "UNDECIDED", decision, null, false, 2, null, disposition,
            )))
            val buffer = DirectBootSampleBuffer(temp.newFolder(), cipher, NoOpCollectionLog)
            buffer.append(listOf(sample("accepted")))
            val result = DirectBootDrainWorker.drainBuffer(context, buffer, "test-owner", null) {
                DirectBootSampleBuffer.DrainTransfer(emptySet())
            }
            assertTrue("$decision/$disposition must retain accepted records", result.failed)
            assertFalse(buffer.isEmpty())
        }
    }

    @Test fun interruptedDiscardIsErasedBeforeReacceptedSensorCanTransfer() {
        state(AndroidSensorType.accelerometer, true)
        state(AndroidSensorType.gyroscope, true)
        pendingPrefs.edit().putStringSet("sensor_types", setOf("accelerometer")).commit()
        val buffer = ownedBuffer()
        buffer.append(listOf(sample("discarded"), sample("keep", "gyroscope")))
        val written = mutableListOf<String>()
        DirectBootDrainWorker.drainBuffer(context, buffer, configuredOwnerKey(), null) {
            written += it.map { sample -> sample.id }; transfer(it)
        }
        assertEquals(listOf("keep"), written)
        assertTrue(pendingPrefs.getStringSet("sensor_types", emptySet()).orEmpty().isEmpty())
    }

    @Test fun declinePersistsErasureIntentBeforeFailingErasure() {
        state(AndroidSensorType.accelerometer, true)
        val hooked = failingErasureContext()
        assertThrows(Exception::class.java) {
            com.openlattice.chronicle.collection.state.CollectionLoopCoordinator(hooked).applyDecisions(
                emptySet(), setOf(SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)),
                com.openlattice.chronicle.collection.ConsentTrigger.entries.first(),
            )
        }
        assertEquals("DECLINED", db.collectionModuleStateDao().get(SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer).id)!!.decision)
        assertEquals(setOf("accelerometer"), pendingPrefs.getStringSet("sensor_types", emptySet()))
    }

    private fun failingErasureContext(): Context {
        val failingEditor = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            if (method.name == "commit") false else proxy
        } as android.content.SharedPreferences.Editor
        val failingPrefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") failingEditor else method.invoke(admissionPrefs, *(args ?: emptyArray()))
        } as android.content.SharedPreferences
        val protected = object : android.content.ContextWrapper(context.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "direct_boot_storage_admission") failingPrefs else super.getSharedPreferences(name, mode)
        }
        return object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = protected
        }
    }

    @Test fun failedPendingErasureRetainsIntentAndPreventsTransfer() {
        state(AndroidSensorType.accelerometer, true)
        pendingPrefs.edit().putStringSet("sensor_types", setOf("accelerometer")).commit()
        val buffer = ownedBuffer()
        buffer.append(listOf(sample("discarded")))
        var transferred = false
        val result = DirectBootDrainWorker.drainBuffer(failingErasureContext(), buffer, configuredOwnerKey(), null) {
            transferred = true; transfer(it)
        }
        assertTrue(result.failed)
        assertFalse(transferred)
        assertFalse(buffer.isEmpty())
        assertEquals(setOf("accelerometer"), pendingPrefs.getStringSet("sensor_types", emptySet()))
    }

    @Test fun pendingErasureIsAccountedExactlyOnceByRecoveryDrain() {
        state(AndroidSensorType.accelerometer, false)
        pendingPrefs.edit().putStringSet("sensor_types", setOf("accelerometer")).commit()
        val buffer = ownedBuffer()
        buffer.append(listOf(sample("erased")))
        DirectBootDrainWorker.drainBuffer(context, buffer, null, null, ::transfer)
        DirectBootDrainWorker.drainBuffer(context, buffer, null, null, ::transfer)
        val events = DirectBootDiagnosticsJournal(context)
        events.replay(context)
        assertEquals(1, db.uploadDiagnosticDao().forEnrollment(
            db.uploadServerDao().getConfiguredServer()!!.studyId, "participant", "device",
            db.uploadServerDao().getConfiguredServer()!!.let { "${it.id}:${it.createdAt}" },
        ).filter { it.issueCode == "MODULE_POLICY_ERASED" }.sumOf { it.count })
    }

    @Test fun unknownSensorDiscardIsJournaledOnceAsMalformed() {
        val losses = mutableListOf<Pair<String, Int>>()
        val buffer = DirectBootSampleBuffer(dir, cipher, NoOpCollectionLog,
            reportLoss = { code, count, _ -> losses += code to count })
        buffer.append(listOf(sample("unknown", "NO_SUCH_SENSOR")))
        repeat(2) {
            buffer.drain { batch -> DirectBootDrainWorker.persistGated(batch,
                sinkFor = { com.openlattice.chronicle.collection.sink.SensorSampleWriter { ModuleResult.Ok(it.size) } },
                log = NoOpCollectionLog) }
        }
        assertEquals(listOf("DIRECT_BOOT_CORRUPT_RECORD" to 1), losses)
        assertTrue(buffer.isEmpty())
    }

    @Test fun ramPolicyErasureIsCountedOnceByClearSensor() {
        com.openlattice.chronicle.collection.sink.SensorSampleWriter.discardSensorSamples(AndroidSensorType.accelerometer.name)
        val runtime = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            com.openlattice.chronicle.collection.sink.SensorSampleWriter { ModuleResult.Ok(it.size) },
            ManualSensorRuntimeScheduler(executeImmediately = false), log = NoOpCollectionLog)
        repeat(2) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3) }
        runtime.recordSample(AndroidSensorType.gyroscope, floatArrayOf(1f, 2f, 3f), 3)
        val apply = com.openlattice.chronicle.collection.state.CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDisposition",
            com.openlattice.chronicle.collection.CollectionModuleId::class.java, com.openlattice.chronicle.collection.CollectionDataDisposition::class.java)
            .apply { isAccessible = true }
        repeat(2) { apply.invoke(com.openlattice.chronicle.collection.state.CollectionLoopCoordinator(context),
            SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer), com.openlattice.chronicle.collection.CollectionDataDisposition.DISCARD_AND_STOP) }
        assertEquals(1, runtime.bufferedCount)
        val server = db.uploadServerDao().getConfiguredServer()!!
        assertEquals(2, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceDeviceId, "${server.id}:${server.createdAt}").filter { it.issueCode == "MODULE_POLICY_ERASED" }.sumOf { it.count })
    }

    @Test fun directBootGenerationValidationSharesTheAppendLease() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val snapshot = DirectBootSensorSnapshot(context)
        snapshot.write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()))
        val buffer = buffer()
        val sink = HardwareSensorService.directBootSink(context, buffer)
        val barrier = com.openlattice.chronicle.collection.state.ResearchPersistenceGate::class.java.getDeclaredField("barrier")
            .apply { isAccessible = true }.get(com.openlattice.chronicle.collection.state.ResearchPersistenceGate)
        val gate = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(barrier) as java.util.concurrent.locks.ReentrantReadWriteLock
        val validated = java.util.concurrent.CountDownLatch(1)
        val appendNow = java.util.concurrent.CountDownLatch(1)
        val writerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val append = pool.submit {
            sink.writeCurrent(listOf(sample("stale"))) {
                validated.countDown()
                check(appendNow.await(5, java.util.concurrent.TimeUnit.SECONDS))
                true
            }
        }
        assertTrue(validated.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val discard = pool.submit {
            writerThread.set(Thread.currentThread())
            gate.writeLock().lockInterruptibly()
            try {
                buffer.eraseSensorType("accelerometer")
                snapshot.clear()
                snapshot.write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()))
            } finally { gate.writeLock().unlock() }
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!discard.isDone && writerThread.get()?.let(gate::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(discard.isDone || writerThread.get()?.let(gate::hasQueuedThread) == true)
            appendNow.countDown()
            append.get(3, java.util.concurrent.TimeUnit.SECONDS)
            discard.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("discard must erase the batch validated under its predecessor lease", buffer.isEmpty())
        } finally {
            appendNow.countDown()
            discard.cancel(true)
            pool.shutdownNow()
            check(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun sensorErasureDeletesUndecodableSamplesAndTailsButPreservesJournals() {
        val buffer = buffer()
        buffer.append(listOf(sample("erase"), sample("keep", "gyroscope")))
        val live = File(dir, "buffer.bin")
        val obfuscated = cipher.encrypt("""{"a":"test-owner","b":[{"a":"old","b":"accelerometer"}]}""".toByteArray())
        DataOutputStream(FileOutputStream(live, true)).use { it.writeInt(obfuscated.size); it.write(obfuscated) }
        live.appendBytes(byteArrayOf(0, 0, 1, 0, 42))
        live.copyTo(File(dir, "corrupt-existing.bin"))
        val quarantine = File(dir, "quarantine").also { it.mkdirs() }
        live.copyTo(File(quarantine, "owner-existing.bin"))
        val journals = listOf("diagnostics.bin", "diagnostics.tmp", "diagnostics-corrupt-existing.bin")
            .map { File(dir, it).also { file -> file.writeBytes(obfuscated) } }

        buffer.eraseSensorType("accelerometer", "test-owner")

        journals.forEach { assertArrayEquals(obfuscated, it.readBytes()) }
        assertFalse(dir.walkTopDown().filter { it.isFile && it !in journals }.any {
            it.readBytes().toList().windowed(obfuscated.size).any { bytes -> bytes == obfuscated.toList() }
        })
        listOf(live, File(dir, "corrupt-existing.bin"), File(quarantine, "owner-existing.bin")).forEachIndexed { i, file ->
            val replay = temp.newFolder("replay-$i")
            file.copyTo(File(replay, "buffer.bin"))
            val ids = mutableListOf<String>()
            val result = DirectBootSampleBuffer(replay, cipher, NoOpCollectionLog).drain { batch ->
                ids += batch.map { it.id }; transfer(batch)
            }
            assertEquals(listOf("keep"), ids)
            assertEquals(0, result.corruptRecordsDropped)
        }
        assertEquals(0, buffer.eraseSensorType("accelerometer", "test-owner"))
    }

    @Test fun sensorErasureScrubsOrphanedSampleCheckpoints() {
        val buffer = buffer()
        buffer.append(listOf(sample("erase"), sample("keep", "gyroscope")))
        val live = File(dir, "buffer.bin")
        val quarantine = File(dir, "quarantine").also { it.mkdirs() }
        val checkpoints = listOf(File(dir, "buffer.bin.tmp"), File(dir, "interrupted.bin.tmp"),
            File(quarantine, "owner-orphan.bin.tmp"))
        checkpoints.forEach { live.copyTo(it) }

        buffer.eraseSensorType("accelerometer", "test-owner")

        checkpoints.filter { it.exists() }.forEachIndexed { i, file ->
            val replay = temp.newFolder("tmp-$i")
            file.copyTo(File(replay, "buffer.bin"))
            val ids = mutableListOf<String>()
            DirectBootSampleBuffer(replay, cipher, NoOpCollectionLog).drain { batch ->
                ids += batch.map { it.id }; transfer(batch)
            }
            assertEquals(listOf("keep"), ids)
        }
    }

    @Test fun explicitErasureDropsUnknownSensorsButKeepsOtherSensorsLegacyRecords() {
        val buffer = buffer()
        buffer.append(listOf(sample("unknown-sensor", "UNKNOWN"), sample("keep", "gyroscope")))
        val legacy = cipher.encrypt(JsonSerializer.toJson(listOf(sample("unknown-owner", "gyroscope"))).toByteArray())
        DataOutputStream(FileOutputStream(File(dir, "buffer.bin"), true)).use {
            it.writeInt(legacy.size); it.write(legacy)
        }

        buffer.eraseSensorType("accelerometer", "test-owner")

        val ids = mutableListOf<String>()
        buffer.drain(enrollmentCreatedAt = "2026-09-29T00:00:00Z") { batch ->
            ids += batch.map { it.id }; transfer(batch)
        }
        // Ownerless records of a sensor that was not erased still follow the enrollment-time adoption rule.
        assertEquals(listOf("keep", "unknown-owner"), ids)
    }

    @Test fun drainFinishesInterruptedDiscardAcrossLiveDrainAndQuarantine() {
        val buffer = ownedBuffer()
        buffer.append(listOf(sample("old"), sample("keep", "gyroscope")))
        buffer.drain(configuredOwnerKey()) { DirectBootSampleBuffer.DrainTransfer(emptySet(), true) }
        buffer.append(listOf(sample("late")))
        val quarantine = File(dir, "quarantine").also { it.mkdirs() }
        File(dir, "buffer.bin").copyTo(File(quarantine, "owner-orphan.bin.tmp"))
        state(AndroidSensorType.accelerometer, false)
        state(AndroidSensorType.gyroscope, true)
        val written = mutableListOf<String>()

        val result = DirectBootDrainWorker.drainBuffer(context, buffer, configuredOwnerKey(), null) { batch ->
            DirectBootDrainWorker.persistGated(batch, sinkFor = { type ->
                com.openlattice.chronicle.collection.sink.SensorSampleWriter { samples ->
                    if (!CollectionLoopStore.of(context).collects(SensorCollectionModules.moduleFor(type))) {
                        ModuleResult.Skipped("inactive")
                    } else { written += samples.map { it.id }; ModuleResult.Ok(samples.size) }
                }
            }, log = NoOpCollectionLog)
        }

        assertEquals(listOf("keep"), written)
        assertFalse(result.failed)
        assertTrue(buffer.isEmpty())
        assertTrue(quarantine.listFiles().orEmpty().all { it.length() == 0L })
    }

    @Test fun unlockHandoverRejectsRamSamplesAfterLiveDiscardDespiteStaleSnapshot() {
        val buffer = buffer()
        DirectBootSensorSnapshot(context).write(mapOf(
            AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig(),
        ))
        state(AndroidSensorType.accelerometer, true)
        val collectable = DirectBootSensorSnapshot(context).collectableSensors()
        val controller = SensorRuntimeController(
            gateway = FakeSensorGateway(),
            settings = FakeSensorRuntimeSettings(sensors = collectable),
            sink = HardwareSensorService.directBootSink(context, buffer),
            scheduler = ManualSensorRuntimeScheduler(executeImmediately = false),
            collectionGate = { it in collectable },
            log = NoOpCollectionLog,
        )
        controller.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3)
        assertEquals(1, controller.bufferedCount)
        state(AndroidSensorType.accelerometer, false)
        buffer.eraseSensorType("accelerometer")
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)

        controller.stop(isServiceDestroy = true)

        assertTrue(buffer.isEmpty())
        assertEquals(0, controller.bufferedCount)
    }

    @Test fun directBootAppendReadsTheCurrentLockedSnapshot() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val snapshot = DirectBootSensorSnapshot(context)
        snapshot.write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()))
        val buffer = buffer()
        val sink = HardwareSensorService.directBootSink(context, buffer)
        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample("before"))))
        snapshot.clear()

        sink.write(listOf(sample("after")))

        val ids = mutableListOf<String>()
        buffer.drain { batch -> ids += batch.map { it.id }; transfer(batch) }
        assertEquals(listOf("before"), ids)
    }

    @Test fun unlockedAppendKeepsAcceptedSiblingAndRejectsDiscardedSensor() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        SensorSettings(context).save(AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            samplingRateHz = 5, dutyCycleActiveSeconds = 30, dutyCyclePeriodSeconds = 300,
        ))
        state(AndroidSensorType.accelerometer, true)
        state(AndroidSensorType.gyroscope, true)
        val buffer = buffer()
        val sink = HardwareSensorService.directBootSink(context, buffer)
        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample("before"))))
        state(AndroidSensorType.accelerometer, false)
        buffer.eraseSensorType("accelerometer")

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample("discarded"), sample("keep", "gyroscope"))))

        val ids = mutableListOf<String>()
        buffer.drain { batch -> ids += batch.map { it.id }; transfer(batch) }
        assertEquals(listOf("keep"), ids)
    }

    @Test fun activeSensorSurvivesStoragePauseAndTransfersOnRetry() {
        state(AndroidSensorType.accelerometer, true)
        val buffer = buffer()
        buffer.append(listOf(sample("retry")))
        val paused = DirectBootDrainWorker.drainBuffer(context, buffer, "test-owner", null) {
            DirectBootSampleBuffer.DrainTransfer(emptySet(), true)
        }
        assertTrue(paused.failed)
        assertFalse(buffer.isEmpty())

        val resumed = DirectBootDrainWorker.drainBuffer(context, buffer, "test-owner", null, ::transfer)

        assertEquals(1, resumed.persisted)
        assertFalse(resumed.failed)
        assertTrue(buffer.isEmpty())
    }

    @Test fun pausedDestinationDrainFinishesDiscardWithoutImportingActiveSibling() {
        val server = db.uploadServerDao().getConfiguredServer()!!
        db.uploadServerDao().update(server.copy(enabled = false))
        val owner = DirectBootSampleBuffer.ownerKey(DirectBootDiagnosticsJournal.configuredOwner(context)!!)
        val buffer = DirectBootSampleBuffer(dir, cipher, NoOpCollectionLog, ownerForAppend = { owner })
        buffer.append(listOf(sample("discarded"), sample("keep", "gyroscope")))
        state(AndroidSensorType.accelerometer, false)
        state(AndroidSensorType.gyroscope, true)
        var imported = false

        val result = DirectBootDrainWorker.drainBuffer(context, buffer, null, null) {
            imported = true; transfer(it)
        }

        assertFalse(imported)
        assertTrue(result.failed)
        val ids = mutableListOf<String>()
        buffer.drain(owner) { batch -> ids += batch.map { it.id }; transfer(batch) }
        assertEquals(listOf("keep"), ids)
    }

    @Test fun globalRequiredModulePauseKeepsAcceptedSensorSamples() {
        state(AndroidSensorType.accelerometer, true)
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            SensorCollectionModules.moduleFor(AndroidSensorType.gyroscope).id, true, "DECLINED", null,
            true, 1, null, null,
        )))
        val buffer = buffer()
        buffer.append(listOf(sample("keep")))

        val result = DirectBootDrainWorker.drainBuffer(context, buffer, "test-owner", null) {
            DirectBootSampleBuffer.DrainTransfer(emptySet(), true)
        }

        assertTrue(result.failed)
        assertFalse(buffer.isEmpty())
    }

    @Test fun pendingPauseIsDroppedWhenEnrollmentOwnerChanges() {
        writeJournal(owner("A"))
        val failing = DirectBootDiagnosticsJournal(journalFile(), object : DirectBootRecordCipher {
            override fun decrypt(blob: ByteArray) = cipher.decrypt(blob)
            override fun encrypt(plaintext: ByteArray): ByteArray = error("journal unavailable")
        })
        assertThrows(Exception::class.java) {
            DirectBootStorageAdmission.evaluate(context, buffer(), LOCAL_STORAGE_RESERVE_BYTES - 1, failing)
        }
        val episode = admissionPrefs.getString("pause_episode_id", null)
        writeJournal(owner("B"))

        assertTrue(DirectBootStorageAdmission.evaluate(context, buffer(), LOCAL_STORAGE_RESERVE_BYTES, journal()))

        assertTrue(journalState().events.none { it.id == episode })
        assertNull(admissionPrefs.getString("pause_episode_id", null))
    }

    @Test fun explicitSensorErasureClearsPendingAdmissionEpisode() {
        admissionPrefs.edit().putBoolean("paused", true).putString("pause_episode_id", "A")
            .putString("pause_episode_at", "2026-09-29T00:00:00Z").commit()

        DistributionRestrictedRuntime.eraseDirectBootSensorSamples(context, "accelerometer", buffer(), journal(), {})

        assertTrue(admissionPrefs.all.isEmpty())
    }
}
