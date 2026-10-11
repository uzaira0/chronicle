package com.openlattice.chronicle.api

import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.DEVICE_ID_HEADER
import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.API_KEY_HEADER

import com.openlattice.chronicle.android.SensorUploadReceipt
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

/**
 * Upload surface for collectors excluded from the minimal Play artifact. It is intentionally
 * separate from [ChronicleStudyApi], allowing R8 to remove these endpoints and wire DTOs when
 * the restricted collection graph is unreachable.
 */
internal interface RestrictedChronicleStudyApi {
    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/app-network-usage")
    fun uploadAndroidAppNetworkUsageData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidAppNetworkUsageEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sensors")
    fun uploadAndroidSensorData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidSensorSample>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sensors/receipt")
    fun uploadAndroidSensorDataReceipt(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidSensorSample>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): SensorUploadReceipt

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/interaction")
    fun uploadAndroidInteractionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidInteractionEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-activity")
    fun uploadAndroidAudioActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidAudioActivityEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-content")
    fun uploadAndroidAudioContentData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidAudioContentEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/notification-activity")
    fun uploadAndroidNotificationActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidNotificationActivityEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sleep")
    fun uploadAndroidSleepData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidSleepEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/activity-recognition")
    fun uploadAndroidActivityRecognitionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidActivityRecognitionEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/health-connect")
    fun uploadAndroidHealthMetricData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header(DEVICE_ID_HEADER) sourceDeviceId: String,
        @Header(API_KEY_HEADER) apiKey: String?,
        @Body data: List<AndroidHealthMetricEvent>,
        @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) settingsVersion: Int? = null,
    ): Int
}
