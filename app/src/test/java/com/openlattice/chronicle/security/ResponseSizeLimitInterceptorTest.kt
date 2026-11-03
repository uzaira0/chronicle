package com.openlattice.chronicle.security

import com.openlattice.chronicle.utils.Utils
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
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

    @Test
    fun decompressedGzipBombIsCappedAtTheApplicationBoundary() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(10_000)) }
        val inflated = GzipSource(compressed).buffer().asResponseBody(null, -1)
        assertThrows(IOException::class.java) { call(inflated).body!!.string() }

    }

    @Test
    fun theProductionClientCapsTheInflatedBodyOfARealGzipResponse() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(8_388_609)) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
            server.start()
            val url = server.url("/")
            val factory = Utils.createRetrofitAdapter(url.toString()).callFactory()
            factory.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertThrows(IOException::class.java) { response.body!!.string() }
            }
        }
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
