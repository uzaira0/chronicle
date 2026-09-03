package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.FakeSensorSampleDao
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.services.sensors.parseSensorValues
import com.openlattice.chronicle.services.sensors.toAndroidSensorSample
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Contract tests for the complete hardware-sensor data path we can validate on the JVM:
 *
 * AndroidSensorType -> SensorTypeMapping -> SensorRuntimeController row ->
 * SensorSampleEntry -> AndroidSensorSample upload model.
 *
 * The tests enumerate [AndroidSensorType.values] so new API sensors cannot appear without
 * an explicit app-side mapping, value-width decision, collection classification, and
 * upload serialization contract.
 */
class SensorDataContractTest {

    private val expectedValueCounts = mapOf(
        AndroidSensorType.accelerometer to 3,
        AndroidSensorType.gyroscope to 3,
        AndroidSensorType.magnetometer to 3,
        AndroidSensorType.gravity to 3,
        AndroidSensorType.linearAcceleration to 3,
        AndroidSensorType.rotationVector to 4,
        AndroidSensorType.stepCounter to 1,
        AndroidSensorType.light to 1,
        AndroidSensorType.proximity to 1,
        AndroidSensorType.significantMotion to 1,
        AndroidSensorType.tiltDetector to 1,
        AndroidSensorType.screenOrientation to 1,
        AndroidSensorType.samsungGripWifi to 16,
        AndroidSensorType.samsungMotion to 4,
    )

    @Test
    fun everyKnownSensorHasAnExplicitCollectionContract() {
        val knownSensors = AndroidSensorType.values().toSet()
        assertEquals(
            "Update expectedValueCounts when the API adds/removes Android sensors.",
            knownSensors,
            expectedValueCounts.keys,
        )

        knownSensors.forEach { sensor ->
            val androidType = SensorTypeMapping.toAndroidType(sensor)
            assertEquals("$sensor should round-trip through Android sensor type", sensor, SensorTypeMapping.fromAndroidType(androidType))
            assertEquals("$sensor value width", expectedValueCounts.getValue(sensor), SensorTypeMapping.valueCount(sensor))

            val continuous = SensorTypeMapping.isContinuousSensor(sensor)
            val persistent = SensorTypeMapping.isPersistentSensor(sensor)
            assertTrue("$sensor must be either continuous or persistent", continuous || persistent)
            assertFalse("$sensor cannot be both continuous and persistent", continuous && persistent)
            if (SensorTypeMapping.isTriggerSensor(sensor)) {
                assertTrue("$sensor trigger sensors must be persistent", persistent)
            }
        }
    }

    @Test
    fun runtimePersistsTypedRowsWithExpectedValuesForEveryKnownSensor() {
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        lateinit var controller: SensorRuntimeController
        controller = SensorRuntimeController(
            gateway = gateway,
            settings = FakeSensorRuntimeSettings(sensors = AndroidSensorType.values().toSet()),
            sink = SensorSampleSink(dao, NoOpCollectionLog),
            scheduler = scheduler,
            log = NoOpCollectionLog,
        )
        gateway.attach(object : SensorGateway.SampleListener {
            override fun onSample(
                sensorType: AndroidSensorType,
                values: FloatArray,
                accuracy: Int,
                timestamp: OffsetDateTime,
            ) {
                controller.recordSample(sensorType, values, accuracy, timestamp)
            }

            override fun onTrigger(
                sensorType: AndroidSensorType,
                values: FloatArray,
                timestamp: OffsetDateTime,
            ) {
                controller.recordSample(sensorType, values, null, timestamp)
            }
        })

        val baseTime = OffsetDateTime.parse("2026-06-07T13:05:00-05:00")
        controller.start()
        AndroidSensorType.values().forEachIndexed { index, sensor ->
            val values = valuesFor(sensor)
            val timestamp = baseTime.plusSeconds(index.toLong())
            if (SensorTypeMapping.isTriggerSensor(sensor)) {
                gateway.fireTrigger(sensor, values, timestamp)
            } else {
                gateway.emitSample(sensor, values, accuracy = 2, timestamp = timestamp)
            }
        }

        controller.flushBuffer()

        assertEquals(AndroidSensorType.values().size, dao.count())
        AndroidSensorType.values().forEachIndexed { index, sensor ->
            val row = dao.rows.values.single { it.sensorType == sensor.name }
            val values = valuesFor(sensor).toList()

            assertNotNull("$sensor id must be a UUID", UUID.fromString(row.id))
            assertEquals("$sensor timestamp", baseTime.plusSeconds(index.toLong()), OffsetDateTime.parse(row.timestamp))
            assertFalse("$sensor timezone must be populated", row.timezone.isBlank())
            assertEquals("$sensor full raw values", values, parseSensorValues(row.valuesJson))
            assertNullableFloat("$sensor x", values.getOrNull(0), row.x)
            assertNullableFloat("$sensor y", values.getOrNull(1), row.y)
            assertNullableFloat("$sensor z", values.getOrNull(2), row.z)
            assertNullableFloat("$sensor w", values.getOrNull(3), row.w)
            if (SensorTypeMapping.isTriggerSensor(sensor)) {
                assertNull("$sensor trigger samples do not carry accuracy", row.accuracy)
            } else {
                assertEquals("$sensor accuracy", 2, row.accuracy)
            }
        }
    }

