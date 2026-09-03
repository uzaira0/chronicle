@file:Suppress("UNNECESSARY_LATEINIT")

package com.openlattice.chronicle.preferences

import android.content.Context
import android.os.Build
import com.openlattice.chronicle.R
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.serialization.JsonSerializer.deserializePropertyTypeIds
import com.openlattice.chronicle.serialization.JsonSerializer.serializePropertyTypeIds
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.AUTH_MODE_DEVICE_ID
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.directboot.clearDirectBootSensorBuffer
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.util.*

// PARTICIPANT_ID / STUDY_ID are owned by :collection-base (ServerMigrationHelper needs
// them) and re-exported here so existing import sites stay unchanged.
const val PARTICIPANT_ID = com.openlattice.chronicle.constants.PARTICIPANT_ID
const val AWARENESS_NOTIFICATIONS_ENABLED = "notificationsEnabled"
const val STUDY_ID = com.openlattice.chronicle.constants.STUDY_ID
const val ORGANIZATION_ID = "organizationId"
const val DEVICE_ID = "deviceId"
const val PARTICIPATION_STATUS = "participationStatus"
const val PROPERTY_TYPE_IDS = "com.openlattice.PropertyTypeIds"
const val MOBILE_REMINDER_REQUEST_CODES = "mobileReminderRequestCodes"

val INVALID_STUDY_ID = UUID(0, 0)

class EnrollmentSettings(private val context: Context) {
    private val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private var participantId: String
    private var studyId: UUID

    private lateinit var chronicleDb: ChronicleDb
    private lateinit var userStorageQueue: UserStorageQueue

    init {
        val studyIdString = settings.getString(STUDY_ID, "") ?: ""
        participantId = settings.getString(PARTICIPANT_ID, "") ?: ""
        studyId =
            if (Utils.isValidUUID(studyIdString)) UUID.fromString(studyIdString) else INVALID_STUDY_ID

        chronicleDb = ChronicleDb.getInstance(context)
        userStorageQueue = chronicleDb.userQueueEntryData()
    }

    fun isEnrolled(): Boolean {
        if (studyId == INVALID_STUDY_ID || participantId.isBlank()) return false
        val server = try {
            // This legacy synchronous API is used by Activity lifecycle callbacks. Room rejects
            // DAO reads on the main thread, so preserve the API while performing its authoritative
            // single-server lookup on the IO dispatcher.
            runBlocking(Dispatchers.IO) {
                chronicleDb.uploadServerDao().getConfiguredServer()
            }
        } catch (_: RuntimeException) {
            // A transient storage failure is not evidence that the enrollment disappeared.
            return false
        }
        if (server == null || server.studyId != studyId.toString() || server.participantId != participantId) {
            clearOrphanedEnrollmentState()
            return false
        }
        return server.enabled &&
            server.enrollmentSetupComplete &&
            server.studyId == studyId.toString() &&
            server.participantId == participantId &&
            server.sourceDeviceId.isNotBlank() &&
            when (server.authMode) {
                AUTH_MODE_API_KEY -> !server.apiKey.isNullOrBlank()
                AUTH_MODE_DEVICE_ID -> true
                else -> false
            }
    }

    /** Clears non-authoritative identity/configuration when Room has no matching destination. */
    private fun clearOrphanedEnrollmentState() {
        val orphanedStudyId = studyId
        if (orphanedStudyId != INVALID_STUDY_ID) {
            runCatching { EncryptionSettingStore.of(context).evict(orphanedStudyId) }
        }
        clearEnrollment()
    }

    fun getParticipantId(): String {
        return participantId
    }

    fun getStudyId(): UUID {
        return studyId
    }

    fun setParticipantId(_participantId: String) {
        participantId = _participantId
        settings.edit()
            .putString(PARTICIPANT_ID, _participantId)
            .commit()
    }

    fun setStudyId(_studyId: UUID) {
        val changed = studyId != _studyId
        if (changed) InteractionPolicySettings.invalidateMemoryCache()
        val editor = settings.edit().putString(STUDY_ID, _studyId.toString())
        if (changed) editor.remove(INTERACTION_POLICY_SNAPSHOT_KEY)
        check(editor.commit()) { "Failed to persist study id" }
        studyId = _studyId
    }

    fun setAwarenessNotificationsEnabled(notificationsEnabled: Boolean) {
        settings.edit()
            .putBoolean(AWARENESS_NOTIFICATIONS_ENABLED, notificationsEnabled)
            .apply()
    }

    fun getAwarenessNotificationsEnabled(): Boolean {
        return settings.getBoolean(AWARENESS_NOTIFICATIONS_ENABLED, false)
    }

    fun setPropertyTypeIds(propertyTypeIds: Map<FullQualifiedName, UUID>) {
        settings
            .edit()
            .putString(PROPERTY_TYPE_IDS, serializePropertyTypeIds(propertyTypeIds))
            .apply()
    }

    fun getPropertyTypeIds(): Map<FullQualifiedName, UUID> {
        return deserializePropertyTypeIds(settings.getString(PROPERTY_TYPE_IDS, ""))
    }

