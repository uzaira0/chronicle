package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.collection.sink.FakeSensorSampleDao
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsPersistence
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.SensorSampleDeliveryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.SensorUploadReceipt
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.study.StudyEncryptionSetting
import java.lang.reflect.Proxy
import java.util.UUID
import java.time.LocalDate
import java.time.OffsetDateTime

/** Dead-lettered sensor samples must reach the operator, not only logcat. */
@RunWith(RobolectricTestRunner::class)
class SensorUploadWorkerDelegateTest {
    private val unusedStudyApi = Proxy.newProxyInstance(
        ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java),
    ) { _, method, _ -> error("plaintext study must not call ${method.name}") } as ChronicleStudyApi

    private fun acceptedSensorApi(onUpload: (Int) -> Unit = {}): RestrictedChronicleStudyApi = Proxy.newProxyInstance(
        RestrictedChronicleStudyApi::class.java.classLoader, arrayOf(RestrictedChronicleStudyApi::class.java),
    ) { _, method, args ->
        check(method.name == "uploadAndroidSensorDataReceipt") { "unexpected call ${method.name}" }
        @Suppress("UNCHECKED_CAST") val samples = args[4] as List<AndroidSensorSample>
        onUpload(samples.size)
        SensorUploadReceipt(samples.mapTo(linkedSetOf()) { it.id }, emptySet(), emptySet(), 1)
    } as RestrictedChronicleStudyApi

    @Test fun successfulAndAlreadyAcknowledgedBatchesDrainPastTheFirstBatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestStores.install(context, enrolled = true)
        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getConfiguredServer()!!
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), StudyEncryptionSetting(enabled = false))
        val uploads = mutableListOf<Int>()
        val accepted = acceptedSensorApi { uploads += it }
        val delegate = SensorUploadWorkerDelegate(
            context, db,
            studyApiFor = { unusedStudyApi },
            restrictedApiFor = { accepted },
        )

        for (alreadyAcknowledged in listOf(false, true)) {
            val samples = (0..SENSOR_UPLOAD_BATCH_SIZE).map { validSample(UUID.randomUUID().toString()) }
            db.sensorSampleDao().insertAll(samples)
            if (alreadyAcknowledged) {
                db.sensorSampleDao().insertDeliveries(samples.map {
                    SensorSampleDeliveryEntity(it.id, server.id, server.sensorDeliveryGeneration, OffsetDateTime.now().toString())
                })
            }
            assertEquals(0, delegate.execute())
            assertEquals("deletion count must allow the drain to reach the next batch", 0, db.sensorSampleDao().count())
        }
        assertEquals(listOf(SENSOR_UPLOAD_BATCH_SIZE, 1), uploads)
    }

    @Test fun acceptedSensorBatchWithStatsFailureIsAcknowledgedButReportedAsFailedWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestStores.install(context, enrolled = true)
        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getConfiguredServer()!!
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), StudyEncryptionSetting(enabled = false))
        db.sensorSampleDao().insertAll(listOf(validSample(UUID.randomUUID().toString())))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE INSERT ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )
        val accepted = acceptedSensorApi()

        val failed = SensorUploadWorkerDelegate(context, db,
            studyApiFor = { unusedStudyApi },
            restrictedApiFor = { accepted },
        ).execute()

        assertEquals("server-accepted rows are acknowledged", 0, db.sensorSampleDao().count())
        assertEquals("the lost stats write is still failed work", 1, failed)
    }

    private class MemoryPersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()
        override fun load() = buckets
        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }

    private fun sample(id: String) = SensorSampleEntry(
        id = id,
        sensorType = "not-a-sensor",
        timestamp = "not-a-timestamp",
        timezone = "UTC",
        x = null, y = null, z = null, w = null,
        accuracy = null,
        valuesJson = "broken",
    )

    private fun validSample(id: String) = sample(id).copy(
        sensorType = "accelerometer", timestamp = "2026-09-24T10:00:00Z", valuesJson = null,
    )

    @Test
    fun malformedSamplesProduceAPendingSensorDiagnosticBucket() {
        val dao = FakeSensorSampleDao().apply { insertAll(listOf(sample("a"), sample("b"))) }
        val persistence = MemoryPersistence()
        val store = LocalUploadDiagnosticsStore(persistence)

        quarantineMalformedSensorSamples(
            dao,
            store,
            listOf(
                sample("a") to IllegalArgumentException("secret-bearing message"),
                sample("b") to IllegalStateException(),
            ),
        )

        assertEquals(2, dao.deadLetters.size)
        val bucket = store.pending(LocalDate.now()).single()
        assertEquals("SENSOR", bucket.moduleFamily)
        assertEquals("SENSOR_SAMPLE_QUARANTINED", bucket.issue)
        assertEquals(2, bucket.count)
        assertNull("no exception text leaves the device", bucket.errorType)
        assertEquals(1, store.toWireEvents(listOf(bucket)).size)
    }

}
