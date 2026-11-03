package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one stored form of a text instant: UTC, fixed width, milliseconds (`2026-10-28T13:00:00.000Z`), so text order is
 * time order wherever a queue sorts by it. [STORED_INSTANT_MIGRATION] rewrites older rows into it.
 */
private val STORED_INSTANT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun storedInstant(instant: Instant): String = STORED_INSTANT.format(instant)

/** [text] (any ISO-8601 offset date-time) in the stored form; text that does not parse is kept as it is. */
fun storedInstant(text: String): String = runCatching { storedInstant(OffsetDateTime.parse(text).toInstant()) }.getOrDefault(text)

/** A row's text capture time (any ISO-8601 offset date-time) in epoch milliseconds, or null when it does not parse. */
fun capturedMillis(text: String?): Long? = try {
    text?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
} catch (_: DateTimeException) {
    null
}

/** SQLite's spelling of the same form, for rows written before it. */
private fun storedSql(column: String) = "$column = COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ', $column), $column)"

/**
 * Version 3: participant selections get a decision order, numbered in the order of
 * their stamps since the clock that stamped them was the only order recorded; battery and diagnostic times take the
 * stored form. A pending enrollment drops the invitation expiry and replay deadline it kept on the phone's clock: the
 * server alone decides them.
 */
internal val STORED_INSTANT_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `userQueue` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `userQueue` SET seq = (SELECT COUNT(*) FROM `userQueue` AS earlier " +
            "WHERE earlier.writeTimestamp <= `userQueue`.writeTimestamp)")
        db.execSQL("UPDATE `battery_samples` SET ${storedSql("timestamp")}")
        db.execSQL("UPDATE `upload_diagnostics` SET ${storedSql("firstOccurredAt")}, ${storedSql("lastOccurredAt")}")
        db.execSQL("ALTER TABLE `upload_servers` DROP COLUMN `pendingEnrollmentInviteExpiresAtEpochMillis`")
        db.execSQL("ALTER TABLE `upload_servers` DROP COLUMN `pendingEnrollmentReplayDeadlineEpochMillis`")
    }
}
