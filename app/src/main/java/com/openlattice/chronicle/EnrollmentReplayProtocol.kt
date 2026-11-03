package com.openlattice.chronicle

import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import java.security.SecureRandom
import java.util.UUID

private const val PROPOSED_KEY_SECRET_LENGTH = 32
private const val PROPOSED_KEY_PREFIX_LENGTH = 8
private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private val PROPOSED_KEY_PATTERN = Regex("^ck_([0-9A-Za-z]{8})_([0-9A-Za-z]{32})$")
private val MANIFEST_DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")

/**
 * Secret-bearing replay request reconstructed exclusively from Chronicle's SQLCipher database.
 * Its string representation is deliberately redacted so exception/assertion/log formatting cannot
 * disclose the one-time code or proposed device credential.
 */
internal data class PendingEnrollmentReplayRequest(
    val serverId: Long,
    val ownerNonce: String,
    val serverUrl: String,
    val studyId: String,
    val participantId: String,
    val sourceRandomDeviceId: String,
    val sourceDeviceJson: String,
    val manifestDigest: String,
    val attemptId: String,
    val accessCode: String,
    val proposedApiKey: String,
    val firstRequestAtEpochMillis: Long?,
) {
    init {
        require(serverId > 0)
        require(ownerNonce.isNotBlank())
        require(serverUrl.isNotBlank())
        require(studyId.isNotBlank())
        require(participantId.isNotBlank())
        require(sourceRandomDeviceId.isNotBlank())
        require(sourceDeviceJson.isNotBlank())
        require(MANIFEST_DIGEST_PATTERN.matches(manifestDigest))
        require(UUID.fromString(attemptId).toString() == attemptId) {
            "Enrollment attempt id must use canonical UUID text"
        }
        require(accessCode.isNotBlank())
        require(isCompatibleProposedEnrollmentApiKey(proposedApiKey))
    }

    override fun toString(): String =
        "PendingEnrollmentReplayRequest(serverId=$serverId, attemptId=$attemptId, secrets=<redacted>)"
}

internal fun encodePendingEnrollmentSourceDevice(sourceDevice: SourceDevice): String =
    ChronicleJson.adapter<SourceDevice>(SourceDevice::class.java).toJson(sourceDevice)

internal fun decodePendingEnrollmentSourceDevice(
    pending: PendingEnrollmentReplayRequest,
): SourceDevice? = runCatching {
    ChronicleJson.adapter<SourceDevice>(SourceDevice::class.java)
        .fromJson(pending.sourceDeviceJson)
}.getOrNull()
    ?.let { it as? AndroidDevice }
    ?.takeIf { it.randomDeviceId == pending.sourceRandomDeviceId }

internal fun generateProposedEnrollmentApiKey(random: SecureRandom = SecureRandom()): String {
    val secret = buildString(PROPOSED_KEY_SECRET_LENGTH) {
        repeat(PROPOSED_KEY_SECRET_LENGTH) {
            append(BASE62[random.nextInt(BASE62.length)])
        }
    }
    return "ck_${secret.take(PROPOSED_KEY_PREFIX_LENGTH)}_$secret"
}

internal fun isCompatibleProposedEnrollmentApiKey(value: String): Boolean {
    val match = PROPOSED_KEY_PATTERN.matchEntire(value) ?: return false
    return match.groupValues[1] == match.groupValues[2].take(PROPOSED_KEY_PREFIX_LENGTH)
}
