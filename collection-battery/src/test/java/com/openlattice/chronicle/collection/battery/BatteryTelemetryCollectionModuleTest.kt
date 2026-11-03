package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.storage.BatterySampleDao
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QueueRowOwner
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.preferences.SecurePreferencesUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import android.database.SQLException

/**
 * JVM unit tests for [BatteryTelemetryCollectionModule]. The module is fully
 * `Context`-free, so it is exercised here through its injected seams — a fake DAO
 * behind a real [BatterySampleSink], a lambda [BatterySampleSource] and a
 * fixed clock.
 */
class BatteryTelemetryCollectionModuleTest {

    /** Records inserts so a test can assert what the module persisted. */
    private class FakeBatterySampleDao(private val failOnInsert: Boolean = false) : BatterySampleDao {
        override fun queueRowOwner(): QueueRowOwner? = null
        override fun insertOversizedQuarantine(row: LocalDataQuarantineEntity): Long = error("unexpected oversized test row")
        override fun insertAllUnchecked(samples: List<BatterySampleEntry>) = insertAll(samples)

        val inserted = mutableListOf<BatterySampleEntry>()

        override fun insertAll(samples: List<BatterySampleEntry>) {
            if (failOnInsert) throw RuntimeException("battery_samples insert boom")
            inserted += samples
        }

        override fun count(): Int = inserted.size
        override fun getByIds(ids: List<String>): List<BatterySampleEntry> = inserted.filter { it.id in ids }
        override fun getOldest(limit: Int): List<BatterySampleEntry> = inserted.take(limit)
        override fun deleteByIds(ids: List<String>) {}
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
        sink = BatterySampleSink(dao.dao),
        source = source,
        enrolled = { enrolled },
        now = { 1_700_000_000_000L },
    )

    /** Bundles a fake DAO with a typed handle so tests can both inject and assert on it. */
    private class BatteryDaoState(failOnInsert: Boolean = false) {
        val dao = FakeBatterySampleDao(failOnInsert)
    }

    /** The queue reads its oldest rows in stored text order, so the stored form sorts in time order. */
    @Test fun storedTimestampsSortInTimeOrder() {
        val state = BatteryDaoState()
        var current = 0L
        val module = BatteryTelemetryCollectionModule(BatterySampleSink(state.dao), { reading }, { true }, now = { current })
        for (time in listOf(1_700_000_040_001L, 1_700_000_040_000L, 1_700_000_039_999L)) {
            current = time
            assertEquals(ModuleResult.Ok(1), module.sample())
        }
        val stored = state.dao.inserted.map { it.timestamp }
        assertEquals(listOf("2023-11-14T22:14:00.001Z", "2023-11-14T22:14:00.000Z", "2023-11-14T22:13:59.999Z"), stored)
        assertEquals(stored.reversed(), stored.sorted())
    }

    @Test fun testSamplePersistsOneRow() {
        val state = BatteryDaoState()
        val result = module(state, { reading }).sample()
        assertEquals(ModuleResult.Ok(1), result)
        assertEquals(1, state.dao.inserted.size)
        assertEquals(72, state.dao.inserted.single().levelPercent)
        assertEquals("DISCHARGING", state.dao.inserted.single().chargingState)
    }

