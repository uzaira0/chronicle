package com.openlattice.chronicle.services.sensors

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.util.UUID
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SensorUploadWorkerDelegateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity
    private val posts = mutableListOf<List<AndroidSensorSample>>()

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("sensor-uploader-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        EnrollmentTestStores.db = db
        val id = db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111", participantId = "p1", sourceRandomDeviceId = "d1"))
        server = db.uploadServerDao().getById(id)!!
    }

    @After fun tearDown() {
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    private fun sample(id: String, type: String = "accelerometer") = SensorSampleEntry(UUID.nameUUIDFromBytes(id.toByteArray()).toString(),
        type, "2026-10-01T00:00:00Z", "UTC", 1f, 2f, 3f, null, 3)

    private fun ids(samples: List<AndroidSensorSample>) = samples.mapTo(linkedSetOf()) { it.id }

    private fun uploader(respond: (List<AndroidSensorSample>) -> UploadReceipt = {
        UploadReceipt(ids(it), emptySet(), 1)
    }): SensorUploadWorkerDelegate {
        val api = Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
            arrayOf(RestrictedChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidSensorDataReceipt")
            val samples = decodedUploadBody<List<AndroidSensorSample>>(args!![3])
            posts += samples
            respond(samples)
        } as RestrictedChronicleStudyApi
        return SensorUploadWorkerDelegate(context, db, restrictedApiFor = { api })
    }

    private fun httpError(status: Int): Nothing =
        throw retrofit2.HttpException(retrofit2.Response.error<Any>(status, "refused".toResponseBody()))

    @Test fun backlogDrainsPastTheFirstBatchInOneRun() {
        db.sensorSampleDao().insertAll((0..SENSOR_UPLOAD_BATCH_SIZE).map { sample("row-$it") })
        assertEquals(0, uploader().execute())
        assertEquals(listOf(SENSOR_UPLOAD_BATCH_SIZE, 1), posts.map { it.size })
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun aStudyDisabledSensorsQueuedRowsStillUploadForTheServerToDecide() {
        val disabled = sample("disabled", "gyroscope")
        val active = sample("active")
        db.sensorSampleDao().insertAll(listOf(disabled, active))
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            CollectionModuleId.SENSOR_GYROSCOPE.id, false, ParticipantDecision.ACCEPTED.name,
            0, false, 2)))

        assertEquals(0, uploader().execute())

        assertEquals(setOf(disabled.id, active.id), posts.flatten().mapTo(mutableSetOf()) { it.id.toString() })
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun credentialRefusalRetainsTheRowsAndTheNextRunRetries() {
        db.sensorSampleDao().insertAll(listOf(sample("active")))
        val delegate = uploader { httpError(HttpURLConnection.HTTP_UNAUTHORIZED) }
        assertEquals(1, delegate.execute())
        assertEquals(1, delegate.execute())
        assertEquals(2, posts.size)
        assertEquals(1, db.sensorSampleDao().count())
    }

    @Test fun mixedReceiptDeletesStoredAndDiscardedRowsWithoutResending() {
        val rows = listOf(sample("accepted"), sample("discarded", "gyroscope"))
        db.sensorSampleDao().insertAll(rows)
        val delegate = uploader { samples ->
            fun of(type: AndroidSensorType) = ids(samples.filter { it.sensor == type })
            UploadReceipt(of(AndroidSensorType.accelerometer), of(AndroidSensorType.gyroscope), 2)
        }
        assertEquals(0, delegate.execute())
        assertEquals(0, db.sensorSampleDao().count())
        assertEquals(0, delegate.execute())
        assertEquals(1, posts.size)
    }

    @Test fun incompleteReceiptCannotDeleteAnySubmittedRow() {
        val rows = listOf(sample("accepted"), sample("unknown", "gyroscope"))
        db.sensorSampleDao().insertAll(rows)
        assertEquals(1, uploader { samples -> UploadReceipt(setOf(samples.first().id), emptySet(), 1) }.execute())
        assertEquals(rows.sortedBy { it.id }, db.sensorSampleDao().getOldest(SENSOR_UPLOAD_BATCH_SIZE))
    }

    @Test fun acceptedBatchAndCountersCommitTogetherAcrossRetry() {
        db.sensorSampleDao().insertAll(listOf(sample("only")))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE INSERT ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )

        assertThrows(SQLiteConstraintException::class.java) { uploader().execute() }

        assertEquals("an incomplete acknowledgment keeps the row", 1, db.sensorSampleDao().count())
        assertEquals(0, db.uploadServerDao().getById(server.id)!!.sensorUploadSuccessCount)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        assertEquals(0, uploader().execute())
        assertEquals(0, db.sensorSampleDao().count())
        assertEquals(1, db.uploadServerDao().getById(server.id)!!.sensorUploadSuccessCount)
        assertEquals(1, db.uploadStatsDao().getRecentStats(server.id, 1).single().sensorSamplesUploaded)
        assertEquals("the retry carries the same sample identity", ids(posts[0]), ids(posts[1]))
    }

    @Test fun malformedSampleMovesToQuarantineWithoutBlockingItsSibling() {
        val malformed = sample("malformed").copy(timestamp = "not-a-timestamp")
        val valid = sample("valid")
        db.sensorSampleDao().insertAll(listOf(malformed, valid))

        assertEquals(0, uploader().execute())

        assertEquals(listOf(listOf(valid.toAndroidSensorSample())), posts)
        assertEquals(0, db.sensorSampleDao().count())
        assertEquals(1, db.localDataQuarantineDao().count(UploadQueueTable.SENSOR.tableName))
        val diagnostic = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").single()
        assertEquals(LocalUploadModuleFamily.SENSOR.name, diagnostic.moduleFamily)
        assertEquals(LocalOperationalIssue.SAMPLE_QUARANTINED.name, diagnostic.issueCode)
    }
}
