package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private const val ROW_TEXT_BYTES = 1L shl 20

/** Moshi writes a control character as a six-byte \u00XX escape. */
private const val ESCAPED_CHAR_BYTES = 6L

class CollectedRequestBatcherTest {
    private val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val time = OffsetDateTime.parse("2026-10-01T00:00:00Z")

    private fun usage(text: String) = ChronicleUsageEvent(study, "test", "example",
        "Activity Resumed", timestamp = time, timezone = "UTC", user = "test", applicationLabel = text,
        clientEventId = UUID(0, 1))

    @Test fun callerElementTypePreservesPolymorphicRows() {
        val rows = listOf<ChronicleSample>(usage("wire"),
            ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"), appPackageName = "example.2",
                interactionType = "Activity Resumed", timestamp = time, timezone = "UTC", user = "test", applicationLabel = "queued"))
        val body = checkNotNull(CollectedUploadBody.encode(rows, ChronicleSample::class.java))
        assertEquals(rows, decodedUploadBody<ChronicleData>(body).toList())
    }

    /** JSON escaping grows a 1 MiB row of control characters sixfold; alone it still encodes, so it is never quarantined. */
    @Test fun aSingleRowEscapedPastTheBatchBudgetStillEncodes() {
        val escaped = usage("\u0001".repeat(ROW_TEXT_BYTES.toInt() / 2))
        val single = checkNotNull(CollectedUploadBody.encode(listOf<ChronicleSample>(escaped), ChronicleSample::class.java))
        assertTrue(single.contentLength() > CollectedUploadBody.ROW_BATCH_BUDGET_BYTES)
        assertNull(CollectedUploadBody.encode(listOf<ChronicleSample>(escaped, usage("")), ChronicleSample::class.java))
    }

    /** Rows sized like real collected samples, far below the server JSON parser's per-string cap. */
    private fun rowsFilling(fill: Long, rows: Int): List<ChronicleSample> =
        List(rows) { usage("x".repeat((fill - it * ROW_TEXT_BYTES).coerceIn(0, ROW_TEXT_BYTES).toInt())) }

    private fun overhead(rows: Int) = checkNotNull(CollectedUploadBody.encode(List<ChronicleSample>(rows) { usage("") },
        ChronicleSample::class.java)).contentLength()

    /** A batch of several rows stops at the batch budget to the byte; the caller splits anything larger. */
    @Test fun rowBatchesStopAtTheBatchBudgetToTheByte() {
        val budget = CollectedUploadBody.ROW_BATCH_BUDGET_BYTES
        var rows = 2
        while (budget - overhead(rows) >= rows * ROW_TEXT_BYTES) rows++
        val fill = budget - overhead(rows)
        assertNull(CollectedUploadBody.encode(rowsFilling(fill + 1, rows), ChronicleSample::class.java))
        assertEquals(budget, checkNotNull(CollectedUploadBody.encode(rowsFilling(fill, rows), ChronicleSample::class.java))
            .contentLength())
    }

    /**
     * The largest body the phone sends is one unsplittable row at the request budget. Escaped control characters fill
     * it while the decoded text stays below the server JSON parser's per-string cap. Retrofit posts exactly those
     * bytes, and the edge test replays them through Caddy.
     */
    @Test fun maximumBodiesUseTheRealRetrofitBytesAndExportForCaddy() {
        val budget = CollectedUploadBody.BODY_BUDGET_BYTES
        val fill = budget - overhead(1)
        fun row(extra: Int) = listOf<ChronicleSample>(usage("\u0001".repeat((fill / ESCAPED_CHAR_BYTES).toInt()) +
            "x".repeat((fill % ESCAPED_CHAR_BYTES).toInt() + extra)))
        assertNull(CollectedUploadBody.encode(row(1), ChronicleSample::class.java))
        val body = checkNotNull(CollectedUploadBody.encode(row(0), ChronicleSample::class.java))
        assertEquals(budget, body.contentLength())
        var posted = byteArrayOf()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertTrue(chain.request().headers.byteCount() <= CollectedUploadBody.PROTOCOL_HEADROOM_BYTES)
            posted = uploadedBodyBytes(chain.request().body)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"acceptedIds":[],"discardedIds":[],"settingsVersion":0}""".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
            .addCallAdapterFactory(ChronicleCallAdapterFactory()).build().create(ChronicleStudyApi::class.java)
        api.uploadAndroidUsageEventData(study, "test", CollectedUploadHeaders(randomDeviceId = "test", apiKey = null).toMap(), body)
        assertArrayEquals(uploadedBodyBytes(body), posted)
        assertTrue(posted.size <= UploadLimits.MAX_REQUEST_SIZE_BYTES)
        System.getenv("CHRONICLE_ANDROID_BATCH_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "plaintext.body").writeBytes(posted)
        }
    }
}
