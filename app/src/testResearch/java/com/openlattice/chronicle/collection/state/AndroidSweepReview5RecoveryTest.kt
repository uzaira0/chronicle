package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.device.healthCheckpointScopeKey
import com.openlattice.chronicle.collection.device.healthCheckpointEndKey
import com.openlattice.chronicle.collection.device.healthConnectTestFeatures
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.audit.auditWorkerParameters
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
import androidx.work.Data
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.WorkDatabase
import androidx.work.impl.background.systemjob.SystemJobScheduler
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.impl.model.WorkProgress
import com.openlattice.chronicle.ChronicleApplication
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.collection.device.ExpansionCollectionWorker
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.device.HealthMetricCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricModuleHolderTestShadow
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkedSQLiteOpenHelperFactory
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
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
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private var beforeSql: (String) -> Unit = {}

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
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .openHelperFactory(checkedSQLiteOpenHelperFactory { beforeSql(it) }).build()
        shadowOf(context.getSystemService(android.hardware.SensorManager::class.java))
            .addSensor(org.robolectric.shadows.SensorBuilder.newBuilder().setType(android.hardware.Sensor.TYPE_ACCELEROMETER).build())
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study",
            url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceRandomDeviceId = "device"))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleId.HEALTH_CONNECT,
            SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)).map {
            CollectionModuleStateEntity(it.id, true, ParticipantDecision.ACCEPTED.name, 1, false, 1)
        })
        ResearchPersistenceGate.initialize(context)
        Unit
    }

    @After fun tearDown() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        HealthMetricModuleHolderTestShadow.module = null
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test @Config(sdk = [26]) fun startupRecoveryRetriesCleanupBeforeReenqueuingOrphanedPeriodicWork() {
        val failing = AtomicBoolean(false)
        val attempts = AtomicInteger()
        val configuration = Configuration.Builder().setExecutor(Executor { }).build()
        val taskExecutor = WorkManagerTaskExecutor(configuration.taskExecutor)
        val workDb = Room.inMemoryDatabaseBuilder(context, WorkDatabase::class.java)
            .setQueryExecutor(configuration.taskExecutor)
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if (sql == "SELECT * FROM workspec WHERE state=1") {
                    attempts.incrementAndGet()
                    if (failing.get()) throw SQLiteException("temporary startup cleanup failure")
                }
            }).build()
        // The native WorkManager factory accepts the real database and OS scheduler directly.
        val wm = workManagerRule.install(configuration) { app, config ->
            WorkManagerImpl(app, config, taskExecutor, workDb, schedulersCreator = { schedulerContext, schedulerConfig, _, database, _, _ ->
                listOf(SystemJobScheduler(schedulerContext, database, schedulerConfig))
            })
        }
        val initialized = CountDownLatch(1)
        wm.workTaskExecutor.executeOnTaskThread { initialized.countDown() }
        assertTrue(initialized.await(5, TimeUnit.SECONDS))
        worker { scheduleChronicleSyncWork(context) }
        val id = wm.getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS).single().id.toString()
        val dao = workDb.workSpecDao()
        worker {
            dao.setState(WorkInfo.State.RUNNING, id)
            dao.markWorkSpecScheduled(id, 1)
            workDb.workProgressDao().insert(WorkProgress(id, Data.Builder().putInt("collected", 1).build()))
        }
        assertEquals(WorkInfo.State.RUNNING, worker { dao.getState(id) })
        attempts.set(0)
        failing.set(true)
        val app = ChronicleApplication().also {
            shadowOf(it).callAttach(context)
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
            waitUntil { worker { dao.getWorkSpec(id)!!.scheduleRequestedAt >= 0 } }
        } finally {
            failing.set(false)
            waitUntil { !WorkSchedulingStatus.unavailable }
        }
    }

    @Test @Config(shadows = [HealthMetricModuleHolderTestShadow::class])
    fun refusedHealthAcknowledgmentRetriesTheSameWindowAfterStorageRecovery() = worker {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val previous = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        val checkpoint = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        checkpoint.edit().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS),
            ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!.key).commit()
        val permissions = Proxy.newProxyInstance(PermissionController::class.java.classLoader, arrayOf(PermissionController::class.java)) { _, _, _ ->
            setOf(HealthPermission.getReadPermission(StepsRecord::class),
                ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
        } as PermissionController
        val reads = AtomicInteger()
        val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getFeatures" -> healthConnectTestFeatures()
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
        val source = AndroidHealthMetricSource(context, clientProvider = { provider() })
        val fail = AtomicBoolean(true)
        val dao = db.healthMetricSampleDao()
        val closingDao = Proxy.newProxyInstance(HealthMetricSampleDao::class.java.classLoader, arrayOf(HealthMetricSampleDao::class.java)) { _,
            method, args ->
            val result = try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
            if (method.name == "insertAll" && fail.get()) ResearchPersistenceGate.closeForPersistenceFailure()
            result
        } as HealthMetricSampleDao
        val health = HealthMetricCollectionModule(HealthMetricSampleSink(closingDao,
            persistenceGuard = ResearchPersistenceGate.guard(context, CollectionModuleId.HEALTH_CONNECT)), source,
            enrolled = { true }, persistLease = { it() })
        HealthMetricModuleHolderTestShadow.module = health
        val collector = ExpansionCollectionWorker(context, auditWorkerParameters())
        assertEquals("refused checkpoint must request a local WorkManager retry", ListenableWorker.Result.retry(), collector.doWork())
        assertEquals(previous, checkpoint.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0))
        assertEquals(1, dao.count())
        fail.set(false)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        assertEquals(ListenableWorker.Result.success(), collector.doWork())
        assertEquals("the pending window must have been rejected so it can be read again", 2, reads.get())
        assertEquals("same record must deduplicate on retry", 1, dao.count())
        assertTrue(checkpoint.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0) > previous)
    }

    @Test fun sensorServiceRetriesItsOwnAuthorizationFailureAndRestartsCollectionOffline() {
        val failing = AtomicBoolean(true)
        val attempts = AtomicInteger()
        beforeSql = { sql ->
            if ("upload_servers" in sql && sql.startsWith("SELECT", ignoreCase = true) && failing.get()) {
                attempts.incrementAndGet()
                throw SQLiteException("temporary sensor authorization failure")
            }
        }
        val service = Robolectric.buildService(HardwareSensorService::class.java).create().get()
        try {
            waitUntil { attempts.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("a transient startup failure must keep its local retry alive", shadowOf(service).isStoppedBySelf)
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            failing.set(false)
            waitUntil {
                shadowOf(context.getSystemService(android.hardware.SensorManager::class.java)).listeners.isNotEmpty()
            }
            assertTrue(ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)))
        } finally {
            failing.set(false)
            service.onDestroy()
            beforeSql = {}
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("recovery did not complete before the deadline", predicate())
    }

}
