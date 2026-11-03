package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Query

/** Erasure-only access to historical tables, including in artifacts without their collectors. */
@Dao
interface RestrictedArtifactErasureDao {
    @Query("DELETE FROM interaction_samples")
    fun deleteInteractions(): Int

    @Query("DELETE FROM audio_activity_samples")
    fun deleteAudioActivity(): Int

    @Query("DELETE FROM audio_content_samples")
    fun deleteAudioContent(): Int

    @Query("DELETE FROM notification_activity_samples")
    fun deleteNotificationActivity(): Int

    @Query("DELETE FROM sleep_samples")
    fun deleteSleep(): Int

    @Query("DELETE FROM activity_recognition_samples")
    fun deleteActivityRecognition(): Int

    @Query("DELETE FROM health_metric_samples")
    fun deleteHealthMetrics(): Int
}
