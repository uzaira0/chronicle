package com.openlattice.chronicle.services.sensors

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import androidx.core.app.ServiceCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.createUnreadableChronicleDb
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SensorServiceBoundaryRegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private val lock = ReentrantReadWriteLock(true)
    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sensor-service-boundary", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        onPersistenceWorker { ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock)) }
    }
    @After fun tearDown() {
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun databaseRecoveryFailureKeepsLocalRetryWithoutEscapingMainOrWorker() {
        val accessed = CountDownLatch(1)
        val unavailable = createUnreadableChronicleDb(context,
            LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED), accessed::countDown)
        EnrollmentTestStores.db = unavailable
        val startup = ScheduledThreadPoolExecutor(1)
        val service = ServiceController.of(HardwareSensorService(startupExecutor = startup), null).create()
        try {
            assertTrue(accessed.await(5, TimeUnit.SECONDS))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
            assertEquals(Service.START_STICKY, service.get().onStartCommand(null, 0, 1))
        } finally {
            service.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
            unavailable.close()
        }
    }

    @Test fun unlockControllerRecoveryFailureIsContainedAtExecutorBoundary() {
        val user = shadowOf(context.getSystemService(UserManager::class.java))
        user.setUserUnlocked(false)
        val startup = ScheduledThreadPoolExecutor(1)
        val service = ServiceController.of(HardwareSensorService(startupExecutor = startup), null).create()
        val accessed = CountDownLatch(1)
        val unavailable = createUnreadableChronicleDb(context,
            LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED), accessed::countDown)
        try {
            startup.submit {}.get(5, TimeUnit.SECONDS)
            EnrollmentTestStores.db = unavailable
            user.setUserUnlocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(accessed.await(5, TimeUnit.SECONDS))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(Service.START_STICKY, service.get().onStartCommand(null, 0, 1))
        } finally {
            service.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
            unavailable.close()
        }
    }

    @Test fun teardownReturnsWhilePendingLossWaitsBehindQueuedPersistenceWriter() {
        val runtime = SensorRuntimeController(gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
            sink = object : SensorSampleWriter {
                override fun write(samples: List<SensorSampleEntry>): ModuleResult = ModuleResult.Failed(IllegalStateException("disk full"),
                    "write failed")
            }, scheduler = ManualSensorRuntimeScheduler(executeImmediately = false), collectionGate = { true },
            reportLoss = { _, _ -> ResearchPersistenceGate.withReadLease {} })
        repeat(SensorRuntimeController.MAX_BUFFERED_SAMPLES + 1) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3) }
        val startup = ScheduledThreadPoolExecutor(1)
        val service = ServiceController.of(HardwareSensorService(runtime, startup), null).create().get()
        startup.submit {}.get(5, TimeUnit.SECONDS)
        withQueuedWriter {
            val callback = Executors.newSingleThreadExecutor()
            try { callback.submit { service.onDestroy() }.get(500, TimeUnit.MILLISECONDS) }
            finally { callback.shutdown() }
        }
        assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun sensorCallbackUsesCachedAdmissionWhilePauseDiagnosticsWaitBehindWriter() {
        val runtime = SensorRuntimeController(gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
            sink = object : SensorSampleWriter {
                override fun write(samples: List<SensorSampleEntry>): ModuleResult = ModuleResult.Ok(samples.size)
            }, scheduler = ManualSensorRuntimeScheduler(), collectionGate = { StorageAdmission.cachedAllowed(context) })
        val startup = ScheduledThreadPoolExecutor(1)
        val service = ServiceController.of(HardwareSensorService(runtime, startup), null).create().get()
        startup.submit {}.get(5, TimeUnit.SECONDS)
        StorageAdmission.clear(context)
        prefs.edit().putString("collection_storage_enrollment_scope", "${prefs.getString(STUDY_ID, "")}:participant")
            .putBoolean("collection_storage_paused", true).putString("collection_storage_pause_episode_id", "pause")
            .putString("collection_storage_pause_episode_at", "2026-09-29T00:00:00Z")
            .putBoolean("collection_storage_pause_episode_recorded", false).commit()
        withQueuedWriter {
            val callback = Executors.newSingleThreadExecutor()
            try {
                callback.submit { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3) }.get(500, TimeUnit.MILLISECONDS)
                assertEquals(0, runtime.bufferedCount)
            } finally { callback.shutdown() }
        }
        service.onDestroy()
        assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
    }

    /** Android can demote the running service; the next start must promote it again. */
    @Test fun everyStartPromotesAgainAfterAndroidDemotesTheService() {
        val runtime = SensorRuntimeController(gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
            sink = object : SensorSampleWriter {
                override fun write(samples: List<SensorSampleEntry>): ModuleResult = ModuleResult.Ok(samples.size)
            }, scheduler = ManualSensorRuntimeScheduler(), collectionGate = { true })
        val startup = ScheduledThreadPoolExecutor(1)
        val controller = ServiceController.of(HardwareSensorService(runtime, startup), null).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
            assertFalse(shadowOf(service).isForegroundStopped)
            assertEquals(context.resources.getInteger(R.integer.sensor_service_notification_id),
                shadowOf(service).lastForegroundNotificationId)
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cachedAdmissionContainsStorageProbeFailureOnRefreshWorker() {
        val accessed = Semaphore(0)
        val threads = ConcurrentLinkedQueue<Thread>()
        val unavailable = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File {
                threads.add(Thread.currentThread())
                accessed.release()
                throw IOException("storage unavailable")
            }
        }
        StorageAdmission.clear(context)
        repeat(2) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var observed: Boolean
            do {
                assertFalse(StorageAdmission.cachedAllowed(unavailable))
                observed = accessed.tryAcquire(10, TimeUnit.MILLISECONDS)
            } while (!observed && System.nanoTime() < deadline)
            assertTrue("The refresh worker must attempt the native storage probe", observed)
        }
        assertSame("A failed storage probe must not kill its refresh worker", threads.first(), threads.last())
    }

    @Test fun aStartAfterCreationWasRefusedStillAttemptsPromotionBeforeStopping() {
        val startup = ScheduledThreadPoolExecutor(1)
        val controller = ServiceController.of(HardwareSensorService(startupExecutor = startup), null)
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(SecurityException("Test permission refusal"))
        try {
            controller.create()
            shadowOf(service).setThrowInStartForeground(null)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertTrue(shadowOf(service).isStoppedBySelf)
            // Stops with its own start token, so a start Android queued since still runs and promotes.
            assertEquals(1, shadowOf(service).stopSelfResultId)
        } finally {
            controller.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun aStartThatOutranksAnIdleStopDecidesAgainAndStopsWithItsOwnToken() {
        val startup = ScheduledThreadPoolExecutor(1)
        val controller = ServiceController.of(HardwareSensorService(startupExecutor = startup), null).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, shadowOf(service).stopSelfResultId)
            // Android refuses that stop when a newer start is already queued; the newer start must decide again.
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 7))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(7, shadowOf(service).stopSelfResultId)
            assertFalse(shadowOf(service).isForegroundStopped)
        } finally {
            controller.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun laterPromotionRefusalStopsCollectorsAndReceiversWithoutLateSamples() {
        onPersistenceWorker {
            AuditStores.enroll(context, db, prefs,
                mapOf(CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = true)))
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock))
        }
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val written = mutableListOf<SensorSampleEntry>()
        val runtime = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(),
            SensorSampleWriter { samples -> written.addAll(samples); ModuleResult.Ok(samples.size) }, scheduler,
            collectionGate = { true })
        val startup = ScheduledThreadPoolExecutor(1)
        val controller = ServiceController.of(HardwareSensorService(runtime, startup), null).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            startup.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(runtime.isStarted)
            assertTrue(gateway.registeredContinuous.isNotEmpty())
            runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3)
            assertEquals(1, runtime.bufferedCount)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            shadowOf(service).setThrowInStartForeground(SecurityException("Test permission refusal"))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertTrue(shadowOf(service).isStoppedBySelf)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, shadowOf(service).stopSelfResultId)
        } finally {
            controller.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse(runtime.isStarted)
        assertTrue(gateway.registeredContinuous.isEmpty())
        assertTrue(scheduler.isShutdown())
        assertTrue(shadowOf(context as Application).registeredReceivers.none { it.context === controller.get() })
        assertEquals(1, written.size) // The already-accepted sample is drained during teardown.
        runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(2f), 3)
        scheduler.runAll()
        assertEquals(0, runtime.bufferedCount)
        assertEquals(1, written.size)
    }

    private fun withQueuedWriter(action: () -> Unit) {
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
