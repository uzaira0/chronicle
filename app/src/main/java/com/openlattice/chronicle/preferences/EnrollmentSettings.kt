@file:Suppress("UNNECESSARY_LATEINIT")

package com.openlattice.chronicle.preferences

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import com.openlattice.chronicle.R
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.hasDeviceCredential
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.currentUserOr
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.constants.PARTICIPANT_ID as ENROLLMENT_PARTICIPANT_ID
import com.openlattice.chronicle.constants.STUDY_ID as ENROLLMENT_STUDY_ID
import com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.directboot.clearDirectBootSensorBuffer
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.utils.Utils
import com.openlattice.chronicle.security.InvitedServerCa
import java.util.*

// Enrollment identity keys live in :collection-base and are re-exported here.
const val PARTICIPANT_ID = ENROLLMENT_PARTICIPANT_ID
const val AWARENESS_NOTIFICATIONS_ENABLED = "notificationsEnabled"
const val STUDY_ID = ENROLLMENT_STUDY_ID
const val ORGANIZATION_ID = "organizationId"
const val PARTICIPATION_STATUS = "participationStatus"
const val MOBILE_REMINDER_REQUEST_CODES = "mobileReminderRequestCodes"
/** Participant's user-identification switch. Stored key, never a translatable resource. */
const val IDENTIFY_USER = "identify_user"
/** Mirror of the current target user. Stored key, never a translatable resource. */
const val CURRENT_USER = "current_user"

val INVALID_STUDY_ID = UUID(0, 0)

class EnrollmentSettings(private val context: Context) {
    private val settings = try {
        EncryptedPrefsHelper.getEncryptedPrefs(context)
    } catch (error: SecurePreferencesUnavailableException) {
        throw LocalStoreRecoveryRequiredException(
            LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, error,
        )
    }
    private var participantId: String
    private var studyId: UUID

    private val chronicleDb: ChronicleDb by lazy { ChronicleDb.getInstance(context) }
    private val userStorageQueue: UserStorageQueue by lazy { chronicleDb.userQueueEntryData() }

    init {
        val studyIdString = settings.getString(STUDY_ID, "") ?: ""
        participantId = settings.getString(PARTICIPANT_ID, "") ?: ""
        studyId =
            if (Utils.isValidUUID(studyIdString)) UUID.fromString(studyIdString) else INVALID_STUDY_ID

    }

    fun isEnrolled(): Boolean = isEnrolled(strictStorage = false)

    /** Persistence gates retry on storage faults instead of treating them as a consent refusal. */
    fun isEnrolledOrThrow(): Boolean = isEnrolled(strictStorage = true)

    /** Dashboard access survives a participant pausing uploads. */
    @WorkerThread
    fun hasConfiguredEnrollmentOrThrow(): Boolean = configuredEnrollment(strictStorage = true) != null

    internal fun hasEnrollmentIdentity(): Boolean = studyId != INVALID_STUDY_ID && participantId.isNotBlank()

