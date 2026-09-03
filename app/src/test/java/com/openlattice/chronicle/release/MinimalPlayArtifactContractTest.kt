package com.openlattice.chronicle.release

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.squareup.moshi.Moshi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level release invariants that must hold before the slower AAB verifier runs. */
class MinimalPlayArtifactContractTest {
    private val appGradle = File("build.gradle").readText()
    private val mainManifest = File("src/main/AndroidManifest.xml").readText()
    private val playManifest = File("src/play/AndroidManifest.xml").readText()
    private val verifier = File("../scripts/verify-play-aab.sh").readText()
    private val storeReadinessVerifier = File("../scripts/verify-store-readiness.sh").readText()
    private val playSubmissionVerifier = File("../scripts/verify-play-submission.py").readText()
    private val proguardRules = File("proguard-rules.pro").readText()
    private val boundaryWorker = File(
        "src/main/java/com/openlattice/chronicle/services/release/MinimalPlayBoundaryWorker.kt",
    ).readText()
    private val enrollmentMonitor = File(
        "src/main/java/com/openlattice/chronicle/services/enrollment/EnrollmentMonitoringWorker.kt",
    ).readText()

    @Test
    fun playReleaseUsesTargetedR8OptimizationAndEnglishResources() {
        assertTrue(appGradle.contains("resourceConfigurations += ['en', 'es']"))
        assertTrue(proguardRules.contains("-repackageclasses 'com.bcm.chronicle.optimized'"))
        assertFalse(proguardRules.contains("-keep class kotlin.** { *; }"))
        assertTrue(proguardRules.contains("-keep class kotlin.jvm.internal.Intrinsics { *; }"))
        assertTrue(proguardRules.contains("-keep class kotlin.Metadata { *; }"))
    }

    @Test
    fun riskyCollectorsAreNotPlayRuntimeDependencies() {
        val restrictedProjects = listOf(
            "collection-sensors",
            "collection-interaction",
            "collection-audio",
            "collection-activity",
            "collection-health",
        )

        restrictedProjects.forEach { project ->
            assertFalse(
                "$project must not be an unconditional Play runtime dependency",
                appGradle.contains("implementation project(':$project')"),
            )
            assertTrue(appGradle.contains("researchImplementation project(':$project')"))
            assertTrue(appGradle.contains("openImplementation project(':$project')"))
            assertFalse(appGradle.contains("compileOnly project(':$project')"))
            assertFalse(appGradle.contains("debugImplementation project(':$project')"))
        }

        assertTrue(appGradle.contains("verifyPublicRestrictedDependencyBoundary"))
        listOf(
            "collection/audio/AudioCaptureController.kt",
            "collection/audio/AudioUploadWorker.kt",
            "api/RestrictedChronicleStudyApi.kt",
            "services/sensors/SensorUploadWorkerDelegate.kt",
            "collection/interaction/InteractionCollectionService.kt",
        ).forEach { relative ->
            assertFalse(
                "restricted implementation leaked into the shared public source set: $relative",
                File("src/main/java/com/openlattice/chronicle/$relative").exists(),
            )
            assertTrue(
                "research/open implementation is missing: $relative",
                File("src/googleServices/java/com/openlattice/chronicle/$relative").isFile,
            )
        }
        assertFalse(
            File("src/main/java/com/openlattice/chronicle/collection/audio/AudioCaptureController.kt").exists(),
        )
        assertFalse(
            File("src/main/java/com/openlattice/chronicle/collection/audio/AudioUploadWorker.kt").exists(),
        )
        assertTrue(
            File("src/googleServices/java/com/openlattice/chronicle/collection/audio/AudioCaptureController.kt").isFile,
        )
        assertTrue(
            File("src/googleServices/java/com/openlattice/chronicle/collection/audio/AudioUploadWorker.kt").isFile,
        )
    }

