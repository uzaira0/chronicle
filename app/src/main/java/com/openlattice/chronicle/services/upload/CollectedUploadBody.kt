package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.serialization.ChronicleJson
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Types
import java.io.IOException
import java.lang.reflect.Type
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ForwardingSink
import okio.buffer

/** Exact JSON bytes of one collected upload; Retrofit sends them without a converter. */
class CollectedUploadBody<T> private constructor(private val bytes: ByteString) : RequestBody() {
    internal fun json(): String = bytes.utf8()
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength(): Long = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) { sink.write(bytes) }

    companion object {
        // Reserve the proxy's full header allowance for the request line and headers.
        const val PROTOCOL_HEADROOM_BYTES = UploadLimits.REQUEST_HEADER_HEADROOM_BYTES
        const val BODY_BUDGET_BYTES = UploadLimits.MAX_REQUEST_SIZE_BYTES - PROTOCOL_HEADROOM_BYTES

        /**
         * A row batch stays small so a slow mobile link finishes it before a timeout forces a full resend; a larger
         * batch is split by the caller. A single row, which cannot be split, keeps the full [BODY_BUDGET_BYTES]:
         * JSON escaping can grow a 1 MiB queue row past this batch budget.
         */
        const val ROW_BATCH_BUDGET_BYTES = 2L * 1024 * 1024

        /** A JSON array of [rows], or null when it would exceed the batch budget (the request budget for one row). */
        fun <T> encode(rows: List<T>, elementType: Type): CollectedUploadBody<T>? =
            encodeJson(rows, Types.newParameterizedType(List::class.java, elementType),
                if (rows.size == 1) BODY_BUDGET_BYTES else ROW_BATCH_BUDGET_BYTES)

        /** One JSON object, which cannot be split, or null when it would exceed the server's request budget. */
        fun <T : Any> encodeSnapshot(value: T, type: Type): CollectedUploadBody<T>? =
            encodeJson(value, type, BODY_BUDGET_BYTES)

        private fun <T> encodeJson(value: Any, type: Type, budget: Long): CollectedUploadBody<T>? {
            val output = Buffer()
            val sink = object : ForwardingSink(output) {
                private var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    if (byteCount > budget - written) throw BodyBudgetExceeded()
                    written += byteCount
                    super.write(source, byteCount)
                }
            }.buffer()
            return try {
                ChronicleJson.adapter<Any>(type).toJson(JsonWriter.of(sink), value)
                sink.flush()
                CollectedUploadBody(output.readByteString())
            } catch (_: BodyBudgetExceeded) {
                null
            }
        }
    }
}

private class BodyBudgetExceeded : IOException()
