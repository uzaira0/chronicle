package com.openlattice.chronicle.api

import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import java.util.UUID
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Upload surface for the collectors only the open and research builds ship (this source set).
 * It is separate from [ChronicleStudyApi] so the amazon build carries none of these endpoints.
 */
internal interface RestrictedChronicleStudyApi {
    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/app-network-usage")
    fun uploadAndroidAppNetworkUsageData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidAppNetworkUsageEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sensors/receipt")
    fun uploadAndroidSensorDataReceipt(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidSensorSample>,
    ): UploadReceipt

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/interaction")
    fun uploadAndroidInteractionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidInteractionEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-activity")
    fun uploadAndroidAudioActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidAudioActivityEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/audio-content")
    fun uploadAndroidAudioContentData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidAudioContentEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/notification-activity")
    fun uploadAndroidNotificationActivityData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidNotificationActivityEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/sleep")
    fun uploadAndroidSleepData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidSleepEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/activity-recognition")
    fun uploadAndroidActivityRecognitionData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidActivityRecognitionEvent>,
    ): Int

    @POST("/chronicle/v4/study/{studyId}/participant/{participantId}/android/health-connect")
    fun uploadAndroidHealthMetricData(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @HeaderMap headers: Map<String, String>,
        @Body data: CollectedUploadBody<AndroidHealthMetricEvent>,
    ): Int
}
