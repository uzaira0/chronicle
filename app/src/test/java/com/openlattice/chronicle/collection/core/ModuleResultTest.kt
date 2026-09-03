package com.openlattice.chronicle.collection.core

import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModuleResultTest {

    @Test
    fun labelsAreStableAndRedactionSafe() {
        assertEquals("OK", ModuleResult.Ok(3).label)
        assertEquals("SKIPPED", ModuleResult.Skipped("disabled").label)
        assertEquals("RETRY", ModuleResult.Retry("offline").label)
        assertEquals("FAILED", ModuleResult.Failed(RuntimeException("boom")).label)
    }

    @Test
    fun isSuccessOnlyTrueForOk() {
        assertTrue(ModuleResult.Ok(0).isSuccess)
        assertFalse(ModuleResult.Skipped("x").isSuccess)
        assertFalse(ModuleResult.Retry("x").isSuccess)
        assertFalse(ModuleResult.Failed(RuntimeException("x")).isSuccess)
    }

    @Test
    fun okWithZeroItemsIsAValidSuccess() {
        val result = ModuleResult.Ok(0)
        assertTrue(result.isSuccess)
        assertEquals(0, result.items)
    }

    @Test
    fun okRejectsNegativeItems() {
        try {
            ModuleResult.Ok(-1)
            fail("Ok must reject a negative item count")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun failedDefaultRedactedMessageFallsBackToExceptionClass() {
        val noMessage = ModuleResult.Failed(RuntimeException())
        assertEquals("RuntimeException", noMessage.redactedMessage)
        val withMessage = ModuleResult.Failed(RuntimeException("explicit cause"))
        assertEquals("explicit cause", withMessage.redactedMessage)
    }

    @Test
    fun resultLabelFeedsDiagnosticsLastResult() {
        // The Ok/Skipped/etc. label is what a module reports as diagnostics.lastResult.
        val diagnostics = CollectionModuleDiagnostics(
            moduleId = CollectionModuleId.USAGE_EVENTS,
            privacyClass = CollectionModuleId.USAGE_EVENTS.privacyClass,
            lastResult = ModuleResult.Ok(7).label,
            itemsCollected = 7,
        )
        assertEquals("OK", diagnostics.lastResult)
        assertEquals(7, diagnostics.itemsCollected)
    }
}
