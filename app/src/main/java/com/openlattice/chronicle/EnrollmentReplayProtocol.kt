package com.openlattice.chronicle

import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import java.security.SecureRandom
import java.util.UUID

internal const val ENROLLMENT_REPLAY_WINDOW_MILLIS: Long = 24 * 60 * 60 * 1_000L

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
internal class PendingEnrollmentReplayRequest(
    val serverId: Long,
    val ownerNonce: String,
    val serverUrl: String,
    val mobileSigningSecretOverride: String?,
    val studyId: String,
    val participantId: String,
    val sourceDeviceId: String,
    val sourceDeviceJson: String,
    val manifestDigest: String,
    val attemptId: String,
    val accessCode: String,
    val proposedApiKey: String,
    val inviteExpiresAtEpochMillis: Long,
    val firstRequestAtEpochMillis: Long?,
    val replayDeadlineEpochMillis: Long?,
) {
    init {
        require(serverId > 0)
        require(ownerNonce.isNotBlank())
        require(serverUrl.isNotBlank())
        require(studyId.isNotBlank())
        require(participantId.isNotBlank())
        require(sourceDeviceId.isNotBlank())
        require(sourceDeviceJson.isNotBlank())
        require(MANIFEST_DIGEST_PATTERN.matches(manifestDigest))
        require(UUID.fromString(attemptId).toString() == attemptId) {
            "Enrollment attempt id must use canonical UUID text"
        }
        require(accessCode.isNotBlank())
        require(isCompatibleProposedEnrollmentApiKey(proposedApiKey))
        require(inviteExpiresAtEpochMillis > 0)
        require((firstRequestAtEpochMillis == null) == (replayDeadlineEpochMillis == null))
        if (firstRequestAtEpochMillis != null && replayDeadlineEpochMillis != null) {
            require(
                replayDeadlineEpochMillis == Math.addExact(
                    firstRequestAtEpochMillis,
                    ENROLLMENT_REPLAY_WINDOW_MILLIS,
                ),
            ) { "Enrollment replay deadline must use the fixed server-compatible window" }
        }
    }

    fun withFirstRequestTiming(firstRequestAtEpochMillis: Long): PendingEnrollmentReplayRequest =
        PendingEnrollmentReplayRequest(
            serverId = serverId,
            ownerNonce = ownerNonce,
            serverUrl = serverUrl,
            mobileSigningSecretOverride = mobileSigningSecretOverride,
            studyId = studyId,
            participantId = participantId,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceJson = sourceDeviceJson,
            manifestDigest = manifestDigest,
            attemptId = attemptId,
            accessCode = accessCode,
            proposedApiKey = proposedApiKey,
            inviteExpiresAtEpochMillis = inviteExpiresAtEpochMillis,
            firstRequestAtEpochMillis = firstRequestAtEpochMillis,
            replayDeadlineEpochMillis = Math.addExact(
                firstRequestAtEpochMillis,
                ENROLLMENT_REPLAY_WINDOW_MILLIS,
            ),
        )

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
    ?.takeIf { it.deviceId == pending.sourceDeviceId }

internal enum class EnrollmentCleanupReason {
    CANCELLED,
    CREDENTIAL_MISMATCH,
    EXPIRED,
    INVALID_STATE,
    REMOTE_REJECTION,
}

internal sealed interface EnrollmentReplayPlan {
    class Send(
        val request: PendingEnrollmentReplayRequest,
        val persistFirstRequestTiming: Boolean,
    ) : EnrollmentReplayPlan {
        override fun toString(): String =
            "Send(request=$request, persistFirstRequestTiming=$persistFirstRequestTiming)"
    }

    data class Cleanup(val reason: EnrollmentCleanupReason) : EnrollmentReplayPlan
}

internal sealed interface EnrollmentResponseDisposition {
    class Promote(val apiKey: String) : EnrollmentResponseDisposition {
        override fun equals(other: Any?): Boolean =
            other is Promote && apiKey == other.apiKey

        override fun hashCode(): Int = apiKey.hashCode()

        override fun toString(): String = "Promote(apiKey=<redacted>)"
    }

    data class Cleanup(val reason: EnrollmentCleanupReason) : EnrollmentResponseDisposition
}

/**
 * Before the first request, the invitation's expiry is authoritative. Once a request may have
 * reached the server, exact replay uses the separately persisted server-compatible recovery window;
 * the original invitation may expire during that window.
 */
internal fun planEnrollmentReplay(
    pending: PendingEnrollmentReplayRequest,
    nowEpochMillis: Long,
): EnrollmentReplayPlan {
    val firstRequestAt = pending.firstRequestAtEpochMillis
    val replayDeadline = pending.replayDeadlineEpochMillis
    if (firstRequestAt == null && replayDeadline == null) {
        if (pending.inviteExpiresAtEpochMillis <= nowEpochMillis) {
            return EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.EXPIRED)
        }
        val timed = runCatching { pending.withFirstRequestTiming(nowEpochMillis) }.getOrNull()
            ?: return EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.INVALID_STATE)
        return EnrollmentReplayPlan.Send(timed, persistFirstRequestTiming = true)
    }
    if (firstRequestAt == null || replayDeadline == null) {
        return EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.INVALID_STATE)
    }
    return if (replayDeadline > nowEpochMillis) {
        EnrollmentReplayPlan.Send(pending, persistFirstRequestTiming = false)
    } else {
        EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.EXPIRED)
    }
}

internal fun evaluateEnrollmentResponse(
    pending: PendingEnrollmentReplayRequest,
    responseApiKey: String?,
): EnrollmentResponseDisposition = if (responseApiKey == pending.proposedApiKey) {
    EnrollmentResponseDisposition.Promote(pending.proposedApiKey)
} else {
    EnrollmentResponseDisposition.Cleanup(EnrollmentCleanupReason.CREDENTIAL_MISMATCH)
}

internal fun cancelPendingEnrollmentReplay(): EnrollmentReplayPlan =
    EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.CANCELLED)

internal fun enrollmentHttpFailureIsTerminal(statusCode: Int): Boolean = statusCode == 401

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
