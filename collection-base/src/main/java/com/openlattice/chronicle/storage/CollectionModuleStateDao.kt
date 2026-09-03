package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CollectionModuleStateDao {
    @Query("SELECT * FROM collection_module_state")
    fun getAll(): List<CollectionModuleStateEntity>

    @Query("SELECT * FROM collection_module_state WHERE moduleId = :moduleId")
    fun get(moduleId: String): CollectionModuleStateEntity?

    /**
     * Count of study-enabled, study-required modules the participant has explicitly DECLINED
     * (per-module consent design §5: declining a required module halts ALL collection until
     * it is re-accepted — reversible). A merely UNDECIDED required module does NOT count
     * (grace window: already-accepted modules keep collecting until the participant decides).
     * Decision is stored as a ParticipantDecision name; Boolean columns are Room INTEGER 0/1.
     */
    @Query(
        "SELECT COUNT(*) FROM collection_module_state " +
            "WHERE serverEnabled = 1 AND requiredApplied = 1 AND decision = 'DECLINED'",
    )
    fun countRequiredDeclined(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: CollectionModuleStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(states: List<CollectionModuleStateEntity>)
}
