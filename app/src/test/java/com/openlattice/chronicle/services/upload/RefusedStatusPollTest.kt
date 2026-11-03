package com.openlattice.chronicle.services.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentRecoveryManager
import com.openlattice.chronicle.EnrollmentRecoveryResult
import com.openlattice.chronicle.EnrollmentSubmission
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.reserveEnrollment
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.ui.DashboardDataRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A server that cannot serve this app version refuses the status step every upload worker runs first, so no queue is
 * ever read. Each refused run keeps every row, asks WorkManager for a backed-off retry, and counts as a failed upload,
 * so the participant's status says uploads keep failing and to contact the study team instead of the last "healthy".
 */
@RunWith(RobolectricTestRunner::class)
class RefusedStatusPollTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private val requests = mutableListOf<String>()

    @get:Rule val persistence = BackgroundPersistenceRule()

    /** A real enrollment: reserved, sent, issued and acknowledged, then one usage row waiting for upload. */
    @Before fun enroll() {
        db = EnrollmentTestStores.install(context)
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        reserveEnrollment(context, EnrollmentSubmission(
            preview = preview,
            partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            serverUrl = preview.manifest.serverOrigin,
            enrollmentAccessCode = "refused-status-invitation",
        ))
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> OK()
                else -> error("unexpected API $method")
            }
        }).use { assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context)) }
        db.queueEntryData().insertEntry(QueueEntry(0, 1, JsonSerializer.serializeQueueEntry(emptyList())))
    }

    @After fun close() { db.close() }

    private fun run(failure: Exception): ListenableWorker.Result {
        EnrollmentTestStores.api = studyApi { method, _ -> requests += method; throw failure }
        return runEnrolledUpload(context, "test") { error("no upload runs without an applied status") }
    }

    private fun server() = db.uploadServerDao().getAll().single()

    @Test fun aRefusedStatusStepKeepsTheRowsRetriesAndReadsAsFailingUploads() {
        val refused = ChronicleCallException("GET", "request", "", HttpURLConnection.HTTP_BAD_REQUEST)
        assertEquals(ListenableWorker.Result.retry(), run(refused))
        assertEquals("one request per run: no hot loop", listOf("getDeviceParticipationStatus"), requests)
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals(1, server().consecutiveFailures)
        assertTrue(LocalUploadDiagnosticsStore.of(context).pending().any { it.httpStatus == HttpURLConnection.HTTP_BAD_REQUEST })
        repeat(10) { assertEquals(ListenableWorker.Result.retry(), run(refused)) }
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals(11, server().consecutiveFailures)
        assertTrue(server().enabled)
        assertEquals(context.getString(R.string.server_health_uploads_failing),
            DashboardDataRepository.loadServerHealth(context, listOf(server()), online = true).message)
    }

    /** No answer from the server says nothing about it; the dashboard's offline state covers this. */
    @Test fun aStatusStepWithoutAnAnswerCountsNothing() {
        assertEquals(ListenableWorker.Result.retry(), run(IOException("offline")))
        assertEquals(0, server().consecutiveFailures)
        assertEquals(1, db.queueEntryData().getSize())
    }

    @Test fun aLateRefusalDoesNotCountAgainstAReplacementEnrollment() {
        EnrollmentTestStores.api = studyApi { _, _ ->
            db.uploadServerDao().update(server().copy(sourceRandomDeviceId = "replacement-device"))
            throw ChronicleCallException("GET", "request", "", HttpURLConnection.HTTP_FORBIDDEN)
        }
        assertEquals(ListenableWorker.Result.retry(), runEnrolledUpload(context, "test") { error("unexpected upload") })
        assertEquals(0, server().consecutiveFailures)
        assertTrue(LocalUploadDiagnosticsStore.of(context).pending().isEmpty())
    }

    @Test fun simultaneousRefusedPollsCountEveryFailure() {
        val parallelWorkers = 2
        val attemptsPerWorker = 10
        val attempts = parallelWorkers * attemptsPerWorker
        // A refusal is counted inside its poll's turn: the next poll, from either worker, sees every earlier refusal.
        val polls = AtomicInteger()
        val behind = AtomicInteger()
        EnrollmentTestStores.api = studyApi { _, _ ->
            if (server().consecutiveFailures != polls.getAndIncrement()) behind.incrementAndGet()
            throw ChronicleCallException("GET", "request", "", HttpURLConnection.HTTP_BAD_REQUEST)
        }
        val workers = Executors.newFixedThreadPool(parallelWorkers)
        try {
            val results = List(parallelWorkers) {
                workers.submit {
                    repeat(attemptsPerWorker) {
                        assertEquals(ListenableWorker.Result.retry(),
                            runEnrolledUpload(context, "test") { error("unexpected upload") })
                    }
                }
            }
            results.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals("a poll started before the previous refusal was counted", 0, behind.get())
            assertEquals(attempts, server().usageUploadFailureCount)
            assertEquals(attempts, server().consecutiveFailures)
            assertEquals(1, db.queueEntryData().getSize())
        } finally { workers.shutdownNow() }
    }
}
