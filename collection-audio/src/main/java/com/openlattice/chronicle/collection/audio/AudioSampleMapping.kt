package com.openlattice.chronicle.collection.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AudioContentType
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.AudioOutputRoute
import com.openlattice.chronicle.collection.AudioPlaybackState
import com.openlattice.chronicle.collection.AudioRingerMode
import com.openlattice.chronicle.collection.NotificationEventType
import com.openlattice.chronicle.storage.AudioActivitySampleEntry
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.NotificationActivitySampleEntry
import java.time.OffsetDateTime

/**
 * Pure mapping between the Room buffer rows ([AudioActivitySampleEntry] / [AudioContentSampleEntry]
 * / [NotificationActivitySampleEntry]) and the wire DTOs, plus the Android-constant → Chronicle-enum
 * helpers used by the capture path. Side-effect-free and JVM-unit-testable: the Android `TYPE_*` /
 * `CONTENT_TYPE_*` / `STATE_*` / `RINGER_MODE_*` values are compile-time `static final int`
 * constants, so the `when`s resolve without touching the framework. Mirrors `InteractionEventMapping`.
 */

/** Collapse an [AudioDeviceInfo] type into an [AudioOutputRoute]. */
fun outputRouteFor(deviceType: Int): AudioOutputRoute = when (deviceType) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutputRoute.SPEAKER
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputRoute.EARPIECE
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputRoute.WIRED_HEADPHONES
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioOutputRoute.BLUETOOTH
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputRoute.USB
    AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputRoute.HEARING_AID
    else -> AudioOutputRoute.OTHER
}

/** Map an [AudioAttributes] content type into an [AudioContentType]. */
fun contentTypeFor(contentType: Int): AudioContentType = when (contentType) {
    AudioAttributes.CONTENT_TYPE_MUSIC -> AudioContentType.MUSIC
    AudioAttributes.CONTENT_TYPE_SPEECH -> AudioContentType.SPEECH
    AudioAttributes.CONTENT_TYPE_MOVIE -> AudioContentType.MOVIE
    AudioAttributes.CONTENT_TYPE_SONIFICATION -> AudioContentType.SONIFICATION
    else -> AudioContentType.UNKNOWN
}

/** Map a [PlaybackState] state into an [AudioPlaybackState]. */
fun playbackStateFor(state: Int): AudioPlaybackState = when (state) {
    PlaybackState.STATE_PLAYING -> AudioPlaybackState.PLAYING
    PlaybackState.STATE_PAUSED -> AudioPlaybackState.PAUSED
    PlaybackState.STATE_STOPPED -> AudioPlaybackState.STOPPED
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> AudioPlaybackState.BUFFERING
    else -> AudioPlaybackState.NONE
}

/** Map an [AudioManager] ringer mode into an [AudioRingerMode]. */
fun ringerModeFor(mode: Int): AudioRingerMode = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> AudioRingerMode.SILENT
    AudioManager.RINGER_MODE_VIBRATE -> AudioRingerMode.VIBRATE
    AudioManager.RINGER_MODE_NORMAL -> AudioRingerMode.NORMAL
    else -> AudioRingerMode.UNKNOWN
}

/** Buffer row → wire DTO. Enum-named strings are parsed back; an unknown name throws (corrupt row). */
fun AudioActivitySampleEntry.toAndroidAudioActivityEvent(): AndroidAudioActivityEvent =
    AndroidAudioActivityEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        eventType = AudioEventType.valueOf(eventType),
        audioActive = audioActive,
        audioPackage = audioPackage,
        contentType = contentType?.let { AudioContentType.valueOf(it) },
        playbackState = playbackState?.let { AudioPlaybackState.valueOf(it) },
        outputRoute = outputRoute?.let { AudioOutputRoute.valueOf(it) },
        routeConnected = routeConnected,
        mediaVolume = mediaVolume,
        maxMediaVolume = maxMediaVolume,
        ringerMode = ringerMode?.let { AudioRingerMode.valueOf(it) },
        dndActive = dndActive,
        callActive = callActive,
    )

fun AudioContentSampleEntry.toAndroidAudioContentEvent(): AndroidAudioContentEvent =
    AndroidAudioContentEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        audioPackage = audioPackage,
        title = title,
        artist = artist,
        album = album,
        durationMillis = durationMillis,
        positionMillis = positionMillis,
    )

fun NotificationActivitySampleEntry.toAndroidNotificationActivityEvent(): AndroidNotificationActivityEvent =
    AndroidNotificationActivityEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        eventType = NotificationEventType.valueOf(eventType),
        packageName = packageName,
        category = category,
        ongoing = ongoing,
        importance = importance,
    )
