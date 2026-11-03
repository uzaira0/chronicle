package com.openlattice.chronicle.collection.device

import android.app.Application
import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HealthCheckpointAuditTest {
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("health-upload-order", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!,
            participantId = "participant", sourceRandomDeviceId = "device", apiKey = "health-test-credential"))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            CollectionModuleId.HEALTH_CONNECT.id, true, "ACCEPTED", 0, false, 1)))
        ResearchPersistenceGate.initialize(context)
    }
    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    private var granted = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
    private val readRanges = mutableListOf<TimeRangeFilter>()
    private val readTypes = mutableListOf<KClass<*>>()
    private var foreground = false
    private var backgroundAvailable = true
    private var skinAvailable = false
    private var readFailure: Exception? = null
    private var refusedType: KClass<*>? = null

    @Test fun grantedTypesAdvanceIndependentlyWhileEveryWindowStaysBounded() {
        val steps = HealthConnectRecordType.STEPS
        val heart = HealthConnectRecordType.HEART_RATE
        HealthConnectScopeStore.of(context).replace(setOf(steps, heart))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var time = System.currentTimeMillis()
        val reader = source { time }
        var previous = 0L
        repeat(4) {
            reader.read()
            reader.acknowledgeRead()
            val saved = prefs.getLong(healthCheckpointEndKey(steps), 0)
            assertTrue("A granted type must not wait for a missing sibling grant", saved > previous)
            assertFalse(prefs.contains(healthCheckpointEndKey(heart)))
            assertTrue(readRanges.all { range ->
                requireNotNull(range.endTime).toEpochMilli() - requireNotNull(range.startTime).toEpochMilli() <= MAX_HEALTH_READ_WINDOW_MILLIS
            })
            previous = saved
            time += MAX_HEALTH_READ_WINDOW_MILLIS * 2
        }
    }

    private fun source(clockNow: () -> Long = System::currentTimeMillis): AndroidHealthMetricSource {
        val client = healthConnectTestClient(permissions = { granted },
            features = { healthConnectTestFeatures(backgroundAvailable, skinAvailable) }) { request ->
            readFailure?.let { throw it }
            if (request.recordType == refusedType) throw SecurityException("Read access revoked")
            readRanges += request.timeRangeFilter
            readTypes += request.recordType
            val now = requireNotNull(request.timeRangeFilter.endTime)
            if (request.recordType == StepsRecord::class) listOf(StepsRecord(
                now.minusSeconds(2), ZoneOffset.UTC, now.minusSeconds(1), ZoneOffset.UTC,
                42, Metadata.manualEntryWithId("steps"))) else emptyList()
        }
        return AndroidHealthMetricSource(context, clientProvider = { client }, nowMillis = clockNow, isForeground = { foreground })
    }

    @Test @Config(sdk = [28, 36]) fun unavailableProviderReturnsEmptyWithoutAcknowledgingAWindow() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val reader = AndroidHealthMetricSource(context, clientProvider = { null })
        assertTrue(reader.read().isEmpty())
        reader.acknowledgeRead()
        assertTrue(prefs.all.isEmpty())
    }

    @Test @Config(sdk = [28, 36]) fun freshPartialGrantAdvancesOnlyTheReadableTypes() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS,
            HealthConnectRecordType.HEART_RATE))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val reader = source()
        assertEquals(1, reader.read().size)
        reader.acknowledgeRead()
        val initialEnd = prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0)
        val initialStart = readRanges.first().startTime!!.toEpochMilli()
        assertEquals(TimeUnit.DAYS.toMillis(1), initialEnd - initialStart)
        source().also { it.read(); it.acknowledgeRead() }
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0) >= initialEnd)
        assertEquals(initialStart, readRanges.last().startTime!!.toEpochMilli())
        assertTrue(readTypes.all { it == StepsRecord::class })
        granted += HealthPermission.getReadPermission(HeartRateRecord::class)
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0) >= initialEnd)
        assertTrue(HeartRateRecord::class in readTypes)
    }

    @Test @Config(sdk = [28, 36]) fun missingSelectedPermissionDoesNotBlockSiblingCheckpoint() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS,
            HealthConnectRecordType.HEART_RATE))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        val reader = source()
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao()), reader,
            { true }, persistLease = { it() })
        assertEquals(ModuleResult.Ok(1), module.sample())
        assertEquals(listOf(StepsRecord::class), readTypes)
        reader.acknowledgeRead()
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1) > previous)
        granted += HealthPermission.getReadPermission(HeartRateRecord::class)
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun deniedBackgroundReadCannotReadOrAdvanceCheckpoint() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        prefs.edit().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous).commit()
        granted -= ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ
        val reader = source()
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        assertTrue(reader.read().isEmpty())
        reader.acknowledgeRead()
        assertTrue(readRanges.isEmpty())
        assertEquals(previous, prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
    }

    @Test @Config(sdk = [28, 36]) fun unavailableBackgroundFeatureAllowsForegroundReadsAndSkipsBackground() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        backgroundAvailable = false
        granted -= ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        val reader = source()
        try {
            foreground = true
            assertEquals(1, reader.read().size)
            reader.acknowledgeRead()
            val foregroundEnd = prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1)
            assertTrue(foregroundEnd > previous)
            readRanges.clear()
            foreground = false
            assertTrue(reader.read().isEmpty())
            reader.acknowledgeRead()
            assertTrue(readRanges.isEmpty())
            assertEquals(foregroundEnd, prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
        } finally {
            foreground = false
        }
    }

    @Test @Config(sdk = [28, 36]) fun skinTemperatureReadsRequireTheProviderFeatureWithARecordGrant() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS,
            HealthConnectRecordType.SKIN_TEMPERATURE))
        granted += HealthPermission.getReadPermission(SkinTemperatureRecord::class)
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        val reader = source()
        assertEquals(1, reader.read().size)
        reader.acknowledgeRead()
        assertEquals(listOf(StepsRecord::class), readTypes)
        assertTrue(previous < prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
        skinAvailable = true
        reader.read(); reader.acknowledgeRead()
        assertTrue(SkinTemperatureRecord::class in readTypes)
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 0) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun providerHistoryRefusalKeepsTheWindowUntilItCanBeRead() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(45)
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        // Robolectric has no Health Connect history enforcement; inject the provider's refusal.
        readFailure = SecurityException("Provider history access refused")
        val reader = source()
        assertTrue(reader.read().isEmpty())
        reader.acknowledgeRead()
        assertEquals(previous, prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
        readFailure = null
        reader.read(); reader.acknowledgeRead()
        assertTrue(readRanges.first().startTime!!.toEpochMilli() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun accessLostMidReadKeepsOnlyTheRefusedTypePending() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS,
            HealthConnectRecordType.HEART_RATE))
        granted += HealthPermission.getReadPermission(HeartRateRecord::class)
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), previous)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        refusedType = HeartRateRecord::class
        val reader = source()
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao()), reader,
            { true }, persistLease = { it() })
        assertEquals(ModuleResult.Ok(1), module.sample())
        reader.acknowledgeRead()
        assertTrue(previous < prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
        refusedType = null
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1) > previous)
    }

    @Test fun rejectedConcurrentReadDoesNotClearFirstScope() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val reader = source()
        reader.read()
        assertThrows(IllegalStateException::class.java) { reader.read() }
        reader.acknowledgeRead()
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        assertTrue(prefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1) > 0)
        reader.read(); reader.acknowledgeRead()
    }

    @Test fun databaseRollbackAfterHealthInsertionDoesNotAcknowledgeTheSource() {
        var failCommit = true
        var acknowledged = false
        val reading = HealthMetricReading(HealthMetricType.STEPS,
            42.0, "count", 1_000, 2_000, "health", "record-1")
        val reader = object : HealthMetricSource {
            override fun read() = if (acknowledged) emptyList() else listOf(reading)
            override fun acknowledgeRead() { acknowledged = true }
            override fun rejectRead() = Unit
        }
        val guard = CollectionPersistenceGuard { persist ->
            if (db.inTransaction()) persist() else db.runInTransaction {
                persist()
                assertEquals(1, db.healthMetricSampleDao().count())
                if (failCommit) error("outer transaction failed after insertion")
            }
            true
        }
        // The sink joins the observation transaction, as the production persistence guard does.
        val sink = HealthMetricSampleSink(db.healthMetricSampleDao(), guard)
        val module = HealthMetricCollectionModule(sink, reader, { true },
            { 10_000L }, persistLease = { it() })
        assertTrue(module.sample() is ModuleResult.Failed)
        assertFalse(acknowledged)
        assertEquals(0, db.healthMetricSampleDao().count())

        failCommit = false
        assertEquals(ModuleResult.Ok(1), module.sample())
        assertTrue(acknowledged)
        assertEquals(1, db.healthMetricSampleDao().count())
    }
}
