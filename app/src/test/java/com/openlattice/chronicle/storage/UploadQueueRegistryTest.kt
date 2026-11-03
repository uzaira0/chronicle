package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadQueueRegistryTest {
    @Test fun everyRoomTableHasAnExplicitUploadReadDisposition() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChronicleDb::class.java)
            .allowMainThreadQueries().build()
        val version = try { db.openHelper.readableDatabase.version } finally { db.close() }
        val resource = "/com.openlattice.chronicle.storage.ChronicleDb/$version.json"
        val schema = JSONObject(checkNotNull(javaClass.getResource(resource)).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val tables = (0 until entities.length()).mapTo(sortedSetOf()) { entities.getJSONObject(it).getString("tableName") }
        for (queue in UploadQueueTable.entries) {
            val fields = (0 until entities.length()).map { entities.getJSONObject(it) }
                .single { it.getString("tableName") == queue.tableName }.getJSONArray("fields")
            val growable = (0 until fields.length()).map { fields.getJSONObject(it) }
                .filter { it.getString("affinity") in setOf("TEXT", "BLOB") }
                .map { it.getString("columnName") }
            assertEquals(queue.tableName, growable, queue.growableColumns)
        }
        // Non-payload state and receipts never materialize collected rows for an upload/replay.
        val controlTables = setOf("userQueue", "upload_servers", "sensor_sample_dead_letters",
            "upload_stats", "usage_poll_checkpoints", "collection_module_state", "lifecycle_state", "upload_queue_evidence",
            "pending_collection_acks")
        assertEquals(tables - controlTables, UploadQueueTable.entries.mapTo(sortedSetOf()) { it.tableName })
        assertTrue(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES <= CollectedUploadBody.ROW_BATCH_BUDGET_BYTES)
    }
}