    fun setParticipationStatus(participationStatus: ParticipationStatus) {
        val changed = getParticipationStatus() != participationStatus
        if (changed) InteractionPolicySettings.invalidateMemoryCache()
        val editor = settings.edit()
            .putString(PARTICIPATION_STATUS, participationStatus.toString())
        if (changed) editor.remove(INTERACTION_POLICY_SNAPSHOT_KEY)
        check(editor.commit()) { "Failed to persist participation status" }
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
        settings.edit()
            .putStringSet(MOBILE_REMINDER_REQUEST_CODES, requestCodes.map(Int::toString).toSet())
            .apply()
    }

    /** Clears enrollment identity after a completed withdrawal while preserving app-level preferences. */
    fun clearEnrollment() {
        InteractionPolicySettings.invalidateMemoryCache()
        // No direct-boot collection for a withdrawn participant (fail closed).
        clearDirectBootSensorSnapshot(context)
        check(clearDirectBootSensorBuffer(context)) {
            "Failed to clear direct-boot research samples"
        }
        HealthConnectScopeStore.of(context).clear()
        LocalUploadDiagnosticsStore.of(context).clear()
        participantId = ""
        studyId = INVALID_STUDY_ID
        check(settings.edit()
            .remove(PARTICIPANT_ID)
            .remove(STUDY_ID)
            .remove(ORGANIZATION_ID)
            .remove(PARTICIPATION_STATUS)
            .remove(PROPERTY_TYPE_IDS)
            .remove(AWARENESS_NOTIFICATIONS_ENABLED)
            .remove(MOBILE_REMINDER_REQUEST_CODES)
            .remove(INTERACTION_POLICY_SNAPSHOT_KEY)
            .remove(context.getString(R.string.identify_user))
            .remove(context.getString(R.string.current_user))
            .commit()) {
            "Failed to clear persisted enrollment state"
        }
    }


    /**
     * The pre-Phase-7 inline target-user write — the regression baseline: appends a
     * [UserQueueEntry] to the `userQueue` Room table and writes the `current_user`
     * EncryptedSharedPreferences key. The `runBlocking { launch { } }` shape is
     * preserved verbatim: the queue insert is launched concurrently with the
     * `current_user` pref write.
     *
     * The Phase 7A migration-switch routing (module-manager path vs this legacy
     * path) lives in `:app`'s `collection.identification.TargetUserRouter`, NOT
     * here — `preferences` must not depend on `collection.*`. Callers route
     * through that router; this method is the legacy fallthrough it delegates to.
     */
    fun setTargetUser(user: String) {
        runBlocking {
            launch {
                userStorageQueue.insertEntry(UserQueueEntry(user = user))
            }
            check(
                settings
                    .edit()
                    .putString(context.getString(R.string.current_user), user)
                    .commit()
            ) {
                "Failed to persist current-user preference"
            }
        }
    }

    fun getCurrentUser(): String? {
        return settings.getString(
            context.getString(R.string.current_user),
            context.getString(R.string.user_unassigned)
        )
    }

    fun isUserIdentificationEnabled(): Boolean {
        return isUserIdentificationStudyAuthorized() &&
            settings.getBoolean(context.getString(R.string.identify_user), false)
    }

    /** The enrollment manifest is authority; the local preference may only narrow this scope. */
    fun isUserIdentificationStudyAuthorized(): Boolean = try {
        configuredStudyModuleEnabled(
            chronicleDb.uploadServerDao().getConfiguredServer(),
            studyId,
            participantId,
            CollectionModuleId.USER_IDENTIFICATION,
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

    fun closeDb() {
        // Singleton DB — no close needed
    }

    companion object {
        /**
         * Clears only enrollment identity and study-scoped encryption settings without opening
         * Room. This is reserved for the explicit local-store recovery screen, where constructing
         * [EnrollmentSettings] would immediately retry the failed database open.
         */
        fun clearForLocalStoreRecovery(context: Context) {
            InteractionPolicySettings.invalidateMemoryCache()
            clearDirectBootSensorSnapshot(context)
            check(clearDirectBootSensorBuffer(context)) {
                "Failed to clear direct-boot research samples during local-store recovery"
            }
            val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
            val existingStudyId = prefs.getString(STUDY_ID, null)
                ?.takeIf(Utils::isValidUUID)
                ?.let(UUID::fromString)
            existingStudyId?.let { EncryptionSettingStore.of(context).evict(it) }
            check(
                prefs.edit()
                    .remove(PARTICIPANT_ID)
                    .remove(STUDY_ID)
                    .remove(ORGANIZATION_ID)
                    .remove(PARTICIPATION_STATUS)
                    .remove(PROPERTY_TYPE_IDS)
                    .remove(AWARENESS_NOTIFICATIONS_ENABLED)
                    .remove(MOBILE_REMINDER_REQUEST_CODES)
                    .remove(INTERACTION_POLICY_SNAPSHOT_KEY)
                    .remove(context.getString(R.string.identify_user))
                    .remove(context.getString(R.string.current_user))
                    .commit(),
            ) {
                "Failed to clear enrollment state during local-store recovery"
            }
        }
    }

}

fun getDevice(deviceInstanceId: String): AndroidDevice {
    return AndroidDevice(
        deviceInstanceId,
        Build.MODEL,
        Build.VERSION.CODENAME,
        Build.BRAND,
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT.toString(),
        Build.PRODUCT,
        deviceInstanceId,
        mapOf()
    )
}
