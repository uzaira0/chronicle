package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.study.StudyEncryptionSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Device-side parity + routing tests for the HIPAA-2028 W2 hybrid envelope-encryption upload path.
 *
 * Parity: what [PayloadSealer.seal] produces, decrypted with the matching RSA + ML-KEM private keys
 * via [EnvelopeCipher.open] using the SAME AAD, must equal the EXACT bytes the plaintext upload path
 * would have posted (the batch serialized with [JsonSerializer] — the same boundary the
 * Retrofit converter uses for the plaintext body). If these ever diverge the server cannot decode an
 * upload, so this is the load-bearing guarantee.
 *
 * Routing: [PayloadSealer.isEncryptionEnabled] is the single source of truth each of the three
 * upload paths consults to pick encrypted-vs-plaintext; its decision matrix is pinned here.
 */
class PayloadSealerTest {

    private val studyId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val participantId = "participant-007"
    private val keyId = "test-key-2026-06"

    // One hybrid keypair pair for the suite: RSA (classical half) + ML-KEM-1024 (PQ half).
    private val mlkemKeyPair = EnvelopeCipher.generateMlkemKeyPair()
    private val mlkemPublicEncoded = EnvelopeCipher.encodeMlkemPublicKey(mlkemKeyPair.first)

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Encodes [publicKey] as an X.509 SubjectPublicKeyInfo PEM (what PemKeys.rsaPublicKey parses). */
    private fun publicKeyPem(publicKey: RSAPublicKey): String {
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        val wrapped = encoded.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
    }

    private fun enabledSetting(publicKey: RSAPublicKey): StudyEncryptionSetting =
        StudyEncryptionSetting(
            enabled = true,
            keyId = keyId,
            algorithm = EnvelopeCipher.DEFAULT_ALG,
            publicKeyPem = publicKeyPem(publicKey),
            mlkemPublicKey = mlkemPublicEncoded,
        )

    /** Open as the server would: both private keys + the AAD rebuilt from trusted path values. */
    private fun open(
        rsaPrivate: RSAPrivateKey,
        envelope: com.openlattice.chronicle.crypto.EncryptedEnvelope,
        aad: ByteArray,
    ): ByteArray = EnvelopeCipher.open(rsaPrivate, mlkemKeyPair.second, envelope, aad)

    private fun sampleSensorBatch(count: Int): List<AndroidSensorSample> =
        (0 until count).map { i ->
            AndroidSensorSample(
                id = UUID.nameUUIDFromBytes("sensor-$i".toByteArray()),
                sensor = AndroidSensorType.accelerometer,
                timestamp = OffsetDateTime.parse("2026-06-14T10:15:3$i+00:00"),
                timezone = "UTC",
                x = i.toFloat(),
                y = i + 0.5f,
                z = i - 0.25f,
                accuracy = 3,
                values = listOf(i.toFloat(), i + 1f),
            )
        }

    // ----- Parity: sealed-then-opened == plaintext-path bytes -----

    @Test
    fun sealedSensorBatchDecryptsToExactPlaintextBytes() {
        val pair = rsaKeyPair()
        val setting = enabledSetting(pair.public as RSAPublicKey)
        val samples = sampleSensorBatch(5)

        // The exact bytes the plaintext /android/sensors path would post.
        val expectedPlaintext = JsonSerializer.serializeToBytes(samples)

        val envelope = PayloadSealer.seal(
            setting = setting,
            studyId = studyId,
            participantId = participantId,
            payloadType = EncryptedPayloadType.SENSOR,
            plaintext = expectedPlaintext,
            sampleCount = samples.size,
        )

        // Server-side: rebuild AAD from trusted path values + open with the study private keys.
        val aad = EnvelopeCipher.aad(
            EnvelopeCipher.ENVELOPE_VERSION, studyId, participantId, EncryptedPayloadType.SENSOR,
        )
        val decrypted = open(pair.private as RSAPrivateKey, envelope, aad)

        assertArrayEquals("decrypted ciphertext must equal the plaintext-path bytes", expectedPlaintext, decrypted)
        // And the decrypted bytes deserialize back to the same batch.
        val roundTripped = JsonSerializer.fromJson<List<AndroidSensorSample>>(decrypted.decodeToString())!!
        assertEquals(samples.size, roundTripped.size)

        // Envelope metadata sanity.
        assertEquals(EncryptedPayloadType.SENSOR, envelope.payloadType)
        assertEquals(keyId, envelope.keyId)
        assertEquals(samples.size, envelope.sampleCount)
        assertEquals(EnvelopeCipher.ENVELOPE_VERSION, envelope.version)
        assertEquals(EnvelopeCipher.DEFAULT_ALG, envelope.alg)
    }

    @Test
    fun sealedUsageBatchDecryptsToExactPlaintextBytes() {
        val pair = rsaKeyPair()
        val setting = enabledSetting(pair.public as RSAPublicKey)
        // ChronicleData is the same object the plaintext usage path posts.
        val usage = ChronicleData(emptyList())
        val expectedPlaintext = JsonSerializer.serializeToBytes(usage)

        val envelope = PayloadSealer.seal(
            setting = setting,
            studyId = studyId,
            participantId = participantId,
            payloadType = EncryptedPayloadType.USAGE,
            plaintext = expectedPlaintext,
            sampleCount = 0,
        )
        val aad = EnvelopeCipher.aad(
            EnvelopeCipher.ENVELOPE_VERSION, studyId, participantId, EncryptedPayloadType.USAGE,
        )
        val decrypted = open(pair.private as RSAPrivateKey, envelope, aad)
        assertArrayEquals(expectedPlaintext, decrypted)
    }

