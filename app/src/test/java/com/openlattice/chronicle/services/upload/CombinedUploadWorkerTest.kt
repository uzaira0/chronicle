package com.openlattice.chronicle.services.upload

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.battery.BATTERY_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
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

/** The periodic pilot upload: usage, the battery sample and diagnostics in one run against the study server. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CombinedUploadWorkerTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    /** Which upload routes the server refuses; the others accept every row they receive. */
    private val unavailable = Collections.synchronizedSet(mutableSetOf<String>())
    private var unavailableStatus = HttpURLConnection.HTTP_UNAVAILABLE

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        EnrollmentTestStores.api = studyApi { method, args ->
            calls += method
            when {
                method == "getDeviceParticipationStatus" ->
                    MobileParticipationStatus(ParticipationStatus.ENROLLED, OffsetDateTime.now(ZoneOffset.UTC), emptyList())
                method in unavailable -> throw ChronicleCallException("POST", "request", "", unavailableStatus)
                method == "uploadAndroidUsageEventData" -> acceptAll(args!![3], "clientEventId")
                method == "uploadAndroidBatteryData" || method == "uploadAndroidUploadDiagnostics" -> acceptAll(args!![3], "id")
                else -> error("unexpected API $method")
            }
        }
        db().queueEntryData().insertEntry(QueueEntry(1_000, 1, JsonSerializer.serializeQueueEntry(listOf(ExtractedUsageEvent(
            eventType = fromInteractionType("Activity Resumed"), appPackageName = "cl.banco.app",
            interactionType = "Activity Resumed", timestamp = OffsetDateTime.parse("2026-10-30T09:15:00-03:00"),
            timezone = "America/Santiago", user = "Study participant", applicationLabel = "Banco",
            clientEventId = UUID.randomUUID(),
        )))))
        db().batterySampleDao().insertAll(listOf(BatterySampleEntry(id = UUID.randomUUID().toString(),
            timestamp = "2026-10-30T12:15:00.000Z", timezone = "America/Santiago", levelPercent = 64,
            chargingState = "DISCHARGING", plugType = "UNPLUGGED", temperatureDeciC = 301, voltageMillivolts = 3900,
            health = "GOOD")))
    }

    @After fun close() {
        UploadQueueSingleFlight.release(COMBINED_UPLOAD_WORK_NAME)
        UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME)
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.api = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun db() = ChronicleDb.getInstance(context)
    private fun server() = onPersistenceWorker { db().uploadServerDao().getConfiguredServer()!! }
    private fun queuedUsage() = onPersistenceWorker { db().queueEntryData().getSize() }
    private fun queuedBattery() = onPersistenceWorker { db().batterySampleDao().getOldest(Int.MAX_VALUE).size }
    private fun todaysStats() = onPersistenceWorker { db().uploadStatsDao().getRecentStats(server().id, 1).single() }

    private fun acceptAll(body: Any?, idField: String): UploadReceipt =
        UploadReceipt(decodedUploadBody<List<Map<String, Any?>>>(body).mapTo(linkedSetOf()) {
            UUID.fromString(it.getValue(idField) as String)
        }, emptySet(), 0)

    private fun worker() = onPersistenceWorker { CombinedUploadWorker(context, auditWorkerParameters()).doWork() }

    /**
     * The study server is down (503) while the phone sends usage: the events stay queued, the outage is counted on the
     * enrollment and in today's stats, the destination stays enabled, and WorkManager retries; the retry after the
     * server recovers delivers them.
     */
    @Test fun aServerOutageKeepsTheUsageEventsCountsTheFailureAndTheRecoveredRetryDeliversThem() {
        unavailable += "uploadAndroidUsageEventData"

        assertEquals(ListenableWorker.Result.retry(), worker())
        assertEquals(1, queuedUsage())
        val failed = server()
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(1, failed.usageUploadFailureCount)
        assertTrue("an outage never disables the study destination", failed.enabled)
        assertEquals(1, todaysStats().usageUploadFailures)
        assertTrue("the battery sample was still sent", "uploadAndroidBatteryData" in calls)

        unavailable.clear()
        assertEquals(ListenableWorker.Result.success(), worker())
        assertEquals(0, queuedUsage())
        assertEquals(1, todaysStats().usageEventsUploaded)
    }

    @Test fun admissionSaturationRetriesAndKeepsUsageAfterTheAttemptBudgetEnds() {
        unavailableStatus = 429
        unavailable += "uploadAndroidUsageEventData"
        assertEquals(ListenableWorker.Result.retry(), worker())
        assertEquals(1, queuedUsage())
        assertTrue(server().enabled)
        assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { runCombinedUpload(context, 6) { false } })
        assertEquals(1, queuedUsage())
        unavailable.clear()
        assertEquals(ListenableWorker.Result.success(), worker())
        assertEquals(0, queuedUsage())
    }

    /**
     * "Upload now" while the periodic upload is still draining the queue: the second run defers (retry) and sends
     * nothing, so no batch is posted twice; once the first run lets go, the next run uploads.
     */
    @Test fun anUploadThatStartsWhileTheQueueIsBeingDrainedDefersWithoutSendingAnything() {
        assertTrue(UploadQueueSingleFlight.tryAcquire(COMBINED_UPLOAD_WORK_NAME))

        assertEquals(ListenableWorker.Result.retry(), worker())
        assertEquals(calls.toString(), listOf("getDeviceParticipationStatus"), calls.toList())
        assertEquals(1, queuedUsage())

        UploadQueueSingleFlight.release(COMBINED_UPLOAD_WORK_NAME)
        assertEquals(ListenableWorker.Result.success(), worker())
        assertEquals(0, queuedUsage())
    }

    /**
     * A battery route that keeps failing past the retry budget ends this period's run as failed, the battery rows stay
     * queued for the next period, and usage, which the server accepted, is not held back by it.
     */
    @Test fun aBatteryRouteFailingPastTheRetryBudgetGivesUpThisRunAndKeepsTheSample() {
        unavailable += "uploadAndroidBatteryData"

        assertEquals(ListenableWorker.Result.retry(), onPersistenceWorker { runCombinedUpload(context, 1) { false } })
        assertEquals(0, queuedUsage())
        assertEquals(1, queuedBattery())
        assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { runCombinedUpload(context, 6) { false } })
        assertEquals(1, queuedBattery())
    }
}
