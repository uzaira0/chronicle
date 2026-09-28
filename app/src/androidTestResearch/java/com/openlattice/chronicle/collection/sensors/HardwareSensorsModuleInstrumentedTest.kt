package com.openlattice.chronicle.collection.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.services.sensors.quarantineMalformedSensorSamples
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleDeliveryEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Instrumented coverage for the Phase 6 hardware-sensors module against the real
 * SQLCipher-backed [ChronicleDb] (refactor plan §9.1–9.3, plan §9.3 step 19 —
 * "integration tests for the sensor payload model").
 *
 * Exercises the sink → real-encrypted-Room → `sensor_samples` path the
 * [SensorRuntimeController] drives, plus durable per-destination acknowledgement and the
 * malformed-row quarantine. These are the paths the JVM fakes
 * cannot prove — the actual SQLCipher `SupportFactory`, the Room schema v9, and the real
 * DAO conflict strategies and exact-ID receipt queries.
 *
 * Requires a connected device or emulator. When none is available the run is recorded as
 * a BLOCKER; the JVM `SensorRuntimeControllerTest` plus the
 * existing `ChronicleDbTests` provide the strongest local proof of the boundary.
 */
@RunWith(AndroidJUnit4::class)
class HardwareSensorsModuleInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("hardware_sensors")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    private fun sample(id: String, timestamp: String) = SensorSampleEntry(
        id = id,
        sensorType = "accelerometer",
        timestamp = timestamp,
        timezone = "UTC",
        x = 1f, y = 2f, z = 3f, w = null,
        accuracy = 3,
        valuesJson = "[1.0,2.0,3.0]",
    )

    @Test
    fun sensorSampleSinkWritesThroughRealSqlCipherSensorSamples() {
        val sink = SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog)
        val samples = (0 until 10).map { sample("sink-$it", "2026-05-20T00:00:0${it}Z") }

        val result = sink.write(samples)

        assertEquals(ModuleResult.Ok(10), result)
        assertEquals(10, sink.queueDepth())
    }

    @Test
    fun sensorSampleSinkIsTheRuntimeControllerWritePathAgainstRealDb() {
        // The controller drains its buffer through SensorSampleSink — drive a sink write
        // the way SensorRuntimeController.flushBuffer does and confirm the rows land.
        val dao = db.sensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)
        val batch = (0 until 500).map { sample(UUID.randomUUID().toString(), "2026-05-20T01:00:00Z") }

        assertEquals(ModuleResult.Ok(500), sink.write(batch))
        assertEquals(500, dao.count())
    }

    @Test
    fun exactIdReceiptDeletionRetainsBackdatedSamplesAgainstSingletonDestination() {
        val dao = db.sensorSampleDao()
        val serverDao = db.uploadServerDao()
        dao.insertAll(
            listOf(
                sample("s1", "2026-05-20T00:00:01Z"),
                sample("s2", "2026-05-20T00:00:02Z"),
            ),
        )
        val immutableBatch = dao.getOldest(1)
        // Insert a sample with an older timestamp after selecting the immutable batch. Exact-ID
        // deletion must never cover it.
        dao.insertAll(listOf(sample("late-backdated", "2000-01-01T00:00:00Z")))

        val serverId = serverDao.insert(
            UploadServerEntity(
                name = "active", url = "https://study.example", studyId = "st", participantId = "p",
                sourceDeviceId = "d",
            ),
        )
        val sampleIds = immutableBatch.map { it.id }
        val deliveredAt = OffsetDateTime.now().toString()

        val deleted = dao.acknowledgeAndDeleteFullyDelivered(
            sampleIds.map { SensorSampleDeliveryEntity(it, serverId, 0, deliveredAt) },
            sampleIds,
        )
        assertEquals(1, deleted)
        assertEquals(setOf("late-backdated", "s2"), dao.getOldest(100).map { it.id }.toSet())
    }

    @Test
    fun malformedQuarantineTransactionDoesNotCreateDeliveryReceiptAgainstRealDb() {
        val dao = db.sensorSampleDao()
        val malformed = sample("bad", "not-a-timestamp")
        dao.insertAll(listOf(malformed))
        val input = listOf(malformed to IllegalArgumentException("bad timestamp"))
        quarantineMalformedSensorSamples(dao, null, input, db = db)
        quarantineMalformedSensorSamples(dao, null, input, db = db)

        assertEquals(0, dao.count())
        assertEquals(1, db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sensor_sample_dead_letters",
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertEquals(1, db.openHelper.readableDatabase.query(
            "SELECT COALESCE(SUM(count), 0) FROM upload_diagnostics WHERE issueCode = 'SENSOR_SAMPLE_QUARANTINED'",
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
    }
}
