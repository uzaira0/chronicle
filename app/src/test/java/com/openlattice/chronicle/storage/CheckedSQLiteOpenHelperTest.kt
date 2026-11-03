package com.openlattice.chronicle.storage

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CheckedSQLiteOpenHelperTest {
    @Test fun queryFailurePropagatesAndRecoveryReadsTheSameCommittedRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var fail = false
        val expected = SQLiteException("controlled read failure")
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if (fail && sql.startsWith("SELECT") && "upload_servers" in sql) throw expected
            }).build()
        try {
            val id = db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
                studyId = "11111111-1111-1111-1111-111111111111", participantId = "participant",
                sourceRandomDeviceId = "fixture-device"))
            fail = true
            assertEquals(expected, assertThrows(SQLiteException::class.java) { db.uploadServerDao().getById(id) })
            fail = false
            assertEquals(id, db.uploadServerDao().getById(id)!!.id)
        } finally { db.close() }
    }
}
