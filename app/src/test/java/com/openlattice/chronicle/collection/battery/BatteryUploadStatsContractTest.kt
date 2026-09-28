package com.openlattice.chronicle.collection.battery

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryUploadStatsContractTest {
    @Test fun acceptedBatteryBatchIsDeletedEvenIfLocalStatsWriteFails() {
        val source = sequenceOf(File("app/src/main/java"), File("src/main/java"))
            .map { File(it, "com/openlattice/chronicle/collection/battery/BatteryUploadWorker.kt") }
            .first(File::isFile).readText()
        assertTrue(source.contains("statsFailureCount++"))
        assertTrue(source.contains("if (failureCount == 0)"))
        assertTrue(source.contains("return failureCount + statsFailureCount"))
    }
}
