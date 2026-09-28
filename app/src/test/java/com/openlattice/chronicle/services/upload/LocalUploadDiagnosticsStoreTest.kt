package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.directboot.InMemorySharedPreferences
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleCallException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.http.GET
import java.time.LocalDate
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalUploadDiagnosticsStoreTest {
    private interface RejectingApi {
        @GET("rejected")
        fun rejected(): ResponseBody
    }

    @Test
    fun realCallAdapterHttp400IsClassifiedAndParkable() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(400).message("Bad Request").body("unsupported diagnostic".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addCallAdapterFactory(ChronicleCallAdapterFactory()).build().create(RejectingApi::class.java)
        val error = runCatching { api.rejected() }.exceptionOrNull() as Exception
        assertTrue(error.toString(), error is ChronicleCallException)
        assertEquals(400, uploadHttpStatus(error))
        val store = LocalUploadDiagnosticsStore(FakePersistence())

        store.recordFailure(LocalUploadModuleFamily.LOCAL_STORE, error)

        val bucket = store.pending(LocalDate.now()).single()
        assertEquals(400, bucket.httpStatus)
        assertEquals("HTTP_CLIENT_ERROR", bucket.issue)
    }

    @Test
    fun deliveredReplayCursorWaitsForCompleteServerAcknowledgment() {
        val prefs = InMemorySharedPreferences()
        val selection = DeliveredReplaySelection("2026-09-28", 500, setOf("a", "b"))
        val offset = "local_upload_delivered_replay_offset"
        val day = "local_upload_delivered_replay_day"

        assertFalse(acknowledgeDeliveredReplaySelection(prefs, selection, selection.ids, setOf("a")))
        assertEquals(0, prefs.getInt(offset, 0))
        assertEquals(null, prefs.getString(day, null))
        assertTrue(acknowledgeDeliveredReplaySelection(prefs, selection, selection.ids, selection.ids))
        assertEquals(500, prefs.getInt(offset, 0))
        assertEquals("2026-09-28", prefs.getString(day, null))
    }

    @Test
    fun parkedTiersAreSubmittedOldestFirstInSeparateBatches() {
        val v106 = LocalUploadIssueBucket("2026-09-27", "SENSOR", "SENSOR_AGE_EXPIRED", 1)
        val v104 = LocalUploadIssueBucket("2026-09-27", "APP_RUNTIME", "APP_CRASH", 1)
        val v107 = LocalUploadIssueBucket("2026-09-27", "LOCAL_STORE", "LOCAL_WRITE_FAILED", 1)
        val batches = diagnosticsUploadBatches(emptyList(), listOf(v107, v106, v104), emptyList())
        assertEquals(listOf(listOf(v104), listOf(v106), listOf(v107)), batches)
    }

    @Test
    fun closedDestinationIssuePersistsAcrossLaterSuccessfulRuns() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")

        store.record(
            LocalUploadModuleFamily.USAGE_LIFECYCLE,
            UploadDestinationIssue.DESTINATION_MISSING,
            day,
        )
        store.record(
            LocalUploadModuleFamily.USAGE_LIFECYCLE,
            UploadDestinationIssue.DESTINATION_MISSING,
            day,
        )

        val bucket = store.recent(today = day).single()
        assertEquals("2026-08-22", bucket.day)
        assertEquals("USAGE_LIFECYCLE", bucket.moduleFamily)
        assertEquals("DESTINATION_MISSING", bucket.issue)
        assertEquals(2, bucket.count)
        assertTrue(runCatching { java.util.UUID.fromString(bucket.id) }.isSuccess)
    }

    @Test
    fun legacyServerRejectionParksAndRetriesTheNewerCodes() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.now()
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.recordOperational(LocalUploadModuleFamily.APP_RUNTIME, LocalOperationalIssue.APP_CRASH)

        store.parkUnsupportedByLegacyServer(store.pending(day).mapTo(mutableSetOf()) { it.id })

        assertEquals(listOf("DESTINATION_MISSING"), store.pending(day).map { it.issue })
        assertEquals(listOf("APP_CRASH"), store.parked().map { it.issue })
        store.acknowledge(store.parked().mapTo(hashSetOf()) { it.id })
        assertTrue(store.parked().isEmpty())
        assertEquals(listOf("APP_CRASH"), store.deliveredReplay().map { it.issue })
        assertEquals(2, store.recent(today = day).size)
    }

    @Test
    fun v104ServerRejectionParksBothUnsupportedTiersWithoutErasure() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.now()
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.recordOperational(LocalUploadModuleFamily.APP_RUNTIME, LocalOperationalIssue.APP_CRASH)
        store.recordOperational(LocalUploadModuleFamily.SENSOR, LocalOperationalIssue.SENSOR_AGE_EXPIRED, 40)

        // A 2026.9.25 (V104) server rejects the batch: only the post-V104 code goes.
        store.parkUnsupportedByLegacyServer(store.pending(day).mapTo(mutableSetOf()) { it.id })
        assertEquals(setOf("DESTINATION_MISSING", "APP_CRASH"), store.pending(day).mapTo(hashSetOf()) { it.issue })

        // A pre-V104 server rejects again: then the V104 tier goes too.
        store.parkUnsupportedByLegacyServer(store.pending(day).mapTo(mutableSetOf()) { it.id })
        assertEquals(listOf("DESTINATION_MISSING"), store.pending(day).map { it.issue })
        assertEquals(setOf("APP_CRASH", "SENSOR_AGE_EXPIRED"), store.parked().mapTo(hashSetOf()) { it.issue })
    }

    @Test
    fun historyIsNeverPrunedByReadingARecentWindow() {
        val persistence = FakePersistence().apply {
            buckets = listOf(
                LocalUploadIssueBucket("2026-08-22", "BATTERY", "DESTINATION_DISABLED", 1),
                LocalUploadIssueBucket("2026-07-01", "BATTERY", "DESTINATION_DISABLED", 4),
                LocalUploadIssueBucket("2026-08-22", "UNKNOWN", "DESTINATION_DISABLED", 3),
                LocalUploadIssueBucket("2026-08-22", "BATTERY", "raw-error-text", 5),
                LocalUploadIssueBucket("2026-08-23", "BATTERY", "DESTINATION_DISABLED", 6),
            )
        }
        val store = LocalUploadDiagnosticsStore(persistence)

        assertEquals(3, store.recent(today = LocalDate.parse("2026-08-22")).size)
        assertEquals(5, persistence.buckets.size)
        store.clear()
        assertTrue(store.recent(today = LocalDate.parse("2026-08-22")).isEmpty())
    }

    @Test
    fun localHistoryHasNoBucketCapAndUploadUsesBatches() {
        val day = LocalDate.parse("2026-08-22")
        val persistence = FakePersistence().apply {
            buckets = List(600) { index ->
                LocalUploadIssueBucket(
                    day = day.toString(),
                    moduleFamily = "BATTERY",
                    issue = "UPLOAD_FAILURE",
                    count = 1,
                    errorType = "Failure$index",
                )
            }
        }
        val store = LocalUploadDiagnosticsStore(persistence)

        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)

        assertEquals(601, persistence.buckets.size)
        assertEquals(500, store.pending(day).size)
    }

    @Test
    fun acknowledgmentMarksOnlySubmittedAggregatesDelivered() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.record(LocalUploadModuleFamily.DEVICE_TELEMETRY, UploadDestinationIssue.DESTINATION_MISSING, day)

        val pending = store.pending(today = day)
        store.acknowledge(setOf(pending.first().id))

        val remaining = store.pending(today = day)
        assertEquals(1, remaining.size)
        assertFalse(remaining.single().id == pending.first().id)
        assertEquals(2, persistence.buckets.size)
        assertEquals("DELIVERED", persistence.buckets.first { it.id == pending.first().id }.deliveryState)
        assertEquals(listOf(pending.first().id), store.deliveredReplay().map { it.id })
    }

    @Test
    fun incrementAfterSealingSurvivesAcknowledgment() {
        val store = LocalUploadDiagnosticsStore(FakePersistence())
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        val submitted = store.pending(day).single()
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.acknowledge(setOf(submitted.id))

        val next = store.pending(day).single()
        assertFalse(submitted.id == next.id)
        assertEquals(1, next.count)
        assertEquals(2, store.recent(today = day).sumOf { it.count })
    }

    @Test
    fun simultaneousIncrementAndAcknowledgmentPreserveBothOccurrences() {
        val store = LocalUploadDiagnosticsStore(FakePersistence())
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        val submitted = store.pending(day).single()
        val start = CountDownLatch(1)
        val increment = thread { start.await(); store.record(
            LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day,
        ) }
        val acknowledge = thread { start.await(); store.acknowledge(setOf(submitted.id)) }
        start.countDown()
        increment.join()
        acknowledge.join()

        assertEquals("DELIVERED", store.recent(today = day).first { it.id == submitted.id }.deliveryState)
        assertEquals(1, store.pending(day).single().count)
        assertEquals(2, store.recent(today = day).sumOf { it.count })
    }

    @Test
    fun malformedBucketIsPreservedOutsideThePendingWindow() {
        val bucket = LocalUploadIssueBucket("bad-day", "SENSOR", "SAMPLE_QUARANTINED", 1)
        val persistence = FakePersistence().apply { buckets = listOf(bucket) }
        val store = LocalUploadDiagnosticsStore(persistence)

        store.quarantineMalformed(setOf(bucket.id))

        assertTrue(store.pending().isEmpty())
        assertEquals("QUARANTINED", persistence.buckets.single().deliveryState)
    }

    @Test
    fun failureDetailsAreClassifiedWithoutPersistingFreeFormText() {
        assertEquals("TIMEOUT", classifyUploadFailure(SocketTimeoutException("late")))
        val fields = LocalUploadIssueBucket::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertFalse("errorMessage" in fields)
        assertFalse("serverOrigin" in fields)
    }

    private class FakePersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()

        override fun load(): List<LocalUploadIssueBucket> = buckets

        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }
}
