package com.openlattice.chronicle.security

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ResponseSizeLimitInterceptorTest {

    @Test
    fun smallBodyPassesThrough() {
        assertEquals("{}", call("{}".toResponseBody()).body!!.string())
    }

    @Test
    fun declaredOversizeBodyIsRejected() {
        assertThrows(IOException::class.java) { call("x".repeat(11).toResponseBody()) }
    }

    @Test
    fun undeclaredOversizeBodyFailsWhileReading() {
        // contentLength -1: a chunked response that only reveals its size as it streams.
        val chunked = Buffer().writeUtf8("x".repeat(11)).asResponseBody(null, -1)
        val response = call(chunked)
        assertThrows(IOException::class.java) { response.body!!.string() }
    }

    private fun call(body: ResponseBody): Response = OkHttpClient.Builder()
        .addInterceptor(ResponseSizeLimitInterceptor(maxBytes = 10))
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        })
        .build()
        .newCall(Request.Builder().url("https://chronicle.example/").build())
        .execute()
}
