package com.openlattice.chronicle.storage

import org.junit.Assert.assertFalse
import org.junit.Test

class UploadServerRedactionTest {
    @Test fun retiredAndWriteOnlyColumnsAreAbsent() {
        val removed = setOf("authMode", "lastUsageUploadAttemptTime", "lastSensorUploadAttemptTime", "lastBatteryUploadAttemptTime",
            "lastBatteryUploadError")
        assertFalse(UploadServerEntity::class.java.declaredFields.any { it.name in removed })
    }

    @Test fun diagnosticRepresentationCannotRevealCredentialsOrEnrollmentIdentity() {
        val credential = "unique-sensitive-sentinel"
        val server = UploadServerEntity(name = credential, url = credential, studyId = credential,
            participantId = credential, sourceRandomDeviceId = credential, apiKey = credential,
            studyDisclosureJson = credential,
            pendingEnrollmentAccessCode = credential, pendingProposedApiKey = credential,
            pendingEnrollmentSourceDeviceJson = credential)
        assertFalse(server.toString().contains(credential))
    }
}
