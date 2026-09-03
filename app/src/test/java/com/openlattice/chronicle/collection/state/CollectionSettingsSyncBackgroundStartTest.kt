package com.openlattice.chronicle.collection.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CollectionSettingsSyncBackgroundStartTest {

    @Test
    fun sensorServiceStartDenialDoesNotThrowThroughCollectionSettingsSync() {
        val serviceSource = File(
            "src/googleServices/java/com/openlattice/chronicle/services/sensors/HardwareSensorService.kt"
        ).readText()
        val coordinatorSource = File(
            "src/main/java/com/openlattice/chronicle/collection/state/CollectionLoopCoordinator.kt"
        ).readText()

        assertTrue(serviceSource.contains("fun tryStartService(context: Context): Boolean"))
        assertTrue(serviceSource.contains("catch (e: IllegalStateException)"))
        assertTrue(coordinatorSource.contains("DistributionRestrictedRuntime.tryStartHardwareSensors(appContext)"))
        assertTrue(coordinatorSource.contains("collection settings sync remains applied"))
    }
}
