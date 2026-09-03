package com.openlattice.chronicle.collection.core

import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisabledCollectionModuleTest {

    private val module = DisabledCollectionModule(CollectionModuleId.HARDWARE_SENSORS, reason = "not opted in")

    @Test
    fun statusIsAlwaysDisabled() {
        assertEquals(CollectionModuleStatus.DISABLED, module.status())
    }

    @Test
    fun everyOperationReturnsSkippedAndNeverThrows() {
        // poll requires a window; the disabled module must still no-op, not throw.
        val window = CollectionWindow(startEpochMs = 0L, endEpochMs = 1_000L)
        val results = listOf(
            module.start(TestContexts.stub()),
            module.stop(TestContexts.stub()),
            module.poll(TestContexts.stub(), window),
            module.flush(TestContexts.stub()),
        )
        results.forEach { result ->
            assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
            assertEquals("not opted in", (result as ModuleResult.Skipped).reason)
        }
    }

    @Test
    fun diagnosticsReportCleanNeverRunRedactionSafeSnapshot() {
        val diagnostics = module.diagnostics()
        assertEquals(CollectionModuleId.HARDWARE_SENSORS, diagnostics.moduleId)
        assertEquals(CollectionModuleId.HARDWARE_SENSORS.privacyClass, diagnostics.privacyClass)
        assertNull("a disabled module has never run", diagnostics.lastRunEpochMs)
        assertEquals("SKIPPED", diagnostics.lastResult)
        assertEquals(0, diagnostics.itemsCollected)
        assertEquals(0, diagnostics.queueDepth)
        assertNull(diagnostics.lastError)
        assertNull("no raw participant reference in diagnostics", diagnostics.redactedParticipantRef)
    }

    @Test
    fun privacyClassIsDerivedFromModuleId() {
        for (id in CollectionModuleId.activeModules) {
            val disabled = DisabledCollectionModule(id)
            assertEquals(id.privacyClass, disabled.privacyClass)
        }
    }
}
