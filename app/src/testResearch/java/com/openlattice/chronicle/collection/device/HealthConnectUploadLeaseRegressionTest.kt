package com.openlattice.chronicle.collection.device

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [HealthMetricModuleHolderTestShadow::class])
class HealthConnectUploadLeaseRegressionTest {
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lock = ReentrantReadWriteLock(true)
    private lateinit var db: ChronicleDb
    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("health-upload-order", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceRandomDeviceId = "device"))
        ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock))
    }
    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        HealthMetricModuleHolderTestShadow.module = null
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun bindingReadDoesNotHoldUploadLeasesAndFinalPersistenceRechecksStop() {
        val sourceEntered = CountDownLatch(1); val bindingCompleted = CountDownLatch(1)
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao(),
            persistenceGuard = ResearchPersistenceGate.guard(context)), source = {
            sourceEntered.countDown()
            check(bindingCompleted.await(5, TimeUnit.SECONDS))
            listOf(HealthMetricReading(HealthMetricType.STEPS,
                1.0, "count", 1, 2, "provider"))
        }, enrolled = { true }, persistLease = { ResearchPersistenceGate.withReadLease(it) })
        HealthMetricModuleHolderTestShadow.module = module
        val pool = Executors.newFixedThreadPool(3)
        val upload = pool.submit<ListenableWorker.Result> { worker().doWork() }
        assertTrue(sourceEntered.await(5, TimeUnit.SECONDS))
        val writer = pool.submit { lock.writeLock().lockInterruptibly(); try { ResearchPersistenceGate.closeForPersistenceFailure() } finally {
            lock.writeLock().unlock() } }
        val queueWriter = pool.submit { UploadQueueSingleFlight.withExclusiveMutation {} }
        try {
            // On the old order both writers wait for a worker that is itself waiting for MAIN binding.
            writer.get(1, TimeUnit.SECONDS)
            queueWriter.get(1, TimeUnit.SECONDS)
            bindingCompleted.countDown()
            assertEquals(ListenableWorker.Result.success(), upload.get(5, TimeUnit.SECONDS))
            assertEquals(0, db.healthMetricSampleDao().count())
        } finally {
            writer.cancel(true); bindingCompleted.countDown()
            pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun completedHealthReadCannotPersistIntoAReplacementEnrollment() {
        val sourceEntered = CountDownLatch(1); val bindingCompleted = CountDownLatch(1)
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao(),
            persistenceGuard = ResearchPersistenceGate.guard(context)), source = {
            sourceEntered.countDown()
            check(bindingCompleted.await(5, TimeUnit.SECONDS))
            listOf(HealthMetricReading(HealthMetricType.STEPS,
                1.0, "count", 1, 2, "provider"))
        }, enrolled = { true }, persistLease = { ResearchPersistenceGate.withReadLease(it) })
        HealthMetricModuleHolderTestShadow.module = module
        val pool = Executors.newSingleThreadExecutor()
        val upload = pool.submit<ListenableWorker.Result> { worker().doWork() }
        try {
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS))
            val old = db.uploadServerDao().getConfiguredServer()!!
            ResearchPersistenceGate.stop {
                db.uploadServerDao().delete(old.id)
                db.uploadServerDao().insert(old.copy(id = 0, sourceRandomDeviceId = "replacement-device"))
            }
            assertTrue("replacement enrollment itself remains active", ResearchPersistenceGate.isActiveEnrollment(context))
            bindingCompleted.countDown()
            val result = upload.get(5, TimeUnit.SECONDS)
            assertEquals(0, db.healthMetricSampleDao().count())
            // The replacement is enrolled, so its upload first polls the status; no study API answers here.
            assertEquals(ListenableWorker.Result.retry(), result)
        } finally {
            bindingCompleted.countDown(); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun healthRecordFutureHasDeadlineAndCanBeCancelled() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            CollectionModuleId.HEALTH_CONNECT.id, true, ParticipantDecision.ACCEPTED.name, 1, false, 1)))
        ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock))
        val readEntered = CountDownLatch(1)
        val permissions = Proxy.newProxyInstance(androidx.health.connect.client.PermissionController::class.java.classLoader,
            arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ ->
            setOf(HealthPermission.getReadPermission(StepsRecord::class))
        }
        val neverCompleting = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getPermissionController" -> permissions
                "readRecords" -> { readEntered.countDown(); COROUTINE_SUSPENDED }
                else -> error("unexpected ${method.name}")
            }
        } as HealthConnectClient
        val source = AndroidHealthMetricSource(context, clientProvider = { neverCompleting }, isForeground = { true })
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<TimeoutException> {
            assertThrows(TimeoutException::class.java) { source.read() }
        }
        try {
            assertTrue("the public source reached the provider", readEntered.await(5, TimeUnit.SECONDS))
            call.get(12, TimeUnit.SECONDS)
        }
        finally { call.cancel(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun permissionFutureHasDeadlineAndCanBeCancelled() {
        val permission = Proxy.newProxyInstance(androidx.health.connect.client.PermissionController::class.java.classLoader,
            arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ -> COROUTINE_SUSPENDED }
        val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            if (method.name == "getPermissionController") permission else error("unexpected ${method.name}")
        } as HealthConnectClient
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<Throwable?> {
            try { grantedHealthConnectPermissions(client); null }
            catch (error: Exception) { error }
        }
        try { assertTrue(call.get(12, TimeUnit.SECONDS) is TimeoutException) }
        finally { call.cancel(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun worker(): ExpansionUploadWorker {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ -> error(
            "no progress") } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ -> error(
            "no foreground") } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return ExpansionUploadWorker(context, WorkerParameters(UUID.randomUUID(), Data.Builder()
            .putBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, true).build(), emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground))
    }
}
