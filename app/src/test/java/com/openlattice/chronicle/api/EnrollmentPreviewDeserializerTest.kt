package com.openlattice.chronicle.api

import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentPreviewDeserializerTest {

    private val validJson = """
        {
          "manifest": {
            "schemaVersion": 1,
            "serverOrigin": "https://research.example.org",
            "studyId": "00000000-0000-0000-0000-000000000001",
            "participantId": "participant-001",
            "studyTitle": "Example Study",
            "studyDescription": "A study of daily routines.",
            "participantPolicy": {
              "responsibleInstitution": "Example Research Institute",
              "serverOperator": "Example Research Institute",
              "researchContact": "study-team@example.org",
              "purpose": "Study daily routines.",
              "expectedDuration": "Twelve weeks",
              "procedures": "Chronicle collects approved modules.",
              "foreseeableRisks": "Daily patterns may be sensitive.",
              "expectedBenefits": "No direct benefit is promised.",
              "dataUseAndSharing": "Coded data is available to the approved study team.",
              "retentionAndDeletion": "Data is retained for seven years.",
              "privacyPolicyUrl": "https://research.example.org/privacy",
              "withdrawalUrl": "https://research.example.org/withdraw",
              "consentDocumentUrl": null,
              "version": "consent-1",
              "effectiveAt": "2026-08-17T00:00:00Z"
            },
            "collectionSettings": {
              "modules": {},
              "version": 2,
              "settingsVersion": 7
            },
            "settingsVersion": 7,
            "issuedAt": "2026-08-17T12:00:00Z",
            "expiresAt": "2026-08-17T12:10:00Z"
          },
          "manifestDigest": "${"a".repeat(64)}"
        }
    """.trimIndent()

    @Test
    fun decodesAuthoritativeStudyIdentityAndCollectionRevision() {
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java).fromJson(validJson)!!

        assertEquals("Example Study", preview.manifest.studyTitle)
        assertEquals("Example Research Institute", preview.manifest.participantPolicy.responsibleInstitution)
        assertEquals("consent-1", preview.manifest.participantPolicy.version)
        assertEquals(7, preview.manifest.collectionSettings.settingsVersion)
    }

    @Test
    fun rejectsMalformedManifestDigest() {
        val invalid = validJson.replace("a".repeat(64), "not-a-digest")

        assertThrows(Exception::class.java) {
            ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java).fromJson(invalid)
        }
    }

    @Test
    fun decodesHealthConnectScopeFromStableWireIds() {
        val settings = ChronicleJson.moshi.adapter(AndroidDataCollectionSetting::class.java).fromJson(
            """
            {
              "modules": {
                "health_connect": {
                  "enabled": true,
                  "healthConnectRecordTypes": ["steps", "sleep"]
                }
              },
              "version": 2,
              "settingsVersion": 7
            }
            """.trimIndent(),
        )!!

        assertEquals(
            setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
            settings.modules.getValue(CollectionModuleId.HEALTH_CONNECT).healthConnectRecordTypes,
        )
    }
}
