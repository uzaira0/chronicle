package com.openlattice.chronicle.serialization

import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * The server decodes these enums through Jackson @JsonValue ids. v49 swapped the Retrofit
 * converter to Moshi, whose default enum adapter emits constant names instead — every
 * collection-ack POST 400'd and the consent trail was never recorded. These tests pin the
 * wire format at the Moshi boundary so a converter or adapter change cannot regress it again.
 */
class ChronicleJsonWireFormatTest {

    private val ackAdapter = ChronicleJson.moshi.adapter(CollectionAcknowledgment::class.java)
    private val moduleIdAdapter = ChronicleJson.moshi.adapter(CollectionModuleId::class.java)
    private val dispositionAdapter = ChronicleJson.moshi.adapter(CollectionDataDisposition::class.java)
    private val payloadTypeAdapter = ChronicleJson.moshi.adapter(EncryptedPayloadType::class.java)
    private val batteryStateAdapter = ChronicleJson.moshi.adapter(BatteryChargingState::class.java)

    @Test
    fun collectionAckSerializesModuleIdsAsWireIds() {
        val json = ackAdapter.toJson(
            CollectionAcknowledgment(
                acknowledgedModules = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.SENSOR_ACCELEROMETER),
                declinedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
                unavailableModules = setOf(CollectionModuleId.SENSOR_GYROSCOPE),
                acknowledgedAt = OffsetDateTime.parse("2026-07-15T00:00:00Z"),
            )
        )

        assertTrue(json.contains("\"usage_events\""))
        assertTrue(json.contains("\"sensor_accelerometer\""))
        assertTrue(json.contains("\"battery_telemetry\""))
        assertTrue(json.contains("\"unavailableModules\":[\"sensor_gyroscope\"]"))
        assertFalse(json.contains("USAGE_EVENTS"))
        assertFalse(json.contains("SENSOR_ACCELEROMETER"))
        assertFalse(json.contains("BATTERY_TELEMETRY"))
    }

    @Test
    fun moduleIdDecodesWireIdAndLegacyConstantName() {
        assertEquals(CollectionModuleId.USAGE_EVENTS, moduleIdAdapter.fromJson("\"usage_events\""))
        // v49 persisted retry records with Moshi's default constant-name encoding; queued acks
        // written by v49 must stay decodable after upgrade so they deliver instead of dropping.
        assertEquals(CollectionModuleId.USAGE_EVENTS, moduleIdAdapter.fromJson("\"USAGE_EVENTS\""))
    }

    @Test(expected = JsonDataException::class)
    fun moduleIdRejectsUnknownId() {
        moduleIdAdapter.fromJson("\"not_a_module\"")
    }

    @Test
    fun dispositionUsesWireIdsBothDirections() {
        assertEquals("\"flush_then_stop\"", dispositionAdapter.toJson(CollectionDataDisposition.FLUSH_THEN_STOP))
        assertEquals(
            CollectionDataDisposition.FLUSH_THEN_STOP,
            dispositionAdapter.fromJson("\"flush_then_stop\""),
        )
        assertEquals(
            CollectionDataDisposition.DISCARD_AND_STOP,
            dispositionAdapter.fromJson("\"DISCARD_AND_STOP\""),
        )
    }

    @Test
    fun encryptedPayloadTypeUsesWireIdsBothDirections() {
        assertEquals("\"usage\"", payloadTypeAdapter.toJson(EncryptedPayloadType.USAGE))
        assertEquals(EncryptedPayloadType.USAGE, payloadTypeAdapter.fromJson("\"usage\""))
        assertEquals(EncryptedPayloadType.USAGE, payloadTypeAdapter.fromJson("\"USAGE\""))
    }

    @Test
    fun ordinaryWireEnumsUseStableNamesWithoutReflectiveFieldLookup() {
        assertEquals("\"UNKNOWN\"", batteryStateAdapter.toJson(BatteryChargingState.UNKNOWN))
        assertEquals(BatteryChargingState.CHARGING, batteryStateAdapter.fromJson("\"CHARGING\""))
    }

    @Test(expected = JsonDataException::class)
    fun ordinaryWireEnumRejectsUnknownName() {
        batteryStateAdapter.fromJson("\"RENAMED_BY_MINIFIER\"")
    }
}