    @Test
    fun playReleaseHasAnImmutableCandidateIdentity() {
        assertTrue(appGradle.contains("RELEASE_CANDIDATE_ID"))
        assertTrue(appGradle.contains("APPROVED_MODULE_REGISTRY_SHA256"))
        assertTrue(appGradle.contains("PLAY_APPROVED_MODULE_IDS"))
        assertTrue(mainManifest.contains("com.bcm.chronicle.RELEASE_CANDIDATE_ID"))
        assertTrue(mainManifest.contains("com.bcm.chronicle.APPROVED_MODULE_REGISTRY_SHA256"))
        assertTrue(verifier.contains("RELEASE_CANDIDATE_ID"))
        assertTrue(verifier.contains("APPROVED_MODULE_REGISTRY_SHA256"))
        assertTrue(verifier.contains("SEALED_SUBMISSION"))
        assertTrue(verifier.contains("verify-store-readiness.sh"))
        assertTrue(verifier.contains("play-aab-verification-receipt.json"))
        assertTrue(verifier.contains("verify_sealed_source_checkout"))
        assertTrue(verifier.contains("--untracked-files=all"))
        assertTrue(verifier.contains("initial sealed verification must build the AAB"))
        assertTrue(verifier.contains("Play-delivered verification must inspect"))
        assertTrue(verifier.contains("--prior-sealed-receipt"))
        assertTrue(verifier.contains("--release-authority-sha"))
        assertTrue(verifier.contains("verify_prior_sealed_receipt"))
        assertTrue(verifier.contains("snapshot_stable_input"))
        assertTrue(verifier.contains("prior-sealed-app-play-release.aab"))
        assertTrue(verifier.contains("prior-sealed-mapping.txt"))
        assertTrue(verifier.contains("prior-sealed-receipt.json"))
        assertTrue(verifier.contains("resolve_gh_attestation_command"))
        assertTrue(verifier.contains("CHRONICLE_TEST_GH_ATTESTATION_COMMAND"))
        assertFalse(verifier.contains("GH_ATTESTATION_COMMAND=\"\${GH_ATTESTATION_COMMAND"))
        assertTrue(verifier.contains("attestation verify"))
        assertTrue(verifier.contains("--signer-workflow"))
        assertTrue(verifier.contains("--source-digest"))
        assertTrue(verifier.contains("--deny-self-hosted-runners"))
        assertTrue(verifier.contains("uzaira0/methodic"))
        assertTrue(verifier.contains("priorSealedReceiptSha256"))
        assertTrue(verifier.contains("priorSealedReceiptAttestationVerificationSha256"))
        assertTrue(verifier.contains("releaseAuthorityCommit"))
        assertTrue(verifier.contains("verificationPhase"))
        assertTrue(verifier.contains("policy_version_code"))
        assertTrue(verifier.contains("policy_version_name"))
        assertTrue(verifier.contains("verify_play_delivered_policy_signer"))
        assertTrue(verifier.contains("installedSplitPayloadManifestSha256"))
        assertTrue(verifier.contains("installedSignerCertificateSha256"))
        assertTrue(storeReadinessVerifier.contains("verify-play-submission.py"))
        assertTrue(storeReadinessVerifier.contains("--sealed"))
        assertTrue(playSubmissionVerifier.contains("APPROVAL_STATUSES"))
        assertTrue(playSubmissionVerifier.contains("\"submission_status\""))
        assertTrue(playSubmissionVerifier.contains("sealed release requires {key}=approved"))
        assertTrue(playSubmissionVerifier.contains("play_app_signing_certificate_sha256"))
        assertTrue(playSubmissionVerifier.contains("maximum_uploaded_version_code"))
    }

    @Test
    fun playReleaseExcludesParticipantFormReminderRuntime() {
        assertTrue(
            appGradle.contains(
                "buildConfigField \"boolean\", \"ALLOW_PARTICIPANT_FORM_REMINDERS\", \"false\"",
            ),
        )
        assertTrue(
            verifier.contains(
                "Lcom/openlattice/chronicle/collection/notifications/QuestionnaireCollectionModule;",
            ),
        )
        assertTrue(
            verifier.contains(
                "Lcom/openlattice/chronicle/collection/notifications/QuestionnaireModuleHolder;",
            ),
        )
        assertTrue(verifier.contains("SurveyNotificationsReceiver;"))
        assertTrue(verifier.contains("NotificationsWorker;"))
    }

    @Test
    fun enrollmentMonitorCannotResurrectWithdrawnEnrollment() {
        assertTrue(enrollmentMonitor.contains("ResearchPersistenceGate.isActiveEnrollment"))
        assertTrue(enrollmentMonitor.contains("ResearchPersistenceGate.runIfActive"))
        assertTrue(enrollmentMonitor.contains("persistStatusIfSameActiveEnrollment"))
        assertTrue(enrollmentMonitor.contains("current.getStudyId() == studyId"))
        assertTrue(enrollmentMonitor.contains("current.getParticipantId() == participantId"))
    }

