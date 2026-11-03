package com.openlattice.chronicle.services.sensors

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sensor too slow to fill a flush batch still reaches the server on the upload schedule, not only in RAM. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LowRateSensorFlushTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        EnrollmentTestStores.prefs = context.getSharedPreferences("low-rate-sensor-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111", participantId = "p1", sourceRandomDeviceId = "d1"))
    }

    @After fun tearDown() {
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun aBufferedLowRateSampleIsUploadedByTheNextSensorUploadRun() {
        val runtime = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            SensorSampleSink(db.sensorSampleDao()), ManualSensorRuntimeScheduler(executeImmediately = false))
        runtime.recordSample(AndroidSensorType.proximity, floatArrayOf(5f), 3)
        assertEquals(1, runtime.bufferedCount)
        val posts = mutableListOf<List<AndroidSensorSample>>()
        val api = Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
            arrayOf(RestrictedChronicleStudyApi::class.java)) { _, _, args ->
            val samples = decodedUploadBody<List<AndroidSensorSample>>(args!![3])
            posts += samples
            UploadReceipt(samples.mapTo(linkedSetOf()) { it.id }, emptySet(), 1)
        } as RestrictedChronicleStudyApi

        assertEquals(0, SensorUploadWorkerDelegate(context, db, restrictedApiFor = { api }).execute())

        assertEquals(listOf(1), posts.map { it.size })
        assertEquals(0, runtime.bufferedCount)
    }
}
