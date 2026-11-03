package com.openlattice.chronicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A pending enrollment whose issued key the full phone storage refuses to save. */
@RunWith(RobolectricTestRunner::class)
class IssuedKeyStorageFullTest {
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun installPendingAttempt() {
        db = EnrollmentTestStores.install(context)
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        reserveEnrollment(context, EnrollmentSubmission(
            preview = preview,
            partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            serverUrl = preview.manifest.serverOrigin,
            enrollmentAccessCode = "replay-test-invitation",
        ))
    }

    @After fun closeStore() { db.close() }

    /**
     * The server issued the key but the phone cannot save it (storage full): the attempt stays pending with its exact
     * request, and once space is freed the retry resends that same attempt and completes the enrollment.
     */
    @Test fun anIssuedKeyThePhoneCannotSaveRetriesTheSameAttemptAndCompletes() {
        val sent = mutableListOf<Pair<String, String>>()
        val api = studyApi { method, args ->
            when (method) {
                "enroll" -> {
                    sent += (args!![6] as String) to (args[7] as String)
                    EnrollmentResponse(UUID.randomUUID(), apiKey = args[7] as String)
                }
                "reportCollectionAck" -> OK()
                else -> error("unexpected study API $method")
            }
        }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER disk_full BEFORE UPDATE ON upload_servers WHEN OLD.enrollmentIssuedAtEpochMillis IS NULL " +
            "AND NEW.enrollmentIssuedAtEpochMillis IS NOT NULL BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        EnrollmentTestStores.installApi(api).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertNotNull("the attempt is still pending", db.uploadServerDao().getRecoverablePendingEnrollment())
            sql.execSQL("DROP TRIGGER disk_full")
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals("the retry resends the exact attempt and proposed key", listOf(sent[0], sent[0]), sent)
        val row = db.uploadServerDao().getAll().single()
        assertTrue(row.enabled)
        assertEquals(sent[0].second, row.apiKey)
    }
}