    @Test
    fun approvedRegistryIsTheMachineReadablePlayAuthority() {
        val registry = File("src/play/assets/approved-module-registry.json")
        assertTrue("Play approved-module registry is missing", registry.isFile)
        val source = registry.readText()
        val expected = setOf(
            "usage_events",
            "in_app_activity_class",
            "device_lifecycle",
            "user_identification",
            "upload_telemetry",
            "battery_telemetry",
            "connectivity_state",
            "device_settings",
        )
        val declared = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(expected, declared)
        listOf(
            "health_connect",
            "sensor_availability",
            "sleep",
            "activity_recognition",
            "audio_activity",
            "audio_content",
            "interaction_events",
            "notification_activity",
            "app_network_usage",
        ).forEach { assertFalse(source.contains("\"$it\"")) }
        listOf("fields", "permissions", "retention", "destinations", "upload", "deletion").forEach {
            assertTrue("registry omits $it", source.contains("\"$it\""))
        }
    }

    @Test
    fun playPrivacyPolicyMatchesTheMinimalResearchArtifactAndPublisher() {
        val policy = File("src/play/res/values/strings.xml").readText()

        listOf(
            "Baylor College of Medicine",
            "app package names and labels",
            "study participant or someone else",
            "When an upload fails",
            "active participant/device enrollment",
            "retained by the server for no more than 30 days",
            "API key",
            "uzairalam998@gmail.com",
            "https://www.bcm.edu/about-us/our-campus/compliance",
            "To withdraw from Chronicle collection, uninstall the app",
            "uninstall cannot notify the enrolled study server",
        ).forEach { required ->
            assertTrue("Play privacy policy omits: $required", policy.contains(required))
        }

        listOf(
            "support@openlattice.com",
            "privacycompliance@bcm.edu",
            "reads only the Health Connect",
            "notification source/category/timing metadata",
            "media title/artist/album metadata",
            "per-app network byte counts",
            "Withdraw from study",
            "withdrawal-and-erasure request",
            "https://www.bcm.edu/privacy",
        ).forEach { prohibited ->
            assertFalse("Play privacy policy contains stale release copy: $prohibited", policy.contains(prohibited))
        }
    }

    @Test
    fun playHasNoParticipantFacingWithdrawalControl() {
        val settingsLayout = File("src/main/res/layout/fragment_settings_home.xml").readText()
        val disclosureLayout = File("src/main/res/layout/activity_study_disclosure.xml").readText()
        val settingsFragment = File(
            "src/main/java/com/openlattice/chronicle/ui/SettingsHomeFragment.kt",
        ).readText()
        val disclosureActivity = File(
            "src/main/java/com/openlattice/chronicle/StudyDisclosureActivity.kt",
        ).readText()
        val policy = File("src/play/res/values/strings.xml").readText()

        listOf("withdrawFromStudyButton", "withdrawalStatus", "Withdraw from study").forEach {
            assertFalse("Settings still expose participant withdrawal: $it", settingsLayout.contains(it))
        }
        listOf("studyWithdrawalButton", "Withdrawal information").forEach {
            assertFalse("Disclosure still exposes participant withdrawal: $it", disclosureLayout.contains(it))
        }
        listOf("confirmWithdrawal()", "ParticipantWithdrawalManager.begin").forEach {
            assertFalse("Settings still initiate participant withdrawal: $it", settingsFragment.contains(it))
        }
        listOf("studyWithdrawalButton", "EXTRA_WITHDRAWAL_URL").forEach {
            assertFalse("Disclosure still initiates participant withdrawal: $it", disclosureActivity.contains(it))
        }
        assertTrue(policy.contains("To withdraw from Chronicle collection, uninstall the app"))
        assertTrue(policy.contains("no in-app withdrawal or server-deletion control"))
    }

    @Test
    fun playResourcesAndVerifierRejectExcludedHealthConnectDisclosureCopy() {
        val playStrings = File("src/play/res/values/strings.xml").readText()
        listOf(
            "Share Health Connect data?",
            "This study requests read-only access to:",
            "This study reads health and fitness summaries",
        ).forEach { prohibited ->
            assertFalse("Play resources contain excluded disclosure copy: $prohibited", playStrings.contains(prohibited))
            assertTrue("AAB verifier does not reject excluded disclosure copy: $prohibited", verifier.contains(prohibited))
        }
        listOf(
            "health_connect_rationale" to "Not available in this release.",
            "health_connect_disclosure_title" to "Not available in this release.",
            // Preserve the base resource's format argument so a Play build cannot fail resource linking.
            "health_connect_disclosure_body" to "Not available in this release.%1$.0s",
        ).forEach { (name, value) ->
            assertTrue(
                "Play resource $name does not have the expected inert disclosure copy",
                playStrings.contains("<string name=\"$name\" translatable=\"false\">$value</string>"),
            )
        }
    }

