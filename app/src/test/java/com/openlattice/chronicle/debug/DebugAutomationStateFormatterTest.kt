package com.openlattice.chronicle.debug

import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.UploadServerEntity
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAutomationStateFormatterTest {

    @Test
    fun stateJsonRedactsServerSecretsAndIdentifiers() {
        val json = DebugAutomationStateFormatter.stateJson(
            operation = "dump_local_state",
            servers = listOf(
                UploadServerEntity(
                    id = 7,
                    name = "secret server label",
                    url = "https://chronicle-testprod.example.test/path/with-secret?token=url-secret",
                    studyId = "study-secret",
                    participantId = "participant-secret",
                    sourceDeviceId = "source-device-secret",
                    authMode = AUTH_MODE_API_KEY,
                    apiKey = "api-key-secret",
                    mobileSigningSecretOverride = "mobile-signing-secret",
                    enabled = true,
                    usageUploadSuccessCount = 3,
                    sensorUploadFailureCount = 2,
                    lastUploadedTimestamp = 1234L,
                    lastUploadedQueueId = 99L,
                    lastUploadedSensorId = "sensor-cursor-secret",
                )
            ),
            queueDepths = mapOf("usageQueue" to 4, "deviceSettingsSamples" to 1),
        )

        assertFalse(json.contains("secret server label"))
        assertFalse(json.contains("path/with-secret"))
        assertFalse(json.contains("url-secret"))
        assertFalse(json.contains("study-secret"))
        assertFalse(json.contains("participant-secret"))
        assertFalse(json.contains("source-device-secret"))
        assertFalse(json.contains("api-key-secret"))
        assertFalse(json.contains("mobile-signing-secret"))
        assertFalse(json.contains("sensor-cursor-secret"))

        val root = JsonParser.parseString(json).asJsonObject
        val server = root["servers"].asJsonArray[0].asJsonObject
        assertEquals("chronicle-testprod.example.test", server["host"].asString)
        assertTrue(server["enabled"].asBoolean)
        assertTrue(server["hasApiKey"].asBoolean)
        assertTrue(server["hasMobileSigningSecretOverride"].asBoolean)
        assertTrue(server["hasSensorCursor"].asBoolean)
        assertEquals(3, server["usageUploadSuccessCount"].asInt)
        assertEquals(2, server["sensorUploadFailureCount"].asInt)
        assertEquals(4, root["queueDepths"].asJsonObject["usageQueue"].asInt)
        assertEquals(1, root["queueDepths"].asJsonObject["deviceSettingsSamples"].asInt)
    }

    @Test
    fun stateJsonIncludesSingleDestinationAssertion() {
        val json = DebugAutomationStateFormatter.stateJson(
            operation = "assert_single_destination",
            servers = emptyList(),
            queueDepths = emptyMap(),
            assertion = DebugDestinationAssertion(
                expectedTotalServers = 1,
                expectedEnabledServers = 1,
                actualTotalServers = 1,
                actualEnabledServers = 1,
                distinctHosts = 1,
                passed = false,
            ),
        )

        val assertion = JsonParser.parseString(json).asJsonObject["assertion"].asJsonObject
        assertEquals(1, assertion["expectedTotalServers"].asInt)
        assertEquals(1, assertion["expectedEnabledServers"].asInt)
        assertEquals(1, assertion["actualTotalServers"].asInt)
        assertEquals(1, assertion["actualEnabledServers"].asInt)
        assertEquals(1, assertion["distinctHosts"].asInt)
        assertFalse(assertion["passed"].asBoolean)
    }
}
