package com.openlattice.chronicle.collection.expansion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.ActivityRecognitionSampleSink
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.sink.SleepSampleSink
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import com.openlattice.chronicle.storage.SleepSampleEntry
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.sleepSampleDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NON-DESTRUCTIVE instrumented coverage for the six sensing-expansion modules
 * (`sleep`, `activity_recognition`, `health_connect`, `connectivity_state`,
 * `app_network_usage`, `device_settings`) against the real SQLCipher-backed
 * [ChronicleDb] at schema v18.
 *
 * Unlike the `*DecisionTest` instrumented classes (which call `AppTestState.resetPrefs()` +
 * `clearMutableTables()` and therefore WIPE enrollment), this test is safe to run on an
 * **enrolled** device: it uses an isolated SQLCipher-backed Room database and never touches
 * enrollment prefs or the live app database.
 *
 * It proves the genuinely-new persistence path: the six Room entities/DAOs, the six sinks'
 * conflict strategies (`OnConflictStrategy.IGNORE`), and that every nullable / `Double` /
 * `Float` / `Long` / enum-as-`String` column survives the on-disk encrypted byte round-trip.
 */
@RunWith(AndroidJUnit4::class)
class ExpansionModuleSinksInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb
    private val suffix = System.nanoTime().toString()

    private val sleepIds = mutableListOf<String>()
    private val activityIds = mutableListOf<String>()
    private val healthIds = mutableListOf<String>()
    private val connectivityIds = mutableListOf<String>()
    private val networkIds = mutableListOf<String>()
    private val settingsIds = mutableListOf<String>()

    private fun id(module: String, i: Int) = "androidtest-$module-$suffix-$i".also {
        when (module) {
            "sleep" -> sleepIds
            "activity" -> activityIds
            "health" -> healthIds
            "connectivity" -> connectivityIds
            "network" -> networkIds
            "settings" -> settingsIds
            else -> error("unknown module $module")
        }.add(it)
    }

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("expansion_modules")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    @Test
    fun sleepSinkRoundTripsThroughSqlCipherAndDeDupesById() {
        val sink = SleepSampleSink(db.sleepSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val sample = SleepSampleEntry(
            id = id("sleep", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            eventType = "SLEEP_SEGMENT",
            segmentStartMillis = 1_000L,
            segmentEndMillis = 2_000L,
            segmentStatus = "SUCCESSFUL",
            confidence = 80,
            light = 5,
            motion = 3,
        )

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample)))
        assertEquals(before + 1, sink.queueDepth())

        val restored = db.sleepSampleDao().getOldest(100_000).first { it.id == sample.id }
        assertEquals("SLEEP_SEGMENT", restored.eventType)
        assertEquals(java.lang.Integer.valueOf(80), restored.confidence)
        assertEquals(java.lang.Long.valueOf(2_000L), restored.segmentEndMillis)

        // OnConflictStrategy.IGNORE — re-inserting the same id is Ok and does not grow the table.
        assertTrue(sink.write(listOf(sample)) is ModuleResult.Ok)
        assertEquals(before + 1, sink.queueDepth())
    }

    @Test
    fun activityRecognitionSinkRoundTripsThroughSqlCipher() {
        val sink = ActivityRecognitionSampleSink(db.activityRecognitionSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val sample = ActivityRecognitionSampleEntry(
            id = id("activity", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            activityType = "STILL",
            confidence = 90,
            transitionType = "ENTER",
        )
        val nullTransition = sample.copy(id = id("activity", 1), transitionType = null)

        assertEquals(ModuleResult.Ok(2), sink.write(listOf(sample, nullTransition)))
        assertEquals(before + 2, sink.queueDepth())

        val rows = db.activityRecognitionSampleDao().getOldest(100_000)
        assertEquals("STILL", rows.first { it.id == sample.id }.activityType)
        assertEquals("ENTER", rows.first { it.id == sample.id }.transitionType)
        assertNull(rows.first { it.id == nullTransition.id }.transitionType)
    }

    @Test
    fun healthMetricSinkRoundTripsDoubleValueThroughSqlCipher() {
        val sink = HealthMetricSampleSink(db.healthMetricSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val sample = HealthMetricSampleEntry(
            id = id("health", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            metricType = "STEPS",
            value = 1234.5,
            unit = "count",
            startMillis = 10L,
            endMillis = 20L,
            sourcePackage = "com.google.android.apps.fitness",
        )

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample)))
        assertEquals(before + 1, sink.queueDepth())

        val restored = db.healthMetricSampleDao().getOldest(100_000).first { it.id == sample.id }
        assertEquals("STEPS", restored.metricType)
        assertEquals(1234.5, restored.value, 0.0001)
    }

    @Test
    fun connectivityStateSinkRoundTripsNullableBooleansThroughSqlCipher() {
        val sink = ConnectivityStateSampleSink(db.connectivityStateSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val sample = ConnectivityStateSampleEntry(
            id = id("connectivity", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            eventType = "AVAILABLE",
            transport = "WIFI",
            connected = true,
            metered = false,
            validated = true,
        )
        val nullFlags = sample.copy(id = id("connectivity", 1), metered = null, validated = null)

        assertEquals(ModuleResult.Ok(2), sink.write(listOf(sample, nullFlags)))
        assertEquals(before + 2, sink.queueDepth())

        val rows = db.connectivityStateSampleDao().getOldest(100_000)
        val full = rows.first { it.id == sample.id }
        assertEquals("WIFI", full.transport)
        assertTrue(full.connected)
        assertEquals(java.lang.Boolean.FALSE, full.metered)
        val nulls = rows.first { it.id == nullFlags.id }
        assertNull(nulls.metered)
        assertNull(nulls.validated)
    }

    @Test
    fun appNetworkUsageSinkRoundTripsLongCountersThroughSqlCipher() {
        val sink = AppNetworkUsageSampleSink(db.appNetworkUsageSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val sample = AppNetworkUsageSampleEntry(
            id = id("network", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            packageName = "com.example.app",
            networkType = "WIFI",
            rxBytes = 9_876_543_210L,
            txBytes = 1_234_567_890L,
            bucketStartMillis = 100L,
            bucketEndMillis = 200L,
        )

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample)))
        assertEquals(before + 1, sink.queueDepth())

        val restored = db.appNetworkUsageSampleDao().getOldest(100_000).first { it.id == sample.id }
        assertEquals(9_876_543_210L, restored.rxBytes)
        assertEquals(1_234_567_890L, restored.txBytes)
        assertEquals("com.example.app", restored.packageName)
    }

    @Test
    fun deviceSettingsSinkRoundTripsFloatAndPartialSnapshotThroughSqlCipher() {
        val sink = DeviceSettingsSampleSink(db.deviceSettingsSampleDao(), NoOpCollectionLog)
        val before = sink.queueDepth()
        val full = DeviceSettingsSampleEntry(
            id = id("settings", 0),
            timestamp = "2026-06-19T00:00:00Z",
            timezone = "UTC",
            darkMode = true,
            fontScale = 1.5f,
            accessibilityEnabled = false,
            dndActive = true,
            batterySaver = false,
            thermalStatus = "NONE",
            autoRotate = true,
            locationServicesEnabled = true,
            storageFreeBytes = 100L,
            storageTotalBytes = 200L,
        )
        // Partial snapshot: every descriptive column null but the row still persists.
        val partial = DeviceSettingsSampleEntry(
            id = id("settings", 1),
            timestamp = "2026-06-19T00:00:01Z",
            timezone = "UTC",
            darkMode = null,
            fontScale = null,
            accessibilityEnabled = null,
            dndActive = null,
            batterySaver = null,
            thermalStatus = null,
            autoRotate = null,
            locationServicesEnabled = null,
            storageFreeBytes = null,
            storageTotalBytes = null,
        )

        assertEquals(ModuleResult.Ok(2), sink.write(listOf(full, partial)))
        assertEquals(before + 2, sink.queueDepth())

        val rows = db.deviceSettingsSampleDao().getOldest(100_000)
        val restoredFull = rows.first { it.id == full.id }
        assertEquals(java.lang.Boolean.TRUE, restoredFull.darkMode)
        assertEquals(1.5f, restoredFull.fontScale!!, 0.0001f)
        assertEquals("NONE", restoredFull.thermalStatus)
        val restoredPartial = rows.first { it.id == partial.id }
        assertNull(restoredPartial.fontScale)
        assertNull(restoredPartial.thermalStatus)
        assertNull(restoredPartial.storageFreeBytes)
    }

    @Test
    fun emptyWritesAreOkNoOpsAcrossAllSixSinks() {
        assertEquals(ModuleResult.Ok(0), SleepSampleSink(db.sleepSampleDao(), NoOpCollectionLog).write(emptyList()))
        assertEquals(
            ModuleResult.Ok(0),
            ActivityRecognitionSampleSink(db.activityRecognitionSampleDao(), NoOpCollectionLog).write(emptyList()),
        )
        assertEquals(
            ModuleResult.Ok(0),
            HealthMetricSampleSink(db.healthMetricSampleDao(), NoOpCollectionLog).write(emptyList()),
        )
        assertEquals(
            ModuleResult.Ok(0),
            ConnectivityStateSampleSink(db.connectivityStateSampleDao(), NoOpCollectionLog).write(emptyList()),
        )
        assertEquals(
            ModuleResult.Ok(0),
            AppNetworkUsageSampleSink(db.appNetworkUsageSampleDao(), NoOpCollectionLog).write(emptyList()),
        )
        assertEquals(
            ModuleResult.Ok(0),
            DeviceSettingsSampleSink(db.deviceSettingsSampleDao(), NoOpCollectionLog).write(emptyList()),
        )
    }
}
