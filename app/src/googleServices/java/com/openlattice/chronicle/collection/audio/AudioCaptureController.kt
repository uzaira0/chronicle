package com.openlattice.chronicle.collection.audio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.AudioOutputRoute
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.notifications.NotificationListener
import com.openlattice.chronicle.storage.AudioActivitySampleEntry
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val TAG = AudioCaptureController::class.java.simpleName

/**
 * Hosts the mic-free app-audio capture for the `audio_activity` (+ opt-in `audio_content`) modules.
 *
 * Owned by [NotificationListener] (a [android.service.notification.NotificationListenerService], which
 * is alive whenever notification access is granted) and constructed with its application [context] —
 * not a singleton, so the "no Context in an object" rule holds. On [register] it subscribes to the
 * device's own audio transitions — output-device connect/disconnect ([AudioDeviceCallback]), playback
 * config changes ([AudioManager.AudioPlaybackCallback]), the becoming-noisy (headphone-unplug)
 * broadcast, and active-[MediaController] changes ([MediaSessionManager]) — and on each transition (and
 * on an explicit [snapshot]) writes one [AudioActivitySampleEntry]. The microphone is never opened.
 *
 * All capture runs on a private [HandlerThread] so the Room writes never touch the main thread. Every
 * write is gated by [CollectionGate]; the media-session attribution (which app / content type /
 * playback state) and the [AudioContentSampleEntry] metadata are only read when their module is
 * accepted, and `getActiveSessions` is wrapped so a missing notification grant degrades to Tier-1.
 */
class AudioCaptureController(private val context: Context) {

    private var handlerThread: HandlerThread? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var playbackRegistration: PlaybackRegistration? = null
    private var becomingNoisyReceiver: BroadcastReceiver? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mediaSessionManager: MediaSessionManager
        get() = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent: ComponentName
        get() = ComponentName(context, NotificationListener::class.java)