    @Test
    fun uploadModelPreservesTypedValuesForEveryKnownSensor() {
        val baseTime = OffsetDateTime.parse("2026-06-07T13:10:00-05:00")

        AndroidSensorType.values().forEachIndexed { index, sensor ->
            val values = valuesFor(sensor).toList()
            val entry = sensorEntry(
                sensor = sensor,
                id = UUID.nameUUIDFromBytes(sensor.name.toByteArray()).toString(),
                timestamp = baseTime.plusSeconds(index.toLong()).toString(),
                values = values,
                accuracy = if (SensorTypeMapping.isTriggerSensor(sensor)) null else 3,
            )

            val api = entry.toAndroidSensorSample()

            assertEquals("$sensor id", UUID.fromString(entry.id), api.id)
            assertEquals("$sensor enum", sensor, api.sensor)
            assertEquals("$sensor timestamp", OffsetDateTime.parse(entry.timestamp), api.timestamp)
            assertEquals("$sensor timezone", "America/Chicago", api.timezone)
            assertEquals("$sensor values", values, api.values)
            assertNullableFloat("$sensor x", values.getOrNull(0), api.x)
            assertNullableFloat("$sensor y", values.getOrNull(1), api.y)
            assertNullableFloat("$sensor z", values.getOrNull(2), api.z)
            assertNullableFloat("$sensor w", values.getOrNull(3), api.w)
            assertEquals("$sensor accuracy", entry.accuracy, api.accuracy)
        }
    }

    @Test
    fun uploadModelRejectsMalformedRowsInsteadOfCorruptingValues() {
        assertThrows<IllegalArgumentException>("bad sensor enum") {
            sensorEntry(AndroidSensorType.accelerometer, sensorTypeOverride = "missingSensor").toAndroidSensorSample()
        }
        assertThrows<java.time.format.DateTimeParseException>("bad timestamp") {
            sensorEntry(AndroidSensorType.accelerometer, timestamp = "not-a-time").toAndroidSensorSample()
        }
        assertThrows<IllegalArgumentException>("values must be bracketed") {
            sensorEntry(AndroidSensorType.accelerometer, valuesJsonOverride = "1.0,2.0,3.0").toAndroidSensorSample()
        }
        assertThrows<IllegalArgumentException>("values must be numeric") {
            sensorEntry(AndroidSensorType.accelerometer, valuesJsonOverride = "[1.0, nope, 3.0]").toAndroidSensorSample()
        }
        assertThrows<IllegalArgumentException>("values must be finite") {
            sensorEntry(AndroidSensorType.accelerometer, valuesJsonOverride = "[1.0, NaN, 3.0]").toAndroidSensorSample()
        }
        assertThrows<IllegalArgumentException>("values must respect the maximum sensor width") {
            val tooMany = (0..16).joinToString(prefix = "[", postfix = "]") { it.toString() }
            sensorEntry(AndroidSensorType.accelerometer, valuesJsonOverride = tooMany).toAndroidSensorSample()
        }
        assertThrows<IllegalArgumentException>("values JSON is bounded before token allocation") {
            val oversized = "[" + "1,".repeat(300) + "1]"
            sensorEntry(AndroidSensorType.accelerometer, valuesJsonOverride = oversized).toAndroidSensorSample()
        }
    }

    private fun valuesFor(sensor: AndroidSensorType): FloatArray {
        val width = expectedValueCounts.getValue(sensor)
        return FloatArray(width) { offset ->
            sensor.ordinal * 100.0f + offset + 0.25f
        }
    }

    private fun sensorEntry(
        sensor: AndroidSensorType,
        id: String = UUID.randomUUID().toString(),
        sensorTypeOverride: String = sensor.name,
        timestamp: String = "2026-06-07T13:05:00-05:00",
        values: List<Float> = valuesFor(sensor).toList(),
        valuesJsonOverride: String? = values.joinToString(prefix = "[", postfix = "]"),
        accuracy: Int? = 2,
    ): SensorSampleEntry {
        return SensorSampleEntry(
            id = id,
            sensorType = sensorTypeOverride,
            timestamp = timestamp,
            timezone = "America/Chicago",
            x = values.getOrNull(0),
            y = values.getOrNull(1),
            z = values.getOrNull(2),
            w = values.getOrNull(3),
            accuracy = accuracy,
            valuesJson = valuesJsonOverride,
        )
    }

    private fun assertNullableFloat(label: String, expected: Float?, actual: Float?) {
        if (expected == null) {
            assertNull(label, actual)
        } else {
            assertNotNull(label, actual)
            assertEquals(label, expected, actual!!, 0.0001f)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}: $label")
        } catch (e: Throwable) {
            if (e !is T) {
                throw AssertionError("Expected ${T::class.java.simpleName} for $label, got ${e::class.java.simpleName}", e)
            }
        }
    }
}
