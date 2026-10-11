package com.openlattice.chronicle.services.sensors

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.SensorUploadReceipt
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UploadRetryGate
import com.openlattice.chronicle.services.upload.pendingUploadBatchIdentity
import com.openlattice.chronicle.services.upload.selectPendingUploadBatch
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.StudyEncryptionSetting
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SensorUploadWorkerDelegateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("sensor-uploader-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        val id = db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111", participantId = "p1", sourceDeviceId = "d1"))
        server = db.uploadServerDao().getById(id)!!
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), StudyEncryptionSetting(enabled = false))
    }

    @After fun tearDown() {
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    private fun sample(id: String, type: String) = SensorSampleEntry(UUID.nameUUIDFromBytes(id.toByteArray()).toString(), type,
        "2026-10-01T00:00:00Z", "UTC", 1f, 2f, 3f, null, 3)

    private fun uploader(upload: (List<AndroidSensorSample>) -> Int): SensorUploadWorkerDelegate = receiptUploader { samples ->
        val count = upload(samples)
        SensorUploadReceipt(samples.take(count).mapTo(linkedSetOf()) { it.id }, emptySet(), emptySet(), 1)
    }

    private fun receiptUploader(upload: (List<AndroidSensorSample>) -> SensorUploadReceipt): SensorUploadWorkerDelegate {
        val api = Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
            arrayOf(RestrictedChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidSensorDataReceipt")
            @Suppress("UNCHECKED_CAST") val samples = args!![4] as List<AndroidSensorSample>
            upload(samples)
        } as RestrictedChronicleStudyApi
        val studyApi = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as ChronicleStudyApi
        return SensorUploadWorkerDelegate(context, db, studyApiFor = { studyApi }, restrictedApiFor = { api })
    }

    @Test fun holdingOneTypeReselectsPendingBatchAndUploadsActiveSensors() {
        val held = sample("held", "gyroscope")
        val active = sample("active", "accelerometer")
        val dao = db.sensorSampleDao()
        dao.insertAll(listOf(held, active))
        selectPendingUploadBatch(db, server, EncryptedPayloadType.SENSOR,
            loadNew = { dao.getOldest(SENSOR_UPLOAD_BATCH_SIZE) }, loadByIds = dao::getByIds, idOf = { it.id })
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            CollectionModuleId.SENSOR_GYROSCOPE.id, false, ParticipantDecision.ACCEPTED.name,
            0, false, 2, null, CollectionDataDisposition.HOLD_PENDING.id)))
        val uploaded = mutableListOf<AndroidSensorSample>()

        assertEquals(0, uploader { uploaded += it; it.size }.execute())

        assertEquals(listOf(active.toAndroidSensorSample()), uploaded)
        assertEquals(listOf(held), dao.getOldest(SENSOR_UPLOAD_BATCH_SIZE))
        assertThrows(IllegalStateException::class.java) { pendingUploadBatchIdentity(db, server, EncryptedPayloadType.SENSOR) }
    }

    @Test fun sensorFailureBlocksTheSensorFamilyAndRetainsItsRows() {
        db.sensorSampleDao().insertAll(listOf(sample("active", "accelerometer")))
        var requests = 0
        val delegate = uploader {
            requests++
            throw retrofit2.HttpException(retrofit2.Response.error<Any>(java.net.HttpURLConnection.HTTP_UNAUTHORIZED,
                "synthetic refusal".toResponseBody()))
        }
        assertEquals(1, delegate.execute())
        assertFalse(UploadRetryGate.shouldAttempt(context, server, LocalUploadModuleFamily.SENSOR, db))
        delegate.execute()
        assertEquals(1, requests)
        assertEquals(1, db.sensorSampleDao().count())
    }

    @Test fun transientSensorFailureBacksOffOnlyUntilItsDeadline() {
        db.sensorSampleDao().insertAll(listOf(sample("active", "accelerometer")))
        assertEquals(1, uploader {
            throw retrofit2.HttpException(retrofit2.Response.error<Any>(java.net.HttpURLConnection.HTTP_UNAVAILABLE,
                "synthetic outage".toResponseBody()))
        }.execute())
        assertFalse(UploadRetryGate.shouldAttempt(context, server, LocalUploadModuleFamily.SENSOR, db))
        assertTrue(UploadRetryGate.shouldAttempt(context, server, LocalUploadModuleFamily.SENSOR, db, Long.MAX_VALUE))
    }
    @Test fun mixedReceiptDeletesOnlyHandledRowsAndDoesNotResendHeldRows() {
        val rows = listOf(sample("accepted", "accelerometer"), sample("discarded", "gyroscope"), sample("held", "light"))
        db.sensorSampleDao().insertAll(rows)
        var requests = 0
        val delegate = receiptUploader { samples ->
            requests++
            if (requests > 1) return@receiptUploader SensorUploadReceipt(samples.mapTo(linkedSetOf()) { it.id }, emptySet(), emptySet(), 2)
            SensorUploadReceipt(samples.filter { it.sensor == com.openlattice.chronicle.android.AndroidSensorType.accelerometer }.mapTo(linkedSetOf(
                )) { it.id },
                samples.filter { it.sensor == com.openlattice.chronicle.android.AndroidSensorType.gyroscope }.mapTo(linkedSetOf()) { it.id },
                samples.filter { it.sensor == com.openlattice.chronicle.android.AndroidSensorType.light }.mapTo(linkedSetOf()) { it.id }, 2)
        }
        assertEquals(0, delegate.execute())
        assertEquals(listOf(rows.last()), db.sensorSampleDao().getOldest(SENSOR_UPLOAD_BATCH_SIZE))
        assertEquals(0, delegate.execute())
        assertEquals(1, requests)
        assertEquals(2, db.uploadQueueEvidenceDao().get(EncryptedPayloadType.SENSOR.name, listOf(rows.last().id)).single().heldVersion)
        db.uploadQueueEvidenceDao().releaseHeldThrough(1)
        assertEquals(0, delegate.execute())
        assertEquals(1, requests)
        db.uploadQueueEvidenceDao().releaseHeldThrough(2)
        assertEquals(0, delegate.execute())
        assertEquals(2, requests)
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun incompleteReceiptCannotDeleteAnySubmittedRow() {
        val rows = listOf(sample("accepted", "accelerometer"), sample("unknown", "gyroscope"))
        db.sensorSampleDao().insertAll(rows)
        assertEquals(1, receiptUploader { samples ->
            SensorUploadReceipt(setOf(samples.first().id), emptySet(), emptySet(), 1)
        }.execute())
        assertEquals(rows.sortedBy { it.id }, db.sensorSampleDao().getOldest(SENSOR_UPLOAD_BATCH_SIZE))
    }

    @Test fun historicalQueueVersionSurvivesSettingsEditsAndFrozenRetry() {
        fun state(version: Int) = CollectionModuleStateEntity(CollectionModuleId.SENSOR_ACCELEROMETER.id,
            true, ParticipantDecision.ACCEPTED.name, 0, false, version, null, null)
        val dao = db.sensorSampleDao()
        val first = sample("a", "accelerometer")
        val second = sample("b", "accelerometer")
        db.collectionModuleStateDao().upsertAll(listOf(state(1)))
        dao.insertAll(listOf(first))
        db.collectionModuleStateDao().upsertAll(listOf(state(2)))
        dao.insertAll(listOf(second))
        fun select() = selectPendingUploadBatch(db, server, EncryptedPayloadType.SENSOR,
            loadNew = { dao.getOldest(SENSOR_UPLOAD_BATCH_SIZE) }, loadByIds = dao::getByIds, idOf = { it.id })
        assertEquals(listOf(first.id), select().map { it.id })
        db.collectionModuleStateDao().upsertAll(listOf(state(3)))
        assertEquals(listOf(first.id), select().map { it.id })
        assertEquals(1, com.openlattice.chronicle.services.upload.pendingUploadBatchVersion(db, server, EncryptedPayloadType.SENSOR))
        db.runInTransaction {
            dao.deleteByIds(listOf(first.id))
            com.openlattice.chronicle.services.upload.acknowledgePendingUploadBatch(db, server, EncryptedPayloadType.SENSOR, context)
        }
        assertEquals(listOf(second.id), select().map { it.id })
        assertEquals(2, com.openlattice.chronicle.services.upload.pendingUploadBatchVersion(db, server, EncryptedPayloadType.SENSOR))
    }

}
