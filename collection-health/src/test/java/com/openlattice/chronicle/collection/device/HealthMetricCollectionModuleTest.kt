package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QueueRowOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMetricCollectionModuleTest {
    @Test
    fun successfulPersistenceAcknowledgesSourceRead() {
        val source = FakeSource(listOf(reading()))
        val sink = FakeSink(ModuleResult.Ok(1))
        val module = module(source, sink)

        assertEquals(ModuleResult.Ok(1), module.sample())
        assertEquals(1, source.acknowledgments)
        assertEquals(0, source.rejections)
        assertEquals(1, sink.samples.size)
    }

    @Test
    fun failedPersistenceRejectsSourceReadForRetry() {
        val source = FakeSource(listOf(reading()))
        val failure = IllegalStateException("database unavailable")
        val module = module(source, FakeSink(ModuleResult.Failed(failure, "insert failed")))

        assertTrue(module.sample() is ModuleResult.Failed)
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
    fun aStopCannotLandBetweenTheWriteAndTheReadAcknowledgement() {
        val lease = ReentrantReadWriteLock(true)
        val source = FakeSource(listOf(reading()))
        var stopSawUnacknowledgedWrite: Boolean? = null
        lateinit var stop: Thread
        val sink = object : FakeSink(ModuleResult.Ok(1)) {
            override fun write(samples: List<HealthMetricSampleEntry>): ModuleResult {
                stop = thread { lease.write { stopSawUnacknowledgedWrite = source.acknowledgments == 0 } }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (stop.isAlive && !lease.hasQueuedThread(stop) && System.nanoTime() < deadline) Thread.sleep(1)
                return super.write(samples)
            }
        }
        val module = HealthMetricCollectionModule(sink, source, { true }, { 10_000L }, persistLease = { lease.read(it) })

        assertEquals(ModuleResult.Ok(1), module.sample())
        stop.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("stop ran between the write and its acknowledgement", checkNotNull(stopSawUnacknowledgedWrite))
    }

    @Test
    fun concurrentSamplesEachAcknowledgeTheirOwnReadWindow() {
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val source = WindowSource {
            firstReadEntered.countDown()
            check(releaseFirstRead.await(5, TimeUnit.SECONDS))
        }
        val module = module(source, FakeSink(ModuleResult.Ok(1)))
        val first = AtomicReference<ModuleResult>()
        val second = AtomicReference<ModuleResult>()
        val firstSample = thread { first.set(module.sample()) }
        check(firstReadEntered.await(5, TimeUnit.SECONDS))
        val secondSample = thread { second.set(module.sample()) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (secondSample.isAlive && secondSample.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        releaseFirstRead.countDown()
        firstSample.join(5_000); secondSample.join(5_000)

        assertEquals(ModuleResult.Ok(1), first.get())
        assertEquals(ModuleResult.Ok(1), second.get())
        assertEquals("each read window is acknowledged exactly once", listOf(1, 2), source.acknowledged)
        assertEquals(0, source.rejections)
    }

    @Test
    fun sampleIdIsStableForRetryAndChangesWithSourceRecord() {
        val first = reading(sourceRecordId = "record-1")
        val retry = reading(sourceRecordId = "record-1")
        val different = reading(sourceRecordId = "record-2")

        assertEquals(stableHealthMetricSampleId(first), stableHealthMetricSampleId(retry))
        assertTrue(stableHealthMetricSampleId(first) != stableHealthMetricSampleId(different))
    }

    private fun module(source: HealthMetricSource, sink: HealthMetricSampleSink) =
        HealthMetricCollectionModule(
            sink = sink,
            source = source,
            enrolled = { true },
            now = { 10_000L },
            persistLease = { it() },
        )

    private fun reading(sourceRecordId: String = "record-1") = HealthMetricReading(
        metricType = HealthMetricType.STEPS,
        value = 42.0,
        unit = "count",
        startMillis = 1_000L,
        endMillis = 2_000L,
        sourcePackage = "com.example.health",
        sourceRecordId = sourceRecordId,
    )

    private class FakeSource(private val readings: List<HealthMetricReading>) : HealthMetricSource {
        var acknowledgments = 0
        var rejections = 0

        override fun read(): List<HealthMetricReading> = readings
        override fun acknowledgeRead() { acknowledgments += 1 }
        override fun rejectRead() { rejections += 1 }
    }

    /** Models AndroidHealthMetricSource's single pending window: a second read before the ack is refused. */
    private class WindowSource(private val firstRead: () -> Unit) : HealthMetricSource {
        private var window = 0
        private var pending = 0
        val acknowledged = mutableListOf<Int>()
        var rejections = 0

        override fun read(): List<HealthMetricReading> {
            if (pending != 0) {
                rejectRead()
                error("Previous Health Connect read has not been acknowledged")
            }
            pending = ++window
            if (pending == 1) firstRead()
            return listOf(HealthMetricReading(HealthMetricType.STEPS, 1.0, "count", 1L, 2L, null, "record-$pending"))
        }
        override fun acknowledgeRead() {
            if (pending != 0) acknowledged += pending
            pending = 0
        }
        override fun rejectRead() { rejections += 1; pending = 0 }
    }

    private open class FakeSink(private val result: ModuleResult) : HealthMetricSampleSink(FakeDao()) {
        var samples: List<HealthMetricSampleEntry> = emptyList()

        override fun write(samples: List<HealthMetricSampleEntry>): ModuleResult {
            this.samples = samples
            return result
        }
    }

    private class FakeDao : HealthMetricSampleDao {
        override fun queueRowOwner(): QueueRowOwner? = null
        override fun insertOversizedQuarantine(row: LocalDataQuarantineEntity): Long = error("unexpected oversized test row")
        override fun insertAllUnchecked(samples: List<HealthMetricSampleEntry>) = insertAll(samples)

        override fun getByIds(ids: List<String>): List<HealthMetricSampleEntry> = emptyList()
        override fun getOldest(limit: Int): List<HealthMetricSampleEntry> = emptyList()
        override fun insertAll(samples: List<HealthMetricSampleEntry>) = Unit
        override fun deleteByIds(ids: List<String>) = Unit
        override fun count(): Int = 0
        override fun deleteAll() = Unit
    }
}
