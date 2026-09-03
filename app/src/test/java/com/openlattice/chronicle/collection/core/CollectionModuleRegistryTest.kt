package com.openlattice.chronicle.collection.core

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A minimal active module used as a registry fixture. */
private class FakeModule(override val id: CollectionModuleId) : DataCollectionModule {
    override val privacyClass = id.privacyClass
    override fun status() = CollectionModuleStatus.IDLE
    override fun diagnostics() = CollectionModuleDiagnostics(moduleId = id, privacyClass = privacyClass)
    override fun start(context: Context) = ModuleResult.Skipped("test")
    override fun stop(context: Context) = ModuleResult.Skipped("test")
    override fun poll(context: Context, window: CollectionWindow) = ModuleResult.Skipped("test")
    override fun flush(context: Context) = ModuleResult.Skipped("test")
}

/** A module that lies about its id so the registry key-mismatch guard can be tested. */
private class MismatchedPrivacyModule(
    override val id: CollectionModuleId,
    override val privacyClass: com.openlattice.chronicle.collection.CollectionPrivacyClass,
) : DataCollectionModule {
    override fun status() = CollectionModuleStatus.IDLE
    override fun diagnostics() = CollectionModuleDiagnostics(moduleId = id, privacyClass = id.privacyClass)
    override fun start(context: Context) = ModuleResult.Skipped("test")
    override fun stop(context: Context) = ModuleResult.Skipped("test")
    override fun poll(context: Context, window: CollectionWindow) = ModuleResult.Skipped("test")
    override fun flush(context: Context) = ModuleResult.Skipped("test")
}

class CollectionModuleRegistryTest {

    private fun registry() = CollectionModuleRegistry(NoOpCollectionLog)

    @Test
    fun registerAndLookupActiveModule() {
        val registry = registry()
        val module = FakeModule(CollectionModuleId.USAGE_EVENTS)
        registry.register(module)

        assertTrue(registry.isRegistered(CollectionModuleId.USAGE_EVENTS))
        assertSame(module, registry.find(CollectionModuleId.USAGE_EVENTS))
        assertSame(module, registry.require(CollectionModuleId.USAGE_EVENTS))
        assertEquals(1, registry.size)
        assertEquals(setOf(CollectionModuleId.USAGE_EVENTS), registry.registeredIds)
    }

    @Test
    fun findReturnsNullForUnregisteredId() {
        val registry = registry()
        registry.register(FakeModule(CollectionModuleId.USAGE_EVENTS))
        assertNull(registry.find(CollectionModuleId.HARDWARE_SENSORS))
        assertFalse(registry.isRegistered(CollectionModuleId.HARDWARE_SENSORS))
    }

    @Test
    fun requireThrowsForUnregisteredId() {
        val registry = registry()
        try {
            registry.require(CollectionModuleId.UPLOAD_TELEMETRY)
            fail("require should throw for an unregistered module id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("upload_telemetry"))
        }
    }

    @Test
    fun registeringReservedInactiveIdIsRejected() {
        val registry = registry()
        // time_use_diary / questionnaire / app_inventory are reserved (active = false).
        for (reserved in CollectionModuleId.entries.filter { !it.active }) {
            try {
                registry.register(FakeModule(reserved))
                fail("register should reject reserved/inactive id '${reserved.id}'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("reserved"))
            }
        }
        assertEquals(0, registry.size)
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val registry = registry()
        registry.register(FakeModule(CollectionModuleId.DEVICE_LIFECYCLE))
        try {
            registry.register(FakeModule(CollectionModuleId.DEVICE_LIFECYCLE))
            fail("duplicate registration should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already registered"))
        }
        assertEquals(1, registry.size)
    }

    @Test
    fun privacyClassMismatchIsRejected() {
        val registry = registry()
        val bad = MismatchedPrivacyModule(
            id = CollectionModuleId.USAGE_EVENTS,
            privacyClass = com.openlattice.chronicle.collection.CollectionPrivacyClass.PHYSICAL_TELEMETRY,
        )
        try {
            registry.register(bad)
            fail("registry should reject a module whose privacyClass disagrees with its id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("privacyClass"))
        }
    }

    @Test
    fun registerAllAndAllPreserveOrder() {
        val registry = registry()
        val usage = FakeModule(CollectionModuleId.USAGE_EVENTS)
        val lifecycle = FakeModule(CollectionModuleId.DEVICE_LIFECYCLE)
        registry.registerAll(listOf(usage, lifecycle))
        assertEquals(listOf<DataCollectionModule>(usage, lifecycle), registry.all())
    }
}
