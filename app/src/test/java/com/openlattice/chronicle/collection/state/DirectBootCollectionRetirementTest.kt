package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.directboot.InMemorySharedPreferences
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot is refreshed only while `HardwareSensorService` runs. When the last sensor gate
 * closes, `CollectionLoopCoordinator.updateSensorService` stops that service — so unless the
 * snapshot is retired first, `LockedBootReceiver` still finds an accepted, in-date snapshot and
 * resumes pre-unlock collection of the sensors the participant just declined.
 */
class DirectBootCollectionRetirementTest {

    @Test
    fun retiringTheSnapshotStopsLockedBootCollection() {
        val snapshot = DirectBootSensorSnapshot(InMemorySharedPreferences())
        assertTrue(
            snapshot.write(
                mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()),
            ),
        )
        assertTrue(
            "precondition: a locked boot would collect",
            snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS),
        )

        assertTrue(clearDirectBootCollection(snapshot))

        assertFalse(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
        assertTrue(snapshot.collectableSensors().isEmpty())
    }
}
