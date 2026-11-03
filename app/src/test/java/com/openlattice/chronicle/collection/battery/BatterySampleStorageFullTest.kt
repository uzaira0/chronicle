package com.openlattice.chronicle.collection.battery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.gateBatchAccounting
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An enrolled pilot phone whose storage is full takes its battery sample: the row cannot be written, and the loss is
 * reported to the study as a local write failure instead of disappearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatterySampleStorageFullTest {
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun close() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    /** The module as BatteryTelemetryModuleHolder wires it, built per test so each test has its own store. */
    private fun module(db: ChronicleDb) = BatteryTelemetryCollectionModule(
        sink = BatterySampleSink(db.batterySampleDao(),
            ResearchPersistenceGate.guard(context, CollectionModuleId.BATTERY_TELEMETRY)),
        source = { BatteryReading(37, BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 291, 3812, BatteryHealth.GOOD) },
        enrolled = { true },
        accounting = gateBatchAccounting(context, LocalUploadModuleFamily.BATTERY),
    )

    @Test fun aSampleTheFullDiskRefusesIsReportedAsALocalWriteFailure() {
        val db = ChronicleDb.getInstance(context)
        assertTrue(onPersistenceWorker { module(db).sample() } is ModuleResult.Ok)
        assertEquals(1, onPersistenceWorker { db.batterySampleDao().count() })

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER disk_full BEFORE INSERT ON battery_samples BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        assertTrue(onPersistenceWorker { module(db).sample() } is ModuleResult.Failed)

        assertEquals(1, onPersistenceWorker { db.batterySampleDao().count() })
        val recorded = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }
        assertEquals(recorded.toString(), listOf(1), recorded.filter {
            it.moduleFamily == LocalUploadModuleFamily.BATTERY.name && it.issue == LocalOperationalIssue.LOCAL_WRITE_FAILED.name
        }.map { it.count })
    }
}
