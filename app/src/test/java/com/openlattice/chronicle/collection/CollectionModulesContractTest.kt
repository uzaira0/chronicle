package com.openlattice.chronicle.collection

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.core.CollectionModuleRegistry
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A minimal active module fixture (mirrors `CollectionModuleRegistryTest.FakeModule`). */
private class FakeModule(override val id: CollectionModuleId) : DataCollectionModule {
    override val privacyClass = id.privacyClass
    override fun status() = CollectionModuleStatus.IDLE
    override fun diagnostics() = CollectionModuleDiagnostics(moduleId = id, privacyClass = privacyClass)
    override fun start(context: Context) = ModuleResult.Skipped("test")
    override fun stop(context: Context) = ModuleResult.Skipped("test")
    override fun poll(context: Context, window: CollectionWindow) = ModuleResult.Skipped("test")
    override fun flush(context: Context) = ModuleResult.Skipped("test")
}

/**
 * Hardening contract for [CollectionModules] — the runtime realization of the
 * [CollectionModuleRegistry] (design §1C.3).
 *
 * These are *property-based* invariants, not magic counts, so they keep holding as modules
 * are added: every registry-managed id must be active, holder-backed, and privacy-class
 * consistent, and the managed set must equal "every active module except the two that are
 * deliberately not `DataCollectionModule` singletons". That last invariant is the
 * completeness lock — adding a new active module forces a conscious choice (register it, or
 * extend the documented exclusions below) rather than silently dropping it from the runtime
 * registry.
 */
class CollectionModulesContractTest {

    /**
     * The active ids that are intentionally NOT holder-backed module singletons, and so are
     * not registry-managed (see [CollectionModules] kdoc):
     *  - [CollectionModuleId.USAGE_EVENTS] — built per-collection in `UsageModuleCollectionDelegate`
     *    with run-scoped poll-cursor state, so it is not a process singleton.
     *  - [CollectionModuleId.SENSOR_AVAILABILITY] — realized by `SensorAvailabilityReporter`,
     *    a reporter, not a `DataCollectionModule`.
     *  - the per-sensor `sensor_*` modules — realized by the one shared `HardwareSensorService`
     *    runtime (per-sensor consent redesign), not by per-sensor `DataCollectionModule` singletons.
     *  - [CollectionModuleId.INTERACTION_EVENTS] — realized by the shared
     *    `InteractionCollectionService` (an `AccessibilityService` driven by OS-delivered
     *    accessibility events), not by a holder-backed `DataCollectionModule` singleton, so it
     *    is service-realized like `usage_events`.
     *  - [CollectionModuleId.IN_APP_ACTIVITY_CLASS] — a field gate on the `usage_events` stream
     *    (the within-app Activity/screen class), enforced in the usage collection delegates, not
     *    a holder-backed `DataCollectionModule` singleton.
     *  - [CollectionModuleId.AUDIO_ACTIVITY] / [CollectionModuleId.AUDIO_CONTENT] /
     *    [CollectionModuleId.NOTIFICATION_ACTIVITY] — realized by `AudioCaptureController` +
     *    the `NotificationListener` service (AudioManager / MediaSessionManager / notification
     *    callbacks), not by holder-backed `DataCollectionModule` singletons.
     *  - [CollectionModuleId.AMBIENT_AUDIO] — iOS-only (Apple SoundAnalysis on-device
     *    classification); there is no Android realization, so it is never registered here.
     */
    private val nonModuleActiveIds = setOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.SENSOR_AVAILABILITY,
        CollectionModuleId.INTERACTION_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.AUDIO_ACTIVITY,
        CollectionModuleId.AUDIO_CONTENT,
        CollectionModuleId.NOTIFICATION_ACTIVITY,
        CollectionModuleId.AMBIENT_AUDIO,
    ) + SensorCollectionModules.sensorModuleIds

    /**
     * Holder-backed modules intentionally absent from this distribution. The public Play flavor
     * must not treat compiled-out Health/research modules as gaps in its runtime registry, while
     * research/open variants still retain the completeness lock for modules they ship.
     */
    private val distributionExcludedModuleIds = buildSet {
        if (!BuildConfig.HAS_HEALTH_CONNECT) {
            add(CollectionModuleId.HEALTH_CONNECT)
        }
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            add(CollectionModuleId.SLEEP)
            add(CollectionModuleId.ACTIVITY_RECOGNITION)
            add(CollectionModuleId.APP_NETWORK_USAGE)
        }
        if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            add(CollectionModuleId.QUESTIONNAIRE)
        }
    }

    @Test
    fun everyManagedIdIsActive() {
        assertTrue(
            "registry-managed modules must all be active",
            CollectionModules.MANAGED_MODULE_IDS.isNotEmpty() &&
                CollectionModules.MANAGED_MODULE_IDS.all { it.active },
        )
    }

    @Test
    fun noManagedIdIsReserved() {
        val reserved = CollectionModuleId.entries.filterNot { it.active }.toSet()
        assertTrue(
            "no reserved/inactive id may be registry-managed",
            CollectionModules.MANAGED_MODULE_IDS.none { it in reserved },
        )
    }

    @Test
    fun managedSetIsEveryActiveModuleExceptTheNonModuleIds() {
        // Completeness lock: a newly-added active module must be either registry-managed or
        // explicitly added to the documented non-module exclusions — never silently omitted.
        assertEquals(
            CollectionModuleId.activeModules - nonModuleActiveIds - distributionExcludedModuleIds,
            CollectionModules.MANAGED_MODULE_IDS,
        )
    }

    @Test
    fun managedModulesRegisterCleanlyAndRoundTrip() {
        // The registry enforces active + identity + privacy-class match on register(); a
        // faithful stub for every managed id must therefore register and round-trip cleanly.
        val registry = CollectionModuleRegistry(NoOpCollectionLog)
        CollectionModules.MANAGED_MODULE_IDS.forEach { registry.register(FakeModule(it)) }

        assertEquals(CollectionModules.MANAGED_MODULE_IDS, registry.registeredIds)
        CollectionModules.MANAGED_MODULE_IDS.forEach { id ->
            assertEquals(id, registry.require(id).id)
        }
    }
}
