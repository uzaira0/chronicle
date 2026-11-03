package com.openlattice.chronicle.storage

import android.app.Application
import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChronicleDbMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ChronicleDb::class.java)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Every exported version from the reset release on upgrades to the current schema and keeps queued rows. */
    @Test fun everyReleasedVersionUpgradesInPlaceAndKeepsQueuedRows() {
        for (version in 1..ChronicleDb.VERSION) {
            val name = "migration-$version"
            migrations.createDatabase(name, version).use { db ->
                db.execSQL("INSERT INTO dataQueue (writeTimestamp, id, data) VALUES (1, 2, X'5B5D')")
                db.execSQL("INSERT INTO sensor_samples (id, sensorType, timestamp, timezone) VALUES ('s', 'light', 't', 'UTC')")
            }
            migrations.runMigrationsAndValidate(name, ChronicleDb.VERSION, true, *ChronicleDb.ALL_MIGRATIONS.toTypedArray()).use { db ->
                assertEquals(1, count(db, "dataQueue"))
                assertEquals(1, count(db, "sensor_samples"))
            }
            val room = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory(), name)
            try { assertEquals(ChronicleDb.VERSION, room.openHelper.writableDatabase.version) } finally { room.close() }
        }
    }

    /** Producer metadata is an additive column; unknown released captures must remain unknown. */
    @Test fun version3BatteryRowsStayUnknownAndNewProducerVersionsSurviveReopen() {
        val name = "battery-provenance"
        migrations.createDatabase(name, 3).use { db ->
            db.execSQL("INSERT INTO battery_samples (id, timestamp, timezone, levelPercent, chargingState, plugType, " +
                "temperatureDeciC, voltageMillivolts, health) VALUES ('legacy', '2026-10-01T12:00:00.000Z', " +
                "'UTC', 50, 'CHARGING', 'AC', 300, 4000, 'GOOD')")
        }
        migrations.runMigrationsAndValidate(name, 4, true, *ChronicleDb.ALL_MIGRATIONS.toTypedArray()).use { db ->
            db.query("SELECT producerVersion FROM battery_samples WHERE id='legacy'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
            }
        }
        val row = BatterySampleEntry("new", "2026-11-01T12:00:00.000Z", "UTC", 50, "CHARGING", "AC", 300, 4000,
            "GOOD", producerVersion = "2026.11.01 (81)")
        val room = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory(), name)
        try {
            CompletableFuture.runAsync { room.batterySampleDao().insertAll(listOf(row)) }.get()
        } finally { room.close() }
        val reopened = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory(), name)
        try {
            val saved = CompletableFuture.supplyAsync { reopened.batterySampleDao().getByIds(listOf("legacy", "new")) }.get()
                .associateBy { it.id }
            assertEquals(null, saved.getValue("legacy").producerVersion)
            assertEquals(row, saved.getValue("new"))
        } finally { reopened.close() }
    }

    /** Play build 79's queued evidence and decisions survive the decision-sequence upgrade; new captures carry the sequence. */
    @Test fun version1EvidenceAndDecisionsSurviveAndNewCapturesCarryTheDecisionSequence() {
        val name = "decision-seq"
        migrations.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO upload_queue_evidence (payloadType, rowId, settingsVersion, heldVersion) VALUES ('SENSOR', 'old', 3, 4)")
            db.execSQL("INSERT INTO collection_module_state VALUES ('usage_events', 1, 'ACCEPTED', 5, 0, 3, 'snapshot', 'hold_pending')")
        }
        migrations.runMigrationsAndValidate(name, 2, true, *ChronicleDb.ALL_MIGRATIONS.toTypedArray()).close()
        val room = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory(), name)
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT settingsVersion, decisionSeq FROM upload_queue_evidence WHERE rowId = 'old'").use {
                assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertTrue(it.isNull(1))
            }
            db.query("SELECT decision, appliedVersion FROM collection_module_state").use {
                assertTrue(it.moveToFirst()); assertEquals("ACCEPTED", it.getString(0)); assertEquals(3, it.getInt(1))
            }
            val captured = CompletableFuture.supplyAsync {
                assertEquals(1L, room.nextDecisionSeq())
                room.sensorSampleDao().insertAll(listOf(SensorSampleEntry("new", "light", "t", "UTC", 1f, 2f, 3f, null, 3)))
                room.uploadQueueEvidenceDao().get(UploadQueueTable.SENSOR.name, listOf("new")).single().decisionSeq
            }.get()
            assertEquals(1L, captured)
        } finally {
            room.close()
        }
    }

    /**
     * Version 3: queued selections get their decision order from the order they were made in, and
     * stored battery and diagnostic times take the one sortable form, so text order becomes time order. A sent pending
     * enrollment keeps its attempt and loses the phone-clock expiry and replay deadline.
     */
    @Test fun version2SelectionsAndTimesTakeTheirVersion3Form() {
        val name = "stored-order"
        migrations.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO userQueue (writeTimestamp, user) VALUES (300, 'later'), (100, 'earlier')")
            for ((id, at) in listOf("minute" to "2023-11-14T22:14Z", "santiago" to "2023-11-14T19:14:00.001-03:00")) {
                db.execSQL("INSERT INTO battery_samples (id, timestamp, timezone, levelPercent, chargingState, plugType, " +
                    "temperatureDeciC, voltageMillivolts, health) VALUES ('$id', '$at', 'UTC', 50, 'CHARGING', 'AC', 300, 4000, 'GOOD')")
            }
            db.execSQL("INSERT INTO upload_diagnostics (id, day, moduleFamily, issueCode, count, firstOccurredAt, lastOccurredAt, " +
                "deliveryState, sealedForUpload) VALUES ('d', '2026-10-28', 'USAGE', 'TIMEOUT', 1, '2026-10-28T10:00:00-03:00', " +
                "'2026-10-28T10:00Z', 'PENDING', 0)")
            db.execSQL("INSERT INTO upload_servers (name, url, studyId, participantId, sourceRandomDeviceId, " +
                "enrollmentSetupComplete, pendingEnrollmentAttemptId, pendingEnrollmentInviteExpiresAtEpochMillis, " +
                "pendingEnrollmentFirstRequestAtEpochMillis, pendingEnrollmentReplayDeadlineEpochMillis, enabled, " +
                "consecutiveFailures, sensorConsecutiveFailures, batteryConsecutiveFailures, usageUploadSuccessCount, " +
                "usageUploadFailureCount, sensorUploadSuccessCount, sensorUploadFailureCount, batteryUploadSuccessCount, " +
                "batteryUploadFailureCount, lastUploadedTimestamp, lastUploadedQueueId, createdAt, " +
                SENT_PENDING.keys.joinToString() + ") VALUES ('s', 'https://s', " +
                "'study', 'p', 'device', 0, 'attempt', 10, 20, 30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'now', " +
                SENT_PENDING.values.joinToString { "'$it'" } + ")")
        }
        migrations.runMigrationsAndValidate(name, 3, true, *ChronicleDb.ALL_MIGRATIONS.toTypedArray()).use { db ->
            db.query("SELECT * FROM upload_servers").use {
                assertTrue(it.moveToFirst())
                assertEquals("attempt", it.getString(it.getColumnIndexOrThrow("pendingEnrollmentAttemptId")))
                assertEquals(20L, it.getLong(it.getColumnIndexOrThrow("pendingEnrollmentFirstRequestAtEpochMillis")))
                assertEquals("device", it.getString(it.getColumnIndexOrThrow("sourceRandomDeviceId")))
                assertEquals(-1, it.getColumnIndex("pendingEnrollmentInviteExpiresAtEpochMillis"))
                assertEquals(-1, it.getColumnIndex("pendingEnrollmentReplayDeadlineEpochMillis"))
                // Everything the replay of a sent attempt binds to crosses the upgrade unchanged.
                assertEquals(SENT_PENDING, SENT_PENDING.keys.associateWith { column -> it.getString(it.getColumnIndexOrThrow(column)) })
                assertTrue(it.isNull(it.getColumnIndexOrThrow("apiKey")))
            }
            db.query("SELECT user, seq FROM userQueue ORDER BY seq").use {
                val rows = generateSequence { if (it.moveToNext()) it.getString(0) to it.getLong(1) else null }.toList()
                assertEquals(listOf("earlier" to 1L, "later" to 2L), rows)
            }
            db.query("SELECT timestamp FROM battery_samples ORDER BY timestamp").use {
                val rows = generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
                assertEquals(listOf("2023-11-14T22:14:00.000Z", "2023-11-14T22:14:00.001Z"), rows)
            }
            db.query("SELECT firstOccurredAt, lastOccurredAt FROM upload_diagnostics").use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-10-28T13:00:00.000Z", it.getString(0))
                assertEquals("2026-10-28T10:00:00.000Z", it.getString(1))
            }
        }
    }

    /** A database from a pre-reset release is never dropped: Room refuses it and the rows stay for recovery. */
    @Test fun aPreResetDatabaseIsRefusedAndPreserved() {
        val name = "pre-reset"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE dataQueue (writeTimestamp INTEGER NOT NULL, id INTEGER NOT NULL, data BLOB, PRIMARY KEY(id))")
            db.execSQL("INSERT INTO dataQueue (writeTimestamp, id, data) VALUES (1, 2, X'5B5D')")
            db.version = PRE_RESET_VERSION
        }
        assertEquals(LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED, refusal(name).recoveryReason)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(PRE_RESET_VERSION, db.version)
            db.rawQuery("SELECT COUNT(*) FROM dataQueue", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    /** Play v78 shipped version 1 with another identity hash; Room refuses it and the participant is told why. */
    @Test fun aSameVersionDatabaseFromAnotherBuildIsReportedAsAnAppUpdateReset() {
        val name = "foreign-identity"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, '${"0".repeat(32)}')")
            db.execSQL("CREATE TABLE dataQueue (writeTimestamp INTEGER NOT NULL, id INTEGER NOT NULL, data BLOB, PRIMARY KEY(id))")
            db.execSQL("INSERT INTO dataQueue (writeTimestamp, id, data) VALUES (1, 2, X'5B5D')")
            db.version = ChronicleDb.VERSION
        }
        assertEquals(LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED, refusal(name).recoveryReason)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM dataQueue", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    /**
     * An installed phone opens its store only while its version keeps the identity it shipped with. A schema
     * change therefore needs a new version and a Migration, never a regenerated file at a published version.
     */
    @Test fun everyPublishedSchemaVersionKeepsTheIdentityItShippedWith() {
        for ((version, identity) in PUBLISHED_IDENTITY) {
            val schema = JSONObject(requireNotNull(javaClass.getResourceAsStream("/${ChronicleDb::class.java.name}/$version.json"))
                .bufferedReader().use { it.readText() })
            assertEquals("schema $version", identity, schema.getJSONObject("database").getString("identityHash"))
        }
        assertTrue(ChronicleDb.VERSION >= PUBLISHED_IDENTITY.keys.max())
    }

    private fun refusal(name: String): LocalStoreRecoveryRequiredException {
        val room = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory(), name)
        try {
            return assertThrows(LocalStoreRecoveryRequiredException::class.java) { room.openHelper.writableDatabase }
        } finally {
            room.close()
        }
    }

    private fun count(db: SupportSQLiteDatabase, from: String): Int =
        db.query("SELECT COUNT(*) FROM $from").use { it.moveToFirst(); it.getInt(0) }

    private companion object {
        /** A version above the reset release's, which Room can only reach by a downgrade it refuses. */
        const val PRE_RESET_VERSION = ChronicleDb.VERSION + 1

        /** A sent pending enrollment's replay binding and accepted-scope fields, as build 79 stored them. */
        val SENT_PENDING = linkedMapOf(
            "pendingEnrollmentAccessCode" to "one-time-code",
            "pendingProposedApiKey" to "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "pendingEnrollmentSourceDeviceJson" to "{\"device\":\"device\",\"model\":\"Pixel\"}",
            "manifestDigest" to "a".repeat(64),
            "studyDisclosureJson" to "{\"manifest\":\"pilot\"}",
            "disclosureVersion" to "v1",
            "reservationNonce" to "owner",
            "pendingAcceptedModuleIds" to "usage_events\nuser_identification",
            "pendingDeclinedModuleIds" to "battery_telemetry",
            "pendingUnavailableModuleIds" to "sensor_gyroscope",
        )

        /** Room identity hash per database version a published build shipped; add one per new version. */
        val PUBLISHED_IDENTITY = mapOf(
            1 to "9849a9acec16ee94bc14c7aa7141a56f", // Play build 79
            3 to "661aab10ad3e82bc5f0ce552e7f5b599", // Play build 80
        )
    }
}
