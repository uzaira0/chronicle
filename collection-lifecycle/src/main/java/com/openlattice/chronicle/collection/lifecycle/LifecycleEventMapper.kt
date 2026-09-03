package com.openlattice.chronicle.collection.lifecycle

import android.content.Intent
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_LABEL
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_DISCHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_OKAY
import com.openlattice.chronicle.constants.INTERACTION_LOW_MEMORY
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Centralized device-lifecycle event mapping (design §1A.2 `device_lifecycle`, refactor
 * plan §8.1 steps 2–17, guardrail §8.1.2 "lifecycle event mapping must be centralized
 * in lifecycle module").
 *
 * Phase 5A moves the event-building logic behind the module boundary. This is the single
 * place a broadcast `Intent` action or a memory-trim level becomes an
 * [ExtractedUsageEvent]; `DeviceLifecycleEventRecorder` and `DeviceStateSampler` delegate
 * here rather than building events themselves, so there is exactly one mapping.
 *
 * This mapper owns only supplemental state that Android's UsageStats timeline does not
 * provide. Screen, keyguard, startup, and shutdown are intentionally excluded here: the
 * original Chronicle collector receives those events from `UsageStatsManager.queryEvents`,
 * in framework order, and a second broadcast producer would duplicate and reorder them.
 *
 *  - power connected → [INTERACTION_BATTERY_CHARGING]; power disconnected →
 *    [INTERACTION_BATTERY_DISCHARGING];
 *  - battery low → [INTERACTION_BATTERY_LOW]; battery okay → [INTERACTION_BATTERY_OKAY];
 *  - low memory → [INTERACTION_LOW_MEMORY] with `activityClass = "memory:trim-level:$level"`;
 *  - the `activityClass` of a broadcast event is the broadcast action string itself;
 *  - `appPackageName` is always [ANDROID_SYSTEM_PACKAGE] (`"android"`) and
 *    `applicationLabel` is always [ANDROID_SYSTEM_LABEL] (`"Android System"`) — lifecycle
 *    events never carry an arbitrary app package (guardrail §8.1.1);
 *  - `user` is always empty (no participant attribution on system-origin rows);
 *  - the timestamp is UTC (`ZoneOffset.UTC`) and `timezone` is the device default zone
 *    id at build time (`TimeZone.getDefault().id`).
 *
 * This object holds no state and no Android `Context` — it is pure mapping.
 *
 */
public object LifecycleEventMapper {

    /**
     * Maps a broadcast [action] to an [ExtractedUsageEvent], or `null` if [action] is not
     * a lifecycle action this module collects.
     *
     * Core UsageStats actions and unrecognised actions yield `null`; emitting them here
     * would violate the original single-timeline contract.
     */
    public fun eventForBroadcastAction(action: String?, timestampMillis: Long): ExtractedUsageEvent? =
        when (action) {
            Intent.ACTION_POWER_CONNECTED ->
                buildEvent(Intent.ACTION_POWER_CONNECTED, INTERACTION_BATTERY_CHARGING, timestampMillis)
            Intent.ACTION_POWER_DISCONNECTED ->
                buildEvent(Intent.ACTION_POWER_DISCONNECTED, INTERACTION_BATTERY_DISCHARGING, timestampMillis)
            Intent.ACTION_BATTERY_LOW ->
                buildEvent(Intent.ACTION_BATTERY_LOW, INTERACTION_BATTERY_LOW, timestampMillis)
            Intent.ACTION_BATTERY_OKAY ->
                buildEvent(Intent.ACTION_BATTERY_OKAY, INTERACTION_BATTERY_OKAY, timestampMillis)
            else -> null
        }

    /**
     * Builds a [INTERACTION_LOW_MEMORY] event for an `onTrimMemory` callback. The trim
     * [level] is recorded coarsely as `activityClass = "memory:trim-level:$level"` — no
     * raw memory contents, exactly as the pre-Phase-5 recorder.
     */
    public fun lowMemoryEvent(level: Int, timestampMillis: Long): ExtractedUsageEvent =
        buildEvent(
            activityClass = "memory:trim-level:$level",
            interactionType = INTERACTION_LOW_MEMORY,
            timestampMillis = timestampMillis,
        )

    /**
     * Builds a system-origin lifecycle [ExtractedUsageEvent].
     *
     * The package/label are always the Android system values, `user` is always empty,
     * the timestamp is UTC and `timezone` is the device-default zone id — identical to
     * the pre-Phase-5 `DeviceLifecycleEventRecorder.buildEvent`.
     */
    public fun buildEvent(
        activityClass: String,
        interactionType: String,
        timestampMillis: Long,
    ): ExtractedUsageEvent =
        ExtractedUsageEvent(
            appPackageName = ANDROID_SYSTEM_PACKAGE,
            interactionType = interactionType,
            // Materialize the numeric wire value instead of relying on a later label fallback.
            // Supplemental Chronicle events use their reserved values; UsageStats-owned events
            // never enter this mapper.
            eventType = fromInteractionType(interactionType),
            timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneOffset.UTC),
            timezone = TimeZone.getDefault().id,
            user = "",
            applicationLabel = ANDROID_SYSTEM_LABEL,
            activityClass = activityClass,
        )
}
