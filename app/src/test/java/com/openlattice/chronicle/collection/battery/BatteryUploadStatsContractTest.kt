package com.openlattice.chronicle.collection.battery

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Proxy
import java.util.UUID
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatteryUploadStatsContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity

    @Before fun setUp() {
        val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val prefs = context.getSharedPreferences("battery-stats-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, study.toString()).putString(PARTICIPANT_ID, "participant").commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = study.toString(),
            participantId = "participant", sourceRandomDeviceId = "device", apiKey = "test-key", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
    }

    @After fun tearDown() {
        EnrollmentTestStores.api = null
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun acceptedBatteryBatchAndCountersCommitTogetherAcrossRetry() {
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        db.batterySampleDao().insertAll(ids.map { id -> BatterySampleEntry(
            id = id, timestamp = "2026-09-28T10:00:00Z", timezone = "UTC", levelPercent = 64,
            chargingState = "DISCHARGING", plugType = "UNPLUGGED", temperatureDeciC = 298,
            voltageMillivolts = 4012, health = "GOOD",
        ) })
        val uploadedIds = mutableListOf<String>()
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidBatteryData") { "unexpected call ${method.name}" }
            val samples = decodedUploadBody<List<BatterySample>>(args[3])
            uploadedIds += samples.map { it.id }
            UploadReceipt(samples.mapTo(linkedSetOf()) { UUID.fromString(it.id) }, emptySet(), 1)
        } as ChronicleStudyApi
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE UPDATE ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )

        val failures = BatteryUploadWorkerDelegate(context, db).execute()

        assertEquals(ids, uploadedIds)
        assertTrue("Local acknowledgment failure requests retry", failures > 0)
        assertEquals("An incomplete acknowledgment must retain the frozen selection", ids.size, db.batterySampleDao().count())
        assertEquals(0, db.uploadServerDao().getById(server.id)!!.batteryUploadSuccessCount)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        assertEquals(0, BatteryUploadWorkerDelegate(context, db).execute())
        assertEquals(0, db.batterySampleDao().count())
        assertEquals(LocalDate.now().toString(),
            db.uploadStatsDao().getRecentStats(server.id, Int.MAX_VALUE).single { it.batterySamplesUploaded > 0 }.date)
        assertEquals(ids.size, db.uploadServerDao().getById(server.id)!!.batteryUploadSuccessCount)
        assertEquals(ids.size, db.uploadStatsDao().getRecentStats(server.id, Int.MAX_VALUE).sumOf { it.batterySamplesUploaded })
        assertEquals("The retry resends the same row identities", ids + ids, uploadedIds)
    }
}
