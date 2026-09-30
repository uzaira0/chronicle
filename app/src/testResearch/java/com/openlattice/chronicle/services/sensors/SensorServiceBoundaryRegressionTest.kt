package com.openlattice.chronicle.services.sensors

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.SensorSampleDao
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SensorServiceBoundaryRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sensor-service-boundary", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
    }
    @After fun tearDown() {
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun databaseRecoveryFailureKeepsLocalRetryWithoutEscapingMainOrWorker() {
        // Inject the same recovery failure as an encrypted Room open, at controller construction.
        db.javaClass.getDeclaredField("_sensorSampleDao").apply { isAccessible = true }.set(db,
            lazy<SensorSampleDao> { throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED) })
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        startup(service).submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        service.onDestroy()
        assertTrue(startup(service).awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun unlockControllerRecoveryFailureIsContainedAtExecutorBoundary() {
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        val escaped = java.util.concurrent.atomic.AtomicReference<Throwable>()
        startup(service).submit { Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.set(error) } }
            .get(5, TimeUnit.SECONDS)
        db.javaClass.getDeclaredField("_sensorSampleDao").apply { isAccessible = true }.set(db,
            lazy<SensorSampleDao> { throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED) })
        service.javaClass.getDeclaredField("directBootMode").apply { isAccessible = true }.setBoolean(service, true)
        service.javaClass.getDeclaredMethod("onUserUnlocked").apply { isAccessible = true }.invoke(service)
        startup(service).submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("unlock task must not escape its worker", escaped.get())
        // The initial unenrolled service may already have stopped itself; the unlock failure
        // is contained and leaves a retry pending until destruction cancels it.
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        service.onDestroy()
        assertTrue(startup(service).awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun teardownReturnsWhilePendingLossWaitsBehindQueuedPersistenceWriter() {
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        startup(service).submit {}.get(5, TimeUnit.SECONDS)
        val runtime = SensorRuntimeController(gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
            sink = object : SensorSampleWriter {
                override fun write(samples: List<SensorSampleEntry>): ModuleResult = ModuleResult.Failed(IllegalStateException("disk full"), "write failed")
            }, scheduler = ManualSensorRuntimeScheduler(executeImmediately = false), collectionGate = { true }, log = NoOpCollectionLog,
            reportLoss = { _, _ -> ResearchPersistenceGate.withReadLease {} })
        repeat(SensorRuntimeController.MAX_BUFFERED_SAMPLES + 1) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3) }
        service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.set(service, runtime)
        withQueuedWriter {
            val callback = Executors.newSingleThreadExecutor()
            try { callback.submit { service.onDestroy() }.get(500, TimeUnit.MILLISECONDS) }
            finally { callback.shutdown() }
        }
        assertTrue(startup(service).awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun sensorCallbackUsesCachedAdmissionWhilePauseDiagnosticsWaitBehindWriter() {
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        startup(service).submit {}.get(5, TimeUnit.SECONDS)
        val runtime = service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service) as SensorRuntimeController
        prefs.edit().putString("collection_storage_enrollment_scope", "${prefs.getString(STUDY_ID, "")}:participant")
            .putBoolean("collection_storage_paused", true).putString("collection_storage_pause_episode_id", "pause")
            .putString("collection_storage_pause_episode_at", "2026-09-29T00:00:00Z")
            .putBoolean("collection_storage_pause_episode_recorded", false).commit()
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }.setLong(StorageAdmission, Long.MIN_VALUE)
        withQueuedWriter {
            val callback = Executors.newSingleThreadExecutor()
            try {
                callback.submit { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3) }.get(500, TimeUnit.MILLISECONDS)
                assertEquals(0, runtime.bufferedCount)
            } finally { callback.shutdown() }
        }
        service.onDestroy()
        assertTrue(startup(service).awaitTermination(5, TimeUnit.SECONDS))
        // Drain the admission worker before the test's in-memory database is closed.
        val refresh = StorageAdmission::class.java.getDeclaredField("refreshExecutor").apply { isAccessible = true }.get(StorageAdmission) as ExecutorService
        refresh.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test fun cachedAdmissionContainsStorageProbeFailureOnRefreshWorker() {
        val refresh = StorageAdmission::class.java.getDeclaredField("refreshExecutor").apply { isAccessible = true }
            .get(StorageAdmission) as ExecutorService
        val escaped = AtomicReference<Throwable>()
        val originalWorker = refresh.submit<Thread> {
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.set(error) }
            Thread.currentThread()
        }.get(5, TimeUnit.SECONDS)
        val unavailable = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): java.io.File = throw java.io.IOException("storage unavailable")
        }
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, Long.MIN_VALUE)
        assertFalse(StorageAdmission.cachedAllowed(unavailable))
        val completedWorker = refresh.submit<Thread> { Thread.currentThread() }.get(5, TimeUnit.SECONDS)
        assertSame("a storage probe failure must not kill the refresh worker", originalWorker, completedWorker)
        assertNull("storage probe must not escape the refresh worker", escaped.get())
        assertFalse(StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }
            .getBoolean(StorageAdmission))
    }

    private fun startup(service: HardwareSensorService): ExecutorService =
        service.javaClass.getDeclaredField("startupExecutor").apply { isAccessible = true }.get(service) as ExecutorService

    private fun withQueuedWriter(action: () -> Unit) {
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier").apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(barrier) as ReentrantReadWriteLock
        val pool = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1); val release = CountDownLatch(1); val writerThread = AtomicReference<Thread>()
        val reader = pool.submit { ResearchPersistenceGate.withReadLease { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val writer = pool.submit { writerThread.set(Thread.currentThread()); lock.writeLock().lockInterruptibly(); lock.writeLock().unlock() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(writerThread.get()?.let(lock::hasQueuedThread) == true)
            action()
        } finally {
            writer.cancel(true); release.countDown(); reader.get(5, TimeUnit.SECONDS)
            pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
