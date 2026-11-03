package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.constants.NotificationType

data class NotificationDetails(
    val id: String,
    val type: NotificationType,
    val recurrenceRule: String,
    val title: String,
    val message: String,
    val serverUrl: String? = null,
    val accessCode: String? = null,
    val scheduledAtMillis: Long? = null,
    /** Zone the rule recurs in (the study zone for the survey); null recurs in the phone's own zone. */
    val timeZone: String? = null,
    /** Survey only: on the last fetch an ended day still had apps to answer; the reminder is posted only then. */
    val unansweredDays: Boolean = false,
) {
    /** Stable across refreshed one-time codes so PendingIntent updates replace old alarms. */
    fun requestCode(): Int = "$id|${type.name}|$recurrenceRule".hashCode()

    /** Never expose participant access codes or server destinations through Logcat. */
    override fun toString(): String =
        "NotificationDetails(type=$type, requestCode=${requestCode()}, " +
            "serverConfigured=${serverUrl != null}, accessCodePresent=${accessCode != null})"
}
