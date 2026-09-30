package com.openlattice.chronicle.collection.directboot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootDrainResumeContractTest {
    @Test fun unlockHandoverTransfersRefusedAcceptedRamToReplacementController() {
        val scheduler = com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler(false)
        val old = com.openlattice.chronicle.collection.sensors.SensorRuntimeController(
            com.openlattice.chronicle.collection.sensors.FakeSensorGateway(),
            com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings(),
            com.openlattice.chronicle.collection.sink.SensorSampleWriter {
                com.openlattice.chronicle.collection.core.ModuleResult.Retry("temporary refusal")
            }, scheduler, log = com.openlattice.chronicle.collection.core.NoOpCollectionLog)
        old.recordSample(com.openlattice.chronicle.android.AndroidSensorType.accelerometer, floatArrayOf(1f), 3)
        old.stop(isServiceDestroy = true, retainedOrigin = { com.openlattice.chronicle.collection.state.CollectionPersistenceGuard.ALLOW })
        org.junit.Assert.assertEquals(0, old.bufferedCount)
        val replacement = com.openlattice.chronicle.collection.sensors.SensorRuntimeController(
            com.openlattice.chronicle.collection.sensors.FakeSensorGateway(),
            com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings(),
            com.openlattice.chronicle.collection.sink.SensorSampleWriter {
                com.openlattice.chronicle.collection.core.ModuleResult.Ok(it.size)
            }, com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler(false),
            retainOnStop = true, log = com.openlattice.chronicle.collection.core.NoOpCollectionLog)
        org.junit.Assert.assertEquals(1, replacement.bufferedCount)
        org.junit.Assert.assertEquals(com.openlattice.chronicle.collection.core.ModuleResult.Ok(1), replacement.flushBuffer())
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
