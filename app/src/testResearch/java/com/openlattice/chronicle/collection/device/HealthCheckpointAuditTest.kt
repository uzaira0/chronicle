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
class HealthCheckpointAuditTest {
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
        db.collectionModuleStateDao().upsertAll(listOf(com.openlattice.chronicle.storage.CollectionModuleStateEntity(
            com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT.id, true, "ACCEPTED", 0, false, 1, null, null)))
        ResearchPersistenceGate.initialize(context)
    }
    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        HealthMetricModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(HealthMetricModuleHolder, null)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    private var granted = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private fun source(): AndroidHealthMetricSource {
        val permission = Proxy.newProxyInstance(androidx.health.connect.client.PermissionController::class.java.classLoader,
            arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ -> granted }
        val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getPermissionController" -> permission
                "readRecords" -> org.robolectric.util.ReflectionHelpers.callConstructor(
                    androidx.health.connect.client.response.ReadRecordsResponse::class.java,
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(List::class.java, emptyList<StepsRecord>()),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, null))
                else -> error("unexpected ${method.name}")
            }
        } as HealthConnectClient
        return AndroidHealthMetricSource(context).also {
            val provider: () -> HealthConnectClient? = { client }
            it.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }.set(it, provider)
        }
    }

    @Test fun missingSelectedPermissionCannotAdvanceSiblingCheckpoint() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS,
            com.openlattice.chronicle.collection.HealthConnectRecordType.HEART_RATE))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        val reader = source()
        reader.read(); reader.acknowledgeRead()
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
        granted += HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class)
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong("last_end_millis", -1) > previous)
    }

    @Test fun rejectedConcurrentReadDoesNotClearFirstScope() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
        val reader = source()
        reader.read()
        assertThrows(IllegalStateException::class.java) { reader.read() }
        reader.acknowledgeRead()
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        assertTrue(prefs.getLong("last_end_millis", -1) > 0)
        reader.read(); reader.acknowledgeRead()
    }
}
