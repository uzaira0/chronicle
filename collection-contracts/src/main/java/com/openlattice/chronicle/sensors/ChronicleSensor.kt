package com.openlattice.chronicle.sensors

import com.openlattice.chronicle.android.ChronicleData
import java.util.NavigableMap

/**
 * A pull-style Chronicle data source: [poll] returns the [ChronicleData] collected for a
 * given poll window, attributing rows to users via the supplied timestamp map.
 *
 * The interface is R/BuildConfig-free and lives in `:collection-contracts` (moved from
 * `:collection-base` in the tranche 7 storage/contract split) so collection library
 * modules (e.g. `:collection-usage`) can implement it without a `:app` dependency.
 * The legacy olingo `FullQualifiedName` property-type constants that used to
 * share this file remain in `:app` (`sensors/ChronicleSensorPropertyTypes.kt`).
 */
interface ChronicleSensor {
    fun poll(currentPollTimestamp: Long, users: NavigableMap<Long, String>): ChronicleData
}
