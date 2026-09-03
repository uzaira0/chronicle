package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.storage.BatterySampleDao
import com.openlattice.chronicle.storage.BatterySampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [BatteryTelemetryCollectionModule]. The module is fully
 * `Context`-free, so it is exercised here through its injected seams — a fake DAO
 * behind a real [BatterySampleSink], a lambda [BatterySampleSource], a
 * [FixedCollectionClock] and a [NoOpCollectionLog].
 */
class BatteryTelemetryCollectionModuleTest {

    /** Records inserts so a test can assert what the module persisted. */
    private class FakeBatterySampleDao(private val failOnInsert: Boolean = false) : BatterySampleDao {
        val inserted = mutableListOf<BatterySampleEntry>()

        override fun insertAll(samples: List<BatterySampleEntry>) {
            if (failOnInsert) throw RuntimeException("battery_samples insert boom")
            inserted += samples
        }

        override fun count(): Int = inserted.size
        override fun getOldest(limit: Int): List<BatterySampleEntry> = inserted.take(limit)
        override fun deleteByIds(ids: List<String>) {}
        override fun deleteOlderThan(cutoffTimestamp: String): Int = 0
        override fun getEntriesAfterTimestamp(cursorTimestamp: String, limit: Int): List<BatterySampleEntry> =
            emptyList()
        override fun deleteEntriesBeforeTimestamp(maxTimestamp: String) {}
        override fun deleteAll() { inserted.clear() }
    }

    private val reading = BatteryReading(
        levelPercent = 72,
        chargingState = BatteryChargingState.DISCHARGING,
        plugType = BatteryPlugType.UNPLUGGED,
        temperatureDeciC = 305,
        voltageMillivolts = 4050,
        health = BatteryHealth.GOOD,
    )

    private fun module(
        dao: BatteryDaoState,
        source: BatterySampleSource,
        enrolled: Boolean = true,
    ): BatteryTelemetryCollectionModule = BatteryTelemetryCollectionModule(
        sink = BatterySampleSink(dao.dao, NoOpCollectionLog),
        source = source,
        enrolled = { enrolled },
        clock = FixedCollectionClock(1_700_000_000_000L),
        log = NoOpCollectionLog,
    )

    /** Bundles a fake DAO with a typed handle so tests can both inject and assert on it. */
    private class BatteryDaoState(failOnInsert: Boolean = false) {
        val dao = FakeBatterySampleDao(failOnInsert)
    }

    @Test fun testSamplePersistsOneRow() {
        val state = BatteryDaoState()
        val result = module(state, { reading }).sample()
        assertEquals(ModuleResult.Ok(1), result)
        assertEquals(1, state.dao.inserted.size)
        assertEquals(72, state.dao.inserted.single().levelPercent)
        assertEquals("DISCHARGING", state.dao.inserted.single().chargingState)
    }

    @Test fun testSampleSkippedWhenNotEnrolled() {
        val state = BatteryDaoState()
        val result = module(state, { reading }, enrolled = false).sample()
        assertTrue(result is ModuleResult.Skipped)
        assertEquals(0, state.dao.inserted.size)
    }

    @Test fun testSampleRetryWhenSourceUnavailable() {
        val state = BatteryDaoState()
        val result = module(state, { null }).sample()
        assertTrue(result is ModuleResult.Retry)
        assertEquals(0, state.dao.inserted.size)
    }

    @Test fun testSampleFailedWhenSinkThrows() {
        val state = BatteryDaoState(failOnInsert = true)
        val mod = module(state, { reading })
        val result = mod.sample()
        assertTrue(result is ModuleResult.Failed)
        assertEquals(CollectionModuleStatus.FAILED, mod.status())
    }

    @Test fun testIdAndPrivacyClass() {
        val mod = module(BatteryDaoState(), { reading })
        assertEquals(CollectionModuleId.BATTERY_TELEMETRY, mod.id)
        assertEquals(CollectionPrivacyClass.DEVICE_STATE_METADATA, mod.privacyClass)
    }

    @Test fun testDiagnosticsReflectSuccessfulSample() {
        val mod = module(BatteryDaoState(), { reading })
        mod.sample()
        val diagnostics = mod.diagnostics()
        assertEquals(CollectionModuleId.BATTERY_TELEMETRY, diagnostics.moduleId)
        assertEquals(1, diagnostics.itemsCollected)
        assertEquals("OK", diagnostics.lastResult)
        assertEquals(CollectionModuleStatus.IDLE, mod.status())
    }
}
