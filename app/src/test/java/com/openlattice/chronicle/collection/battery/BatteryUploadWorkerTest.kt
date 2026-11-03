package com.openlattice.chronicle.collection.battery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The periodic battery-telemetry upload of an enrolled pilot phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatteryUploadWorkerTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var serverDown = false
    private val uploaded = mutableListOf<String>()

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        EnrollmentTestStores.api = studyApi { method, args ->
            when (method) {
                "getDeviceParticipationStatus" ->
                    MobileParticipationStatus(ParticipationStatus.ENROLLED, OffsetDateTime.now(ZoneOffset.UTC), emptyList())
                "uploadAndroidBatteryData" -> {
                    if (serverDown) throw ChronicleCallException("POST", "request", "", HttpURLConnection.HTTP_UNAVAILABLE)
                    val samples = decodedUploadBody<List<BatterySample>>(args!![3])
                    uploaded += samples.map { it.id }
                    UploadReceipt(samples.mapTo(linkedSetOf()) { UUID.fromString(it.id) }, emptySet(), 0)
                }
                else -> error("unexpected API $method")
            }
        }
        onPersistenceWorker {
            ChronicleDb.getInstance(context).batterySampleDao().insertAll(listOf(BatterySampleEntry(
                id = SAMPLE, timestamp = "2026-10-30T15:00:00.000Z", timezone = "America/Santiago", levelPercent = 37,
                chargingState = "DISCHARGING", plugType = "UNPLUGGED", temperatureDeciC = 291, voltageMillivolts = 3812,
                health = "GOOD",
            )))
        }
    }

    @After fun close() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.api = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun run(attempt: Int = 0) =
        onPersistenceWorker { BatteryUploadWorker(context, auditWorkerParameters(attempt)).doWork() }
    private fun queued() = onPersistenceWorker { ChronicleDb.getInstance(context).batterySampleDao().count() }
    private fun server() = onPersistenceWorker { ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!! }

    /**
     * The study server is down: the sample stays queued and the outage is counted, WorkManager retries until its
     * budget, then the run gives up for this period with the sample still queued; the next run after recovery sends it.
     */
    @Test fun anOutageKeepsTheSampleRetriesWithinTheBudgetAndTheRecoveredRunDeliversIt() {
        serverDown = true

        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(1, queued())
        assertEquals(1, server().batteryConsecutiveFailures)
        assertEquals(ListenableWorker.Result.failure(), run(attempt = 6))
        assertEquals(1, queued())

        serverDown = false
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, queued())
        assertEquals(listOf(SAMPLE), uploaded)
    }

    /** The combined upload is draining the battery queue: this run defers without posting the same sample twice. */
    @Test fun aRunThatFindsTheQueueBeingDrainedDefersWithoutSending() {
        assertTrue(UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME))
        try {
            assertEquals(ListenableWorker.Result.retry(), run())
            assertTrue(uploaded.isEmpty())
            assertEquals(1, queued())
        } finally { UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME) }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf(SAMPLE), uploaded)
    }

    private companion object {
        const val SAMPLE = "00000000-0000-0000-0000-0000000000b1"
    }
}
