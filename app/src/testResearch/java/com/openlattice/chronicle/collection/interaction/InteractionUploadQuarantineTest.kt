package com.openlattice.chronicle.collection.interaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.InteractionSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.interactionSampleDao
import com.openlattice.chronicle.study.StudyEncryptionSetting
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InteractionUploadQuarantineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, context.getSharedPreferences("interaction-quarantine-test", Context.MODE_PRIVATE))
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
        db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = study.toString(),
            participantId = "p1", sourceDeviceId = "d1", createdAt = "2026-09-01T00:00:00Z",
        ))
        EncryptionSettingStore.of(context).put(study, StudyEncryptionSetting(enabled = false))
    }

    @After fun tearDown() = db.close()

    private fun sample(id: String, x: Double?) = InteractionSampleEntry(
        id = id, timestamp = "2026-09-28T10:00:00Z", timezone = "UTC",
        eventType = InteractionEventType.CLICK.name, gridRows = 4, gridCols = 4,
        gridRow = 1, gridCol = 1, elementRole = "android.widget.Button",
        foregroundPackage = "example.app", rawX = null, rawY = null,
        screenWidth = null, screenHeight = null, normalizedX = x, normalizedY = null,
        scrollDeltaX = null, scrollDeltaY = null, eventTimeMillis = null,
        episodeId = null, dwellMillisSincePrev = null, orientation = null,
        screenDensityDpi = null, scrollVelocityX = null, scrollVelocityY = null,
        scrollReversed = null,
    )

    @Test fun nonFiniteRowIsQuarantinedWhileValidSiblingUploads() {
        db.interactionSampleDao().insertAll(listOf(sample("bad", Double.POSITIVE_INFINITY), sample("good", 0.5)))
        val uploadedIds = mutableListOf<String>()
        val api = Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
            arrayOf(RestrictedChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidInteractionData")
            @Suppress("UNCHECKED_CAST")
            val events = args[4] as List<com.openlattice.chronicle.collection.AndroidInteractionEvent>
            uploadedIds += events.map { it.id }
            1
        } as RestrictedChronicleStudyApi

        val failures = InteractionUploadWorkerDelegate(context, db, restrictedStudyApiFor = { api }).execute()

        assertEquals(0, failures)
        assertEquals(listOf("good"), uploadedIds)
        assertEquals(0, db.interactionSampleDao().count())
        val raw = db.localDataQuarantineDao().get("interaction_samples", "1:2026-09-01T00:00:00Z:bad")!!.rawData
            .toString(Charsets.UTF_8)
        assertTrue(raw.contains("\"normalizedX\":\"Infinity\""))
    }
}
