package com.openlattice.chronicle.collection.usage

import android.content.Context
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import java.util.NavigableMap

/**
 * The poll-step seam for the usage events module (Phase 4, subphase 4A).
 *
 * The production usage collector — [UsageEventsChronicleSensor] — constructs a
 * [android.app.usage.UsageStatsManager] and an [com.openlattice.chronicle.preferences.EncryptedPrefsHelper]
 * from an Android [Context]; neither is stubbable in a no-Robolectric JVM unit test.
 * [UsageEventPoller] is therefore the seam between [UsageEventsCollectionModule] and the
 * real `UsageStatsManager` query: the module depends on this interface, production wires
 * in [SystemUsageEventPoller] (which delegates verbatim to [UsageEventsChronicleSensor]),
 * and unit tests inject a fake.
 *
 * The seam preserves the existing two-timestamp poll contract **exactly**: the same
 * `poll(previousPollTimestamp, currentPollTimestamp, users)` signature
 * [UsageEventsChronicleSensor] already exposes, querying the half-open
 * `[previous, current)` window. No behaviour changes — this is the additive 4A wrapper.
 *
 */
public interface UsageEventPoller {

    /**
     * Polls `UsageStatsManager` for the half-open window `[previousPollTimestamp, currentPollTimestamp)`
     * and maps each Android event into an [com.openlattice.chronicle.models.ExtractedUsageEvent].
     *
     * Preserves verbatim: activity-class mapping (event `className` → `activityClass`),
     * event-type mapping, timezone mapping, app-label lookup, and `users` resolution.
     */
    public fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>,
    ): ChronicleData
}

/**
 * Production [UsageEventPoller] backed by [UsageEventsChronicleSensor].
 *
 * Holds no [Context] itself; it constructs the sensor lazily per [poll] call so the
 * "no Context in singleton fields" rule (design §1C, refactor plan §6.1 guardrail 2)
 * is honoured — the [Context] is passed in, never retained. This is exactly what the
 * legacy [com.openlattice.chronicle.services.usage.UsageCollectionDelegate] does today:
 * it constructs a fresh `UsageEventsChronicleSensor(context)` for each `execute()`.
 */
public class SystemUsageEventPoller(private val context: Context) : UsageEventPoller {

    override fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>,
    ): ChronicleData =
        UsageEventsChronicleSensor(context).poll(previousPollTimestamp, currentPollTimestamp, users)
}
