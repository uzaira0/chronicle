package com.openlattice.chronicle

import android.app.AppOpsManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.consentPolicySnapshot
import com.openlattice.chronicle.collection.state.MinimalPlayArtifactState
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.StudyParticipantPolicy
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

internal object AppTestState {
    val STUDY_ID: UUID = UUID.fromString("28d661b8-a45a-41b6-aec4-ed9988fa28dc")
    const val PARTICIPANT_ID: String = "participant"
    const val SERVER_ORIGIN: String = "https://study.example"

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun resetPrefs() {
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        EncryptedPrefsHelper.getEncryptedPrefs(context)
            .edit()
            .putBoolean(context.getString(R.string.identify_user), false)
            .commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(context.getString(R.string.identify_user), false)
            .commit()
        context.getSharedPreferences("collection_acknowledgment_prompt", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("minimal_play_artifact_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    fun db(): ChronicleDb = ChronicleDb.getInstance(context)

    fun clearMutableTables() {
        val db = db()
        db.sensorSampleDao().deleteAll()
        db.batterySampleDao().deleteAll()
        db.userQueueEntryData().deleteAll()
        db.queueEntryData().deleteEntries(db.queueEntryData().getNextEntries(10_000))
        db.uploadServerDao().getAll().forEach { db.uploadServerDao().delete(it.id) }
        db.collectionModuleStateDao().upsertAll(
            CollectionModuleId.activeModules.map {
                CollectionModuleStateEntity(
                    moduleId = it.id,
                    serverEnabled = false,
                    decision = "UNDECIDED",
                    decidedAtEpochMillis = null,
                    requiredApplied = false,
                    appliedVersion = 1,
                    appliedPolicySnapshot = null,
                    lastDisposition = null,
                )
            }
        )
    }

    /**
     * Installs the same minimum durable state as a completed public-link enrollment: one enabled
     * HTTPS destination, a per-device credential, the authenticated disclosure manifest, and
     * settled per-module consent rows. Tests may narrow the manifest to the modules they exercise.
     */
    fun enrollActiveStudy(
        enabledModules: Set<CollectionModuleId> = emptySet(),
        userIdentificationEnabled: Boolean = false,
    ): Long {
        require(enabledModules.all { it in AndroidDataCollectionSetting.androidSupportedModuleIds })

        val moduleSettings = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith { moduleId ->
            CollectionModuleSetting(enabled = moduleId in enabledModules)
        }
        val collectionSettings = AndroidDataCollectionSetting(modules = moduleSettings)
        val issuedAt = OffsetDateTime.parse("2026-08-17T12:00:00Z")
        val participantPolicy = StudyParticipantPolicy(
            responsibleInstitution = "Example Research Institution",
            serverOperator = "Example Research Institution",
            researchContact = "researcher@example.test",
            purpose = "Instrumentation-test study purpose",
            expectedDuration = "One test session",
            procedures = "Collect only the explicitly enabled fixture modules.",
            foreseeableRisks = "Synthetic fixture data only.",
            expectedBenefits = "Validate the participant application.",
            dataUseAndSharing = "No fixture data leaves the emulator.",
            retentionAndDeletion = "Fixture data is deleted after the test.",
            privacyPolicyUrl = "$SERVER_ORIGIN/privacy",
            withdrawalUrl = "$SERVER_ORIGIN/withdrawal",
            version = "instrumentation-v1",
            effectiveAt = issuedAt,
        )
        val manifest = MobileEnrollmentManifest(
            schemaVersion = 1,
            serverOrigin = SERVER_ORIGIN,
            studyId = STUDY_ID,
            participantId = PARTICIPANT_ID,
            studyTitle = "Instrumented Study",
            studyDescription = "Synthetic enrollment used only by device tests.",
            participantPolicy = participantPolicy,
            collectionSettings = collectionSettings,
            settingsVersion = collectionSettings.settingsVersion,
            issuedAt = issuedAt,
            expiresAt = issuedAt.plusHours(1),
        )
        val manifestJson = ChronicleJson.moshi
            .adapter(MobileEnrollmentManifest::class.java)
            .toJson(manifest)
        val manifestDigest = MessageDigest.getInstance("SHA-256")
            .digest(manifestJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        val db = db()
        check(db.uploadServerDao().count() == 0) {
            "enrollActiveStudy requires an empty singleton enrollment slot"
        }
        val serverId = db.uploadServerDao().insert(
            UploadServerEntity(
                name = manifest.studyTitle,
                url = SERVER_ORIGIN,
                studyId = STUDY_ID.toString(),
                participantId = PARTICIPANT_ID,
                sourceDeviceId = "11111111-1111-1111-1111-111111111111",
                authMode = AUTH_MODE_API_KEY,
                apiKey = "instrumentation-device-credential",
                studyDisclosureJson = manifestJson,
                disclosureVersion = participantPolicy.version,
                manifestDigest = manifestDigest,
                enrollmentIssuedAtEpochMillis = issuedAt.toInstant().toEpochMilli(),
                enrollmentSetupComplete = true,
                enabled = true,
                createdAt = issuedAt.toString(),
            ),
        )
        val decidedAt = issuedAt.toInstant().toEpochMilli()
        db.collectionModuleStateDao().upsertAll(
            CollectionModuleId.activeModules.map { moduleId ->
                val setting = moduleSettings[moduleId]
                val enabled = setting?.enabled == true
                CollectionModuleStateEntity(
                    moduleId = moduleId.id,
                    serverEnabled = enabled,
                    decision = if (enabled) "ACCEPTED" else "UNDECIDED",
                    decidedAtEpochMillis = decidedAt.takeIf { enabled },
                    requiredApplied = setting?.required == true,
                    appliedVersion = collectionSettings.settingsVersion,
                    appliedPolicySnapshot = setting?.consentPolicySnapshot(),
                    lastDisposition = null,
                )
            },
        )

        // The fixture represents a completed Play enrollment, including the durable artifact and
        // authoritative-policy boundary proofs that production completes before collection starts.
        MinimalPlayArtifactState.markBoundaryApplied(context)
        MinimalPlayArtifactState.markPolicyCompatible(context)

        WithdrawalStateStore(context).completeReenrollment(STUDY_ID, PARTICIPANT_ID)
        check(
            EncryptedPrefsHelper.getEncryptedPrefs(context)
                .edit()
                .putBoolean(context.getString(R.string.identify_user), userIdentificationEnabled)
                .commit(),
        ) { "Failed to persist the fixture's participant module choice" }
        EnrollmentSettings(context).apply {
            toggleBatteryOptimizationDialog(false)
            toggleHibernationExemptionDialog(false)
        }
        return serverId
    }

    fun setUsageStatsAppOp(mode: String = "allow") {
        executeShell("appops set ${context.packageName} ${AppOpsManager.OPSTR_GET_USAGE_STATS} $mode")
        executeShell("appops set ${context.packageName} GET_USAGE_STATS $mode")
    }

    fun grantPostNotificationsIfPossible() {
        executeShell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    fun allowExactAlarmsIfPossible() {
        executeShell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        executeShell("appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    fun executeShell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
            return reader.readText()
        }
    }
}
