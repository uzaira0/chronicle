package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one content-free notification-activity event, stored in
 * `notification_activity_samples`.
 *
 * The "digital interruption load" signal for the `notification_activity` collection module: a
 * notification from [packageName] was [eventType] (posted/removed) at [timestamp], with its Android
 * [category] (a fixed constant such as `msg`/`call`/`alarm`, never message content) and content-free
 * flags. **Content-free by construction** — the notification's title, text, and any free-form
 * payload are never read, so this row has no field for them. [timestamp] is an ISO-8601 UTC string.
 */
@Entity(tableName = "notification_activity_samples")
data class NotificationActivitySampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val eventType: String,
    val packageName: String,
    val category: String?,
    val ongoing: Boolean?,
    val importance: Int?,
)
