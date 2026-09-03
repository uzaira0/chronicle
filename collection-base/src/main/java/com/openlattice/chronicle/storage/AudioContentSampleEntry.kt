package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one media-metadata sample, stored in `audio_content_samples`.
 *
 * The content-bearing layer for the `audio_content` collection module (see
 * `docs/SENSING-EXPANSION-DESIGN.md` §4.2): the active media session's [title]/[artist]/[album]
 * plus its [audioPackage] and playback timing. Still **mic-free** — this is the metadata the
 * producing app publishes, not the audio. `MEDIA_CONTENT`-class; only written when the opt-in
 * `audio_content` module is enabled. [timestamp] is an ISO-8601 UTC string.
 */
@Entity(tableName = "audio_content_samples")
data class AudioContentSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val audioPackage: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long?,
    val positionMillis: Long?,
)
