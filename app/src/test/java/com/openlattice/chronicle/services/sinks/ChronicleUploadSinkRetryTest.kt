package com.openlattice.chronicle.services.sinks

import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.study.StudyEncryptionSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.KeyPairGenerator
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * The server dedupes encrypted batches only on identical bytes (content_hash). A retry after a
 * lost response must therefore resend the envelope it already sealed, not a fresh one.
 */
class ChronicleUploadSinkRetryTest {
    private val studyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val participantId = "participant-retry"

    private val setting: StudyEncryptionSetting = run {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getEncoder().encodeToString(rsa.public.encoded).chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----\n"
        StudyEncryptionSetting(
            enabled = true,
            keyId = "retry-key",
            algorithm = EnvelopeCipher.DEFAULT_ALG,
            publicKeyPem = pem,
            mlkemPublicKey = EnvelopeCipher.encodeMlkemPublicKey(EnvelopeCipher.generateMlkemKeyPair().first),
        )
    }

    private fun event(app: String) = ChronicleUsageEvent(
        studyId = studyId,
        participantId = participantId,
        appPackageName = app,
        interactionType = "Activity Resumed",
        timestamp = OffsetDateTime.parse("2026-09-24T10:00:00Z"),
        timezone = "UTC",
        user = "user",
        applicationLabel = app,
    )

    private fun capturingApi(posted: MutableList<EncryptedEnvelope>): ChronicleStudyApi =
        Proxy.newProxyInstance(
            ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java),
        ) { _, method, args ->
            check(method.name == "uploadAndroidEncryptedData") { "unexpected call ${method.name}" }
            @Suppress("UNCHECKED_CAST")
            posted += args[4] as List<EncryptedEnvelope>
            1
        } as ChronicleStudyApi

    private fun sink(posted: MutableList<EncryptedEnvelope>) = ChronicleUploadSink(
        studyId, participantId, "device-1", "api-key", capturingApi(posted), setting,
    )

    @Test
    fun resubmittingTheSameBatchPostsByteIdenticalEnvelopes() {
        val posted = mutableListOf<EncryptedEnvelope>()
        val batch: List<ChronicleSample> = listOf(event("a.b.c"), event("d.e.f"))

        sink(posted).submit(batch)
        // The response was lost; a fresh worker run builds a new sink and retries the same batch.
        sink(posted).submit(batch)

        assertEquals(2, posted.size)
        assertEquals(posted[0], posted[1])
    }

    @Test
    fun aDifferentBatchIsSealedAfresh() {
        val posted = mutableListOf<EncryptedEnvelope>()

        sink(posted).submit(listOf(event("a.b.c")))
        sink(posted).submit(listOf(event("x.y.z")))

        assertNotEquals(posted[0].ciphertext, posted[1].ciphertext)
    }
}