    @Synchronized
    fun register() {
        if (handlerThread != null) return
        val thread = HandlerThread("audio-capture").apply { start() }
        val h = Handler(thread.looper)
        val am = audioManager
        try {
            val deviceCb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    capture(AudioEventType.ROUTE_CHANGE, routeConnected = true)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    capture(AudioEventType.ROUTE_CHANGE, routeConnected = false)
                }
            }
            am.registerAudioDeviceCallback(deviceCb, h)
            deviceCallback = deviceCb

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playbackRegistration = Api26PlaybackRegistration.register(am, h) {
                    capture(AudioEventType.PLAYBACK_CHANGE)
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    capture(AudioEventType.BECOMING_NOISY)
                }
            }
            val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            // targetSdk 34+ requires an export flag on context-registered receivers; this is a
            // receive-only system broadcast, so it is NOT exported.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, noisyFilter, null, h, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, noisyFilter, null, h)
            }
            becomingNoisyReceiver = receiver

            val listener = MediaSessionManager.OnActiveSessionsChangedListener {
                capture(AudioEventType.MEDIA_SESSION_CHANGE)
            }
            runCatching {
                mediaSessionManager.addOnActiveSessionsChangedListener(listener, listenerComponent, h)
                sessionsListener = listener
            }.onFailure {
                Log.i(TAG, "Media-session listener unavailable (no notification access); Tier-1 only")
            }

            // Commit the active state only after every registration above succeeded, so a partial
            // failure leaves handlerThread null and the next onListenerConnected can retry.
            handlerThread = thread
            Log.i(TAG, "Audio capture registered")
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture registration failed; cleaning up", e)
            clearCallbacks(am)
            thread.quitSafely()
        }
    }

    /** Unregisters whatever callbacks are currently set and nulls their fields. */
    private fun clearCallbacks(am: AudioManager) {
        deviceCallback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        playbackRegistration?.let { runCatching { it.unregister(am) } }
        becomingNoisyReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        sessionsListener?.let { runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(it) } }
        deviceCallback = null
        playbackRegistration = null
        becomingNoisyReceiver = null
        sessionsListener = null
    }

    /** API-safe handle stored by the outer class without referencing API 26 framework types. */
    private fun interface PlaybackRegistration {
        fun unregister(audioManager: AudioManager)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26PlaybackRegistration {
        fun register(
            audioManager: AudioManager,
            handler: Handler,
            onPlaybackChanged: () -> Unit,
        ): PlaybackRegistration {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(
                    configs: MutableList<android.media.AudioPlaybackConfiguration>,
                ) {
                    onPlaybackChanged()
                }
            }
            audioManager.registerAudioPlaybackCallback(callback, handler)
            return PlaybackRegistration { manager -> manager.unregisterAudioPlaybackCallback(callback) }
        }
    }

    @Synchronized
    fun unregister() {
        clearCallbacks(audioManager)
        handlerThread?.quitSafely()
        handlerThread = null
        Log.i(TAG, "Audio capture unregistered")
    }

    /** Reads the current device-audio state and writes one [AudioActivitySampleEntry] for [event]. */
    fun snapshot(event: AudioEventType = AudioEventType.SNAPSHOT) = capture(event)

    private fun capture(event: AudioEventType, routeConnected: Boolean? = null) {
        runCatching {
            ResearchPersistenceGate.persistIfActive(context) {
                val modules = audioModulesToCapture(CollectionLoopStore.of(context)::collects)
                if (modules.isEmpty()) return@persistIfActive

                val am = audioManager
                val nowUtc = OffsetDateTime.now(ZoneOffset.UTC).toString()
                val tz = ZoneId.systemDefault().id
                val controller = activeController()

                if (CollectionModuleId.AUDIO_ACTIVITY in modules) {
                    val maxMediaVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val callActive =
                        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

                    // Tier-2 attribution: which app + content type + playback state from the active session.
                    val playbackState = controller?.playbackState?.let { playbackStateFor(it.state) }
                    val contentType = controller?.playbackInfo?.audioAttributes?.let {
                        contentTypeFor(it.contentType)
                    }

                    val entry = AudioActivitySampleEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = nowUtc,
                        timezone = tz,
                        eventType = event.name,
                        audioActive = am.isMusicActive,
                        audioPackage = controller?.packageName,
                        contentType = contentType?.name,
                        playbackState = playbackState?.name,
                        outputRoute = currentOutputRoute(am).name,
                        routeConnected = routeConnected,
                        mediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC),
                        maxMediaVolume = if (maxMediaVolume >= 1) maxMediaVolume else null,
                        ringerMode = ringerModeFor(am.ringerMode).name,
                        dndActive = null,
                        callActive = callActive,
                    )
                    ChronicleDb.getInstance(context).audioActivitySampleDao().insertAll(listOf(entry))
                }

                // `audio_content` is an independent consent choice. It must not be nested under the
                // `audio_activity` gate: a study may request published media metadata without the
                // broader device-audio activity stream.
                if (CollectionModuleId.AUDIO_CONTENT in modules && controller != null) {
                    captureContent(controller, nowUtc, tz)
                }
            }
        }.onFailure { Log.w(TAG, "Audio capture failed for $event", it) }
    }

    private fun captureContent(controller: MediaController, nowUtc: String, tz: String) {
        val metadata = controller.metadata ?: return
        val content = AudioContentSampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = nowUtc,
            timezone = tz,
            audioPackage = controller.packageName,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMillis = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            positionMillis = controller.playbackState?.position?.takeIf { it >= 0 },
        )
        ChronicleDb.getInstance(context).audioContentSampleDao().insertAll(listOf(content))
    }

    /** The first playing active session, else the first session, else null (or no notification grant). */
    private fun activeController(): MediaController? {
        val sessions = runCatching { mediaSessionManager.getActiveSessions(listenerComponent) }.getOrNull()
            ?: return null
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    private fun currentOutputRoute(am: AudioManager): AudioOutputRoute {
        val routes = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { outputRouteFor(it.type) }
        // Prefer an external/private route over the built-in speaker when one is connected.
        return routes.firstOrNull {
            it == AudioOutputRoute.BLUETOOTH || it == AudioOutputRoute.WIRED_HEADPHONES ||
                it == AudioOutputRoute.USB || it == AudioOutputRoute.HEARING_AID
        } ?: routes.firstOrNull() ?: AudioOutputRoute.UNKNOWN
    }
}

internal fun audioModulesToCapture(
    collects: (CollectionModuleId) -> Boolean,
): Set<CollectionModuleId> = setOf(
    CollectionModuleId.AUDIO_ACTIVITY,
    CollectionModuleId.AUDIO_CONTENT,
).filterTo(LinkedHashSet(), collects)
