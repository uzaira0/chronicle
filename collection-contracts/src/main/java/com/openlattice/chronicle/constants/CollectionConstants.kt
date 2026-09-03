package com.openlattice.chronicle.constants

/**
 * Shared constants used by the R/BuildConfig-free collection libraries and by `:app`.
 * Owned by `:collection-contracts` (tranche 7 storage/contract split; moved from
 * `:collection-base`). Values are moved verbatim from their original `:app` definitions so
 * SharedPreferences keys stay byte-identical.
 *
 *   PARTICIPANT_ID / STUDY_ID  — were `preferences.EnrollmentSettings`
 *
 * `:app` re-exports these from their historical package paths (typealias-style
 * `const val` references) so existing `:app` import sites stay unchanged.
 */

const val PARTICIPANT_ID = "participantId"
const val STUDY_ID = "studyId"

// Combined-upload WorkManager unique-work names — were `services.upload.CombinedUploadWorker`.
// Owned here so :collection-upload can reference them; :app re-exports them.
const val COMBINED_UPLOAD_WORK_NAME = "combined_upload"
const val COMBINED_UPLOAD_IMMEDIATE_WORK_NAME = "combined_upload_immediate"

// Device-lifecycle event constants — were `services.lifecycle.DeviceLifecycleEvents`.
// Owned here so :collection-lifecycle can reference them; :app re-exports them. These
// are wire/event values written to dataQueue, kept byte-identical.
const val ANDROID_SYSTEM_PACKAGE = "android"
const val ANDROID_SYSTEM_LABEL = "Android System"
const val INTERACTION_BATTERY_LOW = "Battery Low"
const val INTERACTION_BATTERY_OKAY = "Battery Okay"
const val INTERACTION_BATTERY_CHARGING = "Battery Charging"
const val INTERACTION_BATTERY_DISCHARGING = "Battery Discharging"
const val INTERACTION_LOW_MEMORY = "Low Memory"
