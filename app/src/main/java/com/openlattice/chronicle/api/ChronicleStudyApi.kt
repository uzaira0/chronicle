package com.openlattice.chronicle.api

import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import java.util.*
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path

interface ChronicleStudyApi {
    companion object {
        const val SERVICE = "/chronicle"
        const val V3 = "/v3/study"
        const val V4 = "/v4/study"
        const val V3_BASE = SERVICE + V3
        const val V4_BASE = SERVICE + V4

        const val STUDY_ID = "studyId"
        const val PARTICIPANT_ID = "participantId"
        const val RANDOM_DEVICE_ID_HEADER = ChronicleHeaders.RANDOM_DEVICE_ID_HEADER
        const val API_KEY_HEADER = ChronicleHeaders.API_KEY_HEADER

        const val STUDY_ID_PATH = "/{$STUDY_ID}"
        const val PARTICIPANT_ID_PATH = "/{$PARTICIPANT_ID}"
        const val PARTICIPANT_PATH = "/participant"
        const val ENROLL_PATH = "/enroll"
        const val ENROLLMENT_PREVIEW_PATH = "/enrollment-preview"
        const val ANDROID_PATH = "/android"
        const val SENSORS_PATH = "/sensors"
        const val BATTERY_PATH = "/battery"
        const val CONNECTIVITY_STATE_PATH = "/connectivity-state"
        const val DEVICE_SETTINGS_PATH = "/device-settings"
        const val UPLOAD_DIAGNOSTICS_PATH = "/upload-diagnostics"
        const val SETTINGS_PATH = "/settings"
        const val COLLECTION_ACK_PATH = "/collection-ack"
        const val AVAILABILITY_PATH = "/availability"
        const val STATUS_PATH = "/status"
        const val NOTIFICATIONS_PATH = "/notifications"
        const val QUESTIONNAIRES_PATH = "/questionnaires"
        const val EDM_PATH = "/edm"

    }

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLL_PATH)
    fun enroll(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(RANDOM_DEVICE_ID_HEADER) randomDeviceId: String,
        @Body datasource: SourceDevice,
        @Header(ChronicleHeaders.ENROLLMENT_CODE_HEADER) enrollmentCode: String? = null,
        @Header(ChronicleHeaders.MANIFEST_DIGEST_HEADER) manifestDigest: String? = null,
        @Header(ChronicleHeaders.ENROLLMENT_ATTEMPT_ID_HEADER) enrollmentAttemptId: String? = null,
        @Header(ChronicleHeaders.PROPOSED_API_KEY_HEADER) proposedApiKey: String? = null,
    ): EnrollmentResponse

    @GET(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLLMENT_PREVIEW_PATH)
    fun getEnrollmentPreview(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(ChronicleHeaders.ENROLLMENT_CODE_HEADER) enrollmentCode: String,
    ): EnrollmentPreviewResponse

    @DELETE("/chronicle/v4/mobile/enrollments/current")
    fun withdrawCurrentEnrollment(
        @Header(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String,
        @Header(ChronicleHeaders.WITHDRAWAL_REQUEST_ID_HEADER) withdrawalRequestId: String,
    ): EnrollmentWithdrawalResponse

    @GET(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + "/reminders")
    fun getMobileReminderSchedule(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String,
    ): MobileReminderConfiguration

    /** Mints one form's code; unlike the reminder manifest it leaves the device's other forms' codes live. */
    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + "/form-access-codes")
    fun createParticipantFormAccessCode(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String,
        @Body request: CreateParticipantFormAccessCodeRequest,
    ): ParticipantFormAccessCodeResponse

    @GET(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + STATUS_PATH)
    fun getDeviceParticipationStatus(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String,
    ): MobileParticipationStatus

    // Upload methods accept BOTH X-Chronicle-Random-Device-Id (used by upstream Chronicle) AND X-Api-Key
    // (used by current self-host servers). The client passes whichever matches the server auth mode;
    // Retrofit omits null headers, so each server only sees what it expects.
    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH)
    fun uploadAndroidUsageEventData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<ChronicleSample>,
    ): UploadReceipt

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + BATTERY_PATH)
    fun uploadAndroidBatteryData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<BatterySample>,
    ): UploadReceipt

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + CONNECTIVITY_STATE_PATH)
    fun uploadAndroidConnectivityStateData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidConnectivityStateEvent>,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + DEVICE_SETTINGS_PATH)
    fun uploadAndroidDeviceSettingsData(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidDeviceSettingsEvent>,
    ): Int

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + UPLOAD_DIAGNOSTICS_PATH)
    fun uploadAndroidUploadDiagnostics(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidUploadDiagnosticEvent>,
    ): UploadReceipt

    // Generalized per-module data collection setting (collection loop closure). The
    // server always resolves a value (stored setting -> legacy bridge -> safe default),
    // so the response is never empty. Public GET, mirroring getAndroidSensorSettings.
    @GET(V3_BASE + STUDY_ID_PATH + SETTINGS_PATH + "/type/DataCollection")
    fun getDataCollectionSettings(
        @Path(STUDY_ID) studyId: UUID,
    ): AndroidDataCollectionSetting

    // Reports a participant's on-device acknowledgment of newly-enabled collection
    // modules. Auth mirrors the v4 uploads (X-Chronicle-Random-Device-Id and/or X-Api-Key;
    // Retrofit omits null headers).
    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + COLLECTION_ACK_PATH)
    fun reportCollectionAck(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @Header(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body acknowledgment: CollectionAcknowledgment,
    ): OK

    @POST(V4_BASE + STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH
        + ANDROID_PATH + SENSORS_PATH + AVAILABILITY_PATH)
    fun reportAndroidSensorAvailability(
        @Path(STUDY_ID) studyId: UUID,
        @Path(PARTICIPANT_ID) participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body availability: CollectedUploadBody<AndroidDeviceSensorAvailability>,
    ): Int

}
