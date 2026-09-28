package com.openlattice.chronicle.collection.directboot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootDrainResumeContractTest {
    @Test fun unlockHandoverCountsSamplesAbandonedByOldController() {
        val source = sequenceOf(File("app/src/googleServices/java"), File("src/googleServices/java"))
            .map { File(it, "com/openlattice/chronicle/services/sensors/HardwareSensorService.kt") }
            .first(File::isFile).readText()
        val handover = source.substringAfter("private fun onUserUnlocked()")
            .substringBefore("private fun buildController(")
        assertTrue(handover.contains("controller.stop(isServiceDestroy = true)"))
    }

    @Test fun activeServiceReconcileRequeuesHeldDirectBootSamples() {
        val source = sequenceOf(File("app/src/googleServices/java"), File("src/googleServices/java"))
            .map { File(it, "com/openlattice/chronicle/services/sensors/HardwareSensorService.kt") }
            .first(File::isFile).readText()
        val reconcile = source.indexOf("controller.reconcile()", source.indexOf("override fun onStartCommand"))
        val drain = source.indexOf("DirectBootDrainWorker.enqueue(applicationContext)", reconcile)
        val branchEnd = source.indexOf("return START_STICKY", reconcile)
        assertTrue(reconcile >= 0 && drain in (reconcile + 1) until branchEnd)
    }
}
