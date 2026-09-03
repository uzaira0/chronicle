package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one content-free app-audio-activity event, stored in `audio_activity_samples`.
 *
 * The structured analogue of [InteractionSampleEntry] for the `audio_activity` collection module
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §4.1). Enum-valued fields are persisted as their enum
 * `name` string (mirroring [InteractionSampleEntry] / [BatterySampleEntry]), so no Room
 * `TypeConverter` is needed. [timestamp] is an ISO-8601 UTC string, ordered like the other sample
 * tables. **Mic-free by construction** — every field is device playback/output state; there is no
 * audio waveform or microphone field. [audioPackage]/[contentType]/[playbackState] are populated
 * only on Tier-2 samples (notification-listener access granted); Tier-1-only samples leave them
 * null but still carry the always-available device-audio state.
 */
@Entity(tableName = "audio_activity_samples")
data class AudioActivitySampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val eventType: String,
    val audioActive: Boolean,
    val audioPackage: String?,
    val contentType: String?,
    val playbackState: String?,
    val outputRoute: String?,
    val routeConnected: Boolean?,
    val mediaVolume: Int?,
    val maxMediaVolume: Int?,
    val ringerMode: String?,
    val dndActive: Boolean?,
    val callActive: Boolean?,
)
