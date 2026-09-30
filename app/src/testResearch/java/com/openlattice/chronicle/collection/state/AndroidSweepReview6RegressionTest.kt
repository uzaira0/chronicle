package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.storage.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview6RegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sensorModule = SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)
    private lateinit var db: ChronicleDb

    private fun <T> worker(action: () -> T): T {
        val future = CompletableFuture<T>()
        ResearchPersistenceGate.executeAsync {
            try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    @Before fun setUp() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("sweep-review6", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED")
            .putStringSet("sensor_enabled_types", setOf(AndroidSensorType.accelerometer.name)).commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceDeviceId = "device"))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleId.HEALTH_CONNECT, CollectionModuleId.BATTERY_TELEMETRY, sensorModule).map {
            CollectionModuleStateEntity(it.id, true, ParticipantDecision.ACCEPTED.name, 1, false, 1, null, null)
        })
        ResearchPersistenceGate.initialize(context)
    }

    @After fun tearDown() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    private fun swapModuleStateDao(failing: AtomicBoolean, method: String, attempts: AtomicInteger = AtomicInteger()): () -> Unit {
        val dao = db.collectionModuleStateDao()
        val proxy = Proxy.newProxyInstance(CollectionModuleStateDao::class.java.classLoader, arrayOf(CollectionModuleStateDao::class.java)) { _, m, args ->
            if (m.name == method && failing.get()) {
                attempts.incrementAndGet()
                throw SQLiteException("temporary module-state failure")
            }
            try { m.invoke(dao, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        }
        val field = db.javaClass.getDeclaredField("_collectionModuleStateDao").apply { isAccessible = true }
        field.set(db, lazyOf(proxy))
        return { field.set(db, lazyOf(dao)) }
    }

    @Test fun transientStartupGateReadRetriesInsteadOfStoppingTheSensorService() {
        val failing = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val restore = swapModuleStateDao(failing, "countRequiredDeclined", attempts)
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        val startup = service.javaClass.getDeclaredField("startupExecutor").apply { isAccessible = true }.get(service) as ExecutorService
        try {
            waitUntil { attempts.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("a transient gate read must not be treated as 'no sensor enabled'", shadowOf(service).isStoppedBySelf)
            failing.set(false)
            waitUntil {
                (service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service) as? SensorRuntimeController)?.isStarted == true
            }
        } finally {
            failing.set(false)
            service.onDestroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
            restore()
        }
    }

    @Test fun declineWhoseSaveFailedReopensCollectionOnceReplayCompletesIt() {
        val failing = AtomicBoolean(true)
        val restore = swapModuleStateDao(failing, "upsertAll")
        val coordinator = CollectionLoopCoordinator(context)
        try {
            val error = runCatching { worker {
                CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDecisionsIf", Set::class.java,
                    Set::class.java, ConsentTrigger::class.java, Boolean::class.javaPrimitiveType,
                    kotlin.Function0::class.java).apply { isAccessible = true }
                    .invoke(coordinator, emptySet<CollectionModuleId>(), setOf(CollectionModuleId.BATTERY_TELEMETRY),
                        ConsentTrigger.values().first(), false, { true })
            } }.exceptionOrNull()
            assertNotNull("the decline's state save must fail", error)
            assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.HEALTH_CONNECT))
            failing.set(false)
            worker { coordinator.replayPendingErasures() }
            assertEquals(ParticipantDecision.DECLINED, worker { CollectionLoopStore.of(context).loadAll()[CollectionModuleId.BATTERY_TELEMETRY]?.decision })
            assertTrue("the decline's outcome is durable, so accepted modules must collect again",
                ResearchPersistenceGate.collectsNow(context, CollectionModuleId.HEALTH_CONNECT))
            assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
        } finally {
            failing.set(false)
            restore()
        }
    }

    @Test fun declineThatNeverCommittedKeepsCollectionClosed() {
        val failing = AtomicBoolean(true)
        val restore = swapModuleStateDao(failing, "upsertAll")
        val coordinator = CollectionLoopCoordinator(context)
        try {
            // The replayed save fails too, so the decline never becomes durable.
            runCatching { worker {
                CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDecisionsIf", Set::class.java,
                    Set::class.java, ConsentTrigger::class.java, Boolean::class.javaPrimitiveType,
                    kotlin.Function0::class.java).apply { isAccessible = true }
                    .invoke(coordinator, emptySet<CollectionModuleId>(), setOf(CollectionModuleId.BATTERY_TELEMETRY),
                        ConsentTrigger.values().first(), false, { true })
            } }
            runCatching { worker { coordinator.replayPendingErasures() } }
            assertEquals(ParticipantDecision.ACCEPTED, worker { CollectionLoopStore.of(context).loadAll()[CollectionModuleId.BATTERY_TELEMETRY]?.decision })
            assertFalse("an unfulfilled decline must keep collection closed",
                ResearchPersistenceGate.collectsNow(context, CollectionModuleId.HEALTH_CONNECT))
        } finally {
            failing.set(false)
            restore()
        }
    }

    @Test fun failedDiagnosticPreservationStillErasesResearchButKeepsSeparateDiagnostics() {
        val root = File(context.noBackupFilesDir, "chronicle-recovery").apply { deleteRecursively(); mkdirs() }
        val bundle = File(root, "bundle-1").apply { mkdirs() }
        File(bundle, "manifest.txt").writeText("owner_scope_sha256=unknown\ndiagnostics_separated=true\ndiagnostic_artifact=upload-diagnostics.json\n")
        val research = File(bundle, "chronicle.db").apply { writeText("research payload") }
        val diagnostics = File(bundle, "upload-diagnostics.json").apply { writeText("{\"diagnostics\":1}") }
        // A file where the preservation directory must go makes preservation fail (like a full disk).
        val blocker = File(context.noBackupFilesDir, "chronicle-diagnostics-recovery").apply { deleteRecursively(); writeText("x") }
        try {
            LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
            assertFalse("explicit erasure must still remove research artifacts", research.exists())
            assertTrue("separately extracted diagnostics must survive a failed preservation", diagnostics.isFile)
            blocker.delete()
            LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
            assertFalse(bundle.exists())
            assertTrue(File(context.noBackupFilesDir, "chronicle-diagnostics-recovery/bundle-1/upload-diagnostics.json").isFile)
        } finally {
            blocker.delete()
            root.deleteRecursively()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition not reached before the deadline", predicate())
    }
}
