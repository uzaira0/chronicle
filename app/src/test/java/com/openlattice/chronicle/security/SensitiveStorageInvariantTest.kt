package com.openlattice.chronicle.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for local sensitive-storage regressions. The Android upload
 * credentials that authenticate to BCM/AWS belong in the SQLCipher-backed Room
 * database, while legacy enrollment/user preferences must go through
 * EncryptedSharedPreferences. This test prevents accidental drift into plain
 * SharedPreferences, extras, or other simple string stores.
 */
class SensitiveStorageInvariantTest {

    private companion object {
        val uploadCredentialTerms = listOf(
            "apiKey",
            "mobileSigningSecret",
            "mobileSigningSecretOverride",
            "MOBILE_SIGNING_SECRET",
            "X-Api-Key",
            "Authorization",
            "deviceSecret",
            "pendingEnrollmentAccessCode",
            "pendingProposedApiKey",
            "pendingEnrollmentSourceDeviceJson",
            "X-Chronicle-Enrollment-Code",
            "X-Chronicle-Proposed-Api-Key",
        )

        fun buildRoot(): File {
            var dir: File? = File(".").absoluteFile
            repeat(6) {
                val base = dir
                if (base != null && File(base, "app/src/main/AndroidManifest.xml").isFile) return base
                dir = base?.parentFile
            }
            error("Could not locate the Android build root from cwd=${File(".").absolutePath}")
        }

        fun mainSources(root: File): List<File> =
            (root.listFiles { f -> f.isDirectory } ?: emptyArray())
                .map { File(it, "src/main") }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

        fun read(root: File, relative: String): String =
            File(root, relative).also { require(it.isFile) { "Missing $relative" } }.readText()
    }

    @Test
    fun uploadServerCredentialsAreRoomBackedNotPreferenceBacked() {
        val root = buildRoot()
        val entity = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/UploadServerEntity.kt")
        assertTrue(entity.contains("tableName = \"upload_servers\""))
        assertTrue(entity.contains("val apiKey: String? = null"))
        assertTrue(entity.contains("val mobileSigningSecretOverride: String? = null"))
        assertTrue(entity.contains("val studyDisclosureJson: String? = null"))
        assertTrue(entity.contains("val disclosureVersion: String? = null"))
        assertTrue(entity.contains("val manifestDigest: String? = null"))
        assertTrue(entity.contains("val pendingEnrollmentAccessCode: String? = null"))
        assertTrue(entity.contains("val pendingProposedApiKey: String? = null"))
        assertTrue(entity.contains("val pendingEnrollmentSourceDeviceJson: String? = null"))
        assertTrue(entity.contains("val pendingUnavailableModuleIds: String? = null"))
        assertTrue(entity.contains("Stored in the SQLCipher database"))

        val dao = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/UploadServerDao.kt")
        assertTrue(dao.contains("apiKey = :apiKey"))
        assertTrue(dao.contains("mobileSigningSecretOverride = :mobileSigningSecretOverride"))
    }

    @Test
    fun roomEnforcesOneEnrollmentRow() {
        val root = buildRoot()
        val entity = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/UploadServerEntity.kt")
        assertTrue(entity.contains("Index(value = [\"singletonKey\"], unique = true)"))
        assertTrue(entity.contains("val singletonKey: Int = 1"))

        val database = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/ChronicleDb.kt")
        assertTrue(database.contains("version = 28"))
        assertTrue(database.contains("MIGRATION_23_24"))
        assertTrue(database.contains("MIGRATION_24_25"))
        assertTrue(database.contains("MIGRATION_25_26"))
        assertTrue(database.contains("MIGRATION_26_27"))
        assertTrue(database.contains("MIGRATION_27_28"))

        val diagnosticMigration = read(
            root,
            "collection-base/src/main/java/com/openlattice/chronicle/storage/Migration27to28.kt",
        )
        assertTrue(diagnosticMigration.contains("'UPLOAD_FAILURE'"))
        assertFalse(diagnosticMigration.contains("DROP TABLE"))

        val migration = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/Migration23to24.kt")
        assertTrue(migration.contains("DELETE FROM `upload_servers`"))
        assertTrue(migration.contains("ADD COLUMN `singletonKey` INTEGER NOT NULL DEFAULT 1"))
        assertTrue(migration.contains("CREATE UNIQUE INDEX"))

        val dao = read(root, "collection-base/src/main/java/com/openlattice/chronicle/storage/UploadServerDao.kt")
        assertTrue(dao.contains("fun reserveSingleEnrollment("))
        assertTrue(dao.contains("fun finalizeSingleEnrollment("))
        assertTrue(dao.contains("enabled = 1 AND enrollmentSetupComplete = 1"))
        assertTrue(dao.contains("AND enrollmentIssuedAtEpochMillis IS NULL"))
        assertFalse(
            "Enrollment identity must never be overwritten by matching only a server URL.",
            dao.contains("fun updateEnrollmentByUrl("),
        )

        val enrollment = read(root, "app/src/main/java/com/openlattice/chronicle/Enrollment.kt")
        val recovery = read(
            root,
            "app/src/main/java/com/openlattice/chronicle/EnrollmentRecoveryManager.kt",
        )
        assertTrue(enrollment.contains("reserveSingleEnrollment"))
        assertTrue(enrollment.contains("pendingEnrollmentAccessCode = enrollmentAccessCode"))
        assertTrue(enrollment.contains("pendingProposedApiKey = proposedApiKey"))
        assertTrue(enrollment.contains("pendingEnrollmentSourceDeviceJson = sourceDeviceJson"))
        assertTrue(
            "Enrollment replay must use the encrypted device snapshot, not rebuild mutable OS fields.",
            recovery.contains("decodePendingEnrollmentSourceDevice(request)"),
        )
        assertFalse(
            "Rebuilding a SourceDevice changes the server-bound request after an OS update.",
            recovery.contains("getDevice(request.sourceDeviceId)"),
        )
        assertTrue(
            "The possible-request boundary must be durable before the remote enrollment call.",
            recovery.indexOf("markPendingEnrollmentRequestStarted") < recovery.indexOf(").enroll("),
        )

        val manualEnrollment = read(
            root,
            "app/src/main/java/com/openlattice/chronicle/ServerEnrollmentActivity.kt",
        )
        assertTrue(manualEnrollment.contains("const val MAX_SERVERS = 1"))
        assertTrue(manualEnrollment.contains("BuildConfig.DISTRIBUTION_CHANNEL != \"RESEARCH\""))
    }

