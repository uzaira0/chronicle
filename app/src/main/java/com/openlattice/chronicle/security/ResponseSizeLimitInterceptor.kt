package com.openlattice.chronicle.security

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Fails a response whose body exceeds [maxBytes], whether or not it declares a length. */
class ResponseSizeLimitInterceptor(private val maxBytes: Long = MAX_RESPONSE_BYTES) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (body.contentLength() > maxBytes) {
            response.close()
            throw IOException("Response larger than $maxBytes bytes")
        }
        val limited = object : ForwardingSource(body.source()) {
            private var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) total += read
                if (total > maxBytes) throw IOException("Response larger than $maxBytes bytes")
                return read
            }
        }
        return response.newBuilder()
            .body(limited.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }
}
