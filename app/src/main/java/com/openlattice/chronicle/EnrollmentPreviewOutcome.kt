package com.openlattice.chronicle

import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.security.isCertificateRejection
import com.openlattice.chronicle.services.upload.isTransportFailure
import com.openlattice.chronicle.services.upload.uploadHttpStatus
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException

/** Recoverable exceptions become results; VM failures must escape enrollment recovery. */
internal inline fun <T> enrollmentAttempt(block: () -> T): Result<T> = runCatching(block).onFailure {
    if (it !is Exception) throw it
}

internal sealed interface EnrollmentPreviewOutcome {
    data class Verified(val preview: EnrollmentPreviewResponse?) : EnrollmentPreviewOutcome
    /** The preview failed; the invitation is kept so the participant can try it again. */
    data class Failed(val message: Int) : EnrollmentPreviewOutcome
}

/** The participant-facing reason a preview failed; [enrollmentRefusal] decides whether the server's answer is final. */
internal fun enrollmentRequestFailureMessage(error: Exception): Int {
    val refusal = enrollmentRefusal(error)
    return when {
        // At the preview a 401 rejects the invitation itself.
        refusal == EnrollmentRecoveryResult.TERMINAL_FAILURE -> R.string.enrollment_invitation_unverified
        refusal != null -> recoveryEndMessage(refusal) ?: R.string.enrollment_server_rejected
        uploadHttpStatus(error) != null -> R.string.enrollment_server_unavailable
        generateSequence<Throwable>(error) { it.cause }.any {
            it is JsonDataException || it is JsonEncodingException
        } -> R.string.enrollment_response_unexpected
        isCertificateRejection(error) -> R.string.enrollment_server_certificate_untrusted
        isTransportFailure(error) ->
            R.string.enrollment_preview_unavailable
        else -> R.string.enrollment_request_unconfirmed
    }
}
