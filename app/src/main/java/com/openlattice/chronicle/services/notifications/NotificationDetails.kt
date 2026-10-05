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
    /** The occurrence's ISO local date in the zone it was armed in; a later zone change must not move it. */
    val localDate: String? = null,
) {
    /** Stable across refreshed one-time codes so PendingIntent updates replace old alarms. */
    fun requestCode(): Int = "$id|${type.name}|$recurrenceRule".hashCode()

    /** Never expose participant access codes or server destinations through Logcat. */
    override fun toString(): String =
        "NotificationDetails(type=$type, requestCode=${requestCode()}, " +
            "serverConfigured=${serverUrl != null}, accessCodePresent=${accessCode != null})"
}
