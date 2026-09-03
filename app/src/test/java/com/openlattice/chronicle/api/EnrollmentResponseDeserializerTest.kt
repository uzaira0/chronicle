package com.openlattice.chronicle.api

import com.openlattice.chronicle.serialization.JsonSerializer
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * Verifies that the enrollment adapter accepts both the new BCM-server
 * shape (`{chronicleId, apiKey}`) and the legacy upstream-Chronicle shape
 * (bare UUID string), so the same Android client code can talk to either server.
 */
class EnrollmentResponseDeserializerTest {

    private fun decode(json: String): EnrollmentResponse =
        requireNotNull(JsonSerializer.fromJson<EnrollmentResponse>(json))

    @Test
    fun parsesBareUuidString() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val json = "\"$uuid\""

        val response = decode(json)

        assertEquals(UUID.fromString(uuid), response.chronicleId)
        assertNull(
            "legacy bare-UUID response must yield null apiKey so the client falls back to deviceId mode",
            response.apiKey
        )
    }

    @Test
    fun parsesObjectWithApiKey() {
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","enrollmentId":"4d254a0f-7840-49c6-995d-28082ceb5a59","apiKey":"ck_abc12345_xyzkeyvalue"}"""

        val response = decode(json)

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), response.chronicleId)
        assertEquals(UUID.fromString("4d254a0f-7840-49c6-995d-28082ceb5a59"), response.enrollmentId)
        assertEquals("ck_abc12345_xyzkeyvalue", response.apiKey)
    }

    @Test
    fun parsesObjectWithExplicitNullApiKey() {
        // Server explicitly returns apiKey=null (e.g., key issuance opt-out).
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","apiKey":null}"""

        val response = decode(json)

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), response.chronicleId)
        assertNull(response.apiKey)
    }

    @Test
    fun parsesObjectWithoutApiKeyField() {
        // Field omitted entirely (older server build that doesn't know about apiKey).
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000"}"""

        val response = decode(json)

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), response.chronicleId)
        assertNull(response.apiKey)
    }

    @Test
    fun ignoresUnknownFields() {
        // Forward-compat: a future server may add fields the client doesn't know.
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","apiKey":"ck_x_y","futureField":"ignored","anotherField":42}"""

        val response = decode(json)

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), response.chronicleId)
        assertEquals("ck_x_y", response.apiKey)
    }

    @Test
    fun rejectsObjectMissingChronicleId() {
        // chronicleId is required — a response without it is malformed.
        val json = """{"apiKey":"ck_abc12345_xyzkeyvalue"}"""

        assertThrows(Exception::class.java) {
            decode(json)
        }
    }

    @Test
    fun rejectsMalformedUuidString() {
        val json = "\"not-a-uuid\""

        assertThrows(IllegalArgumentException::class.java) {
            decode(json)
        }
    }

    @Test
    fun rejectsNonStringNonObjectShape() {
        // Number, array, boolean at the top level are all invalid.
        val numberJson = "42"

        assertThrows(JsonDataException::class.java) {
            decode(numberJson)
        }
    }

    @Test
    fun rejectsNumericApiKey() {
        // A buggy/compromised server returning {"apiKey": 12345} must be rejected
        // rather than silently coerced to "12345" and persisted as the credential.
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","apiKey":12345}"""

        assertThrows(JsonDataException::class.java) {
            decode(json)
        }
    }

    @Test
    fun rejectsBooleanApiKey() {
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","apiKey":true}"""

        assertThrows(JsonDataException::class.java) {
            decode(json)
        }
    }

    @Test
    fun rejectsObjectApiKey() {
        val json = """{"chronicleId":"550e8400-e29b-41d4-a716-446655440000","apiKey":{"k":"v"}}"""

        assertThrows(JsonDataException::class.java) {
            decode(json)
        }
    }

    @Test
    fun rejectsNonStringChronicleId() {
        // Defensive: chronicleId numeric encoding shouldn't string-coerce.
        val json = """{"chronicleId":42,"apiKey":"ck_abc12345_keyvalue"}"""

        assertThrows(JsonDataException::class.java) {
            decode(json)
        }
    }
}
