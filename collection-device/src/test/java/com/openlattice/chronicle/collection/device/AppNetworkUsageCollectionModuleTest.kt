package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.NetworkUsageType
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.storage.AppNetworkUsageSampleDao
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNetworkUsageCollectionModuleTest {
    @Test
    fun successfulPersistenceAcknowledgesSourceWindow() {
        val source = FakeSource(listOf(reading()))
        val sink = FakeSink(ModuleResult.Ok(1))

        assertEquals(ModuleResult.Ok(1), module(source, sink).sample())
        assertEquals(1, source.acknowledgments)
        assertEquals(0, source.rejections)
        assertEquals(1, sink.samples.size)
    }

    @Test
    fun failedPersistenceRejectsSourceWindowForRetry() {
        val source = FakeSource(listOf(reading()))
        val failure = IllegalStateException("database unavailable")

        assertTrue(module(source, FakeSink(ModuleResult.Failed(failure, "insert failed"))).sample() is ModuleResult.Failed)
        assertEquals(0, source.acknowledgments)
        assertEquals(1, source.rejections)
    }

    @Test
    fun emptySuccessfulReadStillAcknowledgesWindow() {
        val source = FakeSource(emptyList())

        assertEquals(ModuleResult.Ok(0), module(source, FakeSink(ModuleResult.Ok(0))).sample())
        assertEquals(1, source.acknowledgments)
        assertEquals(0, source.rejections)
    }

    @Test
    fun checkpointFailureSurfacesAndKeepsWindowRetryable() {
        val source = FakeSource(listOf(reading()), failAcknowledge = true)

        assertTrue(module(source, FakeSink(ModuleResult.Ok(1))).sample() is ModuleResult.Failed)
        assertEquals(1, source.acknowledgments)
        assertEquals(1, source.rejections)
    }

    @Test
    fun sampleIdIsStableForRetryAndChangesWithWindow() {
        val first = reading(endMillis = 2_000L)
        val retry = reading(endMillis = 2_000L)
        val nextWindow = reading(endMillis = 3_000L)

        assertEquals(stableAppNetworkUsageSampleId(first), stableAppNetworkUsageSampleId(retry))
        assertTrue(stableAppNetworkUsageSampleId(first) != stableAppNetworkUsageSampleId(nextWindow))
    }

    private fun module(source: AppNetworkUsageSource, sink: AppNetworkUsageSampleSink) =
        AppNetworkUsageCollectionModule(
            sink = sink,
            source = source,
            enrolled = { true },
            clock = FixedCollectionClock(10_000L),
            log = NoOpCollectionLog,
        )

    private fun reading(endMillis: Long = 2_000L) = AppNetworkUsageReading(
        packageName = "com.example.app",
        networkType = NetworkUsageType.WIFI,
        rxBytes = 100L,
        txBytes = 50L,
        bucketStartMillis = 1_000L,
        bucketEndMillis = endMillis,
    )

    private class FakeSource(
        private val readings: List<AppNetworkUsageReading>,
        private val failAcknowledge: Boolean = false,
    ) : AppNetworkUsageSource {
        var acknowledgments = 0
        var rejections = 0

        override fun read(): List<AppNetworkUsageReading> = readings

        override fun acknowledgeRead() {
            acknowledgments += 1
            if (failAcknowledge) throw IllegalStateException("checkpoint unavailable")
        }

        override fun rejectRead() {
            rejections += 1
        }
    }

    private class FakeSink(private val result: ModuleResult) : AppNetworkUsageSampleSink(FakeDao()) {
        var samples: List<AppNetworkUsageSampleEntry> = emptyList()

        override fun write(samples: List<AppNetworkUsageSampleEntry>): ModuleResult {
            this.samples = samples
            return result
        }
    }

    private class FakeDao : AppNetworkUsageSampleDao {
        override fun getOldest(limit: Int): List<AppNetworkUsageSampleEntry> = emptyList()
        override fun insertAll(samples: List<AppNetworkUsageSampleEntry>) = Unit
        override fun deleteByIds(ids: List<String>) = Unit
        override fun count(): Int = 0
        override fun deleteOlderThan(cutoffTimestamp: String): Int = 0
        override fun deleteAll() = Unit
    }
}
