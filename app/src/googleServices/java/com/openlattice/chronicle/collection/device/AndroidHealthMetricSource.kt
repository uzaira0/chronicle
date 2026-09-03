package com.openlattice.chronicle.collection.device

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.HealthConnectRecordType
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.reflect.KClass

private const val TAG = "HealthMetricSource"
private const val PREFS = "chronicle_health_connect"
private const val KEY_LAST_END = "last_end_millis"

/**
 * Production [HealthMetricSource] over the system Health Connect store. Reads only the record types
 * the participant has granted, only over the window since the last successful read (a
 * SharedPreferences checkpoint). A no-op (empty) when Health Connect is unavailable or no read
 * permission is granted. Read-only — Chronicle never writes health data back.
 *
 * The Health Connect client API is suspend-based; reads run inside [runBlocking] because the
 * collection worker already calls this off the main thread.
 */
public class AndroidHealthMetricSource(context: Context) : HealthMetricSource {

    private val appContext = context.applicationContext
    private val readCoordinator = HealthMetricReadCoordinator(
        object : HealthMetricCheckpoint {
            private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            override fun read(): Long? = if (prefs.contains(KEY_LAST_END)) {
                prefs.getLong(KEY_LAST_END, 0L)
            } else {
                null
            }

            override fun write(endMillis: Long) {
                check(prefs.edit().putLong(KEY_LAST_END, endMillis).commit()) {
                    "Unable to persist Health Connect read checkpoint"
                }
            }
        },
    )