    @Test
    fun sealedBatteryBatchDecryptsToExactPlaintextBytes() {
        val pair = rsaKeyPair()
        val setting = enabledSetting(pair.public as RSAPublicKey)
        val samples = listOf(
            BatterySample(
                id = "battery-0",
                timestamp = OffsetDateTime.parse("2026-06-14T10:15:30+00:00"),
                timezone = "UTC",
                levelPercent = 88,
                chargingState = com.openlattice.chronicle.collection.BatteryChargingState.CHARGING,
                plugType = com.openlattice.chronicle.collection.BatteryPlugType.AC,
                temperatureDeciC = 305,
                voltageMillivolts = 4123,
                health = com.openlattice.chronicle.collection.BatteryHealth.GOOD,
            ),
        )
        val expectedPlaintext = JsonSerializer.serializeToBytes(samples)

        val envelope = PayloadSealer.seal(
            setting = setting,
            studyId = studyId,
            participantId = participantId,
            payloadType = EncryptedPayloadType.BATTERY,
            plaintext = expectedPlaintext,
            sampleCount = samples.size,
        )
        val aad = EnvelopeCipher.aad(
            EnvelopeCipher.ENVELOPE_VERSION, studyId, participantId, EncryptedPayloadType.BATTERY,
        )
        val decrypted = open(pair.private as RSAPrivateKey, envelope, aad)
        assertArrayEquals(expectedPlaintext, decrypted)
    }

    @Test
    fun mismatchedAadFailsToOpen() {
        val pair = rsaKeyPair()
        val setting = enabledSetting(pair.public as RSAPublicKey)
        val samples = sampleSensorBatch(2)
        val plaintext = JsonSerializer.serializeToBytes(samples)
        val envelope = PayloadSealer.seal(
            setting, studyId, participantId, EncryptedPayloadType.SENSOR, plaintext, samples.size,
        )
        // AAD built for a DIFFERENT participant must not open (replay protection).
        val wrongAad = EnvelopeCipher.aad(
            EnvelopeCipher.ENVELOPE_VERSION, studyId, "someone-else", EncryptedPayloadType.SENSOR,
        )
        var threw = false
        try {
            open(pair.private as RSAPrivateKey, envelope, wrongAad)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("opening with a mismatched AAD must fail", threw)
    }

    // ----- Routing: the enabled/disabled decision -----

    @Test
    fun routingDecisionMatrix() {
        val pair = rsaKeyPair()
        val pem = publicKeyPem(pair.public as RSAPublicKey)

        // null setting -> plaintext
        assertFalse(PayloadSealer.isEncryptionEnabled(null))

        // disabled -> plaintext (even with keys present)
        assertFalse(
            PayloadSealer.isEncryptionEnabled(
                StudyEncryptionSetting(
                    enabled = false, keyId = keyId, publicKeyPem = pem, mlkemPublicKey = mlkemPublicEncoded,
                ),
            ),
        )

        // disabled, default (blank) fields -> plaintext
        assertFalse(PayloadSealer.isEncryptionEnabled(StudyEncryptionSetting()))

        // enabled with usable RSA + ML-KEM public keys -> encrypted
        assertTrue(
            PayloadSealer.isEncryptionEnabled(
                StudyEncryptionSetting(
                    enabled = true,
                    keyId = keyId,
                    algorithm = EnvelopeCipher.DEFAULT_ALG,
                    publicKeyPem = pem,
                    mlkemPublicKey = mlkemPublicEncoded,
                ),
            ),
        )
    }

    // ----- Routing with the fail-closed dimension -----

    @Test
    fun routingFailsClosedWhenEncryptionRequiredButKeyUnavailable() {
        val pair = rsaKeyPair()
        val enabled = enabledSetting(pair.public as RSAPublicKey)
        val disabled = StudyEncryptionSetting()

        // Enabled + usable keys -> ENCRYPT, regardless of the required flag.
        assertEquals(PayloadSealer.EncryptionRouting.ENCRYPT, PayloadSealer.routing(enabled, encryptionRequired = false))
        assertEquals(PayloadSealer.EncryptionRouting.ENCRYPT, PayloadSealer.routing(enabled, encryptionRequired = true))

        // Required but no usable key cached (null / disabled) -> FAIL_CLOSED (never plaintext PHI).
        assertEquals(PayloadSealer.EncryptionRouting.FAIL_CLOSED, PayloadSealer.routing(null, encryptionRequired = true))
        assertEquals(PayloadSealer.EncryptionRouting.FAIL_CLOSED, PayloadSealer.routing(disabled, encryptionRequired = true))

        // Not required and no usable key -> PLAINTEXT (legacy path, unchanged).
        assertEquals(PayloadSealer.EncryptionRouting.PLAINTEXT, PayloadSealer.routing(null, encryptionRequired = false))
        assertEquals(PayloadSealer.EncryptionRouting.PLAINTEXT, PayloadSealer.routing(disabled, encryptionRequired = false))
    }
}
