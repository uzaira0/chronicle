package com.openlattice.chronicle.api

import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.API_KEY_HEADER
import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.RANDOM_DEVICE_ID_HEADER
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.HeaderMap
import retrofit2.http.POST

class MobileRequestHeadersTest {
    private interface HeaderApi {
        @POST("headers") fun post(@HeaderMap headers: Map<String, String>): Call<ResponseBody>
    }

    @Test fun uploadHeadersPreserveCredentialsAndFrozenSettingsEvidence() {
        val request = CollectedUploadHeaders("device-fixture", "key-fixture", 7)
        val sent = capture(request)
        assertEquals("device-fixture", sent[RANDOM_DEVICE_ID_HEADER])
        assertEquals("key-fixture", sent[API_KEY_HEADER])
        assertEquals("7", sent[CollectionUploadEvidence.SETTINGS_VERSION_HEADER])
        assertFalse(request.toString().contains("key-fixture"))
        assertFalse(request.toString().contains("device-fixture"))
        val minimal = capture(CollectedUploadHeaders("device-fixture", null))
        assertNull(minimal[API_KEY_HEADER])
        assertNull(minimal[CollectionUploadEvidence.SETTINGS_VERSION_HEADER])
    }

    private fun capture(headers: CollectedUploadHeaders): Headers {
        var observed: Headers? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request().headers
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }.build()
        Retrofit.Builder().baseUrl("https://example.invalid/").client(client).build().create(HeaderApi::class.java)
            .post(headers.toMap()).execute().body()?.close()
        return checkNotNull(observed)
    }
}
