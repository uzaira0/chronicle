package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.CollectionModuleStateDao
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the entity <-> pure-state mapping and the fail-closed gate logic of
 * [CollectionLoopStore] (collection loop closure design §5.5; per-module consent design
 * §4.1) with an in-memory DAO. [CollectionGate] itself needs an Android Context, so only
 * the pure store is unit-tested.
 */
class CollectionLoopStoreTest {

    private class FakeDao(initial: List<CollectionModuleStateEntity> = emptyList()) : CollectionModuleStateDao {
        val rows = initial.associateBy { it.moduleId }.toMutableMap()
        override fun getAll(): List<CollectionModuleStateEntity> = rows.values.toList()
        override fun get(moduleId: String): CollectionModuleStateEntity? = rows[moduleId]
        override fun upsert(state: CollectionModuleStateEntity) { rows[state.moduleId] = state }
        override fun upsertAll(states: List<CollectionModuleStateEntity>) { states.forEach { rows[it.moduleId] = it } }
        // Mirrors the @Query in CollectionModuleStateDao: enabled + required + DECLINED.
        override fun countRequiredDeclined(): Int =
            rows.values.count {
                it.serverEnabled && it.requiredApplied && it.decision == ParticipantDecision.DECLINED.name
            }
    }

    private fun entity(
        moduleId: String,
        serverEnabled: Boolean,
        decision: ParticipantDecision,
        decidedAt: Long? = if (decision == ParticipantDecision.UNDECIDED) null else 1L,
        requiredApplied: Boolean = false,
        lastDisposition: String? = null,
    ) = CollectionModuleStateEntity(
        moduleId = moduleId,
        serverEnabled = serverEnabled,
        decision = decision.name,
        decidedAtEpochMillis = decidedAt,
        requiredApplied = requiredApplied,
        appliedVersion = 1,
        appliedPolicySnapshot = null,
        lastDisposition = lastDisposition,
    )

    @Test fun testSaveThenLoadRoundTrips() {
        val store = CollectionLoopStore(FakeDao())
        val state = CollectionModuleState(
            moduleId = CollectionModuleId.BATTERY_TELEMETRY,
            serverEnabled = true,
            decision = ParticipantDecision.DECLINED,
            decidedAtEpochMillis = 42L,
            requiredApplied = false,
            appliedVersion = 4,
            appliedPolicySnapshot = "policy-v1",
            lastDisposition = CollectionDataDisposition.HOLD_PENDING,
        )
        store.save(listOf(state))
        val loaded = store.loadAll()
        assertEquals(state, loaded.getValue(CollectionModuleId.BATTERY_TELEMETRY))
    }

