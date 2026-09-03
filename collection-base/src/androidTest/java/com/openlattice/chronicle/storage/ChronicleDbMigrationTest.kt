package com.openlattice.chronicle.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "chronicle-migration-test"

/**
 * On-device proof for the `9 → 10` Room migration ([MIGRATION_9_10]).
 *
 * [MigrationTestHelper] creates the database at schema version 9 from the exported
 * `9.json`, applies [MIGRATION_9_10], and validates the resulting schema against the
 * exported `10.json` — so a passing run proves the hand-written `battery_samples`
 * `CREATE TABLE` is byte-for-byte what Room expects for [BatterySampleEntry], on real
 * hardware.
 */
@RunWith(AndroidJUnit4::class)
class ChronicleDbMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChronicleDb::class.java,
    )

    @Test
    fun migrate9To10AddsBatterySamplesTable() {
        // Create the v9 database (no battery_samples table) and close it.
        helper.createDatabase(TEST_DB, 9).close()

        // Apply MIGRATION_9_10 and validate the result matches the exported v10 schema.
        // validateDroppedTables = true catches any unintended table drop.
        helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10).close()
    }

    @Test
    fun migrate11To12AddsBatteryUploadStatusColumns() {
        helper.createDatabase(TEST_DB, 11).close()

        helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12).close()
    }

    @Test
    fun migrate12To13SeparatesUploadAttemptsSuccessesAndFailures() {
        helper.createDatabase(TEST_DB, 12).close()

        helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13).close()
    }

    @Test
    fun migrate13To14AddsPerModuleDecisionColumns() {
        helper.createDatabase(TEST_DB, 13).close()

        helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14).close()
    }

    @Test
    fun migrate20To21AddsDurableSensorReceiptsAndDeadLetters() {
        helper.createDatabase(TEST_DB, 20).close()

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).close()
    }

    @Test
    fun migrate21To22AddsInteractionPositionProvenance() {
        helper.createDatabase(TEST_DB, 21).close()

        helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22).close()
    }

    @Test
    fun migrate23To24PreservesOneEnrollmentAndEnforcesTheSingletonSlot() {
        helper.createDatabase(TEST_DB, 23).use { db -> insertLegacyServer(db, "one") }

        helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).use { db ->
            db.query("SELECT COUNT(*), MIN(singletonKey) FROM upload_servers").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
            try {
                insertLegacyServer(db, "two")
                fail("A second enrollment row must violate the singleton index")
            } catch (_: android.database.SQLException) {
                // Expected: the v24 unique singleton index is the final race-condition backstop.
            }
        }
    }

    @Test
    fun migrate23To24FailsClosedWhenLegacyStateContainsMultipleEnrollments() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            insertLegacyServer(db, "one")
            insertLegacyServer(db, "two")
        }

        helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).use { db ->
            db.query("SELECT COUNT(*) FROM upload_servers").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate24To25PreservesEnrollmentAndAddsClosedRecoveryState() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            insertV24Server(db, "one")
        }

        helper.runMigrationsAndValidate(TEST_DB, 25, true, MIGRATION_24_25).use { db ->
            db.query(
                """
                SELECT enrollmentSetupComplete, reservationNonce,
                       reservationExpiresAtEpochMillis, enrollmentIssuedAtEpochMillis,
                       pendingAcceptedModuleIds, pendingDeclinedModuleIds
                FROM upload_servers
                """.trimIndent(),
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                for (column in 1..5) assertEquals(true, cursor.isNull(column))
            }
        }
    }

    @Test
    fun migrate25To26PreservesEnrollmentAndAddsEmptyReplaySecrets() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertConfiguredServer(this)
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 26, true, MIGRATION_25_26).use { db ->
            db.query(
                """
                SELECT apiKey, pendingEnrollmentAttemptId, pendingEnrollmentAccessCode,
                       pendingEnrollmentInviteExpiresAtEpochMillis, pendingProposedApiKey,
                       pendingEnrollmentSourceDeviceJson,
                       pendingEnrollmentFirstRequestAtEpochMillis,
                       pendingEnrollmentReplayDeadlineEpochMillis
                FROM upload_servers WHERE id = 1
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing-key", cursor.getString(0))
                for (column in 1..7) assertTrue(cursor.isNull(column))
            }
        }
    }

    @Test
    fun migrate26To27PreservesEnrollmentAndAddsEmptyUnavailableEvidence() {
        helper.createDatabase(TEST_DB, 26).apply {
            insertConfiguredServer(this)
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 27, true, MIGRATION_26_27).use { db ->
            db.query(
                "SELECT apiKey, pendingUnavailableModuleIds FROM upload_servers WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing-key", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate27To28RedactsLegacyUploadErrorsWithoutLosingFailureState() {
        helper.createDatabase(TEST_DB, 27).apply {
            insertConfiguredServer(this)
            execSQL(
                """
                UPDATE upload_servers
                SET lastUploadError = 'https://study.example?token=secret',
                    lastSensorUploadError = 'server response body',
                    lastBatteryUploadError = NULL
                WHERE id = 1
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 28, true, MIGRATION_27_28).use { db ->
            db.query(
                "SELECT lastUploadError, lastSensorUploadError, lastBatteryUploadError " +
                    "FROM upload_servers WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("UPLOAD_FAILURE", cursor.getString(0))
                assertEquals("UPLOAD_FAILURE", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    private fun insertLegacyServer(db: SupportSQLiteDatabase, suffix: String) {
        db.execSQL(
            """
            INSERT INTO upload_servers (
                name, url, studyId, participantId, sourceDeviceId, authMode, enabled,
                consecutiveFailures, sensorConsecutiveFailures, batteryConsecutiveFailures,
                usageUploadSuccessCount, usageUploadFailureCount,
                sensorUploadSuccessCount, sensorUploadFailureCount,
                batteryUploadSuccessCount, batteryUploadFailureCount,
                lastUploadedTimestamp, lastUploadedQueueId, sensorDeliveryGeneration, createdAt
            ) VALUES (
                'Study $suffix', 'https://$suffix.example', 'study-$suffix', 'participant-$suffix',
                'device-$suffix', 'API_KEY', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                '2026-08-17T00:00:00Z'
            )
            """.trimIndent(),
        )
    }

    private fun insertV24Server(db: SupportSQLiteDatabase, suffix: String) {
        db.execSQL(
            """
            INSERT INTO upload_servers (
                singletonKey, name, url, studyId, participantId, sourceDeviceId, authMode, enabled,
                consecutiveFailures, sensorConsecutiveFailures, batteryConsecutiveFailures,
                usageUploadSuccessCount, usageUploadFailureCount,
                sensorUploadSuccessCount, sensorUploadFailureCount,
                batteryUploadSuccessCount, batteryUploadFailureCount,
                lastUploadedTimestamp, lastUploadedQueueId, sensorDeliveryGeneration, createdAt
            ) VALUES (
                1, 'Study $suffix', 'https://$suffix.example', 'study-$suffix', 'participant-$suffix',
                'device-$suffix', 'API_KEY', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                '2026-08-17T00:00:00Z'
            )
            """.trimIndent(),
        )
    }

    private fun insertConfiguredServer(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO upload_servers (
                id, singletonKey, name, url, studyId, participantId, sourceDeviceId,
                authMode, apiKey, enrollmentSetupComplete, enabled,
                consecutiveFailures, sensorConsecutiveFailures, batteryConsecutiveFailures,
                usageUploadSuccessCount, usageUploadFailureCount,
                sensorUploadSuccessCount, sensorUploadFailureCount,
                batteryUploadSuccessCount, batteryUploadFailureCount,
                lastUploadedTimestamp, lastUploadedQueueId, sensorDeliveryGeneration, createdAt
            ) VALUES (
                1, 1, 'Existing study', 'https://study.example',
                '11111111-1111-1111-1111-111111111111', 'participant-a', 'device-a',
                'apiKey', 'existing-key', 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                '2026-08-17T00:00:00Z'
            )
            """.trimIndent(),
        )
    }
}