    override fun read(): List<HealthMetricReading> {
        val configuredRecordTypes = runCatching { HealthConnectScopeStore.of(appContext).read() }
            .onFailure { Log.e(TAG, "Health Connect scope is unavailable; reading nothing", it) }
            .getOrDefault(emptySet())
        if (configuredRecordTypes.isEmpty()) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return emptyList()
        }
        if (HealthConnectClient.getSdkStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
            return emptyList()
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .onFailure { Log.w(TAG, "Health Connect client creation failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return emptyList()

        val now = System.currentTimeMillis()
        val granted: Set<String> = runCatching {
            runBlocking { client.permissionController.getGrantedPermissions() }
        }.onFailure { Log.w(TAG, "Health Connect permission query failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptySet())
        if (granted.isEmpty()) return emptyList()

        return readCoordinator.read(now) { start, end ->
            val range = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
            val out = mutableListOf<HealthMetricReading>()
            if (HealthConnectRecordType.STEPS in configuredRecordTypes) out += readSteps(client, granted, range)
            if (HealthConnectRecordType.DISTANCE in configuredRecordTypes) out += readDistance(client, granted, range)
            if (HealthConnectRecordType.HEART_RATE in configuredRecordTypes) out += readHeartRate(client, granted, range)
            if (HealthConnectRecordType.TOTAL_CALORIES_BURNED in configuredRecordTypes) out +=
                readTotalCalories(client, granted, range)
            if (HealthConnectRecordType.ACTIVE_CALORIES_BURNED in configuredRecordTypes) out +=
                readActiveCalories(client, granted, range)
            if (HealthConnectRecordType.FLOORS_CLIMBED in configuredRecordTypes) out += readFloors(client, granted, range)
            if (HealthConnectRecordType.RESTING_HEART_RATE in configuredRecordTypes) out +=
                readRestingHeartRate(client, granted, range)
            if (HealthConnectRecordType.OXYGEN_SATURATION in configuredRecordTypes) out +=
                readOxygenSaturation(client, granted, range)
            if (HealthConnectRecordType.RESPIRATORY_RATE in configuredRecordTypes) out +=
                readRespiratoryRate(client, granted, range)
            if (HealthConnectRecordType.SLEEP in configuredRecordTypes) {
                out += readSleepSessions(client, granted, range)
                out += readSleepStages(client, granted, range)
            }
            if (HealthConnectRecordType.EXERCISE in configuredRecordTypes) out +=
                readExerciseSessions(client, granted, range)
            if (HealthConnectRecordType.HEART_RATE_VARIABILITY in configuredRecordTypes) out +=
                readHrv(client, granted, range)
            if (HealthConnectRecordType.BODY_TEMPERATURE in configuredRecordTypes) out +=
                readBodyTemperature(client, granted, range)
            if (HealthConnectRecordType.SKIN_TEMPERATURE in configuredRecordTypes) out +=
                readSkinTemperature(client, granted, range)
            out
        }
    }

    override fun acknowledgeRead() {
        readCoordinator.acknowledge()
    }

    override fun rejectRead() {
        readCoordinator.reject()
    }

    private fun <T : Record> readRecords(
        client: HealthConnectClient,
        granted: Set<String>,
        type: KClass<T>,
        range: TimeRangeFilter,
    ): List<T> {
        if (!granted.contains(HealthPermission.getReadPermission(type))) return emptyList()
        return try {
            runBlocking {
                readAllHealthMetricPages { pageToken ->
                    val response = client.readRecords(
                        ReadRecordsRequest(type, timeRangeFilter = range, pageToken = pageToken),
                    )
                    HealthMetricPage(response.records, response.pageToken)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readRecords(${type.simpleName}) failed: ${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun readSteps(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, StepsRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.STEPS, it.count.toDouble(), "count",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readDistance(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, DistanceRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.DISTANCE, it.distance.inMeters, "m",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readHeartRate(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, HeartRateRecord::class, r).flatMap { record ->
            record.samples.map { sample ->
                HealthMetricReading(
                    HealthMetricType.HEART_RATE, sample.beatsPerMinute.toDouble(), "bpm",
                    sample.time.toEpochMilli(), sample.time.toEpochMilli(), record.metadata.dataOrigin.packageName,
                    "${record.metadata.id}:${sample.time.toEpochMilli()}",
                )
            }
        }

    private fun readTotalCalories(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, TotalCaloriesBurnedRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.TOTAL_CALORIES, it.energy.inKilocalories, "kcal",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readActiveCalories(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, ActiveCaloriesBurnedRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.ACTIVE_CALORIES, it.energy.inKilocalories, "kcal",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readFloors(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, FloorsClimbedRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.FLOORS_CLIMBED, it.floors, "count",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readRestingHeartRate(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, RestingHeartRateRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.RESTING_HEART_RATE, it.beatsPerMinute.toDouble(), "bpm",
                it.time.toEpochMilli(), it.time.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readOxygenSaturation(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, OxygenSaturationRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.OXYGEN_SATURATION, it.percentage.value, "%",
                it.time.toEpochMilli(), it.time.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readRespiratoryRate(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, RespiratoryRateRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.RESPIRATORY_RATE, it.rate, "rpm",
                it.time.toEpochMilli(), it.time.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    // Sleep/exercise are sessions, not point samples: value is the session duration in minutes.
    private fun readSleepSessions(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, SleepSessionRecord::class, r).map {
            val durationMin = (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0
            HealthMetricReading(
                HealthMetricType.SLEEP_SESSION, durationMin, "min",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readExerciseSessions(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, ExerciseSessionRecord::class, r).map {
            val durationMin = (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0
            HealthMetricReading(
                HealthMetricType.EXERCISE_SESSION, durationMin, "min",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    // Sleep stages live inside SleepSessionRecord.stages and need only the existing READ_SLEEP
    // grant. One reading per stage, value = stage duration in minutes, typed per stage for clean
    // queryability (light/deep/REM/awake/etc.).
    private fun readSleepStages(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, SleepSessionRecord::class, r).flatMap { record ->
            record.stages.map { stage ->
                val durationMin = (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000.0
                HealthMetricReading(
                    sleepStageType(stage.stage), durationMin, "min",
                    stage.startTime.toEpochMilli(), stage.endTime.toEpochMilli(),
                    record.metadata.dataOrigin.packageName,
                    "${record.metadata.id}:${stage.startTime.toEpochMilli()}:${stage.stage}",
                )
            }
        }

    private fun sleepStageType(stage: Int): HealthMetricType = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> HealthMetricType.SLEEP_STAGE_AWAKE
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthMetricType.SLEEP_STAGE_AWAKE_IN_BED
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> HealthMetricType.SLEEP_STAGE_OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthMetricType.SLEEP_STAGE_LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> HealthMetricType.SLEEP_STAGE_DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> HealthMetricType.SLEEP_STAGE_REM
        else -> HealthMetricType.SLEEP_STAGE_UNKNOWN
    }

    private fun readHrv(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, HeartRateVariabilityRmssdRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.HEART_RATE_VARIABILITY, it.heartRateVariabilityMillis, "ms",
                it.time.toEpochMilli(), it.time.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    private fun readBodyTemperature(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, BodyTemperatureRecord::class, r).map {
            HealthMetricReading(
                HealthMetricType.BODY_TEMPERATURE, it.temperature.inCelsius, "celsius",
                it.time.toEpochMilli(), it.time.toEpochMilli(), it.metadata.dataOrigin.packageName,
                it.metadata.id,
            )
        }

    // SkinTemperatureRecord is an interval with a baseline + deltas; we emit the baseline (when
    // present) as a single scalar and skip records without one. The per-delta series is not modeled.
    private fun readSkinTemperature(c: HealthConnectClient, g: Set<String>, r: TimeRangeFilter): List<HealthMetricReading> =
        readRecords(c, g, SkinTemperatureRecord::class, r).mapNotNull { record ->
            val celsius = record.baseline?.inCelsius ?: return@mapNotNull null
            HealthMetricReading(
                HealthMetricType.SKIN_TEMPERATURE, celsius, "celsius",
                record.startTime.toEpochMilli(), record.endTime.toEpochMilli(),
                record.metadata.dataOrigin.packageName,
                record.metadata.id,
            )
        }
}
