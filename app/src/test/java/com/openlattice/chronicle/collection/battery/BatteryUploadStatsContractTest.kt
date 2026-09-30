package com.openlattice.chronicle.collection.battery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryUploadStatsContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity
    private lateinit var apiCache: MutableMap<String, ChronicleStudyApi>
    private lateinit var apiCacheKey: String
    private var previousApi: ChronicleStudyApi? = null

    @Before fun setUp() {
        val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val prefs = context.getSharedPreferences("battery-stats-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, study.toString()).putString(PARTICIPANT_ID, "participant").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = study.toString(),
            participantId = "participant", sourceDeviceId = "device", authMode = AUTH_MODE_API_KEY,
            apiKey = "test-key", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
        EncryptionSettingStore.of(context).put(study, StudyEncryptionSetting(enabled = false))
        @Suppress("UNCHECKED_CAST")
        val cache = UploadWorker::class.java.getDeclaredField("studyApiCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, ChronicleStudyApi>
        apiCache = cache
        apiCacheKey = server.url + "|" + Utils.mobileSigningSecretFingerprint(null)
        previousApi = apiCache[apiCacheKey]
    }

    @After fun tearDown() {
        previousApi?.let { apiCache[apiCacheKey] = it } ?: apiCache.remove(apiCacheKey)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun acceptedBatteryBatchIsDeletedEvenIfLocalStatsWriteFails() {
        val ids = listOf("battery-1", "battery-2")
        db.batterySampleDao().insertAll(ids.map { id -> BatterySampleEntry(
            id = id, timestamp = "2026-09-28T10:00:00Z", timezone = "UTC", levelPercent = 64,
            chargingState = "DISCHARGING", plugType = "UNPLUGGED", temperatureDeciC = 298,
            voltageMillivolts = 4012, health = "GOOD",
        ) })
        val uploadedIds = mutableListOf<String>()
        apiCache[apiCacheKey] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidBatteryData") { "unexpected call ${method.name}" }
            @Suppress("UNCHECKED_CAST")
            val samples = args[4] as List<BatterySample>
            uploadedIds += samples.map { it.id }
            samples.size
        } as ChronicleStudyApi
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE UPDATE ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )

        val failures = BatteryUploadWorkerDelegate(context, db).execute()

        assertEquals(ids, uploadedIds)
        assertEquals("local stats failure is returned even though the server accepted the batch", 1, failures)
        assertEquals("accepted queue rows must be deleted despite the stats failure", 0, db.batterySampleDao().count())
        assertEquals(2, db.uploadServerDao().getById(server.id)!!.batteryUploadSuccessCount)
    }
}
