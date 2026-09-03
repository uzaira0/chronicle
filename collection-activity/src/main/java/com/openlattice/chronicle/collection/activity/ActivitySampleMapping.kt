package com.openlattice.chronicle.collection.activity

import com.openlattice.chronicle.collection.DetectedActivityType
import com.openlattice.chronicle.collection.SleepSegmentStatus

/**
 * Pure mappers from Google Play Services integer constants to the wire enums in chronicle-models.
 *
 * These are intentionally free of any `com.google.android.gms` import so they unit-test on the plain
 * JVM (the GMS constant values are stable platform contract and are asserted inline in the test). The
 * capture controller passes the raw `DetectedActivity.getType()` / `SleepSegmentEvent.getStatus()`
 * ints through these.
 */

// Google Play Services DetectedActivity type constants (stable API contract).
private const val GMS_IN_VEHICLE = 0
private const val GMS_ON_BICYCLE = 1
private const val GMS_ON_FOOT = 2
private const val GMS_STILL = 3
private const val GMS_TILTING = 5
private const val GMS_WALKING = 7
private const val GMS_RUNNING = 8

// Google Play Services SleepSegmentEvent status constants.
private const val GMS_SLEEP_STATUS_SUCCESSFUL = 0
private const val GMS_SLEEP_STATUS_MISSING_DATA = 1
private const val GMS_SLEEP_STATUS_NOT_DETECTED = 2

/** Maps a `DetectedActivity.getType()` value to a [DetectedActivityType]; unknown → [DetectedActivityType.UNKNOWN]. */
public fun detectedActivityTypeFor(gmsType: Int): DetectedActivityType = when (gmsType) {
    GMS_IN_VEHICLE -> DetectedActivityType.IN_VEHICLE
    GMS_ON_BICYCLE -> DetectedActivityType.ON_BICYCLE
    GMS_ON_FOOT -> DetectedActivityType.ON_FOOT
    GMS_STILL -> DetectedActivityType.STILL
    GMS_TILTING -> DetectedActivityType.TILTING
    GMS_WALKING -> DetectedActivityType.WALKING
    GMS_RUNNING -> DetectedActivityType.RUNNING
    else -> DetectedActivityType.UNKNOWN
}

/** Maps a `SleepSegmentEvent.getStatus()` value to a [SleepSegmentStatus]; unknown → [SleepSegmentStatus.UNKNOWN]. */
public fun sleepSegmentStatusFor(gmsStatus: Int): SleepSegmentStatus = when (gmsStatus) {
    GMS_SLEEP_STATUS_SUCCESSFUL -> SleepSegmentStatus.SUCCESSFUL
    GMS_SLEEP_STATUS_MISSING_DATA -> SleepSegmentStatus.MISSING_DATA
    GMS_SLEEP_STATUS_NOT_DETECTED -> SleepSegmentStatus.NOT_DETECTED
    else -> SleepSegmentStatus.UNKNOWN
}
