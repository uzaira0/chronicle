package com.openlattice.chronicle.sensors

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.utils.PackageLabels.getAppFullName
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import androidx.core.content.edit

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
    else -> "Unknown importance: $eventType"
}

/**
 * A sensor that collect information about UsageEvents for uploading to Chronicle.
 */
class UsageEventsChronicleSensor(context: Context) : ChronicleSensor {
    private val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val appContext = context

    @Synchronized
    override fun poll(
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>
    ): ChronicleData {
        return poll(previousPollTimestamp(), currentPollTimestamp, users)
    }

    @Synchronized
    fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>
    ): ChronicleData {
        val usageEventsList: MutableList<UsageEvents.Event> = ArrayList()
        val usageEvents = usageStatsManager.queryEvents(previousPollTimestamp, currentPollTimestamp)
        while (usageEvents.hasNextEvent()) {
            val event: UsageEvents.Event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            usageEventsList.add(event)
        }
        Log.i(javaClass.name, "Collected ${usageEventsList.size} usage events.")
        val timezone = TimeZone.getDefault().id

        val result = ChronicleData(usageEventsList.map {
            ExtractedUsageEvent(
                appPackageName = it.packageName,
                activityClass = it.className,
                interactionType = usageInteractionType(it.eventType),
                eventType = it.eventType,
                timestamp = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(it.timeStamp),
                    ZoneOffset.UTC
                ),
                timezone = timezone,
                applicationLabel = getAppFullName(appContext, it.packageName),
                user = getTargetUser(it.timeStamp, users),
            )
        })

        return result
    }

    fun previousPollTimestamp(): Long {
        return settings.getLong(
            LAST_USAGE_QUERY_TIMESTAMP,
            System.currentTimeMillis() - USAGE_EVENTS_POLL_INTERVAL
        )
    }

    fun commitPollTimestamp(currentPollTimestamp: Long) {
        settings.edit { putLong(LAST_USAGE_QUERY_TIMESTAMP, currentPollTimestamp) }
    }

    private fun getTargetUser(
        eventTimestamp: Long,
        users: NavigableMap<Long, String>
    ): String {
        //If users is empty, you'll get a null and return ""
        val user = users.lowerEntry(eventTimestamp)?.value
        return if (user == null || user == "Not set") {
            ""
        } else user
    }

}
