package com.openlattice.chronicle.collection.sink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the Phase 3 sinks against the real SQLCipher-backed
 * [ChronicleDb] (refactor plan §6.2 step 19 — "Android instrumented tests for SQLCipher
 * DB access boundaries").
 *
 * This exercises the sink → real-encrypted-Room → table path that the JVM fakes cannot:
 * it proves the sinks compose correctly with the SQLCipher `SupportFactory`, the Room
 * schema v9, and the actual conflict strategies declared on the DAOs.
 *
 * Requires a connected device or emulator. When none is available the run is recorded
 * as a BLOCKER; the JVM `*SinkTest` classes plus the existing `ChronicleDbTests` provide
 * the strongest local proof of the sink boundary.
 */
@RunWith(AndroidJUnit4::class)
class CollectionSinkInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("collection_sink")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    @Test
    fun usageEventSinkWritesThroughSqlCipherDataQueue() {
        val sink = UsageEventSink(db.queueEntryData())
        val now = System.currentTimeMillis()
        val entries = listOf(
            QueueEntry(writeTimestamp = now, id = 1L, data = byteArrayOf(1, 2, 3)),
            QueueEntry(writeTimestamp = now + 1, id = 2L, data = byteArrayOf(4, 5, 6)),
        )

        val result = sink.write(entries)

        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, sink.queueDepth())
    }

    @Test
    fun usageEventSinkDuplicateCompositeKeySurfacesAsFailed() {
        val sink = UsageEventSink(db.queueEntryData())
        val entry = QueueEntry(writeTimestamp = 123L, id = 9L, data = byteArrayOf(7))

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(entry)))
        assertTrue(sink.write(listOf(entry)) is ModuleResult.Failed)
        assertEquals(1, sink.queueDepth())
    }

    @Test
    fun sensorSampleSinkWritesThroughSqlCipherAndIgnoresDuplicateIds() {
        val sink = SensorSampleSink(db.sensorSampleDao())
        val sample = SensorSampleEntry(
            id = "sample-1",
            sensorType = "ACCELEROMETER",
            timestamp = "2026-05-20T00:00:00Z",
            timezone = "UTC",
            x = 0.1f, y = 0.2f, z = 0.3f, w = null,
            accuracy = 3,
            valuesJson = null,
        )

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample)))
        // OnConflictStrategy.IGNORE — duplicate id is not a failure.
        assertTrue(sink.write(listOf(sample)) is ModuleResult.Ok)
        assertEquals(1, sink.queueDepth())
    }

    @Test
    fun emptyWritesAreIdempotentNoOpsAgainstRealDb() {
        val usageSink = UsageEventSink(db.queueEntryData())
        val sensorSink = SensorSampleSink(db.sensorSampleDao())

        assertEquals(ModuleResult.Ok(0), usageSink.write(emptyList()))
        assertEquals(ModuleResult.Ok(0), sensorSink.write(emptyList()))
        assertEquals(0, usageSink.queueDepth())
        assertEquals(0, sensorSink.queueDepth())
    }
}