    @Test fun eachCaptureKeepsTheBuildThatProducedItAcrossAnAppUpdate() {
        val state = BatteryDaoState()
        for (version in listOf("2026.10.01 (80)", "2026.11.01 (81)")) {
            val collector = BatteryTelemetryCollectionModule(
                BatterySampleSink(state.dao), { reading }, { true }, producerVersion = version,
            )
            assertEquals(ModuleResult.Ok(1), collector.sample())
        }
        assertEquals(listOf("2026.10.01 (80)", "2026.11.01 (81)"), state.dao.inserted.map { it.producerVersion })
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

    @Test fun platformSourceFailuresAreReportedWithoutWriting() {
        for (failure in listOf(SecurityException("denied"), IllegalArgumentException("invalid"), IllegalStateException("unavailable"))) {
            val state = BatteryDaoState()
            val result = module(state, { throw failure }).sample()
            assertTrue(result is ModuleResult.Failed)
            assertEquals(failure, (result as ModuleResult.Failed).error)
        }
    }

    @Test fun testSampleFailedWhenSinkThrows() {
        val state = BatteryDaoState(failOnInsert = true)
        val mod = module(state, { reading })
        val result = mod.sample()
        assertTrue(result is ModuleResult.Failed)
    }

    /**
     * AND-V03: the module's outer admission transaction (settings evidence) can fail after the sink wrote, e.g. when
     * storage fills; the guard rolls the row back. The sample is a Failed result accounted once to the captured owner,
     * never an exception that skips the accounting.
     */
    @Test fun failedOuterAdmissionTransactionIsAccountedToTheCapturedOwner() {
        val owner = UploadServerEntity(id = 1, name = "A", url = "https://example.invalid",
            studyId = "study-a", participantId = "participant-a", sourceRandomDeviceId = "device-a")
        for (failure in listOf(SQLException("settings evidence transaction failed"),
            LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED),
            SecurePreferencesUnavailableException(IllegalStateException("encrypted preferences unavailable")),
            IllegalStateException("unexpected admission failure"))) {
            var outerTransactions = 0
            val guard = object : CollectionPersistenceGuard {
                override fun persist(persist: () -> Unit): Boolean = persistResult(persist) == CollectionPersistenceResult.PERSISTED
                override fun persistResult(persist: () -> Unit): CollectionPersistenceResult {
                    if (outerTransactions++ == 0) {
                        persist()
                        throw failure
                    }
                    persist()
                    return CollectionPersistenceResult.PERSISTED
                }
            }
            val accounted = mutableListOf<Triple<UploadServerEntity?, ModuleResult, Int>>()
            val module = BatteryTelemetryCollectionModule(
                sink = BatterySampleSink(BatteryDaoState().dao, guard),
                source = { reading },
                enrolled = { true },
                accounting = PullAccounting({ owner }) { captured, result, count -> accounted += Triple(captured, result, count) },
            )

            val result = module.sample()

            assertTrue(result is ModuleResult.Failed)
            assertEquals(failure, (result as ModuleResult.Failed).error)
            assertEquals(1, accounted.size)
            assertEquals(owner, accounted.single().first)
            assertTrue(accounted.single().second is ModuleResult.Failed)
            assertEquals(1, accounted.single().third)
        }
    }

    @Test fun cancellationEscapesWithoutLossAccounting() {
        val cancelled = CancellationException("cancelled")
        for ((failure, duringRead) in listOf(cancelled to false, cancelled to true)) {
            var accounted = 0
            val guard = CollectionPersistenceGuard { throw failure }
            val module = BatteryTelemetryCollectionModule(
                sink = BatterySampleSink(BatteryDaoState().dao, guard),
                source = { if (duringRead) throw failure else reading },
                enrolled = { true },
                accounting = PullAccounting(accountWriteResult = { _, _, _ -> accounted++ }),
            )
            assertEquals(failure, assertThrows(failure.javaClass) { module.sample() })
            assertEquals(0, accounted)
        }
    }

    @Test fun failedSampleKeepsTheOwnerCapturedBeforeSourceRead() {
        val a = UploadServerEntity(id = 1, name = "A", url = "https://example.invalid",
            studyId = "study-a", participantId = "participant-a", sourceRandomDeviceId = "device-a")
        val b = a.copy(id = 2, name = "B", studyId = "study-b")
        var active = a
        var accountedOwner: UploadServerEntity? = null
        val module = BatteryTelemetryCollectionModule(
            sink = BatterySampleSink(FakeBatterySampleDao(failOnInsert = true)),
            source = { active = b; reading },
            enrolled = { true },
            accounting = PullAccounting({ active }) { owner, result, count ->
                if (result is ModuleResult.Failed && count == 1) accountedOwner = owner
            },
        )

        assertTrue(module.sample() is ModuleResult.Failed)
        assertEquals(a, accountedOwner)
    }

}
