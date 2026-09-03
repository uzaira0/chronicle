package com.openlattice.chronicle.collection.battery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof for the `battery_telemetry` module.
 *
 * Unlike the JVM unit tests (which inject fakes), this exercises the **real** seams on
 * the connected device: [AndroidBatterySampleSource] reads the device's actual
 * `ACTION_BATTERY_CHANGED` sticky broadcast, and [BatterySampleSink] writes into a real
 * Room `ChronicleDb` built at schema version 10 — so a passing run proves the device's
 * battery is readable, the mapping is correct, and the v10 `battery_samples` table
 * accepts writes on real hardware.
 *
 * An in-memory `ChronicleDb` is used so the test is isolated and leaves no on-device
 * state; it is still built from the same v10 `@Database` definition.
 */
@RunWith(AndroidJUnit4::class)
class BatteryTelemetryCollectionModuleInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: ChronicleDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Reads the device's real battery and persists one row into the v10 table. */
    @Test
    fun realDeviceBatterySamplePersistsToV10Table() {
        val module = BatteryTelemetryCollectionModule(
            sink = BatterySampleSink(db.batterySampleDao()),
            source = AndroidBatterySampleSource(context),
            enrolled = { true },
        )

        val result = module.sample()
        assertTrue("expected Ok from a real device battery read, got $result", result is ModuleResult.Ok)

        val rows = db.batterySampleDao().getOldest(10)
        assertEquals("exactly one battery sample should be persisted", 1, rows.size)

        val row = rows.single()
        assertTrue("levelPercent ${row.levelPercent} must be within 0..100", row.levelPercent in 0..100)
        // The stored enum-name strings must round-trip to the shared-model enums.
        BatteryChargingState.valueOf(row.chargingState)
        BatteryPlugType.valueOf(row.plugType)
        BatteryHealth.valueOf(row.health)
    }

    /** A non-enrolled participant must not produce any battery row. */
    @Test
    fun notEnrolledPersistsNothing() {
        val module = BatteryTelemetryCollectionModule(
            sink = BatterySampleSink(db.batterySampleDao()),
            source = AndroidBatterySampleSource(context),
            enrolled = { false },
        )

        val result = module.sample()
        assertTrue("expected Skipped when not enrolled, got $result", result is ModuleResult.Skipped)
        assertEquals(0, db.batterySampleDao().count())
    }
}
