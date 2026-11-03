package com.openlattice.chronicle.collection.device

import android.util.Log
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullCollectionModule
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard

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
    enrolled: () -> Boolean,
    now: () -> Long = System::currentTimeMillis,
    /**
     * Holds one persistence read lease from the write through the read acknowledgement, so a stop cannot land
     * between them. The Health Connect read stays outside it: that read can wait on a main-thread binding, and
     * stops must not queue behind it (HealthConnectUploadLeaseRegressionTest).
     */
    private val persistLease: (() -> ModuleResult) -> ModuleResult,
) : PullCollectionModule(enrolled, now) {

    /**
     * The source holds one pending read window, so samples from the periodic collector and the upload worker run
     * one at a time. The monitor is outermost and is never taken under a lease or a stop: order is this monitor, then
     * the source read (its own monitor, no lease), then [persistLease]; a rejection runs after the lease is released.
     */
    override fun sample(): ModuleResult = synchronized(this) { super.sample() }

    override fun runSample(now: Long): ModuleResult {

        val observation = sink.captureAdmission()
        val readings: List<HealthMetricReading> = try {
            source.read()
        } catch (e: Exception) {
            Log.e(TAG, "Health-metric source threw while reading", e)
            return ModuleResult.Failed(e, redactedMessage = "health metric source read failed: ${e.javaClass.simpleName}")
        }
        if (readings.isEmpty()) return commit(observation) { ModuleResult.Ok(0) }

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
            Log.e(TAG, "Failed to map Health Connect readings", e)
            return ModuleResult.Failed(e, redactedMessage = "health metric mapping failed: ${e.javaClass.simpleName}")
        }

        val result = commit(observation) { sink.write(entries) }
        if (result is ModuleResult.Ok) Log.i(TAG, "Persisted ${entries.size} health metric record(s)")
        else if (result is ModuleResult.Failed) Log.e(TAG, "Failed to persist health metric records", result.error)
        return result
    }

    /**
     * Writes and then acknowledges the read inside one [persistLease]; the admission guard has committed the
     * queue transaction before the checkpoint. A refused or failed step rejects the window after the lease.
     */
    private fun commit(observation: CollectionPersistenceGuard, write: () -> ModuleResult): ModuleResult {
        val result = try {
            persistLease {
                when (val written = observation.writeObservation(write = write)) {
                    is ModuleResult.Ok -> acknowledgeSourceRead(written.items)
                    else -> written
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health-metric persistence lease failed", e)
            ModuleResult.Failed(e, redactedMessage = "health metric lease failed: ${e.javaClass.simpleName}")
        }
        if (result !is ModuleResult.Ok) rejectSourceRead()
        return result
    }

    private fun acknowledgeSourceRead(items: Int): ModuleResult = try {
        source.acknowledgeRead()
        ModuleResult.Ok(items)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to persist Health Connect read checkpoint", e)
        ModuleResult.Failed(e, redactedMessage = "health metric checkpoint failed: ${e.javaClass.simpleName}")
    }

    private fun rejectSourceRead() {
        runCatching { source.rejectRead() }
            .onFailure { Log.e(TAG, "Failed to reject Health Connect read window", it) }
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
