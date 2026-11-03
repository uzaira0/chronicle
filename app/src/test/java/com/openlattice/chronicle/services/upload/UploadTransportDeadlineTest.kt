package com.openlattice.chronicle.services.upload

import android.app.Application
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UploadTransportDeadlineTest {
    private val rows = deviceSettingsUploadRows()

    @Test fun sharedClientBoundsOnlyConnectionAndIdleIo() {
        val client = client()
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
    }

    @Test fun movingUploadCompletesAfterThirtySeconds() {
        MockWebServer().use { server ->
            val started = System.nanoTime()
            upload { body ->
                // MockWebServer throttles request reads as well as response writes. A tiny
                // body at one byte per period exercises the old cap in about 32 seconds.
                server.enqueue(MockResponse().setBody("OK").throttleBody(1,
                    TimeUnit.SECONDS.toNanos(32) / body.contentLength(), TimeUnit.NANOSECONDS))
                client().newCall(Request.Builder().url(server.url("/upload")).post(body).build())
                    .execute().use { assertEquals(200, it.code) }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 30_000)
        }
    }

    @Test fun responseIdleFailureFailsTheCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertThrows(IOException::class.java) {
                upload { body ->
                    client().newBuilder().readTimeout(150, TimeUnit.MILLISECONDS).build()
                        .newCall(Request.Builder().url(server.url("/upload")).post(body).build()).execute().close()
                }
            }
        }
    }

    @Test fun anEnclosingPrivacyScopeCannotCancelAnUpload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(300, TimeUnit.MILLISECONDS))
            CallDeadline.within(50) {
                upload { body ->
                    client().newCall(Request.Builder().url(server.url("/upload")).post(body).build())
                        .execute().use { assertEquals(200, it.code) }
                }
            }
        }
    }

    private fun client() = (Utils.createRetrofitAdapter("http://127.0.0.1").callFactory() as UploadTransport).collectedClient

    private fun upload(send: (CollectedUploadBody<*>) -> Unit) =
        send(checkNotNull(CollectedUploadBody.encode(rows, AndroidDeviceSettingsEvent::class.java)))
}
