package com.openlattice.chronicle.collection.device

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

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

    private var granted = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        com.openlattice.chronicle.collection.permissions.ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
    private val readRanges = mutableListOf<TimeRangeFilter>()
    private val readTypes = mutableListOf<kotlin.reflect.KClass<*>>()
    private var backgroundAvailable = true
    private var skinAvailable = false
    private var readFailure: Exception? = null
    private var refusedType: kotlin.reflect.KClass<*>? = null
    private fun source(): AndroidHealthMetricSource {
        val permission = Proxy.newProxyInstance(androidx.health.connect.client.PermissionController::class.java.classLoader,
            arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ -> granted }
        val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, args ->
            when (method.name) {
                "getFeatures" -> healthConnectTestFeatures(backgroundAvailable, skinAvailable)
                "getPermissionController" -> permission
                "readRecords" -> {
                    readFailure?.let { throw it }
                    val request = args!![0] as androidx.health.connect.client.request.ReadRecordsRequest<*>
                    if (request.recordType == refusedType) throw SecurityException("Read access revoked")
                    readRanges += request.timeRangeFilter
                    readTypes += request.recordType
                    val now = java.time.Instant.now()
                    val records = if (request.recordType == StepsRecord::class) listOf(StepsRecord(
                        now.minusSeconds(2), java.time.ZoneOffset.UTC, now.minusSeconds(1), java.time.ZoneOffset.UTC,
                        42, androidx.health.connect.client.records.metadata.Metadata.manualEntryWithId("steps"))) else emptyList()
                    org.robolectric.util.ReflectionHelpers.callConstructor(
                        androidx.health.connect.client.response.ReadRecordsResponse::class.java,
                        org.robolectric.util.ReflectionHelpers.ClassParameter.from(List::class.java, records),
                        org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, null))
                }
                else -> error("unexpected ${method.name}")
            }
        } as HealthConnectClient
        return AndroidHealthMetricSource(context).also {
            val provider: () -> HealthConnectClient? = { client }
            it.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }.set(it, provider)
        }
    }

    @Test @Config(sdk = [28, 36]) fun unavailableProviderReturnsEmptyWithoutAcknowledgingAWindow() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val reader = AndroidHealthMetricSource(context).also {
            val provider: () -> HealthConnectClient? = { null }
            it.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }.set(it, provider)
        }
        assertTrue(reader.read().isEmpty())
        assertEquals(null, reader.javaClass.getDeclaredField("readScope").apply { isAccessible = true }.get(reader))
        reader.acknowledgeRead()
        assertTrue(prefs.all.isEmpty())
    }

    @Test @Config(sdk = [28, 36]) fun freshPartialGrantAnchorsRetriesToTheInitialBackfillWindow() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS,
            com.openlattice.chronicle.collection.HealthConnectRecordType.HEART_RATE))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val reader = source()
        assertEquals(1, reader.read().size)
        reader.acknowledgeRead()
        val initialEnd = prefs.getLong("last_end_millis", 0)
        val initialStart = readRanges.first().startTime!!.toEpochMilli()
        assertEquals(TimeUnit.DAYS.toMillis(1), initialEnd - initialStart)
        source().also { it.read(); it.acknowledgeRead() }
        assertEquals(initialEnd, prefs.getLong("last_end_millis", 0))
        assertEquals(initialStart, readRanges.last().startTime!!.toEpochMilli())
        assertTrue(readTypes.all { it == StepsRecord::class })
        granted += HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class)
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong("last_end_millis", 0) >= initialEnd)
        assertTrue(androidx.health.connect.client.records.HeartRateRecord::class in readTypes)
    }

    @Test @Config(sdk = [28, 36]) fun missingSelectedPermissionCannotAdvanceSiblingCheckpoint() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS,
            com.openlattice.chronicle.collection.HealthConnectRecordType.HEART_RATE))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        val reader = source()
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao()), reader,
            { true }, log = com.openlattice.chronicle.collection.core.NoOpCollectionLog)
        assertEquals(com.openlattice.chronicle.collection.core.ModuleResult.Ok(1), module.sample())
        assertEquals(listOf(StepsRecord::class), readTypes)
        reader.acknowledgeRead()
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
        granted += HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class)
        reader.read(); reader.acknowledgeRead()
        assertTrue(prefs.getLong("last_end_millis", -1) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun deniedBackgroundReadCannotReadOrAdvanceCheckpoint() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        prefs.edit().putLong("last_end_millis", previous).commit()
        granted -= com.openlattice.chronicle.collection.permissions.ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ
        val reader = source()
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        assertTrue(reader.read().isEmpty())
        reader.acknowledgeRead()
        assertTrue(readRanges.isEmpty())
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
    }

    @Test @Config(sdk = [28, 36]) fun unavailableBackgroundFeatureAllowsForegroundReadsAndSkipsBackground() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
        backgroundAvailable = false
        granted -= com.openlattice.chronicle.collection.permissions.ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        val reader = source()
        val owner = ProcessLifecycleOwner.get()
        val registry = owner.javaClass.getDeclaredField("registry").apply { isAccessible = true }
        val original = registry.get(owner)
        val lifecycle = LifecycleRegistry.createUnsafe(owner)
        registry.set(owner, lifecycle)
        try {
            lifecycle.currentState = Lifecycle.State.STARTED
            assertEquals(1, reader.read().size)
            reader.acknowledgeRead()
            val foregroundEnd = prefs.getLong("last_end_millis", -1)
            assertTrue(foregroundEnd > previous)
            readRanges.clear()
            lifecycle.currentState = Lifecycle.State.CREATED
            assertTrue(reader.read().isEmpty())
            reader.acknowledgeRead()
            assertTrue(readRanges.isEmpty())
            assertEquals(foregroundEnd, prefs.getLong("last_end_millis", -1))
        } finally {
            registry.set(owner, original)
        }
    }

    @Test @Config(sdk = [28, 36]) fun skinTemperatureReadsRequireTheProviderFeatureWithARecordGrant() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS,
            com.openlattice.chronicle.collection.HealthConnectRecordType.SKIN_TEMPERATURE))
        granted += HealthPermission.getReadPermission(androidx.health.connect.client.records.SkinTemperatureRecord::class)
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        val reader = source()
        assertEquals(1, reader.read().size)
        reader.acknowledgeRead()
        assertEquals(listOf(StepsRecord::class), readTypes)
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
        skinAvailable = true
        reader.read(); reader.acknowledgeRead()
        assertTrue(androidx.health.connect.client.records.SkinTemperatureRecord::class in readTypes)
        assertTrue(prefs.getLong("last_end_millis", 0) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun providerHistoryRefusalKeepsTheWindowUntilItCanBeRead() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(45)
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        // Robolectric has no Health Connect history enforcement; inject the provider's refusal.
        readFailure = SecurityException("Provider history access refused")
        val reader = source()
        assertTrue(reader.read().isEmpty())
        reader.acknowledgeRead()
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
        readFailure = null
        reader.read(); reader.acknowledgeRead()
        assertTrue(readRanges.first().startTime!!.toEpochMilli() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        assertTrue(prefs.getLong("last_end_millis", -1) > previous)
    }

    @Test @Config(sdk = [28, 36]) fun accessLostMidReadKeepsSiblingRecordsAndTheWindow() {
        HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS,
            com.openlattice.chronicle.collection.HealthConnectRecordType.HEART_RATE))
        granted += HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class)
        val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val previous = System.currentTimeMillis() - 3_600_000
        val scope = ResearchPersistenceGate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)!!
        prefs.edit().clear().putLong("last_end_millis", previous).putString("consent_scope", scope.first).commit()
        refusedType = androidx.health.connect.client.records.HeartRateRecord::class
        val reader = source()
        val module = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao()), reader,
            { true }, log = com.openlattice.chronicle.collection.core.NoOpCollectionLog)
        assertEquals(com.openlattice.chronicle.collection.core.ModuleResult.Ok(1), module.sample())
        reader.acknowledgeRead()
        assertEquals(previous, prefs.getLong("last_end_millis", -1))
        refusedType = null
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

    @Test fun databaseRollbackAfterHealthInsertionDoesNotAcknowledgeTheSource() {
        var failCommit = true
        var acknowledged = false
        val reading = HealthMetricReading(com.openlattice.chronicle.collection.HealthMetricType.STEPS,
            42.0, "count", 1_000, 2_000, "health", "record-1")
        val reader = object : HealthMetricSource {
            override fun read() = if (acknowledged) emptyList() else listOf(reading)
            override fun acknowledgeRead() { acknowledged = true }
            override fun rejectRead() = Unit
        }
        val guard = com.openlattice.chronicle.collection.state.CollectionPersistenceGuard { persist ->
            if (db.inTransaction()) persist() else db.runInTransaction {
                persist()
                assertEquals(1, db.healthMetricSampleDao().count())
                if (failCommit) error("outer transaction failed after insertion")
            }
            true
        }
        // The sink joins the observation transaction, as the production persistence guard does.
        val sink = HealthMetricSampleSink(db.healthMetricSampleDao(),
            com.openlattice.chronicle.collection.core.NoOpCollectionLog, guard)
        val module = HealthMetricCollectionModule(sink, reader, { true },
            com.openlattice.chronicle.collection.core.FixedCollectionClock(10_000),
            com.openlattice.chronicle.collection.core.NoOpCollectionLog)
        assertTrue(module.sample() is com.openlattice.chronicle.collection.core.ModuleResult.Failed)
        assertFalse(acknowledged)
        assertEquals(0, db.healthMetricSampleDao().count())

        failCommit = false
        assertEquals(com.openlattice.chronicle.collection.core.ModuleResult.Ok(1), module.sample())
        assertTrue(acknowledged)
        assertEquals(1, db.healthMetricSampleDao().count())
    }
}
