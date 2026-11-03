package com.openlattice.chronicle

import android.content.Context
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.EnrollmentModulePartition
import java.util.UUID

internal sealed interface WizardStep {
    data object Idle : WizardStep
    class Invited(val accessCode: String) : WizardStep
    class Reviewed(val invitation: EnrollmentInvitation, val preview: EnrollmentPreviewResponse) : WizardStep {
        fun submission(context: Context, accepted: Set<CollectionModuleId>, declined: Set<CollectionModuleId>): EnrollmentSubmission {
            val partition = CollectionLoopCoordinator(context).enrollmentModulePartitionFor(
                fetched = preview.manifest.collectionSettings, accepted = accepted, declined = declined)
            return EnrollmentSubmission(preview = preview, partition = partition,
                serverUrl = invitation.serverUrl, enrollmentAccessCode = invitation.accessCode)
        }
    }
}

internal class EnrollmentInvitation(
    val studyId: UUID,
    val participantId: String,
    val serverUrl: String,
    val accessCode: String,
)

internal class EnrollmentSubmission(
    val preview: EnrollmentPreviewResponse,
    val partition: EnrollmentModulePartition,
    val serverUrl: String,
    val enrollmentAccessCode: String,
) {
    val studyId get() = preview.manifest.studyId
    val participantId get() = preview.manifest.participantId
    val fetched get() = preview.manifest.collectionSettings
}
