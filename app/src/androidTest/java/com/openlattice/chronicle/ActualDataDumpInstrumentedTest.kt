package com.openlattice.chronicle

import android.database.Cursor
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActualDataDumpInstrumentedTest {

    @Test
    fun dumpCurrentLocalValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = ChronicleDb.getInstance(context)
        val sensorSettings = SensorSettings(context)

        log("BEGIN")
        log(
            "sensor_settings configured=${sensorSettings.getConfiguredSensors().sortedBy { it.name }} " +
                "enabled=${sensorSettings.getEnabledSensors().sortedBy { it.name }} " +
                "samplingRateHz=${sensorSettings.getSamplingRateHz()} " +
                "dutyActiveSeconds=${sensorSettings.getDutyCycleActiveSeconds()} " +
                "dutyPeriodSeconds=${sensorSettings.getDutyCyclePeriodSeconds()}"
        )
        dumpAvailableMappedSensors(context)
        dumpQuery(db, "counts", """
            SELECT
              (SELECT COUNT(*) FROM dataQueue) AS usageLifecyclePending,
              (SELECT COUNT(*) FROM sensor_samples) AS sensorPending,
              (SELECT COUNT(*) FROM battery_samples) AS batteryPending,
              (SELECT COUNT(*) FROM userQueue) AS userLabelsPending,
              (SELECT COUNT(*) FROM interaction_samples) AS interactionPending,
              (SELECT COUNT(*) FROM audio_activity_samples) AS audioActivityPending,
              (SELECT COUNT(*) FROM audio_content_samples) AS audioContentPending,
              (SELECT COUNT(*) FROM notification_activity_samples) AS notificationPending,
              (SELECT COUNT(*) FROM sleep_samples) AS sleepPending,
              (SELECT COUNT(*) FROM activity_recognition_samples) AS activityRecognitionPending,
              (SELECT COUNT(*) FROM health_metric_samples) AS healthMetricPending,
              (SELECT COUNT(*) FROM connectivity_state_samples) AS connectivityPending,
              (SELECT COUNT(*) FROM app_network_usage_samples) AS appNetworkPending,
              (SELECT COUNT(*) FROM device_settings_samples) AS deviceSettingsPending
        """.trimIndent())
        dumpQuery(db, "sensor_summary", """
            SELECT
              sensorType,
              COUNT(*) AS count,
              MIN(timestamp) AS firstTimestamp,
              MAX(timestamp) AS lastTimestamp,
              AVG(x) AS avgX,
              MIN(x) AS minX,
              MAX(x) AS maxX,
              AVG(y) AS avgY,
              MIN(y) AS minY,
              MAX(y) AS maxY,
              AVG(z) AS avgZ,
              MIN(z) AS minZ,
              MAX(z) AS maxZ
            FROM sensor_samples
            GROUP BY sensorType
            ORDER BY count DESC, sensorType
        """.trimIndent())
        dumpQuery(db, "battery_summary", """
            SELECT
              COUNT(*) AS count,
              MIN(timestamp) AS firstTimestamp,
              MAX(timestamp) AS lastTimestamp,
              AVG(levelPercent) AS avgLevel,
              MIN(levelPercent) AS minLevel,
              MAX(levelPercent) AS maxLevel,
              AVG(temperatureDeciC) AS avgTempDeciC,
              MIN(temperatureDeciC) AS minTempDeciC,
              MAX(temperatureDeciC) AS maxTempDeciC,
              AVG(voltageMillivolts) AS avgVoltageMv,
              MIN(voltageMillivolts) AS minVoltageMv,
              MAX(voltageMillivolts) AS maxVoltageMv
            FROM battery_samples
        """.trimIndent())
        dumpQuery(db, "upload_stats", """
            SELECT id, serverId, date, usageEventsUploaded, sensorSamplesUploaded,
                   batterySamplesUploaded, usageUploadFailures, sensorUploadFailures,
                   batteryUploadFailures
            FROM upload_stats
            ORDER BY date DESC, id DESC
            LIMIT 10
        """.trimIndent())
        dumpQuery(db, "upload_servers", """
            SELECT
              id,
              name,
              url,
              studyId,
              enabled,
              authMode,
              CASE WHEN apiKey IS NOT NULL AND length(apiKey) > 0 THEN 1 ELSE 0 END AS hasApiKey,
              CASE
                WHEN mobileSigningSecretOverride IS NULL OR length(mobileSigningSecretOverride) = 0 THEN 0
                ELSE 1
              END AS hasSigningOverride,
              consecutiveFailures,
              sensorConsecutiveFailures,
              batteryConsecutiveFailures,
              lastUploadTime,
              lastSensorUploadTime,
              lastBatteryUploadTime,
              lastUsageUploadAttemptTime,
              lastUsageUploadSuccessTime,
              usageUploadSuccessCount,
              usageUploadFailureCount,
              lastSensorUploadAttemptTime,
              lastSensorUploadSuccessTime,
              sensorUploadSuccessCount,
              sensorUploadFailureCount,
              lastBatteryUploadAttemptTime,
              lastBatteryUploadSuccessTime,
              batteryUploadSuccessCount,
              batteryUploadFailureCount,
              lastUploadError,
              lastSensorUploadError,
              lastBatteryUploadError
            FROM upload_servers
            ORDER BY createdAt ASC
        """.trimIndent())
        dumpQuery(db, "collection_module_state", """
            SELECT
              moduleId,
              serverEnabled,
              decision,
              decidedAtEpochMillis,
              requiredApplied,
              appliedVersion,
              lastDisposition
            FROM collection_module_state
            ORDER BY moduleId ASC
        """.trimIndent())

        db.sensorSampleDao().getOldest(20).forEachIndexed { index, sample ->
            log(
                "sensor_row[$index] id=${sample.id.take(8)} type=${sample.sensorType} " +
                    "timestamp=${sample.timestamp} timezone=${sample.timezone} " +
                    "x=${sample.x} y=${sample.y} z=${sample.z} w=${sample.w} " +
                    "accuracy=${sample.accuracy} valuesJson=${sample.valuesJson?.take(240)}"
            )
        }

        db.batterySampleDao().getOldest(20).forEachIndexed { index, sample ->
            log(
                "battery_row[$index] id=${sample.id.take(8)} timestamp=${sample.timestamp} " +
                    "timezone=${sample.timezone} levelPercent=${sample.levelPercent} " +
                    "chargingState=${sample.chargingState} plugType=${sample.plugType} " +
                    "temperatureDeciC=${sample.temperatureDeciC} " +
                    "voltageMillivolts=${sample.voltageMillivolts} health=${sample.health}"
            )
        }

        db.queueEntryData().getNextEntries(10).forEachIndexed { index, entry ->
            log(
                "usage_lifecycle_row[$index] writeTimestamp=${entry.writeTimestamp} " +
                    "id=${entry.id} bytes=${entry.data.size}"
            )
        }
        log("END")
    }

    private fun dumpAvailableMappedSensors(context: Context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        AndroidSensorType.values().sortedBy { it.name }.forEach { sensorType ->
            val androidType = runCatching { SensorTypeMapping.toAndroidType(sensorType) }.getOrNull()
            val sensor = androidType?.let { sensorManager.getDefaultSensor(it) }
            log(
                "mapped_sensor type=$sensorType androidType=${androidType ?: "unknown"} " +
                    "available=${sensor != null} details=${sensor?.toDumpString() ?: "none"}"
            )
        }
    }

    private fun Sensor.toDumpString(): String =
        "name=$name vendor=$vendor version=$version minDelayUs=$minDelay " +
            "reportingMode=$reportingMode wakeUp=$isWakeUpSensor"

    private fun dumpQuery(db: ChronicleDb, label: String, sql: String) {
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) {
                log("$label: <empty>")
                return
            }
            var row = 0
            do {
                log("$label[$row] ${cursor.toMapString()}")
                row++
            } while (cursor.moveToNext())
        }
    }

    private fun Cursor.toMapString(): String {
        return columnNames.joinToString(prefix = "{", postfix = "}") { name ->
            val index = getColumnIndexOrThrow(name)
            val value = when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> "null"
                Cursor.FIELD_TYPE_INTEGER -> getLong(index).toString()
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index).toString()
                Cursor.FIELD_TYPE_BLOB -> "<blob:${getBlob(index).size}>"
                else -> getString(index)
            }
            "$name=$value"
        }
    }

    private fun log(message: String) {
        Log.i(TAG, "ACTUAL_DATA_DUMP|$message")
        println("ACTUAL_DATA_DUMP|$message")
    }

    private companion object {
        private const val TAG = "ActualDataDump"
    }
}
