package com.openlattice.chronicle.collection.sensors

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.ModuleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No-op [HardwareSensorServiceController] for the Context-free JVM contract tests. */
private object NoOpServiceController : HardwareSensorServiceController {
    override fun startService(context: Context) {}
    override fun stopService(context: Context) {}
}

private fun module() = HardwareSensorsCollectionModule(serviceController = NoOpServiceController)

/**
 * JVM unit coverage for [HardwareSensorsCollectionModule] — the Phase 6A registry-facing
 * module handle.
 *
 * The module's `start`/`stop` call `HardwareSensorService.startService`/`stopService`,
 * which require a real Android `Context` — those service-interaction paths are exercised
 * in the instrumented test. This JVM test covers the contract surface that needs no
 * `Context`: identity, privacy class, the no-op push/poll/flush semantics, and the
 * diagnostics shape (redaction-safe, no participant reference).
 */
class HardwareSensorsCollectionModuleTest {

    @Test
    fun moduleDeclaresHardwareSensorsIdentityAndPhysicalTelemetryPrivacyClass() {
        val m = module()
        assertEquals(CollectionModuleId.HARDWARE_SENSORS, m.id)
        assertEquals(CollectionPrivacyClass.PHYSICAL_TELEMETRY, m.privacyClass)
        assertEquals(m.id.privacyClass, m.privacyClass)
    }

    @Test
    fun pollIsANoOpSkipBecauseHardwareSensorsIsAPushModule() {
        val m = module()
        val window = CollectionWindow(startEpochMs = 0L, endEpochMs = 1_000L)
        val result = m.poll(
            com.openlattice.chronicle.collection.core.TestContexts.stub(),
            window,
        )
        assertTrue("hardware_sensors is a push module", result is ModuleResult.Skipped)
    }

    @Test
    fun flushIsANoOpSkipBecauseTheInServiceControllerOwnsTheBuffer() {
        val m = module()
        val result = m.flush(com.openlattice.chronicle.collection.core.TestContexts.stub())
        assertTrue(result is ModuleResult.Skipped)
    }

    @Test
    fun diagnosticsBeforeAnyRunAreRedactionSafeAndNeverRun() {
        val d = module().diagnostics()
        assertEquals(CollectionModuleId.HARDWARE_SENSORS, d.moduleId)
        assertEquals(CollectionPrivacyClass.PHYSICAL_TELEMETRY, d.privacyClass)
        assertEquals("SKIPPED", d.lastResult)
        assertEquals(0, d.itemsCollected)
        // No raw participant reference is ever exposed (design §1B.3).
        assertEquals(null, d.redactedParticipantRef)
        assertTrue(d.notTracked.contains("serviceRunning=false"))
    }

    @Test
    fun statusIsIdleBeforeAnyStartStop() {
        assertEquals(CollectionModuleStatus.IDLE, module().status())
    }

    @Test
    fun holderReturnsASingleSharedInstance() {
        // The holder must hand out one accumulating instance, like DeviceLifecycleModuleHolder.
        assertTrue(HardwareSensorsModuleHolder.module === HardwareSensorsModuleHolder.module)
    }
}
