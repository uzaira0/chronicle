package com.openlattice.chronicle.compat

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.collection.device.HealthConnectPermissions
import com.openlattice.chronicle.serialization.JsonSerializer
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Research-only API-floor coverage for collectors intentionally absent from public artifacts. */
@RunWith(AndroidJUnit4::class)
class RestrictedApiFloorDependencySmokeTest {
    @Test
    fun restrictedAdaptersLoadOnTheApiFloor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sensor = AndroidSensorSample(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            sensor = AndroidSensorType.accelerometer,
            timestamp = OffsetDateTime.parse("2026-07-09T12:34:56Z"),
            timezone = "UTC",
            x = 1f,
        )
        val sensorJson = JsonSerializer.toJson(listOf(sensor))
        assertEquals(sensor, JsonSerializer.fromJson<List<AndroidSensorSample>>(sensorJson)?.single())

        HealthConnectPermissions.requestContract()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            assertTrue(AndroidHealthMetricSource(context).read().isEmpty())
        }
    }
}
