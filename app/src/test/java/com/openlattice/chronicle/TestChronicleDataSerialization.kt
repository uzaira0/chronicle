package com.openlattice.chronicle

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import org.apache.olingo.commons.api.edm.FullQualifiedName
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

class TestChronicleDataSerialization {
    private val event = ExtractedUsageEvent(
        appPackageName = "com.example.app",
        interactionType = "Activity Resumed",
        eventType = 1,
        timestamp = OffsetDateTime.parse("2026-07-09T12:34:56Z"),
        timezone = "UTC",
        user = "0",
        applicationLabel = "Example",
        activityClass = "com.example.MainActivity",
    )

    @Test
    fun chronicleDataPreservesDiscriminatorAndRoundTrips() {
        val json = JsonSerializer.toJson(ChronicleData(listOf(event)))

        assertTrue(json.contains("\"@class\":\"${ExtractedUsageEvent::class.java.name}\""))
        assertEquals(event, JsonSerializer.deserializeQueueEntry(json.toByteArray()).single())
    }

    @Test
    fun queueReadUsesKotlinDefaultForFieldsMissingFromOlderEntries() {
        val json = """[{"@class":"${ExtractedUsageEvent::class.java.name}","appPackageName":"com.example.app","interactionType":"Activity Resumed","timestamp":"2026-07-09T12:34:56Z","timezone":"UTC","user":"0","applicationLabel":"Example"}]"""

        val restored = JsonSerializer.deserializeQueueEntry(json.toByteArray()).single() as ExtractedUsageEvent

        assertEquals(-1, restored.eventType)
        assertEquals(null, restored.activityClass)
    }

    @Test
    fun queueReadRejectsArbitraryClassNames() {
        val json = """[{"@class":"java.net.InetSocketAddress","hostname":"attacker.invalid","port":443}]"""

        assertThrows(java.io.IOException::class.java) {
            JsonSerializer.deserializeQueueEntry(json.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun sourceDevicePreservesDiscriminatorAndRoundTrips() {
        val device: SourceDevice = AndroidDevice(
            "device", "model", "codename", "brand", "6.0.1", "23", "product", "device-id",
            mapOf("channel" to "research"),
        )

        val json = JsonSerializer.toJson<SourceDevice>(device)
        val restored = JsonSerializer.fromJson<SourceDevice>(json)

        assertTrue(json.contains("\"@class\":\"${AndroidDevice::class.java.name}\""))
        assertEquals(device, restored)
    }

    @Test
    fun propertyTypeIdMapRoundTripsStringKeys() {
        val expected = mapOf(
            FullQualifiedName("general.fullname") to
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
        )

        val json = JsonSerializer.serializePropertyTypeIds(expected)

        assertEquals(expected, JsonSerializer.deserializePropertyTypeIds(json))

        val retrofitType = Types.newParameterizedType(
            Map::class.java,
            FullQualifiedName::class.java,
            UUID::class.java,
        )
        val retrofitAdapter = ChronicleJson.adapter<Map<FullQualifiedName, UUID>>(retrofitType)
        assertEquals(expected, retrofitAdapter.fromJson(retrofitAdapter.toJson(expected)))
    }

    @Test
    fun dataCollectionSettingAcceptsNullableLegacyFields() {
        val restored = JsonSerializer.fromJson<AndroidDataCollectionSetting>(
            """{"modules":null,"version":null,"futureField":true}""",
        )

        assertEquals(emptyMap<Any, Any>(), restored?.modules)
        assertEquals(AndroidDataCollectionSetting.CURRENT_VERSION, restored?.version)
    }

    @Test
    fun legacyQueueReaderPreservesPropertyValues() {
        val propertyId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val json = """[{"$propertyId":["com.example.app"]}]"""

        val restored = JsonSerializer.deserializeLegacyQueueEntry(json.toByteArray()).single()

        assertEquals(setOf<Any>("com.example.app"), restored[propertyId])
    }
}
