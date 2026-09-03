package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.NetworkUsageType
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "AppNetworkUsageCollectionModule"
private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `app_network_usage` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`, but each [sample] persists *N* rows (one per app/network
 * bucket the [AppNetworkUsageSource] returns for the window since the last poll).
 * BEHAVIORAL_METADATA-class (default OFF) — per-app byte counts only.
 */
public class AppNetworkUsageCollectionModule(
    private val sink: AppNetworkUsageSampleSink,
    private val source: AppNetworkUsageSource,
    private val enrolled: () -> Boolean,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.APP_NETWORK_USAGE
    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    private data class SampleState(
        val lastRunEpochMs: Long?,
        val lastResult: ModuleResult,
        val itemsCollected: Int,
        val lastError: String?,
    )

    @Volatile
    private var state: SampleState = SampleState(null, ModuleResult.Skipped("not yet run"), 0, null)

    @Synchronized
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

        val readings: List<AppNetworkUsageReading> = try {
            source.read()
        } catch (e: Exception) {
            log.error(TAG, "App-network-usage source threw while reading", e)
            return ModuleResult.Failed(e, redactedMessage = "app network usage source read failed: ${e.javaClass.simpleName}")
        }
        if (readings.isEmpty()) {
            return acknowledgeSourceRead(items = 0)
        }

        val tsString = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString()
        val tz = TimeZone.getDefault().id
        val entries = try {
            readings.map { r ->
                AppNetworkUsageSampleEntry(
                    id = stableAppNetworkUsageSampleId(r),
                    timestamp = tsString,
                    timezone = tz,
                    packageName = r.packageName,
                    networkType = r.networkType.name,
                    rxBytes = r.rxBytes,
                    txBytes = r.txBytes,
                    bucketStartMillis = r.bucketStartMillis,
                    bucketEndMillis = r.bucketEndMillis,
                )
            }
        } catch (e: Exception) {
            rejectSourceRead()
            log.error(TAG, "Failed to map app-network-usage readings", e)
            return ModuleResult.Failed(
                e,
                redactedMessage = "app network usage mapping failed: ${e.javaClass.simpleName}",
            )
        }

        return when (val writeResult = sink.write(entries)) {
            is ModuleResult.Ok -> {
                when (val acknowledged = acknowledgeSourceRead(entries.size)) {
                    is ModuleResult.Ok -> {
                        log.info(TAG, "Persisted ${entries.size} app-network-usage bucket(s)")
                        acknowledged
                    }
                    else -> acknowledged
                }
            }
            is ModuleResult.Failed -> {
                rejectSourceRead()
                log.error(TAG, "Failed to persist app-network-usage buckets", writeResult.error)
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
        log.error(TAG, "Failed to persist app-network-usage read checkpoint", e)
        rejectSourceRead()
        ModuleResult.Failed(
            e,
            redactedMessage = "app network usage checkpoint failed: ${e.javaClass.simpleName}",
        )
    }

    private fun rejectSourceRead() {
        runCatching { source.rejectRead() }
            .onFailure { log.error(TAG, "Failed to reject app-network-usage read window", it) }
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
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("app_network_usage is pull-style")
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("app_network_usage is pull-style")
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("app_network_usage buffers nothing")
}

/** Stable local/server dedup key for retries of the same app/network/time window. */
public fun stableAppNetworkUsageSampleId(reading: AppNetworkUsageReading): String {
    val identity = listOf(
        reading.packageName,
        reading.networkType.name,
        reading.bucketStartMillis.toString(),
        reading.bucketEndMillis.toString(),
    ).joinToString("\u001f")
    return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)).toString()
}

/**
 * Converts a stored [AppNetworkUsageSampleEntry] row into the [AndroidAppNetworkUsageEvent] wire
 * DTO. Throws on an unparseable timestamp or unknown network-type name; the upload path catches
 * this per row so one corrupt row never aborts a batch.
 */
public fun AppNetworkUsageSampleEntry.toAndroidAppNetworkUsageEvent(): AndroidAppNetworkUsageEvent =
    AndroidAppNetworkUsageEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        packageName = packageName,
        networkType = NetworkUsageType.valueOf(networkType),
        rxBytes = rxBytes,
        txBytes = txBytes,
        bucketStartMillis = bucketStartMillis,
        bucketEndMillis = bucketEndMillis,
    )
