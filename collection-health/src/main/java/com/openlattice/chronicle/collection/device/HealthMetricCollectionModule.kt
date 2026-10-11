package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullCollectionModule
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "HealthMetricCollectionModule"

/**
 * The `health_connect` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`, persisting *N* rows per [sample] (one per Health Connect
 * record the [HealthMetricSource] returns for the window since the last poll). HEALTH_METRICS-class,
 * opt-in. Source failures are reported without acknowledging the read window.
 */
public class HealthMetricCollectionModule(
    private val sink: HealthMetricSampleSink,
    private val source: HealthMetricSource,
    private val enrolled: () -> Boolean,
    clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : PullCollectionModule(CollectionModuleId.HEALTH_CONNECT, clock, sink::queueDepth) {

    override fun runSample(now: Long): ModuleResult {
        if (!enrolled()) return ModuleResult.Skipped("participant not enrolled")

        val observation = sink.captureAdmission()
        val readings: List<HealthMetricReading> = try {
            source.read()
        } catch (e: Exception) {
            log.error(TAG, "Health-metric source threw while reading", e)
            return ModuleResult.Failed(e, redactedMessage = "health metric source read failed: ${e.javaClass.simpleName}")
        }
        if (readings.isEmpty()) {
            val result = writeSourceObservation(observation) { ModuleResult.Ok(0) }
            if (result !is ModuleResult.Ok) rejectSourceRead()
            return if (result is ModuleResult.Ok) acknowledgeSourceRead(items = 0) else result
        }

        val tsString = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString()
        val tz = TimeZone.getDefault().id
        val entries = try {
            readings.map { r ->
                HealthMetricSampleEntry(
                    id = stableHealthMetricSampleId(r),
                    timestamp = tsString,
                    timezone = tz,
                    metricType = r.metricType.name,
                    value = r.value,
                    unit = r.unit,
                    startMillis = r.startMillis,
                    endMillis = r.endMillis,
                    sourcePackage = r.sourcePackage,
                )
            }
        } catch (e: Exception) {
            rejectSourceRead()
            log.error(TAG, "Failed to map Health Connect readings", e)
            return ModuleResult.Failed(e, redactedMessage = "health metric mapping failed: ${e.javaClass.simpleName}")
        }

        val result = writeSourceObservation(observation) { sink.write(entries) }
        return when (val writeResult = result) {
            is ModuleResult.Ok -> {
                log.info(TAG, "Persisted ${entries.size} health metric record(s)")
                // The admission guard has committed the outer queue transaction before this checkpoint.
                acknowledgeSourceRead(entries.size)
            }
            is ModuleResult.Failed -> {
                rejectSourceRead()
                log.error(TAG, "Failed to persist health metric records", writeResult.error)
                writeResult
            }
            else -> {
                rejectSourceRead()
                writeResult
            }
        }
    }

    private fun writeSourceObservation(
        observation: com.openlattice.chronicle.collection.state.CollectionPersistenceGuard,
        write: () -> ModuleResult,
    ): ModuleResult = try {
        observation.writeObservation(write = write)
    } catch (e: Exception) {
        log.error(TAG, "Health-metric storage admission failed", e)
        ModuleResult.Failed(e, redactedMessage = "health metric admission failed: ${e.javaClass.simpleName}")
    }

    private fun acknowledgeSourceRead(items: Int): ModuleResult = try {
        source.acknowledgeRead()
        ModuleResult.Ok(items)
    } catch (e: Exception) {
        log.error(TAG, "Failed to persist Health Connect read checkpoint", e)
        rejectSourceRead()
        ModuleResult.Failed(e, redactedMessage = "health metric checkpoint failed: ${e.javaClass.simpleName}")
    }

    private fun rejectSourceRead() {
        runCatching { source.rejectRead() }
            .onFailure { log.error(TAG, "Failed to reject Health Connect read window", it) }
    }

}

/** Stable local/server dedup key for retries of the same Health Connect record. */
public fun stableHealthMetricSampleId(reading: HealthMetricReading): String {
    val identity = listOf(
        reading.sourcePackage.orEmpty(),
        reading.sourceRecordId.orEmpty(),
        reading.metricType.name,
        reading.startMillis.toString(),
        reading.endMillis.toString(),
        reading.value.toBits().toString(),
        reading.unit,
    ).joinToString("\u001f")
    // nosemgrep: chronicle-uuid-from-string -- Source record identity must remain stable when Health Connect overlap windows reread the same record.
    return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)).toString()
}

/**
 * Converts a stored [HealthMetricSampleEntry] row into the [AndroidHealthMetricEvent] wire DTO.
 * Throws on an unparseable timestamp or unknown metric-type name; the upload path catches this per
 * row so one corrupt row never aborts a batch.
 */
public fun HealthMetricSampleEntry.toAndroidHealthMetricEvent(): AndroidHealthMetricEvent =
    AndroidHealthMetricEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        metricType = HealthMetricType.valueOf(metricType),
        value = value,
        unit = unit,
        startMillis = startMillis,
        endMillis = endMillis,
        sourcePackage = sourcePackage,
    )
