package com.openlattice.chronicle.collection.state

import android.app.Application
import android.app.Service
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkProgress
import androidx.work.impl.model.WorkSpecDao
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.ChronicleApplication
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.device.*
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.storage.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview5RecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
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
        val prefs = context.getSharedPreferences("sweep-review5-recovery", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED")
            .putStringSet("sensor_enabled_types", setOf(AndroidSensorType.accelerometer.name)).commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceDeviceId = "device"))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleId.HEALTH_CONNECT,
            SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)).map {
            CollectionModuleStateEntity(it.id, true, ParticipantDecision.ACCEPTED.name, 1, false, 1, null, null)
        })
        ResearchPersistenceGate.initialize(context)
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        Unit
    }

    @After fun tearDown() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, null)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test @Config(sdk = [26]) fun row1_startupRecoveryRetriesCleanupBeforeReenqueuingOrphanedPeriodicWork() {
        val wm = WorkManagerImpl.getInstance(context)
        // A fresh process has no in-process execution of the previous process's RUNNING row.
        // Retain the real OS scheduler but disable GreedyScheduler's immediate worker launch.
        val schedulers = WorkManagerImpl::class.java.getDeclaredField("mSchedulers").apply { isAccessible = true }
        val originalSchedulers = wm.schedulers
        schedulers.set(wm, originalSchedulers.filter { it.hasLimitedSchedulingSlots() })
        worker { scheduleChronicleSyncWork(context) }
        val id = wm.getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS).single().id.toString()
        val workDb = wm.workDatabase
        val dao = workDb.workSpecDao()
        worker {
            dao.setState(WorkInfo.State.RUNNING, id)
            dao.markWorkSpecScheduled(id, 1)
            workDb.workProgressDao().insert(WorkProgress(id, Data.Builder().putInt("collected", 1).build()))
        }
        assertEquals(WorkInfo.State.RUNNING, worker { dao.getState(id) })
        val failing = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val proxy = Proxy.newProxyInstance(WorkSpecDao::class.java.classLoader, arrayOf(WorkSpecDao::class.java)) { _, method, args ->
            if (method.name == "getRunningWork") {
                attempts.incrementAndGet()
                if (failing.get()) throw SQLiteException("temporary startup cleanup failure")
            }
            try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        val field = workDb.javaClass.getDeclaredField("_workSpecDao").apply { isAccessible = true }
        field.set(workDb, proxy)
        val app = ChronicleApplication().also {
            Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }.invoke(it, context)
        }
        try {
            app.workManagerConfiguration.initializationExceptionHandler!!.accept(SQLiteException("startup exhausted its attempts"))
            waitUntil { attempts.get() >= 2 }
            assertTrue("enqueue success must not mask failed startup reconciliation", WorkSchedulingStatus.unavailable)
            assertEquals(WorkInfo.State.RUNNING, worker { dao.getState(id) })
            failing.set(false)
            waitUntil { !WorkSchedulingStatus.unavailable }
            assertEquals(WorkInfo.State.ENQUEUED, worker { dao.getState(id) })
            assertNull("orphaned progress must be cleared", worker { workDb.workProgressDao().getProgressForWorkSpecId(id) })
            assertTrue("recovered work must reach the scheduler", worker { dao.getWorkSpec(id)!!.scheduleRequestedAt >= 0 })
        } finally {
            failing.set(false)
            field.set(workDb, dao)
            waitUntil { !WorkSchedulingStatus.unavailable }
            schedulers.set(wm, originalSchedulers)
        }
    }

    @Test fun row2_refusedHealthAcknowledgmentRetriesTheSameWindowAfterStorageRecovery() = worker {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val source = AndroidHealthMetricSource(context)
        val previous = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        val checkpoint = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        checkpoint.edit().putLong("last_end_millis", previous).putString("consent_scope",
            ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!.first).commit()
        val permissions = Proxy.newProxyInstance(PermissionController::class.java.classLoader, arrayOf(PermissionController::class.java)) { _, _, _ ->
            setOf(HealthPermission.getReadPermission(StepsRecord::class))
        } as PermissionController
        val reads = AtomicInteger()
        val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getPermissionController" -> permissions
                "readRecords" -> {
                    reads.incrementAndGet()
                    ReflectionHelpers.callConstructor(ReadRecordsResponse::class.java,
                        ReflectionHelpers.ClassParameter.from(List::class.java, listOf(StepsRecord(
                            Instant.ofEpochMilli(previous + 1), null, Instant.ofEpochMilli(previous + 1000), null, 42,
                            Metadata.manualEntry()))),
                        ReflectionHelpers.ClassParameter.from(String::class.java, null))
                }
                else -> error("unexpected provider method ${method.name}")
            }
        } as HealthConnectClient
        val provider: () -> HealthConnectClient? = { client }
        source.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }.set(source, provider)
        val fail = AtomicBoolean(true)
        val dao = db.healthMetricSampleDao()
        val closingDao = Proxy.newProxyInstance(HealthMetricSampleDao::class.java.classLoader, arrayOf(HealthMetricSampleDao::class.java)) { _, method, args ->
            val result = try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
            if (method.name == "insertAll" && fail.get()) ResearchPersistenceGate.closeForPersistenceFailure()
            result
        } as HealthMetricSampleDao
        val health = HealthMetricCollectionModule(HealthMetricSampleSink(closingDao,
            persistenceGuard = ResearchPersistenceGate.guard(context, CollectionModuleId.HEALTH_CONNECT)), source,
            enrolled = { true }, log = NoOpCollectionLog)
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, health)
        val collector = ExpansionCollectionWorker(context, workerParameters())
        assertEquals("refused checkpoint must request a local WorkManager retry", ListenableWorker.Result.retry(), collector.doWork())
        assertEquals(previous, checkpoint.getLong("last_end_millis", 0))
        assertEquals(1, dao.count())
        fail.set(false)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        assertEquals(ListenableWorker.Result.success(), collector.doWork())
        assertEquals("the pending window must have been rejected so it can be read again", 2, reads.get())
        assertEquals("same record must deduplicate on retry", 1, dao.count())
        assertTrue(checkpoint.getLong("last_end_millis", 0) > previous)
    }

    @Test fun row4_sensorServiceRetriesItsOwnAuthorizationFailureAndRestartsCollectionOffline() {
        val dao = db.uploadServerDao()
        val failing = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val proxy = Proxy.newProxyInstance(UploadServerDao::class.java.classLoader, arrayOf(UploadServerDao::class.java)) { _, method, args ->
            if (method.name == "getConfiguredServer" && failing.get()) {
                attempts.incrementAndGet()
                throw SQLiteException("temporary sensor authorization failure")
            }
            try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        val field = db.javaClass.getDeclaredField("_uploadServerDao").apply { isAccessible = true }
        field.set(db, lazyOf(proxy))
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        val startup = service.javaClass.getDeclaredField("startupExecutor").apply { isAccessible = true }.get(service) as ExecutorService
        try {
            waitUntil { attempts.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("a transient startup failure must keep its local retry alive", shadowOf(service).isStoppedBySelf)
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            failing.set(false)
            waitUntil {
                (service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service) as? SensorRuntimeController)?.isStarted == true
            }
            assertTrue(ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)))
        } finally {
            failing.set(false)
            service.onDestroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
            field.set(db, lazyOf(dao))
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("recovery did not complete before the deadline", predicate())
    }

    private fun workerParameters(): WorkerParameters {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ ->
            error("no progress expected")
        } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ ->
            error("no foreground expected")
        } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, kotlin.coroutines.EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground)
    }
}
