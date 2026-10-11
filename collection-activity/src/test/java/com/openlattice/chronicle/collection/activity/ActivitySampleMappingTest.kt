package com.openlattice.chronicle.collection.activity

import com.openlattice.chronicle.collection.DetectedActivityType
import com.openlattice.chronicle.collection.SleepSegmentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure Play Services int -> enum mappers. The GMS constant values are inlined
 * here (they are stable platform contract) so the test runs without the GMS classes on the classpath.
 */
class ActivitySampleMappingTest {

    @Test fun testDetectedActivityTypeMapping() {
        assertEquals(DetectedActivityType.IN_VEHICLE, detectedActivityTypeFor(0))
        assertEquals(DetectedActivityType.ON_BICYCLE, detectedActivityTypeFor(1))
        assertEquals(DetectedActivityType.ON_FOOT, detectedActivityTypeFor(2))
        assertEquals(DetectedActivityType.STILL, detectedActivityTypeFor(3))
        assertEquals(DetectedActivityType.TILTING, detectedActivityTypeFor(5))
        assertEquals(DetectedActivityType.WALKING, detectedActivityTypeFor(7))
        assertEquals(DetectedActivityType.RUNNING, detectedActivityTypeFor(8))
    }

    @Test fun testDetectedActivityUnknownFallback() {
        // GMS UNKNOWN (4), the obsolete 6, and any out-of-range value all collapse to UNKNOWN.
        assertEquals(DetectedActivityType.UNKNOWN, detectedActivityTypeFor(4))
        assertEquals(DetectedActivityType.UNKNOWN, detectedActivityTypeFor(6))
        assertEquals(DetectedActivityType.UNKNOWN, detectedActivityTypeFor(99))
        assertEquals(DetectedActivityType.UNKNOWN, detectedActivityTypeFor(-1))
    }

    @Test fun testSleepSegmentStatusMapping() {
        assertEquals(SleepSegmentStatus.SUCCESSFUL, sleepSegmentStatusFor(0))
        assertEquals(SleepSegmentStatus.MISSING_DATA, sleepSegmentStatusFor(1))
        assertEquals(SleepSegmentStatus.NOT_DETECTED, sleepSegmentStatusFor(2))
    }

    @Test fun testSleepSegmentStatusUnknownFallback() {
        assertEquals(SleepSegmentStatus.UNKNOWN, sleepSegmentStatusFor(3))
        assertEquals(SleepSegmentStatus.UNKNOWN, sleepSegmentStatusFor(-1))
    }

    @Test fun pushModulesShareRedactedQueueDiagnostics() {
        for (create in listOf<(() -> Int) -> com.openlattice.chronicle.collection.core.DataCollectionModule>(
            { ActivityRecognitionCollectionModule(it) }, { SleepCollectionModule(it) },
        )) {
            val module = create { 3 }
            val diagnostics = module.diagnostics()
            assertEquals(module.id, diagnostics.moduleId)
            assertEquals(module.id.privacyClass, diagnostics.privacyClass)
            assertEquals(3, diagnostics.queueDepth)
            assertEquals("SKIPPED", diagnostics.lastResult)
            assertEquals(-1, create { throw java.io.IOException("queue unavailable") }.diagnostics().queueDepth)
        }
    }

}