    @Test
    fun enrollmentUsesAuthoritativePreviewAndBindsFinalRequestToItsDigest() {
        val root = buildRoot()
        val api = read(root, "app/src/main/java/com/openlattice/chronicle/api/ChronicleStudyApi.kt")
        val enrollment = read(root, "app/src/main/java/com/openlattice/chronicle/Enrollment.kt")

        assertTrue(api.contains("fun getEnrollmentPreview("))
        assertTrue(api.contains("X-Chronicle-Manifest-Digest"))
        assertTrue(api.contains("X-Chronicle-Enrollment-Attempt-Id"))
        assertTrue(api.contains("X-Chronicle-Proposed-Api-Key"))
        assertTrue(enrollment.contains("getEnrollmentPreview("))
        assertTrue(enrollment.contains("preview.manifest.participantPolicy"))
        assertTrue(enrollment.contains("preview.manifestDigest"))
        assertFalse(
            "Public enrollment must not use anonymous settings as its consent authority.",
            enrollment.contains("getDataCollectionSettings(studyId)"),
        )
    }

    @Test
    fun uploadCredentialsAreNotWrittenToPlainStringStores() {
        val root = buildRoot()
        val storeWriteTokens = listOf(
            ".putString(",
            ".putStringSet(",
            ".putExtra(",
            ".putStringArrayListExtra(",
        )
        val offenders = mainSources(root).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val writesStringStore = storeWriteTokens.any { line.contains(it) }
                val mentionsUploadCredential = uploadCredentialTerms.any { line.contains(it) }
                if (writesStringStore && mentionsUploadCredential) {
                    "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            "Upload credentials must not be written to SharedPreferences, intents, or other plain string stores. Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun enrollmentSettingsUsesEncryptedPreferencesOnly() {
        val source = read(buildRoot(), "app/src/main/java/com/openlattice/chronicle/preferences/EnrollmentSettings.kt")
        assertTrue(source.contains("EncryptedPrefsHelper.getEncryptedPrefs(context)"))
        assertFalse(source.contains("context.getSharedPreferences"))
        assertTrue(source.contains(".putString(PARTICIPANT_ID, _participantId)"))
        assertTrue(source.contains(".putString(STUDY_ID, _studyId.toString())"))
        assertTrue(source.contains("uploadServerDao().getConfiguredServer()"))
        assertTrue(source.contains("server.studyId == studyId.toString()"))
        assertTrue(source.contains("server.participantId == participantId"))
    }

    @Test
    fun legacyStateCannotInventOrFallbackToAnOperatorDestination() {
        val root = buildRoot()
        val migrationHelper = read(
            root,
            "collection-base/src/main/java/com/openlattice/chronicle/storage/ServerMigrationHelper.kt",
        )
        val notifications = read(
            root,
            "app/src/main/java/com/openlattice/chronicle/services/notifications/NotificationsWorker.kt",
        )
        val enrollmentMonitor = read(
            root,
            "app/src/main/java/com/openlattice/chronicle/services/enrollment/EnrollmentMonitoringWorker.kt",
        )
        val enrollmentSettings = read(
            root,
            "app/src/main/java/com/openlattice/chronicle/preferences/EnrollmentSettings.kt",
        )

        assertFalse(migrationHelper.contains("url = PRODUCTION"))
        assertFalse(migrationHelper.contains("dao.insert("))
        assertFalse(notifications.contains("createRetrofitAdapter(PRODUCTION)"))
        assertFalse(enrollmentMonitor.contains("?: PRODUCTION"))
        assertTrue(notifications.contains("completeServerForIdentity("))
        assertTrue(enrollmentMonitor.contains("completeServerForIdentity("))
        assertTrue(enrollmentSettings.contains("clearOrphanedEnrollmentState()"))
        assertTrue(enrollmentSettings.contains("clearDirectBootSensorBuffer(context)"))
    }

    @Test
    fun encryptedPrefsHelperRefusesPlaintextFallback() {
        val source = read(
            buildRoot(),
            // Moved from :collection-base in the tranche 7 storage/contract split.
            "collection-contracts/src/main/java/com/openlattice/chronicle/preferences/EncryptedPrefsHelper.kt",
        )
        assertTrue(source.contains("EncryptedSharedPreferences.create"))
        assertTrue(source.contains("refusing plaintext fallback"))
        assertTrue(source.contains("throw IllegalStateException(\"Secure preference storage is unavailable\", e)"))
    }
}
