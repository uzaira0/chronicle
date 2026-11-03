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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: CollectionModuleStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(states: List<CollectionModuleStateEntity>)
}
