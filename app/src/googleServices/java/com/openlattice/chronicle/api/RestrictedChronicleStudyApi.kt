package com.openlattice.chronicle.api

import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
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
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidAppNetworkUsageEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sensors")
    fun uploadAndroidSensorData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidSensorSample>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/interaction")
    fun uploadAndroidInteractionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidInteractionEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-activity")
    fun uploadAndroidAudioActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidAudioActivityEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-content")
    fun uploadAndroidAudioContentData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidAudioContentEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/notification-activity")
    fun uploadAndroidNotificationActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidNotificationActivityEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sleep")
    fun uploadAndroidSleepData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidSleepEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/activity-recognition")
    fun uploadAndroidActivityRecognitionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidActivityRecognitionEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/health-connect")
    fun uploadAndroidHealthMetricData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Header("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Header("X-Api-Key") apiKey: String?,
        @Body data: List<AndroidHealthMetricEvent>,
    ): Int
}
