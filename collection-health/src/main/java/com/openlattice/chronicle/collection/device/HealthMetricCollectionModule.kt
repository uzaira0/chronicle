package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "HealthMetricCollectionModule"
private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `health_connect` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`, persisting *N* rows per [sample] (one per Health Connect
 * record the [HealthMetricSource] returns for the window since the last poll). HEALTH_METRICS-class,
 * opt-in. A no-op when Health Connect is absent or no read permission is granted.
 */
public class HealthMetricCollectionModule(
    private val sink: HealthMetricSampleSink,
    private val source: HealthMetricSource,
    private val enrolled: () -> Boolean,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.HEALTH_CONNECT
    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    private data class SampleState(
        val lastRunEpochMs: Long?,
        val lastResult: ModuleResult,
        val itemsCollected: Int,
        val lastError: String?,
    )

    @Volatile
    private var state: SampleState = SampleState(null, ModuleResult.Skipped("not yet run"), 0, null)

    public fun sample(): ModuleResult {
        val now = clock.nowEpochMs()
        val result = runSample(now)
        state = SampleState(
            lastRunEpochMs = now,
            lastResult = result,
            itemsCollected = if (result is ModuleResult.Ok) result.items else 0,
            lastError = if (result is ModuleResult.Failed) result.redactedMessage else null,
        )
        return result
    }

    private fun runSample(now: Long): ModuleResult {
        if (!enrolled()) return ModuleResult.Skipped("participant not enrolled")

        val readings: List<HealthMetricReading> = try {
            source.read()
        } catch (e: Exception) {
            log.error(TAG, "Health-metric source threw while reading", e)
            return ModuleResult.Failed(e, redactedMessage = "health metric source read failed: ${e.javaClass.simpleName}")
        }
        if (readings.isEmpty()) {
            return acknowledgeSourceRead(items = 0)
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

        return when (val writeResult = sink.write(entries)) {
            is ModuleResult.Ok -> {
                when (val acknowledged = acknowledgeSourceRead(entries.size)) {
                    is ModuleResult.Ok -> {
                        log.info(TAG, "Persisted ${entries.size} health metric record(s)")
                        acknowledged
                    }
                    else -> acknowledged
                }
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

    override fun status(): CollectionModuleStatus = when (state.lastResult) {
        is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        is ModuleResult.Ok -> CollectionModuleStatus.IDLE
        is ModuleResult.Retry -> CollectionModuleStatus.DEGRADED
        is ModuleResult.Skipped -> CollectionModuleStatus.IDLE
    }

    override fun diagnostics(): CollectionModuleDiagnostics {
        val snapshot = state
        return CollectionModuleDiagnostics(
            moduleId = id,
            privacyClass = privacyClass,
            lastRunEpochMs = snapshot.lastRunEpochMs,
            lastResult = snapshot.lastResult.label,
            itemsCollected = snapshot.itemsCollected,
            queueDepth = runCatching { sink.queueDepth() }.getOrDefault(QUEUE_DEPTH_UNAVAILABLE),
            lastError = snapshot.lastError,
            redactedParticipantRef = null,
        )
    }

    override fun poll(context: Context, window: CollectionWindow): ModuleResult = sample()
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("health_connect is pull-style")
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("health_connect is pull-style")
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("health_connect buffers nothing")
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
