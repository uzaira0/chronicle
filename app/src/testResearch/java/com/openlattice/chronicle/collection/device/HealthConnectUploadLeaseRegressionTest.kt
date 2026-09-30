package com.openlattice.chronicle.collection.device

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HealthConnectUploadLeaseRegressionTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("health-upload-order", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceDeviceId = "device"))
        ResearchPersistenceGate.initialize(context)
    }
    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, null)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun bindingReadDoesNotHoldUploadLeasesAndFinalPersistenceRechecksStop() {
        val sourceEntered = CountDownLatch(1); val bindingCompleted = CountDownLatch(1)
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao(),
            persistenceGuard = ResearchPersistenceGate.guard(context)), source = {
            sourceEntered.countDown()
            check(bindingCompleted.await(5, TimeUnit.SECONDS))
            listOf(HealthMetricReading(com.openlattice.chronicle.collection.HealthMetricType.STEPS,
                1.0, "count", 1, 2, "provider"))
        }, enrolled = { true })
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, module)
        val pool = Executors.newFixedThreadPool(3)
        val upload = pool.submit<ListenableWorker.Result> { worker().doWork() }
        assertTrue(sourceEntered.await(5, TimeUnit.SECONDS))
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier").apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(barrier) as ReentrantReadWriteLock
        val writer = pool.submit { lock.writeLock().lockInterruptibly(); try { ResearchPersistenceGate.closeForPersistenceFailure() } finally { lock.writeLock().unlock() } }
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
            listOf(HealthMetricReading(com.openlattice.chronicle.collection.HealthMetricType.STEPS,
                1.0, "count", 1, 2, "provider"))
        }, enrolled = { true })
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, module)
        val pool = Executors.newSingleThreadExecutor()
        val upload = pool.submit<ListenableWorker.Result> { worker().doWork() }
        try {
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS))
            val old = db.uploadServerDao().getConfiguredServer()!!
            ResearchPersistenceGate.stop {
                db.uploadServerDao().delete(old.id)
                db.uploadServerDao().insert(old.copy(id = 0, sourceDeviceId = "replacement-device"))
            }
            assertTrue("replacement enrollment itself remains active", ResearchPersistenceGate.isActiveEnrollment(context))
            bindingCompleted.countDown()
            val result = upload.get(5, TimeUnit.SECONDS)
            assertEquals(0, db.healthMetricSampleDao().count())
            assertEquals(ListenableWorker.Result.success(), result)
        } finally {
            bindingCompleted.countDown(); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun healthRecordFutureHasDeadlineAndCanBeCancelled() {
        val neverCompleting = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            if (method.name == "readRecords") COROUTINE_SUSPENDED else error("unexpected ${method.name}")
        } as HealthConnectClient
        val source = AndroidHealthMetricSource(context)
        val read = source.javaClass.getDeclaredMethod("readRecords", HealthConnectClient::class.java, Set::class.java,
            KClass::class.java, TimeRangeFilter::class.java).apply { isAccessible = true }
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<Throwable?> {
            try {
                read.invoke(source, neverCompleting, setOf(HealthPermission.getReadPermission(StepsRecord::class)),
                    StepsRecord::class, TimeRangeFilter.between(Instant.EPOCH, Instant.ofEpochMilli(10)))
                null
            } catch (error: InvocationTargetException) { error.targetException }
        }
        try { assertTrue(call.get(12, TimeUnit.SECONDS) is TimeoutException) }
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
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ -> error("no progress") } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ -> error("no foreground") } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return ExpansionUploadWorker(context, WorkerParameters(UUID.randomUUID(), Data.Builder()
            .putBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, true).build(), emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground))
    }
}