    @Test fun testLoadDropsUnknownModuleId() {
        val dao = FakeDao(
            listOf(
                entity("battery_telemetry", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
                entity("not_a_real_module", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
            ),
        )
        val loaded = CollectionLoopStore(dao).loadAll()
        assertEquals(setOf(CollectionModuleId.BATTERY_TELEMETRY), loaded.keys)
    }

    @Test fun testUnknownDispositionMapsToNull() {
        val dao = FakeDao(
            listOf(
                entity(
                    "battery_telemetry",
                    serverEnabled = false,
                    decision = ParticipantDecision.UNDECIDED,
                    lastDisposition = "not_a_disposition",
                ),
            ),
        )
        val state = CollectionLoopStore(dao).loadAll().getValue(CollectionModuleId.BATTERY_TELEMETRY)
        assertNull(state.lastDisposition)
    }

    @Test fun testUnknownDecisionMapsToUndecided() {
        val dao = FakeDao(
            listOf(
                CollectionModuleStateEntity(
                    "battery_telemetry",
                    serverEnabled = true,
                    decision = "BOGUS",
                    decidedAtEpochMillis = null,
                    requiredApplied = false,
                    appliedVersion = 1,
                    appliedPolicySnapshot = null,
                    lastDisposition = null,
                ),
            ),
        )
        val state = CollectionLoopStore(dao).loadAll().getValue(CollectionModuleId.BATTERY_TELEMETRY)
        assertEquals(ParticipantDecision.UNDECIDED, state.decision)
    }

    @Test fun testCollectsOnlyWhenServerEnabledAndAccepted() {
        val dao = FakeDao(
            listOf(
                entity("battery_telemetry", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
                entity("usage_events", serverEnabled = true, decision = ParticipantDecision.UNDECIDED),
                entity("hardware_sensors", serverEnabled = true, decision = ParticipantDecision.DECLINED),
                entity("device_lifecycle", serverEnabled = false, decision = ParticipantDecision.ACCEPTED),
            ),
        )
        val store = CollectionLoopStore(dao)
        assertTrue(store.collects(CollectionModuleId.BATTERY_TELEMETRY))
        assertFalse("awaiting decision does not collect", store.collects(CollectionModuleId.USAGE_EVENTS))
        assertFalse("declined does not collect", store.collects(CollectionModuleId.HARDWARE_SENSORS))
        assertFalse("server-disabled does not collect", store.collects(CollectionModuleId.DEVICE_LIFECYCLE))
        assertFalse("absent row does not collect", store.collects(CollectionModuleId.USER_IDENTIFICATION))
    }

    @Test fun testUndecidedRequiredModuleDoesNotHalt_graceWindow() {
        // The study made usage_events required mid-study; the participant hasn't decided yet.
        // Grace window: already-accepted modules keep collecting; only the undecided required
        // module itself does not collect (it isn't accepted). No global halt.
        val dao = FakeDao(
            listOf(
                entity("battery_telemetry", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
                entity("usage_events", serverEnabled = true, decision = ParticipantDecision.UNDECIDED, requiredApplied = true),
            ),
        )
        val store = CollectionLoopStore(dao)
        assertFalse("an undecided required module does not halt collection", store.haltedByDeclinedRequired())
        assertTrue(
            "accepted module keeps collecting during the grace window",
            store.collects(CollectionModuleId.BATTERY_TELEMETRY),
        )
        assertFalse("the undecided required module itself does not collect", store.collects(CollectionModuleId.USAGE_EVENTS))
    }

    @Test fun testDeclinedRequiredModuleHaltsAllCollection() {
        // The participant explicitly DECLINED a required module → the global halt: NOTHING
        // collects, not even an already-accepted module.
        val dao = FakeDao(
            listOf(
                entity("battery_telemetry", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
                entity("usage_events", serverEnabled = true, decision = ParticipantDecision.DECLINED, requiredApplied = true),
            ),
        )
        val store = CollectionLoopStore(dao)
        assertTrue("declining a required module halts collection", store.haltedByDeclinedRequired())
        assertFalse(
            "accepted module does not collect while a required module is declined",
            store.collects(CollectionModuleId.BATTERY_TELEMETRY),
        )
        assertFalse(store.collects(CollectionModuleId.USAGE_EVENTS))
    }

    @Test fun testReacceptingDeclinedRequiredModuleLiftsHaltAndResumes() {
        // Same config, but the participant re-accepted the required usage_events: the halt
        // lifts and every accepted module resumes (reversible — no reinstall).
        val dao = FakeDao(
            listOf(
                entity("battery_telemetry", serverEnabled = true, decision = ParticipantDecision.ACCEPTED),
                entity("usage_events", serverEnabled = true, decision = ParticipantDecision.ACCEPTED, requiredApplied = true),
            ),
        )
        val store = CollectionLoopStore(dao)
        assertFalse("no declined required module — not halted", store.haltedByDeclinedRequired())
        assertTrue(store.collects(CollectionModuleId.BATTERY_TELEMETRY))
        assertTrue(store.collects(CollectionModuleId.USAGE_EVENTS))
    }
}
