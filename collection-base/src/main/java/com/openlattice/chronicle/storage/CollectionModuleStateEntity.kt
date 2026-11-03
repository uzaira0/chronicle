package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The device's persisted per-module collection-loop state (collection loop closure
 * design §5.5). One row per [com.openlattice.chronicle.collection.CollectionModuleId];
 * the primary key is the module's stable wire id string.
 *
 * Stored as Room primitives — [com.openlattice.chronicle.collection.CollectionModuleId] is kept as
 * its wire-id string so collection-base needs no Room TypeConverters and stays decoupled
 * from the pure `CollectionModuleState` model (the orchestration layer maps between them).
 */
@Entity(tableName = "collection_module_state")
data class CollectionModuleStateEntity(
    @PrimaryKey val moduleId: String,
    val serverEnabled: Boolean,
    /**
     * The participant's standing decision, as a
     * [com.openlattice.chronicle.collection.state.ParticipantDecision] name
     * (`UNDECIDED` / `ACCEPTED` / `DECLINED`). Stored as a string so collection-base
     * keeps no Room TypeConverters (per-module consent design §4.1).
     */
    val decision: String,
    /** When the participant last decided (accepted/declined); null while UNDECIDED. */
    val decidedAtEpochMillis: Long?,
    /** The study's `required` flag from the last applied settings version. */
    val requiredApplied: Boolean,
    val appliedVersion: Int,
)
