package com.openlattice.chronicle.compat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.state.PendingCollectionAckRecord
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.concurrent.thread

/** ADB-invoked runtime proof compiled only into the non-distributed R8 proof build. */
public class MinifiedRuntimeProofReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        thread(name = "chronicle-minified-runtime-proof") {
            try {
                verifyRuntime(context.applicationContext, intent)
                pendingResult.resultCode = Activity.RESULT_OK
                pendingResult.resultData =
                    "OK channel=${BuildConfig.DISTRIBUTION_CHANNEL} api=${Build.VERSION.SDK_INT}"
            } catch (error: Throwable) {
                pendingResult.resultCode = Activity.RESULT_CANCELED
                pendingResult.resultData =
                    "FAIL ${error.javaClass.name}: ${error.message.orEmpty().take(240)}"
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun verifyRuntime(context: Context, intent: Intent) {
        val expectedFingerprint = intent.getStringExtra(EXTRA_EXPECTED_SECRET_FINGERPRINT)
            ?: error("Missing expected secret fingerprint")
        check(Utils.mobileSigningSecretFingerprint(null).startsWith(expectedFingerprint)) {
            "Mobile signing secret fingerprint mismatch"
        }

        val sensor = AndroidSensorSample(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            sensor = AndroidSensorType.accelerometer,
            timestamp = OffsetDateTime.parse("2026-07-10T12:34:56Z"),
            timezone = "UTC",
            x = 1f,
        )
        val sensorJson = JsonSerializer.toJson(listOf(sensor))
        check(JsonSerializer.fromJson<List<AndroidSensorSample>>(sensorJson)?.single() == sensor) {
            "Moshi sensor round trip failed"
        }

        val retry = PendingCollectionAckRecord(
            serverId = 7L,
            acceptedModuleIds = listOf("battery"),
            declinedModuleIds = listOf("audio_content"),
            trigger = "ENROLLMENT",
            acknowledgedAt = "2026-07-10T12:34:56Z",
        )
        val retryJson = JsonSerializer.toJson(listOf(retry))
        check(JsonSerializer.fromJson<List<PendingCollectionAckRecord>>(retryJson)?.single() == retry) {
            "Moshi persisted-state round trip failed"
        }

        check(ChronicleDb.getInstance(context).openHelper.writableDatabase.isOpen) {
            "SQLCipher database did not open"
        }
        verifyRetrofitEnrollment()
    }

    private fun verifyRetrofitEnrollment() {
        val deviceId = "minified-proof-device"
        val expectedChronicleId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.encodedPath.endsWith("/participant/proof/enroll"))
                val requestJson = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                check(requestJson.contains("\"@class\":\"${AndroidDevice::class.java.name}\""))
                check(requestJson.contains(deviceId))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"chronicleId":"$expectedChronicleId","apiKey":"proof"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://chronicle.invalid/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
            .addCallAdapterFactory(ChronicleCallAdapterFactory())
            .build()
            .create(ChronicleStudyApi::class.java)
        val device = AndroidDevice(
            deviceId,
            "proof-model",
            "proof-codename",
            "proof-brand",
            "proof-display",
            Build.VERSION.SDK_INT.toString(),
            "proof-product",
            deviceId,
            emptyMap(),
        )
        val response = api.enroll(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            "proof",
            deviceId,
            device,
        )
        check(response.chronicleId == expectedChronicleId)
        check(response.apiKey == "proof")
    }

    private companion object {
        const val EXTRA_EXPECTED_SECRET_FINGERPRINT = "expectedMobileSecretFingerprint"
    }
}
