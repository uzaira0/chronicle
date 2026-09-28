package com.openlattice.chronicle.security

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.abs
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MobileApiSigningInterceptor(
    private val signingSecret: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (signingSecret.isBlank() || !request.url.encodedPath.requiresMobileSignature()) {
            return chain.proceed(request)
        }

        val bodyBytes = request.body?.let { body ->
            Buffer().use { buffer ->
                body.writeTo(buffer)
                buffer.readByteArray()
            }
        } ?: ByteArray(0)

        val response = chain.proceed(signed(request, bodyBytes))
        if (response.code != 401) return response
        // The server rejects a timestamp outside its window with 401. A device clock that far
        // off would fail every upload, so sign against the server's clock from its Date header
        // and retry once. Recomputed on every 401, so a later clock fix corrects itself.
        val serverTime = response.headers.getDate("Date")?.toInstant() ?: return response
        val offset = serverTime.epochSecond - Instant.now().epochSecond
        if (abs(offset) <= CLOCK_SKEW_TOLERANCE_SECONDS) return response
        clockOffsetSeconds = offset
        response.close()
        return chain.proceed(signed(request, bodyBytes))
    }

    private fun signed(request: Request, bodyBytes: ByteArray): Request {
        val timestamp = (Instant.now().epochSecond + clockOffsetSeconds).toString()
        val nonce = UUID.randomUUID().toString()
        val signature = sign(
            method = request.method,
            path = request.url.encodedPath,
            timestamp = timestamp,
            nonce = nonce,
            bodyBytes = bodyBytes,
            secret = signingSecret
        )
        return request
            .newBuilder()
            .headersWithSignature(timestamp, nonce, signature)
            .withReplayableBody(request, bodyBytes)
            .build()
    }

    private fun Request.Builder.headersWithSignature(
        timestamp: String,
        nonce: String,
        signature: String
    ): Request.Builder = header(TIMESTAMP_HEADER, timestamp)
        .header(NONCE_HEADER, nonce)
        .header(SIGNATURE_HEADER, signature)

    private fun Request.Builder.withReplayableBody(
        request: Request,
        bodyBytes: ByteArray
    ): Request.Builder {
        val body = request.body ?: return this
        return method(request.method, bodyBytes.toRequestBody(body.contentType()))
    }

    companion object {
        const val SIGNATURE_HEADER = "X-Chronicle-Signature"
        const val TIMESTAMP_HEADER = "X-Chronicle-Timestamp"
        const val NONCE_HEADER = "X-Chronicle-Nonce"

        // Below the server's 30 s future allowance; its past allowance is 5 min.
        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 25L

        /** Server time minus device time, learned from a rejected request; shared by all clients. */
        @Volatile
        internal var clockOffsetSeconds = 0L

        fun sign(
            method: String,
            path: String,
            timestamp: String,
            nonce: String,
            bodyBytes: ByteArray,
            secret: String
        ): String {
            val signingString = listOf(
                method.uppercase(),
                path,
                timestamp,
                nonce,
                bodyBytes.sha256Hex()
            ).joinToString("|")

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(
                mac.doFinal(signingString.toByteArray(Charsets.UTF_8))
            )
        }

        private fun String.requiresMobileSignature(): Boolean {
            return startsWith("/chronicle/v3/study/") ||
                startsWith("/chronicle/v4/study/") ||
                startsWith("/chronicle/v4/mobile/")
        }

        private fun ByteArray.sha256Hex(): String {
            return MessageDigest
                .getInstance("SHA-256")
                .digest(this)
                .joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
