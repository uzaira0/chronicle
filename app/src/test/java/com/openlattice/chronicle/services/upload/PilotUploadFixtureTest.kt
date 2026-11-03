package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.battery.toBatterySample
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.BatterySampleEntry
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Phone half: small, distinct pilot rows go through the production mappers, encoder and Retrofit, and
 * every collected value is in the bytes on the wire under the name the backend reads; the backend's receipt for them
 * decodes back to their IDs. With CHRONICLE_ANDROID_PILOT_UPLOAD_DIR set, each body is written there as
 * `<last path segment>.json` (usage posts to `.../android`) for the backend half to ingest and export.
 */
class PilotUploadFixtureTest {
    private val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val participant = "pilot-p-07"
    private val sent = linkedMapOf<String, ByteArray>()
    private var reply = ""

    private val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(OkHttpClient.Builder().addInterceptor { chain ->
        sent[chain.request().url.encodedPath.substringAfterLast('/')] = uploadedBodyBytes(chain.request().body)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply.toResponseBody()).build()
    }.build()).addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
        .addCallAdapterFactory(ChronicleCallAdapterFactory()).build().create(ChronicleStudyApi::class.java)
    private val headers = CollectedUploadHeaders(randomDeviceId = "device-7", apiKey = "ck_key").toMap()

    /** The JSON tree, read without the Android row classes. */
    private fun json(bytes: ByteArray): Any? = ChronicleJson.moshi.adapter(Any::class.java).fromJson(bytes.decodeToString())
    private fun tree(bytes: ByteArray): List<Map<*, *>> = (json(bytes) as List<*>).map { it as Map<*, *> }
    private operator fun Map<*, *>.get(name: String): Any? = entries.firstOrNull { it.key == name }?.value

    @Test fun usageRowsCarryLabelsActivityClassZoneAndTheirIds() {
        val ids = listOf(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
        val queued = listOf(
            ExtractedUsageEvent("cl.banco.app", "Activity Resumed", fromInteractionType("Activity Resumed"),
                OffsetDateTime.parse("2026-10-30T09:15:00-03:00"), "America/Santiago", "Study participant", "Banco",
                "cl.banco.app.Login", ids[0]),
            ExtractedUsageEvent("com.whatsapp", "Activity Paused", fromInteractionType("Activity Paused"),
                OffsetDateTime.parse("2026-10-30T21:40:00-03:00"), "America/Santiago", "Someone else", "WhatsApp",
                null, ids[1]),
        )
        reply = """{"acceptedIds":["${ids[0]}","${ids[1]}"],"discardedIds":[],"settingsVersion":4}"""
        val rows = mapUsageSamplesForUpload(queued, study, participant)
        val receipt = api.uploadAndroidUsageEventData(study, participant, headers,
            checkNotNull(CollectedUploadBody.encode(rows, ChronicleSample::class.java)))

        val wire = tree(sent.getValue("android"))
        assertEquals(listOf("cl.banco.app", "com.whatsapp"), wire.map { it["appPackageName"] })
        assertEquals(listOf("Study participant", "Someone else"), wire.map { it["user"] })
        assertEquals(listOf("cl.banco.app.Login", null), wire.map { it["activityClass"] })
        assertEquals(listOf("Banco", "WhatsApp"), wire.map { it["applicationLabel"] })
        assertEquals(listOf("Activity Resumed", "Activity Paused"), wire.map { it["interactionType"] })
        assertEquals(listOf("America/Santiago", "America/Santiago"), wire.map { it["timezone"] })
        assertEquals(listOf("2026-10-30T12:15:00Z", "2026-10-31T00:40:00Z"),
            wire.map { OffsetDateTime.parse(it["timestamp"] as String).toInstant().toString() })
        assertEquals(ids.map(UUID::toString), wire.map { it["clientEventId"] })
        assertEquals(listOf(study.toString(), study.toString()), wire.map { it["studyId"] })
        assertEquals(listOf(participant, participant), wire.map { it["participantId"] })
        assertEquals(UploadReceipt(ids.toSet(), emptySet(), 4), receipt)
        export()
    }

    @Test fun batteryRowsCarryEveryMeasuredValue() {
        val id = "00000000-0000-0000-0000-0000000000b1"
        val entry = BatterySampleEntry(id, "2026-10-30T15:00:00.000Z", "America/Santiago", 37, "DISCHARGING",
            "UNPLUGGED", 291, 3812, "GOOD")
        reply = """{"acceptedIds":["$id"],"discardedIds":[],"settingsVersion":4}"""
        val receipt = api.uploadAndroidBatteryData(study, participant, headers,
            checkNotNull(CollectedUploadBody.encode(listOf(entry.toBatterySample()), BatterySample::class.java)))

        assertEquals(UploadReceipt(setOf(UUID.fromString(id)), emptySet(), 4), receipt)
        val row = tree(sent.getValue("battery")).single()
        assertEquals(mapOf("id" to id, "timezone" to "America/Santiago", "levelPercent" to 37.0,
            "chargingState" to "DISCHARGING", "plugType" to "UNPLUGGED", "temperatureDeciC" to 291.0,
            "voltageMillivolts" to 3812.0, "health" to "GOOD", "producerVersion" to null), row.filterKeys { it != "timestamp" })
        assertEquals("2026-10-30T15:00:00Z", OffsetDateTime.parse(row["timestamp"] as String).toInstant().toString())
        export()
    }

    @Test fun sensorAvailabilityCarriesItsInventoryAndOwner() {
        reply = "1"
        api.reportAndroidSensorAvailability(study, participant, headers, checkNotNull(CollectedUploadBody.encodeSnapshot(
            AndroidDeviceSensorAvailability(participant, "device-7", setOf(AndroidSensorType.accelerometer,
                AndroidSensorType.light)), AndroidDeviceSensorAvailability::class.java)))

        val body = json(sent.values.single()) as Map<*, *>
        assertEquals(participant, body["participantId"])
        assertEquals("device-7", body["randomDeviceId"])
        assertEquals(setOf("accelerometer", "light"), (body["availableSensors"] as List<*>).toSet())
        export()
    }

    private fun export() {
        val dir = System.getenv("CHRONICLE_ANDROID_PILOT_UPLOAD_DIR")?.let(::File) ?: return
        dir.mkdirs()
        sent.forEach { (route, bytes) -> File(dir, "$route.json").writeBytes(bytes) }
    }
}
