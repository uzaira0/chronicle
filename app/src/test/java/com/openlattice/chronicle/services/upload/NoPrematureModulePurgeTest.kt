package com.openlattice.chronicle.services.upload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** Every upload worker may delete only rows whose exact payload was acknowledged. */
class NoPrematureModulePurgeTest {
    @Test fun oldUndeliveredRowsHaveNoWorkerAgePurge() {
        listOf(
            "src/main/java/com/openlattice/chronicle/collection/battery/BatteryUploadWorker.kt",
            "src/main/java/com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt",
            "src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionUploadWorker.kt",
            "src/googleServices/java/com/openlattice/chronicle/collection/audio/AudioUploadWorker.kt",
            "src/googleServices/java/com/openlattice/chronicle/collection/DistributionCollectionContributions.kt",
        ).forEach { path ->
            val source = File(path).readText()
            assertFalse("$path still purges old undelivered rows", source.contains(".deleteOlderThan(cutoff)"))
        }
        val usage = File("src/main/java/com/openlattice/chronicle/services/upload/UploadWorkerDelegate.kt").readText()
        assertFalse(usage.contains("queue.deleteOldest("))
        val stats = File("src/main/java/com/openlattice/chronicle/services/upload/CombinedUploadWorker.kt").readText()
        assertFalse(stats.contains("uploadStatsDao().deleteOlderThan"))
    }
}
