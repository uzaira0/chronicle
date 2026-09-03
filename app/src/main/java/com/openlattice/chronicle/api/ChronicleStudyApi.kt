package com.openlattice.chronicle.api

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.sources.SourceDevice
import org.apache.olingo.commons.api.edm.FullQualifiedName
import retrofit2.http.*
import java.util.*

interface ChronicleStudyApi {
    companion object {
        const val SERVICE = "/chronicle"
        const val V3 = "/v3/study"
        const val V4 = "/v4/study"
        const val V3_BASE = SERVICE + V3
        const val V4_BASE = SERVICE + V4

        const val STUDY_ID = "studyId"
        const val PARTICIPANT_ID = "participantId"
        const val SOURCE_DEVICE_ID = "sourceDeviceId"

        const val STUDY_ID_PATH = "/{$STUDY_ID}"
        const val PARTICIPANT_ID_PATH = "/{$PARTICIPANT_ID}"
        const val SOURCE_DEVICE_ID_PATH = "/{$SOURCE_DEVICE_ID}"
        const val PARTICIPANT_PATH = "/participant"
        const val ENROLL_PATH = "/enroll"
        const val ENROLLMENT_PREVIEW_PATH = "/enrollment-preview"
        const val ANDROID_PATH = "/android"
        const val SENSORS_PATH = "/sensors"
        const val BATTERY_PATH = "/battery"
        const val CONNECTIVITY_STATE_PATH = "/connectivity-state"
        const val DEVICE_SETTINGS_PATH = "/device-settings"
        const val UPLOAD_DIAGNOSTICS_PATH = "/upload-diagnostics"
        const val ENCRYPTED_PATH = "/encrypted"
        const val SETTINGS_PATH = "/settings"
        const val COLLECTION_ACK_PATH = "/collection-ack"
        const val AVAILABILITY_PATH = "/availability"
        const val VERIFY_PATH = "/verify"
        const val STATUS_PATH = "/status"
        const val NOTIFICATIONS_PATH = "/notifications"
        const val QUESTIONNAIRES_PATH = "/questionnaires"
        const val EDM_PATH = "/edm"

        // Legacy v1 base path (used by older endpoints still needed by the app)
        const val LEGACY_CONTROLLER = "/study"
        const val LEGACY_BASE = SERVICE + LEGACY_CONTROLLER

        // Legacy v2 base path
        const val V2_CONTROLLER = "/v2"
        const val V2_BASE = SERVICE + V2_CONTROLLER
    }

    @GET("/health")
    fun health(): retrofit2.Response<okhttp3.ResponseBody>

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLL_PATH)
    fun enroll(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") deviceInstanceId: String,
        @Body datasource: SourceDevice,
        @Header("X-Chronicle-Enrollment-Code") enrollmentCode: String? = null,
        @Header("X-Chronicle-Manifest-Digest") manifestDigest: String? = null,
        @Header("X-Chronicle-Enrollment-Attempt-Id") enrollmentAttemptId: String? = null,
        @Header("X-Chronicle-Proposed-Api-Key") proposedApiKey: String? = null,
    ): EnrollmentResponse

    @GET(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLLMENT_PREVIEW_PATH)
    fun getEnrollmentPreview(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Enrollment-Code") enrollmentCode: String,
    ): EnrollmentPreviewResponse

    @DELETE("/chronicle/v4/mobile/enrollments/current")
    fun withdrawCurrentEnrollment(
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String,
        @Header("X-Chronicle-Withdrawal-Request-Id") withdrawalRequestId: String,
    ): EnrollmentWithdrawalResponse

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + "/reminders")
    fun getMobileReminderConfiguration(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String,
    ): MobileReminderConfiguration

    @GET(V3_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + VERIFY_PATH)
    fun isKnownParticipant(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
    ): Boolean

    // Upload methods accept BOTH X-Chronicle-Device-Id (used by upstream Chronicle) AND X-Api-Key
    // (used by current self-host servers). The client passes whichever matches the server auth mode;
    // Retrofit omits null headers, so each server only sees what it expects.
    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH)
    fun uploadAndroidUsageEventData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: ChronicleData,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + BATTERY_PATH)
    fun uploadAndroidBatteryData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<BatterySample>,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + CONNECTIVITY_STATE_PATH)
    fun uploadAndroidConnectivityStateData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidConnectivityStateEvent>,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + DEVICE_SETTINGS_PATH)
    fun uploadAndroidDeviceSettingsData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidDeviceSettingsEvent>,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + UPLOAD_DIAGNOSTICS_PATH)
    fun uploadAndroidUploadDiagnostics(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidUploadDiagnosticEvent>,
    ): List<String>

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + ENCRYPTED_PATH)
    fun uploadAndroidEncryptedData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<EncryptedEnvelope>,
    ): Int

    @GET(V3_BASE + STUDY_ID_PATH + SETTINGS_PATH + "/type/AndroidSensor")
    fun getAndroidSensorSettings(
        @Path(STUDY_ID) studyId: UUID,
    ): AndroidSensorSetting

    // Per-study payload-encryption setting (HIPAA-2028 W2). Public GET, mirroring
    // getAndroidSensorSettings / getDataCollectionSettings. The body carries the study's
    // PUBLIC key only. A study without e2ee may have no Encryption setting at all, so the
    // call site MUST tolerate a non-2xx (404/403)/absent response and treat it as disabled.
    @GET(V3_BASE + STUDY_ID_PATH + SETTINGS_PATH + "/type/Encryption")
    fun getStudyEncryptionSetting(
        @Path(STUDY_ID) studyId: UUID,
    ): StudyEncryptionSetting

    // Generalized per-module data collection setting (collection loop closure). The
    // server always resolves a value (stored setting -> legacy bridge -> safe default),
    // so the response is never empty. Public GET, mirroring getAndroidSensorSettings.
    @GET(V3_BASE + STUDY_ID_PATH + SETTINGS_PATH + "/type/DataCollection")
    fun getDataCollectionSettings(
        @Path(STUDY_ID) studyId: UUID,
    ): AndroidDataCollectionSetting

    // Reports a participant's on-device acknowledgment of newly-enabled collection
    // modules. Auth mirrors the v4 uploads (X-Chronicle-Device-Id and/or X-Api-Key;
    // Retrofit omits null headers).
    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + COLLECTION_ACK_PATH)
    fun reportCollectionAck(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body acknowledgment: CollectionAcknowledgment,
    ): OK

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH
        + ANDROID_PATH + SENSORS_PATH + AVAILABILITY_PATH)
    fun reportAndroidSensorAvailability(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body availability: AndroidDeviceSensorAvailability,
    ): Int

    @GET(LEGACY_BASE + STUDY_ID_PATH + PARTICIPANT_ID_PATH + STATUS_PATH)
    fun getParticipationStatus(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
    ): ParticipationStatus?

    @GET(LEGACY_BASE + STUDY_ID_PATH + NOTIFICATIONS_PATH)
    fun isNotificationsEnabled(
        @Path(STUDY_ID) studyId: UUID,
    ): Boolean?

    @GET(LEGACY_BASE + STUDY_ID_PATH + QUESTIONNAIRES_PATH)
    fun getStudyQuestionnaires(
        @Path(STUDY_ID) studyId: UUID,
    ): Map<UUID, Map<FullQualifiedName, Set<Any>>>?

    @POST(V2_BASE + EDM_PATH)
    fun getPropertyTypeIds(
        @Body propertyTypeFqns: Set<FullQualifiedName>,
    ): Map<FullQualifiedName, UUID>?
}
