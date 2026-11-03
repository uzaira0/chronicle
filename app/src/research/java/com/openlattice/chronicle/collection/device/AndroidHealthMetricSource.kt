package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.core.ObservationScope
import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import java.time.Instant
import java.util.WeakHashMap
import kotlin.reflect.KClass

private const val TAG = "HealthMetricSource"
private const val KEY_LAST_END = "last_end_millis"
internal fun healthCheckpointEndKey(type: HealthConnectRecordType): String = "$KEY_LAST_END:${type.id}"
internal fun healthCheckpointScopeKey(type: HealthConnectRecordType): String = "consent_scope:${type.id}"
internal fun healthCheckpointSeenKey(type: HealthConnectRecordType): String = "seen_records:${type.id}"

/**
 * Production [HealthMetricSource] over the system Health Connect store. Reads only the record types
 * the participant has granted, only over the window since the last successful read (a
 * SharedPreferences checkpoint). Unavailable or denied reads skip without advancing the checkpoint.
 * Read-only — Chronicle never writes health data back.
 *
 * The Health Connect client API suspends; [awaitHealthConnect] bounds each request while the
 * collection worker calls this off the main thread.
 */
public class AndroidHealthMetricSource(context: Context,
    private val clientProvider: (Context) -> HealthConnectClient? = HealthConnectPermissions::clientIfAvailable,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val isForeground: () -> Boolean = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
) : HealthMetricSource {

    public companion object {
        private val sources = WeakHashMap<AndroidHealthMetricSource, Unit>()
        public fun clearCheckpoint(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(HEALTH_CONNECT_PREFS, Context.MODE_PRIVATE)
            val live = synchronized(sources) { sources.keys.filter { it.appContext == context.applicationContext } }
            // Erasure holds the gate writer: never wait for a source monitor whose read may need the gate.
            live.forEach { source ->
                source.readCoordinators.values.forEach { it.reject() }
                source.readScope = null
            }
            checkLocalStoreWrite(prefs.edit().clear().commit()) { "Health Connect checkpoint erasure failed" }
        }
    }

    private val appContext = context.applicationContext
    private fun scope(): ObservationScope? = ResearchPersistenceGate
        .observationScope(appContext, CollectionModuleId.HEALTH_CONNECT)
    @Volatile private var readScope: ObservationScope? = null
    private var incompletePermissions = false
    private val pendingSeen = mutableMapOf<HealthConnectRecordType, Set<String>>()
    private val completedTypes = mutableSetOf<HealthConnectRecordType>()
    private val checkpointPrefs = appContext.getSharedPreferences(HEALTH_CONNECT_PREFS, Context.MODE_PRIVATE)
    private val readCoordinators = HealthConnectRecordType.entries.associateWith { type ->
        HealthMetricReadCoordinator(object : HealthMetricCheckpoint {
            override fun read(): Long? {
                val current = scope() ?: return null
                return checkpointPrefs.getLong(healthCheckpointEndKey(type), 0L).takeIf {
                    checkpointPrefs.contains(healthCheckpointEndKey(type)) &&
                        checkpointPrefs.getString(healthCheckpointScopeKey(type), null) == current.key
                }
            }
            override fun write(endMillis: Long) {
                checkLocalStoreWrite(checkpointPrefs.edit().putLong(healthCheckpointEndKey(type), endMillis)
                    .putString(healthCheckpointScopeKey(type), readScope?.key)
                    .putStringSet(healthCheckpointSeenKey(type), pendingSeen[type].orEmpty()).commit()) {
                    "Unable to persist Health Connect read checkpoint"
                }
            }
        }, consentScope = ::scope)
    }

    init { synchronized(sources) { sources[this] = Unit } }

    @Synchronized
    override fun read(): List<HealthMetricReading> {
        check(readScope == null) { "Previous Health Connect read has not been acknowledged" }
        pendingSeen.clear()
        completedTypes.clear()
        return try {
            readAdmitted().also { if (readScope == null) pendingSeen.clear() }
        } catch (error: Exception) {
            rejectRead()
            throw error
        }
    }

    private fun readAdmitted(): List<HealthMetricReading> {
        incompletePermissions = false
        val configuredRecordTypes = runCatching { HealthConnectScopeStore.of(appContext).read() }
            .onFailure { Log.e(TAG, "Health Connect scope is unavailable; reading nothing", it) }
            .getOrDefault(emptySet())
        val client = configuredRecordTypes.takeIf { it.isNotEmpty() }?.let { clientProvider(appContext) } ?: return emptyList()

        val now = nowMillis()
        val granted = readableGrants(client) ?: return emptyList()

        readScope = scope() ?: return emptyList()
        return configuredRecordTypes.flatMap { type ->
            incompletePermissions = false
            val coordinator = readCoordinators.getValue(type)
            val records = coordinator.read(now) { start, end ->
                val range = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
                val out = when (type) {
                    HealthConnectRecordType.STEPS -> readSteps(client, granted, range)
                    HealthConnectRecordType.DISTANCE -> readDistance(client, granted, range)
                    HealthConnectRecordType.HEART_RATE -> readHeartRate(client, granted, range)
                    HealthConnectRecordType.TOTAL_CALORIES_BURNED -> readTotalCalories(client, granted, range)
                    HealthConnectRecordType.ACTIVE_CALORIES_BURNED -> readActiveCalories(client, granted, range)
                    HealthConnectRecordType.FLOORS_CLIMBED -> readFloors(client, granted, range)
                    HealthConnectRecordType.RESTING_HEART_RATE -> readRestingHeartRate(client, granted, range)
                    HealthConnectRecordType.OXYGEN_SATURATION -> readOxygenSaturation(client, granted, range)
                    HealthConnectRecordType.RESPIRATORY_RATE -> readRespiratoryRate(client, granted, range)
                    HealthConnectRecordType.SLEEP -> readSleepSessions(client, granted, range) + readSleepStages(client, granted, range)
                    HealthConnectRecordType.EXERCISE -> readExerciseSessions(client, granted, range)
                    HealthConnectRecordType.HEART_RATE_VARIABILITY -> readHrv(client, granted, range)
                    HealthConnectRecordType.BODY_TEMPERATURE -> readBodyTemperature(client, granted, range)
                    HealthConnectRecordType.SKIN_TEMPERATURE -> readSkinTemperature(client, granted, range)
                }
                val seen = if (checkpointPrefs.getString(healthCheckpointScopeKey(type), null) == readScope?.key)
                    checkpointPrefs.getStringSet(healthCheckpointSeenKey(type), emptySet()).orEmpty()
                        .filterTo(linkedSetOf()) { it.substringBefore(':').toLongOrNull()?.let { time -> time >= start } == true }
                    else emptySet()
                val admitted = out.filter { readScope?.admits(it.startMillis) != false }
                fun key(reading: HealthMetricReading) = "${reading.startMillis}:${stableHealthMetricSampleId(reading)}"
                pendingSeen[type] = seen + admitted.map(::key)
                admitted.filter { key(it) !in seen }.distinctBy(::key)
            }
            if (incompletePermissions) coordinator.reject() else completedTypes.add(type)
            records
        }
    }

    /** Grants usable now, or null when none are granted or the app is in the background without background read. */
    private fun readableGrants(client: HealthConnectClient): Set<String>? {
        val granted: Set<String> = runCatching {
            grantedHealthConnectPermissions(client)
        }.onFailure { Log.w(TAG, "Health Connect permission query failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptySet())
        if (granted.isEmpty()) return null
        val foreground = isForeground()
        return granted.takeIf { foreground || HealthConnectPermissions.canReadInBackground(client, granted) }
    }

    @Synchronized
    override fun acknowledgeRead() {
        // An early-return read with no scope never reached the checkpoint, so there
        // is nothing to acknowledge; guarding a null scope would refuse, fail the module and back
        // off the whole worker. A window retired by erasure still has its scope and is refused.
        if (readScope == null) return
        try { ResearchPersistenceGate.withReadLease {
            val module = CollectionModuleId.HEALTH_CONNECT
            val origin = ResearchPersistenceGate
                .guardForRetainedRegistration(appContext, module, readScope?.key)
            if (!origin.persist {
                    completedTypes.forEach { readCoordinators.getValue(it).acknowledge() }
                }) {
                readCoordinators.values.forEach { it.reject() }
                error("Health Connect checkpoint persistence was refused")
            }
        } } finally { readScope = null; pendingSeen.clear(); completedTypes.clear() }
    }

    @Synchronized
    override fun rejectRead() {
        readCoordinators.values.forEach { it.reject() }
        readScope = null
        pendingSeen.clear()
        completedTypes.clear()
    }

    private fun <T : Record> readRecords(
        client: HealthConnectClient,
        granted: Set<String>,
        type: KClass<T>,
        range: TimeRangeFilter,
    ): List<T> {
        if (!granted.contains(HealthPermission.getReadPermission(type))) {
            incompletePermissions = true
            return emptyList()
        }
        if (type == SkinTemperatureRecord::class && !HealthConnectPermissions.isFeatureAvailable(
                client, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) {
            incompletePermissions = true
            return emptyList()
        }
        return try {
            awaitHealthConnect {
                readAllHealthMetricPages { pageToken ->
                    val response = client.readRecords(
                        ReadRecordsRequest(type, timeRangeFilter = range, pageToken = pageToken),
                    )
                    HealthMetricPage(response.records, response.pageToken)
                }
            }
        } catch (e: SecurityException) {
            // Access lost mid-read (grant revoked, app left the foreground): skip without advancing.
            // https://developer.android.com/health-and-fitness/health-connect/read-data
            Log.w(TAG, "readRecords(${type.simpleName}) refused: access lost", e)
            incompletePermissions = true
            emptyList()
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
