package com.openlattice.chronicle

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.sources.AndroidDevice
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.*


@RunWith(AndroidJUnit4::class)
class ChronicleUploadTests {
    companion object ChronicleDbHolder {
        lateinit var chronicleStudyApi: ChronicleStudyApi

        @BeforeClass
        @JvmStatic
        fun setupChronicleDb() {
            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals(
                        "/chronicle/v4/study/28d661b8-a45a-41b6-aec4-ed9988fa28dc/participant/participant1/enroll",
                        request.url.encodedPath
                    )
                    val requestJson = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    assertTrue(requestJson.contains("\"@class\":\"${AndroidDevice::class.java.name}\""))
                    assertTrue(requestJson.contains("test-device-00000000-0000-0000-0000-000000000000"))
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                            {"chronicleId":"11111111-1111-1111-1111-111111111111","apiKey":"ck_test"}
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl("https://chronicle.test/")
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
                .addCallAdapterFactory(ChronicleCallAdapterFactory())
                .build()
            chronicleStudyApi = retrofit.create(ChronicleStudyApi::class.java)
        }

    }

    @Test
    fun testChronicleEnrollRequestUsesBcmPath() {
        // Use a fixed test UUID instead of Android ID to avoid leaking hardware identifiers
        val deviceId = "test-device-00000000-0000-0000-0000-000000000000"

        val device = AndroidDevice(deviceId, Build.MODEL, Build.VERSION.CODENAME, Build.BRAND, Build.DISPLAY, Build.VERSION.SDK_INT.toString(), Build.PRODUCT, deviceId, mapOf())
        val studyId = UUID.fromString("28d661b8-a45a-41b6-aec4-ed9988fa28dc")
        val participantId = "participant1"

        val deviceJson = JsonSerializer.toJson<com.openlattice.chronicle.sources.SourceDevice>(device)

        val response = chronicleStudyApi.enroll(studyId, participantId, deviceId, device)
        assertTrue(deviceJson.contains(deviceId))
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), response.chronicleId)
        assertEquals("ck_test", response.apiKey)
    }


}
