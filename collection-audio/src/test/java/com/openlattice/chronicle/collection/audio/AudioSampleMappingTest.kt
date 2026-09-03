package com.openlattice.chronicle.collection.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import com.openlattice.chronicle.collection.AudioContentType
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.AudioOutputRoute
import com.openlattice.chronicle.collection.AudioPlaybackState
import com.openlattice.chronicle.collection.AudioRingerMode
import com.openlattice.chronicle.collection.NotificationEventType
import com.openlattice.chronicle.storage.AudioActivitySampleEntry
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.NotificationActivitySampleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSampleMappingTest {

    @Test fun outputRouteForMapsKnownTypes() {
        assertEquals(AudioOutputRoute.SPEAKER, outputRouteFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioOutputRoute.WIRED_HEADPHONES, outputRouteFor(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioOutputRoute.BLUETOOTH, outputRouteFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioOutputRoute.USB, outputRouteFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioOutputRoute.HEARING_AID, outputRouteFor(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(AudioOutputRoute.OTHER, outputRouteFor(Int.MIN_VALUE))
    }

    @Test fun contentTypeForMapsKnownTypes() {
        assertEquals(AudioContentType.MUSIC, contentTypeFor(AudioAttributes.CONTENT_TYPE_MUSIC))
        assertEquals(AudioContentType.SPEECH, contentTypeFor(AudioAttributes.CONTENT_TYPE_SPEECH))
        assertEquals(AudioContentType.MOVIE, contentTypeFor(AudioAttributes.CONTENT_TYPE_MOVIE))
        assertEquals(AudioContentType.UNKNOWN, contentTypeFor(AudioAttributes.CONTENT_TYPE_UNKNOWN))
    }

    @Test fun playbackStateForMapsKnownStates() {
        assertEquals(AudioPlaybackState.PLAYING, playbackStateFor(PlaybackState.STATE_PLAYING))
        assertEquals(AudioPlaybackState.PAUSED, playbackStateFor(PlaybackState.STATE_PAUSED))
        assertEquals(AudioPlaybackState.BUFFERING, playbackStateFor(PlaybackState.STATE_BUFFERING))
        assertEquals(AudioPlaybackState.NONE, playbackStateFor(PlaybackState.STATE_NONE))
    }

    @Test fun ringerModeForMapsKnownModes() {
        assertEquals(AudioRingerMode.SILENT, ringerModeFor(AudioManager.RINGER_MODE_SILENT))
        assertEquals(AudioRingerMode.VIBRATE, ringerModeFor(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(AudioRingerMode.NORMAL, ringerModeFor(AudioManager.RINGER_MODE_NORMAL))
        assertEquals(AudioRingerMode.UNKNOWN, ringerModeFor(Int.MIN_VALUE))
    }

    @Test fun audioActivityEntryMapsToDto() {
        val entry = AudioActivitySampleEntry(
            id = "a-1",
            timestamp = "2026-06-19T10:15:30Z",
            timezone = "America/Chicago",
            eventType = AudioEventType.ROUTE_CHANGE.name,
            audioActive = true,
            audioPackage = "com.spotify.music",
            contentType = AudioContentType.MUSIC.name,
            playbackState = AudioPlaybackState.PLAYING.name,
            outputRoute = AudioOutputRoute.BLUETOOTH.name,
            routeConnected = true,
            mediaVolume = 9,
            maxMediaVolume = 15,
            ringerMode = AudioRingerMode.NORMAL.name,
            dndActive = false,
            callActive = false,
        )
        val dto = entry.toAndroidAudioActivityEvent()
        assertEquals("a-1", dto.id)
        assertEquals(AudioEventType.ROUTE_CHANGE, dto.eventType)
        assertEquals(AudioOutputRoute.BLUETOOTH, dto.outputRoute)
        assertEquals(true, dto.routeConnected)
        assertEquals(AudioContentType.MUSIC, dto.contentType)
        assertEquals("com.spotify.music", dto.audioPackage)
    }

    @Test fun audioActivityEntryWithNullAttributionMapsToDto() {
        val entry = AudioActivitySampleEntry(
            id = "a-2",
            timestamp = "2026-06-19T10:16:00Z",
            timezone = "America/Chicago",
            eventType = AudioEventType.SNAPSHOT.name,
            audioActive = false,
            audioPackage = null,
            contentType = null,
            playbackState = null,
            outputRoute = AudioOutputRoute.SPEAKER.name,
            routeConnected = null,
            mediaVolume = 0,
            maxMediaVolume = 15,
            ringerMode = AudioRingerMode.VIBRATE.name,
            dndActive = null,
            callActive = null,
        )
        val dto = entry.toAndroidAudioActivityEvent()
        assertEquals(null, dto.audioPackage)
        assertEquals(null, dto.contentType)
        assertEquals(AudioOutputRoute.SPEAKER, dto.outputRoute)
    }

    @Test fun audioContentEntryMapsToDto() {
        val entry = AudioContentSampleEntry(
            id = "c-1",
            timestamp = "2026-06-19T10:15:30Z",
            timezone = "America/Chicago",
            audioPackage = "com.spotify.music",
            title = "Some Track",
            artist = "Some Artist",
            album = "Some Album",
            durationMillis = 213_000L,
            positionMillis = 42_000L,
        )
        val dto = entry.toAndroidAudioContentEvent()
        assertEquals("Some Track", dto.title)
        assertEquals(213_000L, dto.durationMillis)
        assertEquals("com.spotify.music", dto.audioPackage)
    }

    @Test fun notificationEntryMapsToDto() {
        val entry = NotificationActivitySampleEntry(
            id = "n-1",
            timestamp = "2026-06-19T10:15:30Z",
            timezone = "America/Chicago",
            eventType = NotificationEventType.POSTED.name,
            packageName = "com.whatsapp",
            category = "msg",
            ongoing = false,
            importance = 3,
        )
        val dto = entry.toAndroidNotificationActivityEvent()
        assertEquals(NotificationEventType.POSTED, dto.eventType)
        assertEquals("com.whatsapp", dto.packageName)
        assertEquals("msg", dto.category)
    }
}
