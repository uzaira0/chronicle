package com.openlattice.chronicle

import com.openlattice.chronicle.api.EnrollmentPreviewResponse

internal sealed interface EnrollmentPreviewOutcome {
    data class Verified(val preview: EnrollmentPreviewResponse?) : EnrollmentPreviewOutcome
    data object Retryable : EnrollmentPreviewOutcome
    data object Invalid : EnrollmentPreviewOutcome
}
