package com.openlattice.chronicle.serialization

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.data.ParticipationStatus

import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The server decodes these enums through Jackson @JsonValue ids. v49 swapped the Retrofit
 * converter to Moshi, whose default enum adapter emits constant names instead — every
 * collection-ack POST 400'd and the consent trail was never recorded. These tests pin the
 * wire format at the Moshi boundary so a converter or adapter change cannot regress it again.
 */
class ChronicleJsonWireFormatTest {
    @Test
    fun unknownParticipationStatusFailsClosedWithoutLosingErasureSignal() {
        val adapter = ChronicleJson.adapter<ParticipationStatus>(ParticipationStatus::class.java)
        assertEquals(ParticipationStatus.UNKNOWN, adapter.fromJson("\"UNRECOGNIZED_STATUS\""))
        for (status in ParticipationStatus.values()) {
            assertEquals(status, adapter.fromJson("\"${status.name}\""))
        }
    }


    private val ackAdapter = ChronicleJson.moshi.adapter(CollectionAcknowledgment::class.java)
    private val moduleIdAdapter = ChronicleJson.moshi.adapter(CollectionModuleId::class.java)
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
    fun uploadDiagnosticDaySerializesAsIsoDate() {
        val adapter = ChronicleJson.moshi.adapter(AndroidUploadDiagnosticEvent::class.java)
        val event = AndroidUploadDiagnosticEvent(
            id = "7b0d4c52-3f3e-4a8e-9a55-2f5f2a0e6c11",
            day = LocalDate.parse("2026-09-30"),
            moduleFamily = "USAGE_LIFECYCLE",
            issueCode = "TIMEOUT",
            count = 1,
            firstOccurredAt = OffsetDateTime.parse("2026-09-30T12:00:00Z"),
            lastOccurredAt = OffsetDateTime.parse("2026-09-30T12:00:00Z"),
        )

        val json = adapter.toJson(event)

        assertTrue(json, json.contains("\"day\":\"2026-09-30\""))
        assertEquals(event, adapter.fromJson(json))
    }

    @Test
    fun protocolEnumsAcceptOnlyCurrentWireIds() {
        assertEquals(CollectionModuleId.USAGE_EVENTS, moduleIdAdapter.fromJson("\"usage_events\""))
        assertThrows(JsonDataException::class.java) { moduleIdAdapter.fromJson("\"USAGE_EVENTS\"") }
        assertThrows(JsonDataException::class.java) {
            ChronicleJson.adapter<HealthConnectRecordType>(HealthConnectRecordType::class.java).fromJson("\"STEPS\"")
        }
    }

    @Test
    fun moduleIdRejectsUnknownId() {
        assertThrows(JsonDataException::class.java) {
            moduleIdAdapter.fromJson("\"not_a_module\"")
        }
    }

    @Test
    fun retiredModuleSettingFieldsFromAnOlderServerAreIgnored() {
        val setting = ChronicleJson.moshi.adapter(CollectionModuleSetting::class.java).fromJson(
            """{"enabled":false,"disableDisposition":"discard_and_stop","uploadCadence":{"intervalSeconds":60},""" +
                """"batteryPolicy":{"minLevelPercent":30},"networkPolicy":{"requireUnmetered":true}}""",
        )
        assertEquals(CollectionModuleSetting(enabled = false), setting)
    }

    @Test
    fun ordinaryWireEnumsUseStableNamesWithoutReflectiveFieldLookup() {
        assertEquals("\"UNKNOWN\"", batteryStateAdapter.toJson(BatteryChargingState.UNKNOWN))
        assertEquals(BatteryChargingState.CHARGING, batteryStateAdapter.fromJson("\"CHARGING\""))
    }

    @Test
    fun ordinaryWireEnumRejectsUnknownName() {
        assertThrows(JsonDataException::class.java) {
            batteryStateAdapter.fromJson("\"RENAMED_BY_MINIFIER\"")
        }
    }

    /** A newer server's module this build does not know is skipped; versions it leaves null take their defaults. */
    @Test
    fun aNewerServersUnknownModuleIsSkippedAndNullVersionsTakeTheirDefaults() {
        val settings = ChronicleJson.moshi.adapter(AndroidDataCollectionSetting::class.java).fromJson(
            """{"modules":{"future_module":{"enabled":true},"usage_events":{"enabled":true}},"version":null,"settingsVersion":null}""",
        )!!
        assertEquals(mapOf(CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true)), settings.modules)
        assertEquals(AndroidDataCollectionSetting.CURRENT_VERSION, settings.version)
        assertEquals(AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION, settings.settingsVersion)
    }

    /** An enrollment answer without a hard-stop generation decodes with none; a negative one is refused. */
    @Test
    fun anEnrollmentAnswerWithoutAHardStopGenerationHasNone() {
        val adapter = ChronicleJson.moshi.adapter(EnrollmentResponse::class.java)
        val id = UUID.randomUUID()
        assertEquals(EnrollmentResponse(id, apiKey = "ck_key"),
            adapter.fromJson("""{"chronicleId":"$id","apiKey":"ck_key","hardStopGeneration":null}"""))
        assertThrows(IllegalArgumentException::class.java) {
            adapter.fromJson("""{"chronicleId":"$id","apiKey":"ck_key","hardStopGeneration":-1}""")
        }
    }
}
