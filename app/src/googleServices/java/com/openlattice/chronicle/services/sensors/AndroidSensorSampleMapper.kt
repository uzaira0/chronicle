package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.storage.SensorSampleEntry
import java.time.OffsetDateTime
import java.util.UUID

internal const val MAX_SENSOR_VALUE_COUNT = 16
internal const val MAX_SENSOR_VALUES_JSON_CHARS = 512

/**
 * Converts persisted sensor rows to the public Chronicle API model.
 *
 * Kept small and package-visible so tests can validate the exact upload contract without
 * needing to run a Worker or talk to a server. Malformed rows throw, matching the upload
 * delegate's corrupt-sample skip path.
 */
internal fun SensorSampleEntry.toAndroidSensorSample(): AndroidSensorSample {
    return AndroidSensorSample(
        id = UUID.fromString(id),
        sensor = AndroidSensorType.valueOf(sensorType),
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        x = x,
        y = y,
        z = z,
        w = w,
        accuracy = accuracy,
        values = parseSensorValues(valuesJson),
    )
}

internal fun parseSensorValues(valuesJson: String?): List<Float> {
    if (valuesJson.isNullOrBlank()) return emptyList()

    val trimmed = valuesJson.trim()
    require(trimmed.length <= MAX_SENSOR_VALUES_JSON_CHARS) {
        "sensor values exceed the local size limit"
    }
    require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
        "sensor values must be a JSON-style numeric array"
    }

    val body = trimmed.removePrefix("[").removeSuffix("]").trim()
    if (body.isEmpty()) return emptyList()

    val tokens = body.split(",")
    require(tokens.size <= MAX_SENSOR_VALUE_COUNT) {
        "sensor values exceed the $MAX_SENSOR_VALUE_COUNT-value contract"
    }
    return tokens.map { token ->
        val value = token.trim().toFloatOrNull()
            ?: throw IllegalArgumentException("sensor value is not numeric")
        require(value.isFinite()) { "sensor value must be finite" }
        value
    }
}