    private fun isEnrolled(strictStorage: Boolean): Boolean {
        if (!hasEnrollmentIdentity()) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ResearchPersistenceGate.executeAsync {
                try { isEnrolled(strictStorage = true) }
                catch (error: Exception) {
                    ResearchPersistenceGate.closeForPersistenceFailure()
                    Log.e("EnrollmentSettings", "Queued enrollment reconciliation failed; collection closed", error)
                }
            }
            return ResearchPersistenceGate.isEnrolledSnapshot(studyId, participantId)
        }
        return configuredEnrollment(strictStorage)?.enabled == true
    }

    private fun configuredEnrollment(strictStorage: Boolean): UploadServerEntity? {
        if (!hasEnrollmentIdentity()) return null
        val server = try {
            chronicleDb.uploadServerDao().getConfiguredServer()
        } catch (error: RuntimeException) {
            // A transient storage failure is not evidence that the enrollment disappeared.
            if (strictStorage) throw error
            return null
        }
        if (server == null || server.studyId != studyId.toString() || server.participantId != participantId) {
            reconcileOrphanedEnrollment()
            return null
        }
        return server.takeIf {
            server.enrollmentSetupComplete &&
            server.sourceRandomDeviceId.isNotBlank() &&
            server.hasDeviceCredential()
        }
    }

    private fun reconcileOrphanedEnrollment() {
        try {
            val reconcile = {
                ResearchPersistenceGate.enrollmentMutation(context) {
                    val currentSettings = EnrollmentSettings(context)
                    val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
                    if (currentSettings.getStudyId() == studyId && currentSettings.getParticipantId() == participantId &&
                        (current == null || current.studyId != studyId.toString() || current.participantId != participantId)) {
                        currentSettings.clearOrphanedEnrollmentState()
                    }
                }
            }
            if (ResearchPersistenceGate.canStopOnCurrentThread() && !chronicleDb.inTransaction()) {
                reconcile()
            } else {
                ResearchPersistenceGate.executeAsync(reconcile)
            }
        } catch (error: Exception) {
            Log.e("EnrollmentSettings", "Enrollment reconciliation could not be persisted", error)
            throw LocalStoreRecoveryRequiredException(
                LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, error,
            )
        }
    }

    /** Clears non-authoritative identity/configuration when Room has no matching destination. */
    private fun clearOrphanedEnrollmentState() {
        // A transient or replaced destination is not evidence of explicit erasure. Keep the
        // local encryption material and data so an enrollment recovery can still reconcile them.
        clearEnrollment()
    }

    fun getParticipantId(): String {
        return participantId
    }

    fun getStudyId(): UUID {
        return studyId
    }

    fun setParticipantId(newParticipantId: String) = ResearchPersistenceGate.enrollmentUpdate(context) {
        participantId = newParticipantId
        checkLocalStoreWrite(settings.edit().putString(PARTICIPANT_ID, newParticipantId).commit()) { "Failed to persist participant id" }
    }

    fun setStudyId(newStudyId: UUID) = ResearchPersistenceGate.enrollmentUpdate(context) {
        val changed = studyId != newStudyId
        if (changed) InteractionPolicySettings.invalidateMemoryCache()
        val editor = settings.edit().putString(STUDY_ID, newStudyId.toString())
        if (changed) editor.remove(INTERACTION_POLICY_SNAPSHOT_KEY)
        checkLocalStoreWrite(editor.commit()) { "Failed to persist study id" }
        studyId = newStudyId
    }

    fun setAwarenessNotificationsEnabled(notificationsEnabled: Boolean) {
        settings.edit()
            .putBoolean(AWARENESS_NOTIFICATIONS_ENABLED, notificationsEnabled)
            .apply()
    }

    fun getAwarenessNotificationsEnabled(): Boolean {
        return settings.getBoolean(AWARENESS_NOTIFICATIONS_ENABLED, false)
    }

    

    

    fun setParticipationStatus(participationStatus: ParticipationStatus) =
        ResearchPersistenceGate.enrollmentUpdate(context) {
        val changed = getParticipationStatus() != participationStatus
        if (changed) InteractionPolicySettings.invalidateMemoryCache()
        val editor = settings.edit()
            .putString(PARTICIPATION_STATUS, participationStatus.toString())
        if (changed) editor.remove(INTERACTION_POLICY_SNAPSHOT_KEY)
        checkLocalStoreWrite(editor.commit()) { "Failed to persist participation status" }
        // A participant no longer ENROLLED must not be collected for during a future
        // direct-boot window; the snapshot is rewritten from live gate reads when (if)
        // collection resumes. Fail closed.
        if (changed && participationStatus != ParticipationStatus.ENROLLED) {
            clearDirectBootSensorSnapshot(context)
        }
    }

    fun getParticipationStatus(): ParticipationStatus {
        val status = settings.getString(PARTICIPATION_STATUS, ParticipationStatus.UNKNOWN.name)
            ?: ParticipationStatus.UNKNOWN.name

        return try {
            ParticipationStatus.valueOf(status)
        } catch (_: IllegalArgumentException) {
            ParticipationStatus.UNKNOWN
        }
    }

    fun getMobileReminderRequestCodes(): Set<Int> = settings
        .getStringSet(MOBILE_REMINDER_REQUEST_CODES, emptySet())
        .orEmpty()
        .mapNotNull(String::toIntOrNull)
        .toSet()

    fun setMobileReminderRequestCodes(requestCodes: Set<Int>) {
        checkLocalStoreWrite(settings.edit()
            .putStringSet(MOBILE_REMINDER_REQUEST_CODES, requestCodes.map(Int::toString).toSet())
            .commit()) { "Unable to persist survey cancellation handles" }
    }

    /** Clears identity; withdrawal or confirmed server erasure also removes retained local data. */
    fun clearEnrollment(eraseLocalData: Boolean = false, diagnostics: LocalUploadDiagnosticsStore? = null) =
        ResearchPersistenceGate.enrollmentUpdate(context) {
        InvitedServerCa.clear(context)
        InteractionPolicySettings.invalidateMemoryCache()
        eraseSurveyArtifacts(context)
        // Retire collection permission even when identity is temporarily orphaned.
        clearDirectBootSensorSnapshot(context)
        if (eraseLocalData) {
            checkLocalStoreWrite(clearDirectBootSensorBuffer(context)) {
                "Failed to clear direct-boot research samples"
            }
        }
        HealthConnectScopeStore.of(context).clear()
        if (eraseLocalData) (diagnostics ?: LocalUploadDiagnosticsStore.of(context)).clear()
        checkLocalStoreWrite(settings.edit()
            .removeEnrollmentIdentity()
            .commit()) {
            "Failed to clear persisted enrollment state"
        }
        participantId = ""
        studyId = INVALID_STUDY_ID
    }


    /**
     * Appends a [UserQueueEntry] to the `userQueue` Room table and writes the `current_user`
     * EncryptedSharedPreferences key. Room commits before the preference mirror is updated, and
     * the displayed selection is derived from that same committed history.
     *
     * Callers route through `:app`'s `collection.identification.TargetUserRouter`, which delegates
     * here; `preferences` must not depend on `collection.*`.
     */
    fun setTargetUser(user: String) {
        userStorageQueue.insertEntries(listOf(UserQueueEntry(user = user)))
        checkLocalStoreWrite(settings.edit().putString(CURRENT_USER, user).commit()) {
            "Failed to persist current-user preference"
        }
    }

    fun getCurrentUser(): String? {
        return DeviceUser.current(userStorageQueue.currentUserOr(DeviceUser.NOT_SET.stored))
    }

    fun isUserIdentificationEnabled(): Boolean {
        return isUserIdentificationStudyAuthorized() && isUserIdentificationSwitchOn()
    }

    /** The participant's own switch alone; the usage sink applies the study's decision per event time. */
    fun isUserIdentificationSwitchOn(): Boolean = settings.getBoolean(IDENTIFY_USER, false)

    /** Current study settings and consent authorize the module; the local toggle narrows it. */
    fun isUserIdentificationStudyAuthorized(): Boolean = try {
        configuredStudyModuleEnabled(
            chronicleDb.uploadServerDao().getConfiguredServer(),
            studyId,
            participantId,
            CollectionModuleId.USER_IDENTIFICATION,
            CollectionLoopStore.of(context)
                .loadAll()[CollectionModuleId.USER_IDENTIFICATION],
        )
    } catch (_: RuntimeException) {
        false
    }

    fun toggleBatteryOptimizationDialog(enable: Boolean) {
        settings
            .edit()
            .putBoolean(context.getString(R.string.disable_battery_optimization_dialog), enable)
            .apply()
    }

    fun isBatteryOptimizationDialogEnabled(): Boolean {
        // Default true: nothing in production ever toggled this on, so the Doze-exemption
        // prompt was dead code and enrolled devices were never asked to exempt Chronicle
        // from battery optimization. Tests (and a future settings toggle) can still opt out.
        return settings.getBoolean(
            context.getString(R.string.disable_battery_optimization_dialog),
            true
        )
    }

    fun toggleHibernationExemptionDialog(enable: Boolean) {
        settings
            .edit()
            .putBoolean(context.getString(R.string.disable_hibernation_exemption_dialog), enable)
            .apply()
    }

    fun isHibernationExemptionDialogEnabled(): Boolean {
        return settings.getBoolean(
            context.getString(R.string.disable_hibernation_exemption_dialog),
            true
        )
    }

    fun toggleOemGuidanceDialog(enable: Boolean) {
        settings
            .edit()
            .putBoolean(context.getString(R.string.disable_oem_guidance_dialog), enable)
            .apply()
    }

    fun isOemGuidanceDialogEnabled(): Boolean {
        return settings.getBoolean(
            context.getString(R.string.disable_oem_guidance_dialog),
            true
        )
    }

    fun toggleBackgroundDataDialog(enable: Boolean) {
        settings
            .edit()
            .putBoolean(context.getString(R.string.disable_background_data_dialog), enable)
            .apply()
    }

    fun isBackgroundDataDialogEnabled(): Boolean {
        return settings.getBoolean(
            context.getString(R.string.disable_background_data_dialog),
            true
        )
    }

    fun closeDb() {
        // Singleton DB — no close needed
    }

    companion object {
        /**
         * Clears only enrollment identity and the direct-boot snapshot and buffer, without opening
         * Room. This is reserved for the explicit local-store recovery screen, where constructing
         * [EnrollmentSettings] would immediately retry the failed database open.
         */
        fun clearForLocalStoreRecovery(context: Context) {
            InvitedServerCa.clear(context)
            InteractionPolicySettings.invalidateMemoryCache()
            clearDirectBootSensorSnapshot(context)
            checkLocalStoreWrite(clearDirectBootSensorBuffer(context)) {
                "Failed to clear direct-boot research samples during local-store recovery"
            }
            val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
            checkLocalStoreWrite(
                prefs.edit()
                    .removeEnrollmentIdentity()
                    .commit(),
            ) {
                "Failed to clear enrollment state during local-store recovery"
            }
        }
    }

}

private fun SharedPreferences.Editor.removeEnrollmentIdentity() =
    remove(PARTICIPANT_ID)
        .remove(STUDY_ID)
        .remove(ORGANIZATION_ID)
        .remove(PARTICIPATION_STATUS)
        .remove(AWARENESS_NOTIFICATIONS_ENABLED)
        .remove(MOBILE_REMINDER_REQUEST_CODES)
        .remove(INTERACTION_POLICY_SNAPSHOT_KEY)
        .remove(IDENTIFY_USER)
        .remove(CURRENT_USER)

fun getDevice(randomDeviceId: String): AndroidDevice {
    return AndroidDevice(
        randomDeviceId,
        Build.MODEL,
        Build.VERSION.CODENAME,
        Build.BRAND,
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT.toString(),
        Build.PRODUCT,
        randomDeviceId,
    )
}
