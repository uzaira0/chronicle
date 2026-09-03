package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `activity_recognition` sample (Play Services Activity Recognition / Transition),
 * one row per AndroidActivityRecognitionEvent before upload. BEHAVIORAL_METADATA-class,
 * content-free: an activity label + confidence.
 */
@Entity(tableName = "activity_recognition_samples")
data class ActivityRecognitionSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val activityType: String,
    val confidence: Int,
    val transitionType: String?,
)
