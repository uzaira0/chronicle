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
 * The Health Connect client API suspends; [awaitHealthConnect] bounds each request while the
 * collection worker calls this off the main thread.
 */
public class AndroidHealthMetricSource(context: Context) : HealthMetricSource {

    public companion object {
        private val sources = java.util.WeakHashMap<AndroidHealthMetricSource, Unit>()
        public fun clearCheckpoint(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val live = synchronized(sources) { sources.keys.filter { it.appContext == context.applicationContext } }
            live.forEach { it.readCoordinator.reject() }
            check(prefs.edit().clear().commit()) { "Health Connect checkpoint erasure failed" }
        }
    }

    private val appContext = context.applicationContext
    private fun scope(): Pair<String, Long>? = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
        .observationScope(appContext, com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT)
    private var readScope: Pair<String, Long>? = null
    private val readCoordinator = HealthMetricReadCoordinator(
        object : HealthMetricCheckpoint {
            private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

                override fun read(): Long? {
                readScope = scope()
                val current = readScope ?: return null
                return com.openlattice.chronicle.collection.state.ResearchPersistenceGate.withReadLease {
                    if (current != scope()) return@withReadLease null
                    val initial = current.second == 0L && current.first.substringAfterLast(':').toLongOrNull() in 0L..1L
                    if (initial && prefs.contains(KEY_LAST_END) && !prefs.contains("consent_scope")) {
                        check(prefs.edit().putString("consent_scope", current.first).commit()) {
                            "Unable to adopt Health Connect checkpoint"
                        }
                    }
                    prefs.getLong(KEY_LAST_END, 0L).takeIf { prefs.getString("consent_scope", null) == current.first }
                }
            }

            override fun write(endMillis: Long) {
                check(prefs.edit().putLong(KEY_LAST_END, endMillis).putString("consent_scope", readScope?.first).commit()) {
                    "Unable to persist Health Connect read checkpoint"
                }
            }
        },
        consentScope = ::scope,
    )

    init { synchronized(sources) { sources[this] = Unit } }

    private var clientProvider: () -> HealthConnectClient? = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            HealthConnectClient.getSdkStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) null
        else runCatching { HealthConnectClient.getOrCreate(appContext) }
            .onFailure { Log.w(TAG, "Health Connect client creation failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    override fun read(): List<HealthMetricReading> = readAdmitted()

    private fun readAdmitted(): List<HealthMetricReading> {
        readScope = null
        val configuredRecordTypes = runCatching { HealthConnectScopeStore.of(appContext).read() }
            .onFailure { Log.e(TAG, "Health Connect scope is unavailable; reading nothing", it) }
            .getOrDefault(emptySet())
        if (configuredRecordTypes.isEmpty()) return emptyList()
        val client = clientProvider() ?: return emptyList()

        val now = System.currentTimeMillis()
        val granted: Set<String> = runCatching {
            grantedHealthConnectPermissions(client)
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
            out.filter { it.startMillis >= (readScope?.second ?: Long.MIN_VALUE) }
        }
    }

    override fun acknowledgeRead() {
        // An early-return read (no scope, client or grant) never reached the checkpoint, so there
        // is nothing to acknowledge; guarding a null scope would refuse, fail the module and back
        // off the whole worker. A window retired by erasure still has its scope and is refused.
        if (readScope == null) return
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.withReadLease {
            val module = com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT
            val origin = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
                .guardForRetainedRegistration(appContext, module, readScope?.first)
            if (!origin.persist { readCoordinator.acknowledge() }) {
                readCoordinator.reject()
                error("Health Connect checkpoint persistence was refused")
            }
        }
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
            awaitHealthConnect {
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
