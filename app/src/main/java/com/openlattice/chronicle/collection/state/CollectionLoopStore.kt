package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateDao
import com.openlattice.chronicle.storage.CollectionModuleStateEntity

/**
 * Room-backed persistence + mapping for the collection-loop per-module state (design
 * §5.5). Maps between the storage primitives ([CollectionModuleStateEntity]) and the
 * pure [CollectionModuleState] the [CollectionStateMachine] consumes.
 *
 * The mapping functions are pure and unit-tested; the DAO wrapper is the thin Android
 * seam. Unknown wire ids (a downgraded app reading a future module/disposition) are
 * dropped tolerantly, mirroring the settings read path.
 */
class CollectionLoopStore(private val dao: CollectionModuleStateDao) {

    /** All persisted module states, keyed by id (unknown ids dropped). */
    fun loadAll(): Map<CollectionModuleId, CollectionModuleState> =
        dao.getAll().mapNotNull { it.toStateOrNull() }.associateBy { it.moduleId }

    /** Upserts the given states. */
    fun save(states: Collection<CollectionModuleState>) {
        if (states.isEmpty()) return
        dao.upsertAll(states.map { it.toEntity() })
    }

    /**
     * The device gate for a single module: collect only when the row exists, the server
     * has enabled it, AND the participant has ACCEPTED it. Absent row ⇒ false (the
     * absolute gate — nothing collects until both conditions hold).
     *
     * Additionally enforces the GLOBAL halt (per-module consent design §5): while the
     * participant has **explicitly declined** a study-enabled, study-required module,
     * NOTHING collects — not even already-accepted modules — until that module is
     * re-accepted. This is intrinsic to the gate, so every `collects()` call-site honors it
     * automatically (reversible — no reinstall). A merely UNDECIDED required module does NOT
     * halt: already-accepted modules keep collecting during the grace window until the
     * participant accepts or declines it.
     */
    fun collects(moduleId: CollectionModuleId): Boolean {
        if (haltedByDeclinedRequired()) return false
        val e = dao.get(moduleId.id) ?: return false
        return e.serverEnabled && e.decision == ParticipantDecision.ACCEPTED.name
    }

    /**
     * True when collection is globally halted because the participant explicitly declined a
     * study-required module. Surfaced to the Data Sharing UI to show the "paused" banner +
     * an Accept affordance to resume.
     */
    fun haltedByDeclinedRequired(): Boolean = dao.countRequiredDeclined() > 0

    companion object {
        fun CollectionModuleStateEntity.toStateOrNull(): CollectionModuleState? {
            val id = CollectionModuleId.fromIdOrNull(moduleId) ?: return null
            return CollectionModuleState(
                moduleId = id,
                serverEnabled = serverEnabled,
                decision = decision.toParticipantDecision(),
                decidedAtEpochMillis = decidedAtEpochMillis,
                requiredApplied = requiredApplied,
                appliedVersion = appliedVersion,
                appliedPolicySnapshot = appliedPolicySnapshot,
                lastDisposition = lastDisposition?.let { CollectionDataDisposition.fromIdOrNull(it) },
            )
        }

        fun CollectionModuleState.toEntity(): CollectionModuleStateEntity =
            CollectionModuleStateEntity(
                moduleId = moduleId.id,
                serverEnabled = serverEnabled,
                decision = decision.name,
                decidedAtEpochMillis = decidedAtEpochMillis,
                requiredApplied = requiredApplied,
                appliedVersion = appliedVersion,
                appliedPolicySnapshot = appliedPolicySnapshot,
                lastDisposition = lastDisposition?.id,
            )

        /** Tolerant decode of the persisted decision string (unknown ⇒ UNDECIDED). */
        private fun String.toParticipantDecision(): ParticipantDecision =
            runCatching { ParticipantDecision.valueOf(this) }.getOrDefault(ParticipantDecision.UNDECIDED)

        fun of(context: Context): CollectionLoopStore =
            CollectionLoopStore(ChronicleDb.getInstance(context.applicationContext).collectionModuleStateDao())
    }
}

/**
 * Fail-closed gate used by each module holder's collection seam: returns true only when
 * the persisted state says the module is server-enabled AND acknowledged. Any error
 * reading the state returns false, so a storage problem never causes collection without
 * a confirmed server-enable + acknowledgment.
 */
object CollectionGate {
    private const val TAG = "CollectionGate"

    fun collects(context: Context, moduleId: CollectionModuleId): Boolean =
        try {
            CollectionLoopStore.of(context).collects(moduleId)
        } catch (e: Exception) {
            Log.e(TAG, "Gate read failed for '${moduleId.id}', failing closed (no collection)", e)
            false
        }
}
