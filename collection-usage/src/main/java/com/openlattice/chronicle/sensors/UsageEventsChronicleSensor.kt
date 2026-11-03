package com.openlattice.chronicle.sensors

import android.app.usage.UsageEvents
import android.content.Context
import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.utils.PackageLabels.getAppFullName
import com.openlattice.chronicle.utils.UsageAccess
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

const val USAGE_EVENTS_POLL_INTERVAL = 15 * 60 * 1000L
const val LAST_USAGE_QUERY_TIMESTAMP = "com.openlattice.sensors.LastUsageQueryTimestamp"
const val USAGE_EVENTS_SENSOR_CHECKPOINT = "usage_events"
/** Original upstream interaction labels, kept stable for existing researcher exports. */
internal fun usageInteractionType(eventType: Int): String = when (eventType) {
    UsageEvents.Event.ACTIVITY_RESUMED -> "Activity Resumed"
    UsageEvents.Event.ACTIVITY_PAUSED -> "Activity Paused"
    UsageEvents.Event.ACTIVITY_STOPPED -> "Activity Stopped"
    UsageEvents.Event.CONFIGURATION_CHANGE -> "Configuration Change"
    UsageEvents.Event.USER_INTERACTION -> "User Interaction"
    UsageEvents.Event.SHORTCUT_INVOCATION -> "Shortcut Invocation"
    UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "Standby Bucket Changed"
    UsageEvents.Event.SCREEN_INTERACTIVE -> "Screen Interactive"
    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "Screen Non-interactive"
    UsageEvents.Event.KEYGUARD_SHOWN -> "Keyguard Shown"
    UsageEvents.Event.KEYGUARD_HIDDEN -> "Keyguard Hidden"
    UsageEvents.Event.FOREGROUND_SERVICE_START -> "Foreground Service Start"
    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "Foreground Service Stop"
    UsageEvents.Event.DEVICE_SHUTDOWN -> "Device Shutdown"
    UsageEvents.Event.DEVICE_STARTUP -> "Device Startup"
    UsageEvents.Event.NONE -> "None"
    // Hidden platform event types (no public SDK constant) that OEM builds such as MIUI still
    // surface through queryEvents; keep them named rather than leaking the raw code.
    USAGE_EVENT_NOTIFICATION_SEEN -> "Notification Seen"
    USAGE_EVENT_NOTIFICATION_INTERRUPTION -> "Notification Interruption"
    else -> "Unknown importance: $eventType"
}

/** Screen off ends a participant's session and unlock starts the next one. */
private val SESSION_BOUNDARIES = setOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_HIDDEN)

/** `UsageEvents.Event.NOTIFICATION_SEEN`, hidden in the SDK. */
internal const val USAGE_EVENT_NOTIFICATION_SEEN = 10

/** `UsageEvents.Event.NOTIFICATION_INTERRUPTION`, hidden in the SDK. */
internal const val USAGE_EVENT_NOTIFICATION_INTERRUPTION = 12

/**
 * A sensor that collect information about UsageEvents for uploading to Chronicle.
 */
class UsageEventsChronicleSensor(context: Context) : ChronicleSensor {
    private val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val appContext = context

    @Synchronized
    override fun poll(
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>
    ): ChronicleData {
        return poll(previousPollTimestamp(), currentPollTimestamp, users) ?: ChronicleData(emptyList())
    }

    /**
     * [users] maps each selection's time to its label. A selection labels its own session only: screen off and unlock
     * end it whatever was stored, so the reading starts at the selection that governs [previousPollTimestamp]
     * to see the boundaries between it and this window; only events from [previousPollTimestamp] on are returned.
     */
    @Synchronized
    fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>
    ): ChronicleData? {
        val readFrom = minOf(previousPollTimestamp, users.floorKey(previousPollTimestamp) ?: previousPollTimestamp)
        val usageEvents = UsageAccess.queryEvents(appContext, readFrom, currentPollTimestamp) ?: return null
        val timezone = TimeZone.getDefault().id
        val result = mutableListOf<ExtractedUsageEvent>()
        var sessionStart = Long.MIN_VALUE
        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (event.eventType in SESSION_BOUNDARIES) sessionStart = event.timeStamp
            if (event.timeStamp < previousPollTimestamp) continue
            result += ExtractedUsageEvent(
                appPackageName = event.packageName,
                activityClass = event.className,
                interactionType = usageInteractionType(event.eventType),
                eventType = event.eventType,
                timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.timeStamp), ZoneOffset.UTC),
                timezone = timezone,
                applicationLabel = getAppFullName(appContext, event.packageName),
                user = getTargetUser(event.timeStamp, sessionStart, users),
            )
        }
        Log.i(javaClass.name, "Collected ${result.size} usage events.")
        return ChronicleData(result)
    }

    fun previousPollTimestamp(): Long {
        return settings.getLong(
            LAST_USAGE_QUERY_TIMESTAMP,
            System.currentTimeMillis() - USAGE_EVENTS_POLL_INTERVAL
        )
    }

    /**
     * The latest selection before [eventTimestamp] inside its session, in its current spelling (a legacy
     * "Target child" selection labels new rows "Study participant"); none, or [DeviceUser.NOT_SET], is unlabelled.
     */
    private fun getTargetUser(eventTimestamp: Long, sessionStart: Long, users: NavigableMap<Long, String>): String {
        val user = users.lowerEntry(eventTimestamp)?.takeIf { it.key >= sessionStart }?.value ?: return ""
        return if (DeviceUser.fromStored(user) == DeviceUser.NOT_SET) "" else DeviceUser.current(user)
    }

}
