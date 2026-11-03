package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.storage.DiagnosticDeliveryState
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleCallException
import java.lang.reflect.UndeclaredThrowableException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.http.GET

@RunWith(RobolectricTestRunner::class)
class LocalUploadDiagnosticsStoreTest {
    @Test
    fun errorTypeLabelsKeepWholeUnicodeCharactersWithinTheWireLimit() {
        val supplementary = "\uD83D\uDE00"
        val prefix = "A".repeat(127)
        assertEquals(prefix + supplementary, LocalUploadDiagnosticsStore.errorTypeLabel(prefix + supplementary + "Z"))
        assertEquals(supplementary.repeat(128), LocalUploadDiagnosticsStore.errorTypeLabel(supplementary.repeat(129)))
        assertEquals("A".repeat(128), LocalUploadDiagnosticsStore.errorTypeLabel("A".repeat(129)))
    }

    private interface RejectingApi {
        @GET("rejected")
        fun rejected(): ResponseBody
    }

    @Test
    fun realCallAdapterHttp400IsClassified() {
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

        val bucket = store.pending().single()
        assertEquals(400, bucket.httpStatus)
        assertEquals("HTTP_CLIENT_ERROR", bucket.issue)
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
        assertTrue(runCatching { UUID.fromString(bucket.id) }.isSuccess)
    }



    @Test
    fun historyIsNeverPrunedByReadingARecentWindow() {
        val persistence = FakePersistence().apply {
            buckets = listOf(
                LocalUploadIssueBucket("2026-08-22", "BATTERY", "DESTINATION_DISABLED", 1),
                LocalUploadIssueBucket("2026-07-01", "BATTERY", "DESTINATION_DISABLED", 4),
                LocalUploadIssueBucket("2026-08-22", "unsupported-test-family", "DESTINATION_DISABLED", 3),
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
        assertEquals(500, store.pending().size)
    }

    @Test
    fun receiptSettlesStoredRowsDeliveredAndRefusedRowsDiscarded() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.record(LocalUploadModuleFamily.DEVICE_TELEMETRY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.record(LocalUploadModuleFamily.SENSOR, UploadDestinationIssue.DESTINATION_MISSING, day)
        val (stored, refused, unsent) = store.pending().map { it.id }

        store.settle(UploadReceipt(setOf(UUID.fromString(stored)), setOf(UUID.fromString(refused)), 0))

        assertEquals(listOf(unsent), store.pending().map { it.id })
        val states = persistence.buckets.associate { it.id to it.deliveryState }
        assertEquals(DiagnosticDeliveryState.DELIVERED, states[stored])
        assertEquals(DiagnosticDeliveryState.DISCARDED, states[refused])
    }

    /** Rows are immutable, so a failed upload changes nothing and the retry resends exactly what was offered. */
    @Test
    fun aFailedUploadLeavesEveryOccurrenceForTheRetryAndTheDailySummaryCountsBoth() {
        val store = LocalUploadDiagnosticsStore(FakePersistence())
        val t0 = OffsetDateTime.parse("2026-08-22T15:00:00Z")
        store.recordFailure(LocalUploadModuleFamily.BATTERY, ConnectException(), LocalDate.parse("2026-08-22"), t0)
        val offered = store.pending()
        store.recordFailure(LocalUploadModuleFamily.BATTERY, ConnectException(), LocalDate.parse("2026-08-22"), t0.plusMinutes(15))

        val retry = store.pending()
        assertEquals(offered.single(), retry.first())
        assertEquals(listOf(1, 1), retry.map { it.count })
        assertEquals(2, store.recent(today = LocalDate.parse("2026-08-22")).single().count)
    }

    @Test
    fun simultaneousOccurrenceAndSettlementPreserveBoth() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        val submitted = store.pending().single()
        val start = CountDownLatch(1)
        val occurrence = thread { start.await(); store.record(
            LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day,
        ) }
        val settle = thread { start.await(); store.settle(UploadReceipt(setOf(UUID.fromString(submitted.id)), emptySet(), 0)) }
        start.countDown()
        occurrence.join()
        settle.join()

        assertEquals(DiagnosticDeliveryState.DELIVERED, persistence.buckets.single { it.id == submitted.id }.deliveryState)
        assertEquals(1, store.pending().single().count)
        assertEquals(2, store.recent(today = day).sumOf { it.count })
    }

    @Test
    fun malformedBucketIsPreservedOutsideThePendingWindow() {
        val bucket = LocalUploadIssueBucket("bad-day", "SENSOR", "SAMPLE_QUARANTINED", 1)
        val persistence = FakePersistence().apply { buckets = listOf(bucket) }
        val store = LocalUploadDiagnosticsStore(persistence)

        store.quarantineMalformed(setOf(bucket.id))

        assertTrue(store.pending().isEmpty())
        assertEquals(DiagnosticDeliveryState.QUARANTINED, persistence.buckets.single().deliveryState)
    }

    @Test
    fun failureDetailsAreClassifiedWithoutPersistingFreeFormText() {
        assertEquals(UploadFailureIssue.TIMEOUT, classifyUploadFailure(SocketTimeoutException("late")))
        val fields = LocalUploadIssueBucket::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertFalse("errorMessage" in fields)
        assertFalse("serverOrigin" in fields)
    }

    @Test
    fun wrappedUnknownHostFailureIsClassifiedAndRecorded() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val error = UndeclaredThrowableException(UnknownHostException("offline"))

        store.recordFailure(LocalUploadModuleFamily.LOCAL_STORE, error)

        val issue = classifyUploadFailure(error)
        assertEquals(UploadFailureIssue.DNS_FAILURE, issue)
        val bucket = persistence.buckets.single()
        assertEquals(issue.name, bucket.issue)
        assertEquals("UnknownHostException", bucket.errorType)
    }

    @Test fun failureCatalogClassifiesEveryCurrentFailure() {
        val failures = mapOf(
            UploadFailureIssue.HTTP_SERVER_ERROR to ChronicleCallException("unavailable", "https://example.invalid", "", 503),
            UploadFailureIssue.HTTP_CLIENT_ERROR to ChronicleCallException("unauthorized", "https://example.invalid", "", 401),
            UploadFailureIssue.TIMEOUT to SocketTimeoutException(),
            UploadFailureIssue.DNS_FAILURE to UnknownHostException(),
            UploadFailureIssue.TLS_FAILURE to SSLException("handshake"),
            UploadFailureIssue.CONNECTION_FAILURE to ConnectException(),
            UploadFailureIssue.UPLOAD_FAILURE to IllegalStateException(),
        )
        assertEquals(UploadFailureIssue.entries.toSet(), failures.keys)
        for ((issue, error) in failures) {
            assertEquals(issue, classifyUploadFailure(error, uploadHttpStatus(error)))
            val store = LocalUploadDiagnosticsStore(FakePersistence())
            store.recordFailure(LocalUploadModuleFamily.USAGE_LIFECYCLE, error)
            val bucket = store.pending().single()
            assertEquals(issue.name, bucket.issue)
        }
    }

    private class FakePersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()

        override fun load(): List<LocalUploadIssueBucket> = buckets

        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }
}