    @Test
    fun playUnlockChoicesMatchTheApprovedTwoCategoryContract() {
        val playStrings = File("src/play/res/values/strings.xml").readText()
        assertTrue(playStrings.contains("<string name=\"user_target_child\">Study participant</string>"))
        assertTrue(playStrings.contains("<string name=\"user_other\">Someone else</string>"))
        assertFalse(playStrings.contains(">Target child</string>"))
        assertFalse(playStrings.contains(">Other</string>"))
    }

    @Test
    fun approvedRegistryMatchesTheActualWireAndLocalDiagnosticsContracts() {
        val source = File("src/play/assets/approved-module-registry.json").readText()
        val root = requireNotNull(Moshi.Builder().build().adapter(Map::class.java).fromJson(source))
        val modules = (root["modules"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .associateBy { it["id"] as String }

        fun fields(id: String): Set<String> = (modules.getValue(id)["fields"] as? List<*>)
            .orEmpty()
            .filterIsInstance<String>()
            .toSet()

        fun permissions(id: String): Set<String> = (modules.getValue(id)["permissions"] as? List<*>)
            .orEmpty()
            .filterIsInstance<String>()
            .toSet()

        assertEquals(
            setOf(
                "id", "timestamp", "timezone", "levelPercent", "chargingState", "plugType",
                "temperatureDeciC", "voltageMillivolts", "health",
            ),
            fields("battery_telemetry"),
        )
        assertEquals(
            setOf(
                "studyId", "participantId", "appPackageName", "applicationLabel",
                "activityClass", "eventType", "interactionType", "timestamp", "timezone",
                "user", "collectedAt",
            ),
            fields("device_lifecycle"),
        )
        assertTrue("id" in fields("connectivity_state"))
        assertTrue("id" in fields("device_settings"))
        assertEquals(setOf("user"), fields("user_identification"))

        val supportingPermissions = mapOf(
            CollectionModuleId.DEVICE_LIFECYCLE to setOf("android.permission.RECEIVE_BOOT_COMPLETED"),
            CollectionModuleId.USER_IDENTIFICATION to setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            ),
        )
        modules.keys.forEach { id ->
            val moduleId = requireNotNull(CollectionModuleId.fromIdOrNull(id))
            val expected = ModulePermissions.requirementsFor(moduleId)
                .mapTo(linkedSetOf()) { it.permission }
                .apply { addAll(supportingPermissions[moduleId].orEmpty()) }
            assertEquals("registry permission drift for $id", expected, permissions(id))
        }

        val identificationDeletion = modules.getValue("user_identification")["deletion"] as Map<*, *>
        assertEquals(
            "clear_user_identification_queue",
            identificationDeletion["decline"],
        )
        assertEquals(
            "append_local_unassigned_transition_and_stop_future_unlock_prompts",
            identificationDeletion["localToggleOff"],
        )
        assertEquals(
            "preserve_pre_disable_usage_until_delivery_or_uninstall",
            identificationDeletion["queuedUsageAfterLocalToggle"],
        )
        assertEquals("android_removes_app_local_data", identificationDeletion["uninstall"])
        assertEquals("not_requested_by_uninstall", identificationDeletion["server"])

        val uploadTelemetry = modules.getValue("upload_telemetry")
        assertTrue(
            fields("upload_telemetry").containsAll(
                setOf(
                    "destinationIssueDay",
                    "destinationIssueModuleFamily",
                    "destinationIssueCode",
                    "destinationIssueCount",
                ),
            ),
        )
        assertEquals(
            listOf("local_app_diagnostics_view", "exact_enrolled_study_server"),
            uploadTelemetry["destinations"],
        )
        val retention = uploadTelemetry["retention"] as Map<*, *>
        assertEquals(
            "live_snapshot_plus_pending_issue_aggregates_for_at_most_30_days",
            retention["local"],
        )
        assertEquals(
            "earlier_of_30_days_from_last_occurrence_or_first_server_receipt",
            retention["server"],
        )
        val upload = uploadTelemetry["upload"] as Map<*, *>
        assertEquals("upload_diagnostics", upload["family"])
        assertEquals("device_api_key_and_optional_request_signature", upload["authentication"])
        assertEquals(
            "encrypted_local_queue_until_full_server_acknowledgment_or_30_day_expiration",
            upload["retry"],
        )
    }

    @Test
    fun uploadDiagnosticsUseOnlyTheAuthenticatedEnrolledServerPath() {
        val uploader = File(
            "src/main/java/com/openlattice/chronicle/services/upload/UploadDiagnosticsUploader.kt",
        ).readText()
        val api = File("src/main/java/com/openlattice/chronicle/api/ChronicleStudyApi.kt").readText()

        assertTrue(uploader.contains("exactActiveEnrollmentServer"))
        assertTrue(uploader.contains("exactActiveEnrollmentServer(context, db) ?: return 0"))
        assertTrue(uploader.contains("server.mobileSigningSecretOverride"))
        assertTrue(uploader.contains("server.apiKey"))
        assertTrue(uploader.contains("store.acknowledge(submitted)"))
        assertTrue(uploader.contains("must not create a recursive diagnostic loop"))
        assertTrue(api.contains("UPLOAD_DIAGNOSTICS_PATH = \"/upload-diagnostics\""))
        assertTrue(api.contains("fun uploadAndroidUploadDiagnostics("))
    }

    @Test
    fun retryExhaustionCannotDisableTheActiveEnrollmentDestination() {
        val uploadWorker = File(
            "src/main/java/com/openlattice/chronicle/services/upload/UploadWorker.kt",
        ).readText()

        assertTrue(uploadWorker.contains("nextConsecutiveUploadFailureCount"))
        assertFalse(uploadWorker.contains("MAX_SERVER_FAILURES"))
        assertFalse(uploadWorker.contains("serverDao.disable"))
        assertFalse(uploadWorker.contains("UploadServerDao"))
    }

    @Test
    fun uploadIssueHistoryIsBoundedRedactedAndExplicitlyMappedToTheWireDto() {
        val storeSource = File("src/main/java/com/openlattice/chronicle/services/upload/LocalUploadDiagnosticsStore.kt").readText()
        val bucketBody = storeSource
            .substringAfter("data class LocalUploadIssueBucket(")
            .substringBefore("\n)")
        val fields = Regex("val\\s+([A-Za-z0-9_]+)")
            .findAll(bucketBody)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "day", "moduleFamily", "issue", "count", "id", "firstOccurredAt",
                "lastOccurredAt", "httpStatus", "errorType",
            ),
            fields,
        )
        assertTrue(storeSource.contains("RETENTION_DAYS = 30L"))
        assertTrue(storeSource.contains("MAX_LOCAL_BUCKETS = 500"))
        assertFalse(storeSource.contains("errorMessage"))
        assertFalse(storeSource.contains("serverOrigin"))
        assertTrue(storeSource.contains("fun toWireEvents("))
        assertTrue(storeSource.contains("AndroidUploadDiagnosticEvent("))
    }

    @Test
    fun verifierCoversEveryDeliveredSplitAndRejectsPackageReplacement() {
        assertTrue(verifier.contains("bundletool"))
        assertTrue(verifier.contains("build-apks"))
        assertTrue(verifier.contains("installed-package-before"))
        assertTrue(verifier.contains("installed-package-after"))
        assertTrue(verifier.contains("--verify-installed"))
        assertTrue(verifier.contains("--verify-play-delivered"))
        assertTrue(verifier.contains("--installed-cert-sha256"))
        assertTrue(verifier.contains("play_delivered_payload_identity_no_replacement"))
        assertTrue(verifier.contains("existing_exact_aab_derived_splits_no_replacement"))
        assertTrue(verifier.contains("installed-signer-certificate.sha256"))
        assertTrue(verifier.contains("installed split payloads differ"))
        assertTrue(verifier.contains("split-hashes.sha256"))
        assertTrue(verifier.contains("forbidden_dex_descriptor"))
        assertTrue(verifier.contains("native-libraries.txt"))
        assertTrue(verifier.contains("resources-inventory.txt"))
        assertTrue(verifier.contains("com.android.tools.build.obfuscation/proguard.map"))
        assertTrue(verifier.contains("cmp -s \"\$MAPPING_PATH\" \"\$embedded_mapping\""))
        assertTrue(verifier.contains("MobileEnrollmentManifest"))
        assertTrue(verifier.contains("MobileReminderConfiguration"))
        assertTrue(verifier.contains("MobileReminderForm"))
        assertTrue(verifier.contains("ParticipantFormKind"))
        assertTrue(verifier.contains("TIME_USE_DIARY"))
        assertTrue(verifier.contains("PORTAL"))
        assertTrue(verifier.contains("reflection-bound enrollment DTO member was removed or renamed by R8"))
        assertTrue(verifier.contains("reflection-bound enum constant was removed or renamed by R8"))
        assertTrue(verifier.contains("consent-trigger.dexdump.txt"))
        assertTrue(verifier.contains("participant-form-kind.dexdump.txt"))
        assertTrue(
            proguardRules.contains(
                "-keep class com.openlattice.chronicle.api.MobileEnrollmentManifest { *; }",
            ),
        )
        assertTrue(
            proguardRules.contains(
                "-keep class com.openlattice.chronicle.collection.ConsentTrigger { *; }",
            ),
        )
        assertTrue(
            proguardRules.contains(
                "-keep class com.openlattice.chronicle.participantaccess.MobileReminderConfiguration { *; }",
            ),
        )
        assertTrue(
            proguardRules.contains(
                "-keep class com.openlattice.chronicle.participantaccess.MobileReminderForm { *; }",
            ),
        )
        assertTrue(
            proguardRules.contains(
                "-keep class com.openlattice.chronicle.participantaccess.ParticipantFormKind { *; }",
            ),
        )
        assertTrue(verifier.contains("HealthMetricCollectionModule"))
        assertTrue(verifier.contains("ActivityRecognitionModuleHolder"))
        assertTrue(verifier.contains("confirmAccessibilityDisclosure|showInteractionAccessibilityDisclosure"))
        assertTrue(verifier.contains("Allow interaction event access?"))
        assertTrue(verifier.contains("StartOnBoot is not structurally bound to BOOT_COMPLETED"))
        assertTrue(verifier.contains("assert_build_config_value"))
        assertTrue(verifier.contains("expected_module_permissions"))
        assertTrue(verifier.contains("--runtime-egress-origin"))
        assertTrue(verifier.contains("-m owner --uid-owner"))
        assertTrue(verifier.contains("runtime-egress-summary.txt"))
        assertTrue(verifier.contains("runtime-egress-receipt.json"))
        assertTrue(verifier.contains("firewallCleanupProven"))
        assertTrue(verifier.contains("activeEnrollmentExactOriginUiProven"))
        assertTrue(verifier.contains("activeEnrollmentHealthyUiProven"))
        assertTrue(verifier.contains("temporaryUiDumpCleanupProven"))
        assertTrue(verifier.contains("allowedOriginEndpointTrafficProven"))
        assertTrue(verifier.contains("ipLiteralOriginRequired"))
        assertTrue(verifier.contains("shared or ambiguous"))
        assertTrue(verifier.contains("could not be proven"))
        assertTrue(verifier.contains("blocked_packets"))
        assertTrue(verifier.contains("no Chronicle traffic reached the enrolled HTTPS origin"))
        assertTrue(verifier.contains("Chronicle attempted traffic outside the enrolled HTTPS origin"))
    }

    @Test
    fun boundaryUsesDurableErasureAndReevaluatesUnlockMonitoring() {
        assertTrue(boundaryWorker.contains("check(SensorSettings(applicationContext).clear())"))
        assertTrue(boundaryWorker.contains("MinimalPlayArtifactState.markBoundaryApplied"))
        assertTrue(boundaryWorker.contains("DeviceUnlockMonitoringService.startAuthorizedService(applicationContext)"))
        assertTrue(
            boundaryWorker.indexOf("check(SensorSettings(applicationContext).clear())") <
                boundaryWorker.indexOf("MinimalPlayArtifactState.markBoundaryApplied"),
        )
    }

    @Test
    fun playManifestRemovesEveryRestrictedComponent() {
        listOf(
            "HardwareSensorService",
            "InteractionCollectionService",
            "NotificationListener",
            "SurveyNotificationsReceiver",
            "HealthConnectRationaleActivity",
            "SleepActivityReceiver",
        ).forEach { component ->
            if (mainManifest.contains(component)) {
                assertTrue("Play manifest does not remove $component", playManifest.contains(component))
            }
        }
    }
}
