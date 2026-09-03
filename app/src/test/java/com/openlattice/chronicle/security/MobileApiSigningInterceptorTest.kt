package com.openlattice.chronicle.security

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileApiSigningInterceptorTest {

    @Test
    fun signsMobileApiRequestsUsingServerCompatibleCanonicalString() {
        val body = """
            {"@class":"com.openlattice.chronicle.sources.AndroidDevice","deviceId":"device-1"}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val signature = MobileApiSigningInterceptor.sign(
            method = "post",
            path = "/chronicle/v4/study/study-1/participant/participant-1/enroll",
            timestamp = "1779144000",
            nonce = "123e4567-e89b-12d3-a456-426614174000",
            bodyBytes = body,
            secret = "test-secret"
        )

        assertEquals("ZCbTUKLqe3qUwR0jFiXQIm/m3p2vlwCFbKhtvC+qrRg=", signature)
    }

    @Test
    fun interceptorSignsOnlyMobileStudyPathsAndKeepsBodyReplayable() {
        val body = """{"deviceId":"device-1"}"""
        lateinit var observed: Request
        val client = signedClient("server-override-secret") { request ->
            observed = request
        }

        client.newCall(
            Request.Builder()
                .url("https://chronicle-screentime-app.research.bcm.edu/chronicle/v4/study/study-1/participant/participant-1/enroll")
                .post(body.toRequestBody())
                .build()
        ).execute().close()

        val timestamp = observed.header(MobileApiSigningInterceptor.TIMESTAMP_HEADER)
        val nonce = observed.header(MobileApiSigningInterceptor.NONCE_HEADER)
        val signature = observed.header(MobileApiSigningInterceptor.SIGNATURE_HEADER)
        assertTrue("timestamp header must be present", !timestamp.isNullOrBlank())
        assertTrue("nonce header must be present", !nonce.isNullOrBlank())
        assertEquals(
            MobileApiSigningInterceptor.sign(
                method = "POST",
                path = observed.url.encodedPath,
                timestamp = timestamp!!,
                nonce = nonce!!,
                bodyBytes = body.toByteArray(Charsets.UTF_8),
                secret = "server-override-secret",
            ),
            signature,
        )
    }

    @Test
    fun interceptorDoesNotSignNonMobilePaths() {
        lateinit var observed: Request
        val client = signedClient("server-override-secret") { request ->
            observed = request
        }

        client.newCall(
            Request.Builder()
                .url("https://chronicle-screentime-app.research.bcm.edu/health")
                .get()
                .build()
        ).execute().close()

        assertNull(observed.header(MobileApiSigningInterceptor.TIMESTAMP_HEADER))
        assertNull(observed.header(MobileApiSigningInterceptor.NONCE_HEADER))
        assertNull(observed.header(MobileApiSigningInterceptor.SIGNATURE_HEADER))
    }

    @Test
    fun interceptorDoesNotSignWhenSecretIsBlank() {
        lateinit var observed: Request
        val client = signedClient("   ") { request ->
            observed = request
        }

        client.newCall(
            Request.Builder()
                .url("https://chronicle-screentime-app.research.bcm.edu/chronicle/v4/study/study-1/participant/participant-1/enroll")
                .post(ByteArray(0).toRequestBody())
                .build()
        ).execute().close()

        assertNull(observed.header(MobileApiSigningInterceptor.TIMESTAMP_HEADER))
        assertNull(observed.header(MobileApiSigningInterceptor.NONCE_HEADER))
        assertNull(observed.header(MobileApiSigningInterceptor.SIGNATURE_HEADER))
    }

    @Test
    fun perServerOverrideSecretProducesADifferentSignatureThanDefaultSecret() {
        val body = """{"deviceId":"device-1"}""".toByteArray(Charsets.UTF_8)
        val timestamp = "1779144000"
        val nonce = "123e4567-e89b-12d3-a456-426614174000"
        val path = "/chronicle/v4/study/study-1/participant/participant-1/enroll"

        val defaultSignature = MobileApiSigningInterceptor.sign(
            method = "POST",
            path = path,
            timestamp = timestamp,
            nonce = nonce,
            bodyBytes = body,
            secret = "apk-default-secret",
        )
        val overrideSignature = MobileApiSigningInterceptor.sign(
            method = "POST",
            path = path,
            timestamp = timestamp,
            nonce = nonce,
            bodyBytes = body,
            secret = "server-override-secret",
        )

        assertTrue(defaultSignature != overrideSignature)
    }

    @Test
    fun signatureBindsMethodPathTimestampNonceAndBody() {
        val body = """{"deviceId":"device-1"}""".toByteArray(Charsets.UTF_8)
        val timestamp = "1779144000"
        val nonce = "123e4567-e89b-12d3-a456-426614174000"
        val path = "/chronicle/v4/study/study-1/participant/participant-1/enroll"
        val secret = "server-override-secret"

        val baseline = MobileApiSigningInterceptor.sign(
            method = "POST",
            path = path,
            timestamp = timestamp,
            nonce = nonce,
            bodyBytes = body,
            secret = secret,
        )

        val tamperedSignatures = listOf(
            MobileApiSigningInterceptor.sign(
                method = "GET",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodyBytes = body,
                secret = secret,
            ),
            MobileApiSigningInterceptor.sign(
                method = "POST",
                path = "$path/extra",
                timestamp = timestamp,
                nonce = nonce,
                bodyBytes = body,
                secret = secret,
            ),
            MobileApiSigningInterceptor.sign(
                method = "POST",
                path = path,
                timestamp = (timestamp.toLong() + 1).toString(),
                nonce = nonce,
                bodyBytes = body,
                secret = secret,
            ),
            MobileApiSigningInterceptor.sign(
                method = "POST",
                path = path,
                timestamp = timestamp,
                nonce = "223e4567-e89b-12d3-a456-426614174000",
                bodyBytes = body,
                secret = secret,
            ),
            MobileApiSigningInterceptor.sign(
                method = "POST",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodyBytes = """{"deviceId":"device-2"}""".toByteArray(Charsets.UTF_8),
                secret = secret,
            ),
        )

        tamperedSignatures.forEach { signature ->
            assertTrue("Tampering a signed field must change the HMAC", baseline != signature)
        }
    }

    @Test
    fun interceptorSignsMobilePathsWithQueriesUsingServerCanonicalPath() {
        lateinit var observed: Request
        val client = signedClient("server-override-secret") { request ->
            observed = request
        }

        client.newCall(
            Request.Builder()
                .url(
                    "https://chronicle-screentime-app.research.bcm.edu" +
                        "/chronicle/v4/study/study-1/participant/participant-1/enrolled?ignored=query"
                )
                .get()
                .build()
        ).execute().close()

        val timestamp = observed.header(MobileApiSigningInterceptor.TIMESTAMP_HEADER)
        val nonce = observed.header(MobileApiSigningInterceptor.NONCE_HEADER)
        assertEquals(
            MobileApiSigningInterceptor.sign(
                method = "GET",
                path = observed.url.encodedPath,
                timestamp = timestamp!!,
                nonce = nonce!!,
                bodyBytes = ByteArray(0),
                secret = "server-override-secret",
            ),
            observed.header(MobileApiSigningInterceptor.SIGNATURE_HEADER),
        )
    }

    @Test
    fun interceptorSignsV4MobileWithdrawalRequests() {
        lateinit var observed: Request
        val client = signedClient("server-override-secret") { request ->
            observed = request
        }

        client.newCall(
            Request.Builder()
                .url("https://chronicle-screentime-app.research.bcm.edu/chronicle/v4/mobile/enrollments/current")
                .delete()
                .build()
        ).execute().close()

        val timestamp = observed.header(MobileApiSigningInterceptor.TIMESTAMP_HEADER)
        val nonce = observed.header(MobileApiSigningInterceptor.NONCE_HEADER)
        assertEquals(
            MobileApiSigningInterceptor.sign(
                method = "DELETE",
                path = observed.url.encodedPath,
                timestamp = timestamp!!,
                nonce = nonce!!,
                bodyBytes = ByteArray(0),
                secret = "server-override-secret",
            ),
            observed.header(MobileApiSigningInterceptor.SIGNATURE_HEADER),
        )
    }

    @Test
    fun interceptorUsesFreshNonceForEachSignedRequest() {
        val observed = mutableListOf<Request>()
        val client = signedClient("server-override-secret") { request ->
            observed += request
        }
        val request = Request.Builder()
            .url("https://chronicle-screentime-app.research.bcm.edu/chronicle/v4/study/study-1/participant/participant-1/enrolled")
            .get()
            .build()

        client.newCall(request).execute().close()
        client.newCall(request).execute().close()

        assertEquals(2, observed.size)
        val firstNonce = observed[0].header(MobileApiSigningInterceptor.NONCE_HEADER)
        val secondNonce = observed[1].header(MobileApiSigningInterceptor.NONCE_HEADER)
        val firstSignature = observed[0].header(MobileApiSigningInterceptor.SIGNATURE_HEADER)
        val secondSignature = observed[1].header(MobileApiSigningInterceptor.SIGNATURE_HEADER)

        assertTrue("first nonce must be present", !firstNonce.isNullOrBlank())
        assertTrue("second nonce must be present", !secondNonce.isNullOrBlank())
        assertTrue("each request must get a fresh nonce", firstNonce != secondNonce)
        assertTrue("fresh nonce must change the HMAC", firstSignature != secondSignature)
    }

    private fun signedClient(
        secret: String,
        capture: (Request) -> Unit,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(MobileApiSigningInterceptor(secret))
        .addInterceptor(terminalInterceptor(capture))
        .build()

    private fun terminalInterceptor(capture: (Request) -> Unit) = Interceptor { chain ->
        val request = chain.request()
        capture(request)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody())
            .build()
    }
}
