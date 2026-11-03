package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.utils.Utils
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.generated.CollectionConsentContracts
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.UploadServerEntity
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.OffsetDateTime

/** A queued acknowledgment is dropped only when the server says this acknowledgment can never be accepted. */
@RunWith(RobolectricTestRunner::class)
class CollectionAckRejectionTest {
    private val server = UploadServerEntity(
        id = 1, name = "server-1", url = "https://localhost",
        studyId = "00000000-0000-0000-0000-000000000001", participantId = "participant-1",
        sourceRandomDeviceId = "device-1", apiKey = "api-key-1", disclosureVersion = "disclosure-1",
        manifestDigest = "a".repeat(64), enabled = true,
    )
    private val record = PendingCollectionAckRecord.from(server, setOf(CollectionModuleId.BATTERY_TELEMETRY), emptySet(),
        trigger = ConsentTrigger.PARTICIPANT_TOGGLE, acknowledgedAt = OffsetDateTime.parse("2026-10-29T12:00:00Z"))
    private val ackRejectedBody = """{"code":"${CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE}","message":"refused"}"""

    private class Outcome(val result: CollectionAckRetryResult, val failures: Int, val rejections: Int)

    @Before fun trustedHost() = assertNotNull(Utils.normalizeTrustedServerUrl(server.url))

    private fun retry(refusal: Exception): Outcome {
        var failures = 0
        var rejections = 0
        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(listOf(record), listOf(server),
            report = { _, _, _ -> throw refusal },
            onFailure = { _, _ -> failures++ },
            onRejected = { _, _ -> rejections++ })
        return Outcome(result, failures, rejections)
    }

    private fun http(status: Int, body: String) =
        retrofit2.HttpException(retrofit2.Response.error<Any>(status, body.toResponseBody()))

    @Test
    fun uncodedOrForeignCoded400And422KeepTheAcknowledgmentQueued() {
        val refusals = listOf(
            http(400, """{"message":"proxy or contract refusal"}"""),
            http(422, """{"message":"unprocessable"}"""),
            http(400, "<html>Bad Request</html>"),
            http(400, """{"code":"${UploadLimits.UPLOAD_ROW_REJECTED_CODE}"}"""),
            http(422, ackRejectedBody),
            ChronicleCallException("refused", server.url, """{"message":"bad"}""", 400),
        )
        refusals.forEach { refusal ->
            val outcome = retry(refusal)
            assertTrue("$refusal", outcome.result.removedStableKeys.isEmpty())
            assertFalse("$refusal", outcome.result.allAttemptedSucceeded)
            assertEquals("$refusal", 1, outcome.failures)
            assertEquals("$refusal", 0, outcome.rejections)
        }
    }

    @Test
    fun uncodedPermanentFailureAttemptsOnlyTheOldestDecisionPerRun() {
        val pending = (1L..100L).map { record.copy(decisionSeq = it) }
        for (status in listOf(400, 422)) {
            val attempted = mutableListOf<Long?>()
            val result = CollectionLoopCoordinator.retryPendingCollectionAcks(pending, listOf(server),
                report = { _, _, ack ->
                    attempted += ack.decisionSeq
                    throw http(status, """{"message":"permanent contract mismatch"}""")
                })
            assertEquals(listOf(1L), attempted)
            assertTrue(result.removedStableKeys.isEmpty())
            assertFalse(result.allAttemptedSucceeded)
        }
    }

    @Test
    fun codedRejectionAllowsTheNextDecisionInTheSameRun() {
        val pending = listOf(record.copy(decisionSeq = 1), record.copy(decisionSeq = 2))
        val attempted = mutableListOf<Long?>()
        var rejections = 0
        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(pending, listOf(server),
            report = { _, _, ack ->
                attempted += ack.decisionSeq
                if (ack.decisionSeq == 1L) throw http(400, ackRejectedBody)
            },
            onRejected = { _, _ -> rejections++ })
        assertEquals(listOf(1L, 2L), attempted)
        assertEquals(pending.map { it.stableKey() }.toSet(), result.removedStableKeys)
        assertEquals(1, rejections)
        assertTrue(result.allAttemptedSucceeded)
    }

    @Test
    fun ackRejected400DropsTheAcknowledgmentAndReportsTheRejection() {
        listOf(
            http(400, ackRejectedBody),
            ChronicleCallException("refused", server.url, ackRejectedBody, 400),
        ).forEach { refusal ->
            val outcome = retry(refusal)
            assertEquals("$refusal", setOf(record.stableKey()), outcome.result.removedStableKeys)
            assertEquals("$refusal", 1, outcome.rejections)
            assertEquals("$refusal", 0, outcome.failures)
        }
    }
}
