package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.storage.UploadServerEntity

public data class EnrollmentIdentity(
    val studyId: String,
    val participantId: String,
    val sourceRandomDeviceId: String,
)

public fun UploadServerEntity.enrollmentIdentity(): EnrollmentIdentity = EnrollmentIdentity(
    studyId = studyId,
    participantId = participantId,
    sourceRandomDeviceId = sourceRandomDeviceId,
)
